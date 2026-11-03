package com.cloudbackup.app;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** Re-locks the app (clears LockState) once every activity has left the screen, so returning from the
 *  recent-apps list or another app always asks for the PIN again, if one is set. */
public class App extends Application {
    private int started = 0;

    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) { started++; }
            @Override public void onActivityStopped(Activity a) {
                started--;
                if (started <= 0) LockState.setUnlocked(false);
            }
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }
}
