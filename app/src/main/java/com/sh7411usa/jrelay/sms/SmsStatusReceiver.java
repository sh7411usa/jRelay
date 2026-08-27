package com.sh7411usa.jrelay.sms;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.sh7411usa.jrelay.db.OutboxRepository;
import com.sh7411usa.jrelay.util.NotificationHelper;

public class SmsStatusReceiver extends BroadcastReceiver {

    public static final String ACTION_SMS_SENT = "com.sh7411usa.jrelay.SMS_SENT";
    public static final String ACTION_SMS_DELIVERED = "com.sh7411usa.jrelay.SMS_DELIVERED";
    public static final String EXTRA_OUTBOX_ID = "outbox_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        long outboxId = intent.getLongExtra(EXTRA_OUTBOX_ID, -1);
        if (outboxId < 0) {
            return;
        }

        int resultCode = getResultCode();
        boolean success = resultCode == Activity.RESULT_OK;
        OutboxRepository outbox = new OutboxRepository(context);
        if (ACTION_SMS_SENT.equals(intent.getAction())) {
            outbox.recordPartSent(outboxId, success, resultCode);
            if (!success) {
                SmsRetryScheduler.scheduleIfSafe(context, outboxId);
                NotificationHelper.showSmsFailure(context, resultCode);
            }
        } else if (ACTION_SMS_DELIVERED.equals(intent.getAction())) {
            outbox.recordPartDelivered(outboxId, success, resultCode);
            if (!success) {
                NotificationHelper.showSmsDeliveryFailure(context, resultCode);
            }
        }
    }
}
