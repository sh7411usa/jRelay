package com.sh7411usa.jrelay.sms.mms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires before the MMS body is downloaded; MmsIngestService's content://mms observer reads it later. */
public class WapPushReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if ("android.provider.Telephony.WAP_PUSH_RECEIVED".equals(intent.getAction())) {
            MmsIngestService.start(context.getApplicationContext());
        }
    }
}
