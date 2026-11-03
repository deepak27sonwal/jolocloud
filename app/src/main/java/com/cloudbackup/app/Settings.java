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

    // ---- app PIN lock
    String pinHash()   { return p.getString("pinHash", ""); }
    String pinSalt()   { return p.getString("pinSalt", ""); }
    boolean pinSet()   { return !pinHash().isEmpty(); }

    boolean configured() { return !url().isEmpty() && !anonKey().isEmpty() && !refresh().isEmpty(); }
}
