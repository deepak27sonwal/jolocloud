package com.cloudbackup.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Foreground service for user-triggered backups (keeps running with the screen off). */
public class BackupService extends Service {
    static final String ACTION_STOP = "stop";
    private static volatile BackupEngine current;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            if (current != null) current.cancel();
            return START_NOT_STICKY;
        }
        if (Status.running) return START_NOT_STICKY;

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("backup", "Backup",
                NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "backup")
                .setContentTitle("Cloud Backup")
                .setContentText("Backing up…")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, n);
        }

        Status.running = true;
        new Thread(() -> {
            BackupEngine e = new BackupEngine(this);
            current = e;
            boolean ok = e.run();
            current = null;
            nm.notify(2, new Notification.Builder(this, "backup")
                    .setContentTitle("Cloud Backup")
                    .setContentText(Status.summary)
                    .setSmallIcon(ok ? android.R.drawable.stat_sys_upload_done
                                     : android.R.drawable.stat_notify_error)
                    .setAutoCancel(true).build());
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }, "backup").start();
        return START_NOT_STICKY;
    }
}
