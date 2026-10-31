# Jolocloud

Android app that backs up photos, videos and contacts to a private Supabase Storage bucket.

- Sign in with email/password (Supabase Auth); files go to `backups/<user-id>/…`, protected by row-level policies (`supabase/setup.sql`).
- Each file is uploaded once; optional daily backup on Wi-Fi while charging.
- Build: `ANDROID_HOME=/path/to/sdk gradle assembleDebug` (needs JDK 17+, Android SDK 34).
- The project URL and publishable key in `Settings.java` are public client values; change them for your own project.
