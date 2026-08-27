package com.sh7411usa.jrelay.sms;

import android.app.Service;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.telephony.SmsManager;
import android.util.Log;

import com.sh7411usa.jrelay.db.OutboxRepository;
import com.sh7411usa.jrelay.util.Prefs;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class SmsSendService extends Service {

    private static final String TAG = "SmsSendService";
    private static final int MAX_PART_LENGTH = 160;

    public static void start(Context context) {
        context.startService(new Intent(context, SmsSendService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        new Thread(() -> {
            drainOutbox();
            stopSelf(startId);
        }).start();
        return START_NOT_STICKY;
    }

    private void drainOutbox() {
        OutboxRepository outbox = new OutboxRepository(this);
        Prefs prefs = new Prefs(this);
        Random random = new Random();
        SmsManager smsManager = SmsManager.getDefault();

        try {
            if (outbox.countPending() <= 0) {
                return;
            }

            int burstSize = randomBurstSize(prefs, random);
            if (prefs.isInitialDelayEnabled()) {
                waitBeforeNextBurst(outbox, prefs, random, burstSize);
            }

            List<OutboxRepository.OutboxItem> burst = outbox.takeBurst(burstSize);
            while (!burst.isEmpty()) {
                for (OutboxRepository.OutboxItem item : burst) {
                    sendOne(smsManager, outbox, item);
                }
                if (outbox.countPending() <= 0) {
                    break;
                }
                int nextBurstSize = randomBurstSize(prefs, random);
                waitBeforeNextBurst(outbox, prefs, random, nextBurstSize);
                burst = outbox.takeBurst(nextBurstSize);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            SendQueueStatus.clear();
        }
    }

    private int randomBurstSize(Prefs prefs, Random random) {
        int min = Math.max(1, prefs.getBurstMin());
        int max = Math.max(min, prefs.getBurstMax());
        return min + (max > min ? random.nextInt(max - min + 1) : 0);
    }

    private void waitBeforeNextBurst(OutboxRepository outbox, Prefs prefs, Random random, int upcomingBurstSize)
            throws InterruptedException {
        int min = Math.max(0, prefs.getMinWaitSeconds());
        int max = Math.max(min, prefs.getMaxWaitSeconds());
        int waitSeconds = min + (max > min ? random.nextInt(max - min + 1) : 0);

        int pending = outbox.countPending();
        int displaySize = pending > 0 ? Math.min(upcomingBurstSize, pending) : upcomingBurstSize;
        SendQueueStatus.setNextBurst(System.currentTimeMillis() + waitSeconds * 1000L, displaySize);

        Thread.sleep(waitSeconds * 1000L);
    }

    private void sendOne(SmsManager smsManager, OutboxRepository outbox, OutboxRepository.OutboxItem item) {
        try {
            ArrayList<String> parts = item.body.length() > MAX_PART_LENGTH
                    ? smsManager.divideMessage(item.body)
                    : new ArrayList<>();
            if (parts.isEmpty()) {
                parts.add(item.body);
            }
            outbox.prepareTracking(item.id, parts.size());

            ArrayList<PendingIntent> sentIntents = new ArrayList<>();
            ArrayList<PendingIntent> deliveryIntents = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                sentIntents.add(statusIntent(item.id, i, SmsStatusReceiver.ACTION_SMS_SENT));
                deliveryIntents.add(statusIntent(item.id, i, SmsStatusReceiver.ACTION_SMS_DELIVERED));
            }

            if (parts.size() == 1) {
                smsManager.sendTextMessage(item.phoneE164, null, parts.get(0),
                        sentIntents.get(0), deliveryIntents.get(0));
            } else {
                smsManager.sendMultipartTextMessage(item.phoneE164, null, parts,
                        sentIntents, deliveryIntents);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to send SMS to " + item.phoneE164, e);
            outbox.markFailed(item.id, -1);
            SmsRetryScheduler.scheduleIfSafe(this, item.id);
        }
    }

    private PendingIntent statusIntent(long outboxId, int partIndex, String action) {
        Intent intent = new Intent(this, SmsStatusReceiver.class);
        intent.setAction(action);
        intent.setData(Uri.parse("jrelay://sms/" + outboxId + "/" + partIndex + "/" + action));
        intent.putExtra(SmsStatusReceiver.EXTRA_OUTBOX_ID, outboxId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(this, 0, intent, flags);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
