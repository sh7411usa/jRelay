package com.sh7411usa.jrelay.sms;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.telephony.SmsManager;
import android.util.Log;

import com.sh7411usa.jrelay.R;
import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.db.MessageRepository;
import com.sh7411usa.jrelay.db.OutboxRepository;
import com.sh7411usa.jrelay.db.ThreadRepository;
import com.sh7411usa.jrelay.model.Member;
import com.sh7411usa.jrelay.sms.mms.MmsSender;
import com.sh7411usa.jrelay.sms.mms.ThreadMatcher;
import com.sh7411usa.jrelay.util.MessageSalt;
import com.sh7411usa.jrelay.util.NotificationHelper;
import com.sh7411usa.jrelay.util.Prefs;
import com.sh7411usa.jrelay.util.RateLimitConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

public class SmsSendService extends Service {

    private static final String TAG = "SmsSendService";
    private static final long HOLD_WAIT_MARGIN_MILLIS = 250L;
    private static final long HOLD_WAIT_SLICE_MILLIS = 5_000L;
    // How long a sent-intent can plausibly take; keep separate from the (much longer) handoff staleness window.
    private static final long OUTSTANDING_RESULTS_WAIT_MILLIS = 60 * 1000L;
    private static final long MESSAGE_LOG_RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1000L;
    // Renewed every loop pass, so a stuck drain can never keep the CPU awake for long.
    private static final long WAKE_LOCK_SLICE_MILLIS = 10 * 60 * 1000L;

    // One drain at a time; a start() suppressed by a running drain sets RERUN so its work is not stranded.
    private static final AtomicBoolean DRAINING = new AtomicBoolean(false);
    private static final AtomicBoolean RERUN = new AtomicBoolean(false);
    // The drain must stopSelf against the LATEST startId, not its own.
    private static volatile int latestStartId;

    public static void start(Context context) {
        Intent intent = new Intent(context, SmsSendService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Exception e) {
            Log.e(TAG, "Could not start send service", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Must run first on every path: startForegroundService requires startForeground within ~5s.
        NotificationHelper.ensureSendChannel(this);
        startForeground(NotificationHelper.FOREGROUND_NOTIFICATION_ID,
                NotificationHelper.buildSendNotification(this, getString(R.string.notif_send_idle)));
        latestStartId = startId;
        if (!DRAINING.compareAndSet(false, true)) {
            RERUN.set(true);   // do NOT stopSelf here: it would kill the running drain
            return START_NOT_STICKY;
        }
        new Thread(() -> {
            try {
                boolean again = true, first = true;
                while (again) {
                    try {
                        if (first) {
                            int resetCount = new OutboxRepository(this).resetOrphanedSending();
                            if (resetCount > 0) Log.i(TAG, "Reset " + resetCount + " orphaned SENDING row(s) to PENDING");
                            int prunedCount = new MessageRepository(this).pruneOlderThan(
                                    System.currentTimeMillis() - MESSAGE_LOG_RETENTION_MILLIS);
                            if (prunedCount > 0) Log.i(TAG, "Pruned " + prunedCount + " message_log row(s) older than retention window");
                            first = false;
                        }
                        drainAll();
                    } catch (Throwable t) {
                        // No immediate retry (a failing DB would spin); only re-enter via RERUN.
                        Log.e(TAG, "Drain thread crashed; outbox will resume on next trigger", t);
                    } finally {
                        DRAINING.set(false);
                    }
                    // Re-enter on this thread: background startService throws on API 26+.
                    again = RERUN.compareAndSet(true, false) && DRAINING.compareAndSet(false, true);
                }
            } finally {
                if (stopSelfResult(latestStartId)) stopForeground(Service.STOP_FOREGROUND_REMOVE);
            }
        }).start();
        return START_NOT_STICKY;
    }

    private void drainAll() {
        OutboxRepository outbox = new OutboxRepository(this);
        MessageRepository messageRepository = new MessageRepository(this);
        MemberRepository memberRepository = new MemberRepository(this);
        Prefs prefs = new Prefs(this);
        RateLimitConfig config = RateLimitConfig.groupDefault(prefs);
        Random random = new Random();
        SmsManager smsManager = SmsManager.getDefault();
        boolean shuffle = prefs.isDeliveryShuffleEnabled();
        // Without this, Thread.sleep stalls in deep sleep and a few-second stagger stretches to minutes.
        PowerManager.WakeLock wake = ((PowerManager) getSystemService(POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jRelay:send");
        wake.setReferenceCounted(false);
        try {
            boolean firstBurst = true, pauseNotified = false;
            while (true) {
                wake.acquire(WAKE_LOCK_SLICE_MILLIS);
                if (prefs.isStopped()) return;
                long pauseUntil = prefs.getPauseUntilMillis();   // read once so the sleep can't go negative
                if (pauseUntil == Prefs.PAUSE_INDEFINITE) return;
                long pauseLeft = pauseUntil - System.currentTimeMillis();
                if (pauseUntil != 0 && pauseLeft > 0) {
                    // Timed pause: wait it out here in slices; nothing else would restart the drain.
                    if (!pauseNotified) {
                        NotificationHelper.updateSendNotification(this,
                                getString(R.string.notif_send_paused, outbox.countPending()));
                        pauseNotified = true;
                    }
                    wake.release();   // a timed pause can last hours; don't hold the CPU through it
                    Thread.sleep(Math.min(pauseLeft, HOLD_WAIT_SLICE_MILLIS));
                    continue;
                }
                pauseNotified = false;
                mergeReleasedRelayRows(outbox, prefs, smsManager);
                int pending = outbox.countPending();
                int holding = outbox.countHolding();
                boolean burstScheduled = pending > holding
                        && config.staggeringEnabled && (!firstBurst || config.initialDelayEnabled);
                NotificationHelper.updateSendNotification(this, getString(
                        burstScheduled ? R.string.notif_send_status_burst : R.string.notif_send_status,
                        pending, holding));
                if (pending <= holding) {
                    if (idleOrWaitHold(outbox, holding)) return;
                    firstBurst = true;
                    continue;
                }
                int burstSize = nextBurstSize(outbox, config, random);
                if (burstScheduled) waitBeforeNextBurst(outbox, config, random, burstSize);
                firstBurst = false;
                if (prefs.isPaused() || prefs.isStopped()) continue;
                List<OutboxRepository.OutboxItem> burst = outbox.takeBurst(burstSize, shuffle);
                if (burst.isEmpty()) {
                    if (idleOrWaitHold(outbox, outbox.countHolding())) return;
                    firstBurst = true;
                    continue;
                }
                sendBurst(smsManager, outbox, messageRepository, memberRepository, prefs, config, random, burst);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (wake.isHeld()) wake.release();
            SendQueueStatus.clear();
        }
    }

    /** Returns true when the drain should exit (nothing held); otherwise waits for the earliest hold. */
    private boolean idleOrWaitHold(OutboxRepository outbox, int holding) throws InterruptedException {
        if (holding <= 0) {
            // Stay up so SentReceiver's start() for a tail result lands while DRAINING is still held.
            waitForOutstandingResults(outbox);
            return true;
        }
        waitForHold(outbox);
        return false;
    }

    private void mergeReleasedRelayRows(OutboxRepository outbox, Prefs prefs, SmsManager smsManager) {
        if (prefs.getCoalesceWindowSeconds() == 0) return;
        List<OutboxRepository.OutboxItem> released = outbox.takeReleasedRelayRows();
        if (released.isEmpty()) return;
        Map<String, List<MessageMerger.Row>> byRecipient = new LinkedHashMap<>();
        for (OutboxRepository.OutboxItem item : released) {
            byRecipient.computeIfAbsent(item.phoneE164, k -> new ArrayList<>())
                    .add(new MessageMerger.Row(item.id, item.body));
        }
        int maxSegments = prefs.getMaxMergedSegments();
        // Count on a salted body: the ZWSP salt forces UCS-2 (70 chars/segment).
        MessageMerger.SegmentCounter counter =
                body -> smsManager.divideMessage(MessageSalt.applySendTime(prefs, body)).size();
        for (List<MessageMerger.Row> rows : byRecipient.values()) {
            for (MessageMerger.Merged merged : MessageMerger.merge(rows, maxSegments, counter)) {
                if (!merged.mergedRowIds.isEmpty()) outbox.applyMerge(merged.keepRowId, merged.body, merged.mergedRowIds);
            }
        }
    }

    private void waitForHold(OutboxRepository outbox) throws InterruptedException {
        long deadline = outbox.earliestHoldUntil() + HOLD_WAIT_MARGIN_MILLIS;
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0 || outbox.countPending() > outbox.countHolding()) return;
            Thread.sleep(Math.min(remaining, HOLD_WAIT_SLICE_MILLIS));
        }
    }

    private void waitForOutstandingResults(OutboxRepository outbox) throws InterruptedException {
        long deadline = System.currentTimeMillis() + OUTSTANDING_RESULTS_WAIT_MILLIS;
        while (outbox.countAwaitingResults() > 0 && System.currentTimeMillis() < deadline) {
            if (outbox.countPending() > outbox.countHolding()) return;   // real work arrived; RERUN covers results
            Thread.sleep(HOLD_WAIT_SLICE_MILLIS);
        }
    }

    private int nextBurstSize(OutboxRepository outbox, RateLimitConfig config, Random random) {
        switch (config.burstMode) {
            case FIXED: return Math.max(1, config.fixedBurstSize);
            case ALL_AT_ONCE: return Math.max(1, outbox.countPending());
            default: {
                int min = Math.max(1, config.burstMin);
                return randomBetween(random, min, Math.max(min, config.burstMax));
            }
        }
    }

    private static int randomBetween(Random random, int min, int max) {
        return min + (max > min ? random.nextInt(max - min + 1) : 0);
    }

    private void waitBeforeNextBurst(OutboxRepository outbox, RateLimitConfig config, Random random, int upcomingBurstSize)
            throws InterruptedException {
        int min = Math.max(0, config.minWaitSeconds);
        int waitSeconds = randomBetween(random, min, Math.max(min, config.maxWaitSeconds));
        int pending = outbox.countPending();
        int displaySize = pending > 0 ? Math.min(upcomingBurstSize, pending) : upcomingBurstSize;
        SendQueueStatus.setNextBurst(System.currentTimeMillis() + waitSeconds * 1000L, displaySize);
        Thread.sleep(waitSeconds * 1000L);
    }

    private void sendBurst(SmsManager smsManager, OutboxRepository outbox, MessageRepository messageRepository,
                            MemberRepository memberRepository, Prefs prefs, RateLimitConfig config,
                            Random random, List<OutboxRepository.OutboxItem> burst) {
        for (int i = 0; i < burst.size(); i++) {
            sendOne(smsManager, outbox, memberRepository, prefs, burst.get(i));
            if (config.microspacingEnabled && i < burst.size() - 1) {
                MicroSpacer.waitMillis(microspacingDelayMillis(config, random));
            }
        }
    }

    private double microspacingDelayMillis(RateLimitConfig config, Random random) {
        if (config.microspacingMode == Prefs.MicrospacingMode.RANDOM_RANGE) {
            int min = Math.max(0, config.microspacingMinMs);
            int max = Math.max(min, config.microspacingMaxMs);
            return min + random.nextDouble() * (max - min);
        }
        return Math.max(0, config.microspacingFixedMs);
    }

    private static String sendBody(Prefs prefs, OutboxRepository.OutboxItem item) {
        return item.applySalt ? MessageSalt.applySendTime(prefs, item.body) : item.body;
    }

    // Outcome comes from SentReceiver. Split by divideMessage (segments), not length: UCS-2 bodies
    // over 70 chars fail single-part with RESULT_ERROR_NULL_PDU. One token per attempt.
    private void sendOne(SmsManager smsManager, OutboxRepository outbox, MemberRepository memberRepository,
                          Prefs prefs, OutboxRepository.OutboxItem item) {
        if (item.subgroupId != null) {
            sendGroup(outbox, memberRepository, prefs, item);
            return;
        }
        long token = System.currentTimeMillis();
        try {
            String body = sendBody(prefs, item);   // never written back: retries re-salt fresh
            ArrayList<String> parts = smsManager.divideMessage(body);
            if (parts.size() > 1) {
                ArrayList<PendingIntent> sentIntents = new ArrayList<>(parts.size());
                for (int i = 0; i < parts.size(); i++) sentIntents.add(SentReceiver.create(this, item.id, i, token));
                // Recorded before the send so a process death right after still shows it awaiting results.
                outbox.markHandedOff(item.id, parts.size(), token);
                smsManager.sendMultipartTextMessage(item.phoneE164, null, parts, sentIntents, null);
            } else {
                PendingIntent sentIntent = SentReceiver.create(this, item.id, 0, token);
                outbox.markHandedOff(item.id, 1, token);
                smsManager.sendTextMessage(item.phoneE164, null, body, sentIntent, null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to send SMS for outbox row " + item.id, e);
            outbox.markHandedOff(item.id, 0, token);
            SentReceiver.handleFailure(this, item, token, SentReceiver.RESULT_NOT_SENT);
        }
    }

    // Members resolved at send time so a removed member never receives it.
    private void sendGroup(OutboxRepository outbox, MemberRepository memberRepository, Prefs prefs,
                            OutboxRepository.OutboxItem item) {
        long token = System.currentTimeMillis();
        List<Member> members = memberRepository.getSubgroupMembers(item.subgroupId);
        if (members.isEmpty()) {
            Log.e(TAG, "Sub-group " + item.subgroupId + " for outbox row " + item.id
                    + " has zero active members at send time; failing the row instead of sending to nobody");
            outbox.markHandedOff(item.id, 0, token);
            outbox.markFailed(item.id, token);
            return;
        }
        int target = prefs.getSubgroupTargetSize();
        if (members.size() > target) {
            // Over target means 11+ participants, which some carriers fail silently.
            Log.e(TAG, "Sub-group " + item.subgroupId + " has " + members.size() + " members, over the ceiling of "
                    + target + "; failing outbox row " + item.id + " instead of sending");
            outbox.markHandedOff(item.id, 0, token);
            outbox.markFailed(item.id, token);
            new CommandProcessor(this).alertAdminsOfGroupFailure(item.subgroupId);
            return;
        }
        List<String> recipients = new ArrayList<>(members.size());
        for (Member m : members) recipients.add(m.phoneE164);
        String body = sendBody(prefs, item);
        // Thread and handoff are recorded before the radio is invoked, with the same token MmsSender gets.
        new ThreadRepository(this).recordThread(ThreadMatcher.signature(recipients), item.subgroupId);
        outbox.markHandedOff(item.id, 1, token);
        MmsSender.send(this, item.id, token, recipients, body, resultCode -> {
            Log.e(TAG, "Failed to hand off group MMS for outbox row " + item.id + " (sub-group "
                    + item.subgroupId + "), resultCode=" + resultCode);
            outbox.markHandedOff(item.id, 0, token);
            SentReceiver.handleFailure(this, item, token, resultCode);
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
