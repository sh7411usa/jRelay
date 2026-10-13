package com.sh7411usa.jrelay.sms.mms;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Telephony;
import android.util.Log;

import com.sh7411usa.jrelay.MainActivity;
import com.sh7411usa.jrelay.R;
import com.sh7411usa.jrelay.db.MemberRepository;
import com.sh7411usa.jrelay.db.ThreadRepository;
import com.sh7411usa.jrelay.model.Member;
import com.sh7411usa.jrelay.sms.CommandProcessor;
import com.sh7411usa.jrelay.sms.PhoneNumberUtils;
import com.sh7411usa.jrelay.sms.SmsSendService;
import com.sh7411usa.jrelay.util.Prefs;
import com.sh7411usa.jrelay.util.RelayIdentity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Watches content://mms for inbound group-MMS replies and bridges them (GROUP_MMS mode only). */
public class MmsIngestService extends Service {
    private static final String TAG = "MmsIngestService";
    private static final String CHANNEL_ID = "mms_ingest";
    private static final int NOTIFICATION_ID = 2;
    // The stock app writes a downloaded MMS in stages; negative outcomes only become permanent after this.
    private static final long SETTLE_MILLIS = 120_000L;
    private static volatile boolean running;
    private static final Object SCAN_LOCK = new Object();

    private enum ScanOutcome { DONE, MODE_OFF, DEFERRED }

    private HandlerThread thread;
    private Handler handler;
    private ContentObserver observer;
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private final AtomicBoolean rerunScan = new AtomicBoolean(false);

    private static boolean groupMode(Context context) {
        return new Prefs(context).getDeliveryMode() == Prefs.DeliveryMode.GROUP_MMS;
    }

    public static void start(Context context) {
        if (!groupMode(context)) return;
        Intent intent = new Intent(context, MmsIngestService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Exception e) {
            Log.e(TAG, "Could not start MMS ingest service", e);
        }
    }

    public static boolean isRunning() {
        return running;
    }

    /** Synchronous one-off scan for when the service can't be started. Call off the main thread. */
    public static void scanOnce(Context context) {
        if (!groupMode(context)) return;
        try {
            synchronized (SCAN_LOCK) {
                scan(context.getApplicationContext());
            }
        } catch (Throwable t) {
            Log.e(TAG, "One-off MMS ingest scan failed", t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        thread = new HandlerThread("MmsIngestWorker");
        thread.start();
        handler = new Handler(thread.getLooper());
        observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                requestScan();
            }
        };
        getContentResolver().registerContentObserver(Telephony.Mms.CONTENT_URI, true, observer);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureChannel();
        // A START_STICKY restart on Android 12+ may be refused foreground; don't crash, resume on next trigger.
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (IllegalStateException e) {
            Log.e(TAG, "Not allowed to start in the foreground right now; will resume on next trigger", e);
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!groupMode(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        requestScan();
        return START_STICKY;
    }

    private void requestScan() {
        if (!scanning.compareAndSet(false, true)) {
            rerunScan.set(true);
            return;
        }
        handler.post(this::runScanLoop);
    }

    private void runScanLoop() {
        try {
            ScanOutcome outcome;
            synchronized (SCAN_LOCK) {
                outcome = scan(this);
            }
            if (outcome == ScanOutcome.MODE_OFF) stopSelf();
            else if (outcome == ScanOutcome.DEFERRED) handler.postDelayed(this::requestScan, SETTLE_MILLIS + 5_000L);
        } catch (Throwable t) {
            Log.e(TAG, "MMS ingest scan failed", t);
        } finally {
            scanning.set(false);
        }
        if (rerunScan.compareAndSet(true, false) && scanning.compareAndSet(false, true)) {
            handler.post(this::runScanLoop);
        }
    }

    /** Callers must hold SCAN_LOCK. */
    private static ScanOutcome scan(Context context) {
        Prefs prefs = new Prefs(context);
        if (prefs.getDeliveryMode() != Prefs.DeliveryMode.GROUP_MMS) return ScanOutcome.MODE_OFF;
        long since = prefs.getMmsIngestSinceSeconds();
        if (since == 0) {
            // Never bridge history from before group delivery was enabled.
            prefs.setMmsIngestSinceSeconds(System.currentTimeMillis() / 1000L);
            return ScanOutcome.DONE;
        }
        MemberRepository memberRepository = new MemberRepository(context);
        ThreadRepository threadRepository = new ThreadRepository(context);
        for (Long subgroupId : memberRepository.getDistinctSubgroupIds()) {
            List<String> phones = new ArrayList<>();
            for (Member m : memberRepository.getSubgroupMembers(subgroupId)) phones.add(m.phoneE164);
            threadRepository.recordThread(ThreadMatcher.signature(phones), subgroupId);
        }
        MmsReader reader = new MmsReader(context);
        MmsReader.ReadResult result = reader.fetchRecentInboxIds(since);
        if (result.permissionDenied) {
            Log.e(TAG, "READ_SMS denied; cannot scan for inbound group MMS");
            return ScanOutcome.DONE;
        }
        CommandProcessor processor = new CommandProcessor(context);
        boolean bridgedAny = false, deferredAny = false;
        long nowMs = System.currentTimeMillis();
        for (MmsReader.InboundMms stub : result.messages) {
            if (threadRepository.isIngested(stub.id)) continue;
            boolean settled = nowMs - stub.timestampMs >= SETTLE_MILLIS;
            MmsReader.InboundMms m = reader.readMessage(stub.id, stub.timestampMs);
            String rejection = null;
            ThreadMatcher.Match match = null;
            Member sender = null;
            if (m == null) {
                rejection = "UNREADABLE";
            } else if (m.body == null || m.body.trim().isEmpty()) {
                rejection = "NO_TEXT";
            } else if ((match = ThreadMatcher.match(m.sender, m.recipients, threadRepository::findSubgroupForSignature)) == null) {
                rejection = "UNMATCHED";
            } else {
                RelayIdentity.observeThreadCandidate(context, match.removedParticipant, match.signature);
                sender = memberRepository.findByPhone(PhoneNumberUtils.normalize(m.sender));
                if (sender == null) rejection = "NOT_MEMBER";
            }
            if (rejection != null) {
                if (settled) threadRepository.markIngested(stub.id, rejection);
                else deferredAny = true;
                continue;
            }
            // Mark BEFORE forwarding (at-most-once): a retry must never double-bridge to every group.
            if (threadRepository.markIngested(stub.id, "BRIDGED")) {
                processor.handleThreadMessage(sender, match.subgroupId, m.body);
                bridgedAny = true;
            }
        }
        if (bridgedAny) SmsSendService.start(context);
        return deferredAny ? ScanOutcome.DEFERRED : ScanOutcome.DONE;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        if (observer != null) getContentResolver().unregisterContentObserver(observer);
        if (thread != null) thread.quitSafely();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notif_mms_ingest_channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notif_mms_ingest_channel_desc));
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        }
        return builder.setContentTitle(getString(R.string.notif_mms_ingest_title))
                .setContentText(getString(R.string.notif_mms_ingest_idle))
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
