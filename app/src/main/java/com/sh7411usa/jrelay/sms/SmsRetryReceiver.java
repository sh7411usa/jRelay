package com.sh7411usa.jrelay.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.sh7411usa.jrelay.db.OutboxRepository;

public class SmsRetryReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        long id = intent.getLongExtra(SmsStatusReceiver.EXTRA_OUTBOX_ID, -1);
        if (id >= 0 && new OutboxRepository(context).releaseScheduledRetry(id)) {
            SmsSendService.start(context);
        }
    }
}
