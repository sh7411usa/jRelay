package com.sh7411usa.jrelay.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

import com.sh7411usa.jrelay.sms.mms.MmsIngestService;

public class SmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (messages == null || messages.length == 0) return;
        String sender = messages[0].getOriginatingAddress();
        String normalized = sender == null ? null : PhoneNumberUtils.normalize(sender);
        if (normalized == null) return;
        String[] partBodies = new String[messages.length];
        for (int i = 0; i < messages.length; i++) partBodies[i] = messages[i].getMessageBody();
        String body = SmsCatchUp.concatBodies(partBodies);
        // Claim key must match SmsCatchUp's (first part's timestamp == provider date_sent) so a late broadcast is not relayed twice.
        if (SmsCatchUp.claim(context, normalized, messages[0].getTimestampMillis(), body,
                System.currentTimeMillis(), SmsCatchUp.SOURCE_LIVE)) {
            new CommandProcessor(context).handleIncoming(normalized, body);
        }
        MmsIngestService.start(context.getApplicationContext());
    }
}
