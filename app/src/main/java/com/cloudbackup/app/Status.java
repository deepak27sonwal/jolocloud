package com.cloudbackup.app;

import java.util.ArrayList;
import java.util.List;

/** Process-wide progress shared between the engine (worker threads) and the UI. */
final class Status {
    private static final List<String> lines = new ArrayList<>();
    static volatile boolean running = false;
    static volatile String summary = "Idle";

    static synchronized void log(String s) {
        lines.add(s);
        if (lines.size() > 200) lines.remove(0);
    }

    static synchronized String text() {
        return String.join("\n", lines);
    }
}
