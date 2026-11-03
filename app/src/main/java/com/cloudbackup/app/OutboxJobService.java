package com.cloudbackup.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Every ~15 minutes (when there is a network): uploads captured messages that are still waiting in the queue. */
public class OutboxJobService extends JobService {
    private static final int JOB_ID = 43;

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (!new Settings(c).serviceEnabled()) { js.cancel(JOB_ID); return; }
        for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;     // already scheduled
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, OutboxJobService.class))
                .setPeriodic(15L * 60 * 1000)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build());
    }

    static void cancel(Context c) { c.getSystemService(JobScheduler.class).cancel(JOB_ID); }

    @Override public boolean onStartJob(JobParameters params) {
        if (!new Settings(this).serviceEnabled()) return false;
        new Thread(() -> {
            boolean retry = false;
            try { Outbox.flush(this); } catch (Exception e) { retry = true; }
            jobFinished(params, retry);
        }, "outbox-job").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return true; }
}
