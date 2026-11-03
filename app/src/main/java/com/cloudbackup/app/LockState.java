package com.cloudbackup.app;

/** Whether the PIN has been entered since the app was last backgrounded. Process-wide, in memory only. */
final class LockState {
    private static volatile boolean unlocked = false;

    static boolean isUnlocked() { return unlocked; }
    static void setUnlocked(boolean v) { unlocked = v; }
}
