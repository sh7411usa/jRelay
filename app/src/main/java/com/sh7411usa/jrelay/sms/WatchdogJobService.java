package com.sh7411usa.jrelay.sms;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.util.Log;

import com.sh7411usa.jrelay.sms.mms.MmsIngestService;

// On API 31+ the service starts below may be refused; the direct scans still do the work.
public class WatchdogJobService extends JobService {
    private static final String TAG = "WatchdogJobService";

    @Override
    public boolean onStartJob(JobParameters params) {
        Context context = getApplicationContext();
        new Thread(() -> {
            try {
                MmsIngestService.start(context);
                if (!MmsIngestService.isRunning()) MmsIngestService.scanOnce(context);
                SmsCatchUp.run(context);
                SmsSendService.start(context);
            } catch (Throwable t) {
                Log.e(TAG, "Watchdog run failed", t);
            } finally {
                jobFinished(params, false);
            }
        }, "WatchdogJob").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
