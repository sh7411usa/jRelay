package com.sh7411usa.jrelay.sms;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

public final class Watchdog {
    private static final String TAG = "Watchdog";
    private static final int JOB_ID = 7411;
    private static final long INTERVAL_MILLIS = 15 * 60 * 1000L;

    private Watchdog() {
    }

    public static void schedule(Context context) {
        Context appContext = context.getApplicationContext();
        JobScheduler scheduler = appContext.getSystemService(JobScheduler.class);
        // Keep an existing job: rescheduling restarts its period, so frequent calls could keep it from ever firing.
        if (scheduler == null || scheduler.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(appContext, WatchdogJobService.class))
                .setPeriodic(INTERVAL_MILLIS)
                .setPersisted(true)
                .build();
        try {
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) Log.e(TAG, "Could not schedule watchdog job");
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not schedule watchdog job", e);
        }
    }
}
