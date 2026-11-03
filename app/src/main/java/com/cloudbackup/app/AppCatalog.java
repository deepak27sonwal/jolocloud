package com.cloudbackup.app;

import java.util.HashMap;
import java.util.Map;

/** Apps whose notifications are captured, with the friendly name stored in the backup. */
final class AppCatalog {
    private static final Map<String, String> NAMES = new HashMap<>();

    private static void add(String name, String... packages) {
        for (String p : packages) NAMES.put(p, name);
    }

    static {
        add("WhatsApp", "com.whatsapp");
        add("WhatsApp Business", "com.whatsapp.w4b");
        add("Facebook", "com.facebook.katana", "com.facebook.lite");
        add("Messenger", "com.facebook.orca", "com.facebook.mlite");
        add("Instagram", "com.instagram.android", "com.instagram.lite");
        add("Telegram", "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.plus",
                "org.thunderdog.challegram");
        add("Snapchat", "com.snapchat.android");
        add("SMS", "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
                "com.android.messaging", "com.miui.mms", "com.oneplus.mms", "com.oppo.mms", "com.coloros.mms",
                "com.vivo.mms", "com.motorola.messaging", "com.sonyericsson.conversations", "com.asus.message",
                "com.huawei.message", "com.nothing.messaging");
    }

    /** Friendly app name, or null if the package is not one of the listed apps. */
    static String nameOf(String pkg) { return NAMES.get(pkg); }

    static final String SUMMARY = "WhatsApp, WhatsApp Business, Facebook, Messenger, Instagram, Telegram, Snapchat and SMS apps";
}
