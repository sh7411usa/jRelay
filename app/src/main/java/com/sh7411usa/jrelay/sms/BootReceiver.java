package com.sh7411usa.jrelay.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.sh7411usa.jrelay.db.OutboxRepository;
import com.sh7411usa.jrelay.sms.mms.MmsIngestService;
import com.sh7411usa.jrelay.util.Prefs;

// Also handles MY_PACKAGE_REPLACED: an app update stops services and Android never restarts them.
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        if (!boot && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        Context appContext = context.getApplicationContext();
        Watchdog.schedule(appContext);
        Prefs prefs = new Prefs(appContext);
        // Resetting SENDING rows is only safe while paused/stopped; otherwise a drain in this process may own them.
        if (boot && (prefs.isPaused() || prefs.isStopped())) {
            int reset = new OutboxRepository(appContext).resetOrphanedSending();
            if (reset > 0) Log.i(TAG, "Reset " + reset + " orphaned SENDING row(s) after boot");
            if (!prefs.isStopped() && prefs.getPauseUntilMillis() != Prefs.PAUSE_INDEFINITE) {
                SmsSendService.start(appContext);
            }
            return;
        }
        SmsSendService.start(appContext);
        MmsIngestService.start(appContext);
        SmsCatchUp.runAsync(appContext, goAsync());
    }
}
