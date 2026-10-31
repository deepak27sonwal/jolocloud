package com.multiweb.app;

import android.app.Application;
import android.webkit.WebView;

/** Gives every ":sN" process its own WebView data directory, so cookies/storage never mix. */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        String proc = Application.getProcessName();
        int i = proc == null ? -1 : proc.indexOf(":s");
        if (i >= 0) WebView.setDataDirectorySuffix(proc.substring(i + 1));
    }
}
