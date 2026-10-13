package com.sh7411usa.jrelay.sms.mms;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.sh7411usa.jrelay.db.OutboxRepository;
import com.sh7411usa.jrelay.sms.SentReceiver;

/** Manifest-declared so a result survives process death; retry policy lives in SentReceiver.handleFailure. */
public class MmsSentReceiver extends BroadcastReceiver {
    private static final String TAG = "MmsSentReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getData() == null) return;
        long[] parsed = MmsSender.parseSentData(intent.getData());
        if (parsed == null) {
            Log.e(TAG, "Unparseable data Uri on MMS sent broadcast: " + intent.getData());
            return;
        }
        long token = parsed[1];
        Context appContext = context.getApplicationContext();
        OutboxRepository outbox = new OutboxRepository(appContext);
        OutboxRepository.OutboxItem item = outbox.findById(parsed[0]);
        if (item == null) return;
        int resultCode = getResultCode();
        if (resultCode == Activity.RESULT_OK) {
            // One PDU resolves the whole row; recordPartSent no-ops on stale/duplicate tokens.
            outbox.recordPartSent(item.id, token);
        } else if (outbox.recordSendFailure(item.id, token, resultCode)) {
            SentReceiver.handleFailure(appContext, item, token, resultCode);
        }
    }
}
