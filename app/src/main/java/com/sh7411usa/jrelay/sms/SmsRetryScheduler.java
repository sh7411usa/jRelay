package com.sh7411usa.jrelay.sms;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import com.sh7411usa.jrelay.db.OutboxRepository;

public final class SmsRetryScheduler {
    private SmsRetryScheduler() { }

    public static void scheduleIfSafe(Context context, long outboxId) {
        Long retryAt = new OutboxRepository(context).scheduleAutoRetry(outboxId);
        if (retryAt == null) return;
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, retryAt, pendingIntent(context, outboxId));
    }

    private static PendingIntent pendingIntent(Context context, long outboxId) {
        Intent intent = new Intent(context, SmsRetryReceiver.class);
        intent.putExtra(SmsStatusReceiver.EXTRA_OUTBOX_ID, outboxId);
        return PendingIntent.getBroadcast(context, (int) (outboxId ^ (outboxId >>> 32)), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
