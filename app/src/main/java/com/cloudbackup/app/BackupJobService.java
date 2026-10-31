package com.cloudbackup.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Daily automatic backup: only on unmetered network (Wi-Fi) while charging. */
public class BackupJobService extends JobService {
    private static final int JOB_ID = 42;
    private volatile BackupEngine engine;

    static void schedule(Context c, boolean enable) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (!enable) { js.cancel(JOB_ID); return; }
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, BackupJobService.class))
                .setPeriodic(24L * 60 * 60 * 1000)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setRequiresCharging(true)
                .setPersisted(true)
                .build());
    }

    @Override public boolean onStartJob(JobParameters params) {
        if (Status.running || !new Settings(this).configured()) return false;
        new Thread(() -> {
            engine = new BackupEngine(this);
            boolean ok = engine.run();
            jobFinished(params, !ok);
        }, "backup-job").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (engine != null) engine.cancel();
        return true; // reschedule
    }
}
