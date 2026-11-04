package com.cloudbackup.app;

import java.util.Locale;

/**
 * Matches MediaStore album names belonging to chat apps, so new photos/videos that land there can be
 * backed up promptly without sweeping the whole camera roll. Matched by substring, case-insensitively,
 * since exact album names vary a little across app versions and phone makers.
 */
final class ChatMediaBuckets {
    private static final String[] NEEDLES = {"whatsapp", "telegram", "instagram", "facebook", "messenger"};

    static boolean matches(String bucketDisplayName) {
        if (bucketDisplayName == null) return false;
        String b = bucketDisplayName.toLowerCase(Locale.US);
        for (String n : NEEDLES) if (b.contains(n)) return true;
        return false;
    }
}
