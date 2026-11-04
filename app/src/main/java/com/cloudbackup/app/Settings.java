package com.cloudbackup.app;

import android.content.Context;
import android.content.SharedPreferences;

/** User configuration and Supabase session. Stored in app-private storage. */
final class Settings {
    final SharedPreferences p;

    Settings(Context c) { p = c.getSharedPreferences("settings", Context.MODE_PRIVATE); }

    String url()       { return p.getString("url", "https://vnnuiycbgyyzsagabzmz.supabase.co"); }
    String anonKey()   { return p.getString("anon", "sb_publishable_vWQbx5p4mOvjcA8-NXHJkQ_e6WciNpQ"); }
    String bucket()    { return p.getString("bucket", "backups"); }
    String email()     { return p.getString("email", ""); }
    String refresh()   { return p.getString("refresh", ""); }
    String access()    { return p.getString("access", ""); }
    String uid()       { return p.getString("uid", ""); }
    long expiresAt()   { return p.getLong("exp", 0); }
    boolean photos()   { return p.getBoolean("photos", true); }
    boolean videos()   { return p.getBoolean("videos", true); }
    boolean contacts() { return p.getBoolean("contacts", true); }
    boolean messages() { return p.getBoolean("messages", true); }
    boolean compress() { return p.getBoolean("compress", true); }
    boolean auto()     { return p.getBoolean("auto", false); }

    // ---- master on/off switch for everything that runs in the background (auto backup + message capture)
    boolean serviceEnabled() { return p.getBoolean("serviceEnabled", true); }

    // ---- time of day the automatic backup runs (24h, device local time)
    int backupHour()   { return p.getInt("backupHour", 2); }
    int backupMinute() { return p.getInt("backupMinute", 0); }

    // ---- chosen files / folders to back up (content:// URIs with a persisted read grant)
    boolean filesEnabled() { return p.getBoolean("filesEnabled", false); }
    java.util.Set<String> pickedTrees() { return p.getStringSet("pickedTrees", java.util.Collections.emptySet()); }
    java.util.Set<String> pickedFiles() { return p.getStringSet("pickedFiles", java.util.Collections.emptySet()); }

    void addPickedTree(String uri) { addTo("pickedTrees", uri); }
    void addPickedFile(String uri) { addTo("pickedFiles", uri); }
    void removePickedTree(String uri) { removeFrom("pickedTrees", uri); }
    void removePickedFile(String uri) { removeFrom("pickedFiles", uri); }

    private void addTo(String key, String val) {
        java.util.Set<String> s = new java.util.HashSet<>(p.getStringSet(key, java.util.Collections.emptySet()));
        s.add(val);
        p.edit().putStringSet(key, s).apply();
    }

    private void removeFrom(String key, String val) {
        java.util.Set<String> s = new java.util.HashSet<>(p.getStringSet(key, java.util.Collections.emptySet()));
        s.remove(val);
        p.edit().putStringSet(key, s).apply();
    }

    // ---- app PIN lock
    String pinHash()   { return p.getString("pinHash", ""); }
    String pinSalt()   { return p.getString("pinSalt", ""); }
    boolean pinSet()   { return !pinHash().isEmpty(); }

    boolean configured() { return !url().isEmpty() && !anonKey().isEmpty() && !refresh().isEmpty(); }

    // ---- back up new chat media (WhatsApp/Telegram/etc. albums) promptly instead of sweeping everything
    boolean chatMediaWatch() { return p.getBoolean("chatMediaWatch", false); }

    // ---- lifetime stats shown on the dashboard
    long totalUploaded() { return p.getLong("totalUploaded", 0); }
    void addUploaded(int n) { if (n > 0) p.edit().putLong("totalUploaded", totalUploaded() + n).apply(); }
    long lastBackupAt() { return p.getLong("lastBackupAt", 0); }
    void setLastBackupAt(long t) { p.edit().putLong("lastBackupAt", t).apply(); }
}
