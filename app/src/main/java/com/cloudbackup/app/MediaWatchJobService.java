package com.cloudbackup.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.provider.MediaStore;

/**
 * Wakes up automatically (the OS does this via JobScheduler content triggers, no app code needs to be
 * running) whenever anything changes in the device's photo/video index, and backs up just the new files
 * that belong to a chat app's own folder — see {@link ChatMediaBuckets} and
 * {@link BackupEngine#backupNewChatMedia()}. A content-trigger job fires once per batch of changes, so
 * it re-registers itself every time it runs to keep watching.
 */
public class MediaWatchJobService extends JobService {
    private static final int JOB_ID = 44;

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        Settings st = new Settings(c);
        if (!st.chatMediaWatch() || !st.serviceEnabled()) { js.cancel(JOB_ID); return; }
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, MediaWatchJobService.class))
                .addTriggerContentUri(new JobInfo.TriggerContentUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .addTriggerContentUri(new JobInfo.TriggerContentUri(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(10_000)   // wait a little in case several files land together
                .setTriggerContentMaxDelay(120_000)     // but never more than ~2 minutes
                .build();
        js.schedule(job);
    }

    static void cancel(Context c) { c.getSystemService(JobScheduler.class).cancel(JOB_ID); }

    @Override public boolean onStartJob(JobParameters params) {
        schedule(this); // re-arm for the next change before doing anything else
        Settings st = new Settings(this);
        if (!st.chatMediaWatch() || !st.serviceEnabled() || !st.configured()) return false;
        new Thread(() -> {
            boolean ok;
            try {
                ok = new BackupEngine(this).backupNewChatMedia();
            } catch (Exception e) {
                ok = false;
            }
            jobFinished(params, !ok);
        }, "media-watch").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return true; }
}
