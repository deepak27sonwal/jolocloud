package com.cloudbackup.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import java.util.Calendar;

/** Daily automatic backup at a chosen time of day, on Wi-Fi while charging. Reschedules itself for the
 *  next day each time it runs, so the job need not be periodic (JobScheduler periodic jobs cannot be
 *  pinned to a specific time of day). */
public class BackupJobService extends JobService {
    private static final int JOB_ID = 42;
    private volatile BackupEngine engine;

    /** (Re)schedules the next run for the configured time of day; cancels it if disabled. */
    static void schedule(Context c, boolean enable) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (!enable || !new Settings(c).serviceEnabled()) { js.cancel(JOB_ID); return; }
        Settings st = new Settings(c);
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, BackupJobService.class))
                .setMinimumLatency(delayToNext(st.backupHour(), st.backupMinute()))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setRequiresCharging(true)
                .setPersisted(true)
                .build());
    }

    /** Milliseconds from now until the next occurrence of hour:minute (today if still ahead, else tomorrow). */
    static long delayToNext(int hour, int minute) {
        Calendar now = Calendar.getInstance();
        Calendar target = (Calendar) now.clone();
        target.set(Calendar.HOUR_OF_DAY, hour);
        target.set(Calendar.MINUTE, minute);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);
        if (!target.after(now)) target.add(Calendar.DAY_OF_YEAR, 1);
        return target.getTimeInMillis() - now.getTimeInMillis();
    }

    @Override public boolean onStartJob(JobParameters params) {
        schedule(this, true);          // line up tomorrow's run before this one even starts
        if (Status.running || !new Settings(this).configured() || !new Settings(this).serviceEnabled()) return false;
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
