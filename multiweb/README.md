# MultiWeb

Android app that runs up to 6 isolated WhatsApp Web sessions side by side.

- Each account slot runs in its own process with its own WebView data directory, so logins never mix.
- Link each slot by scanning the QR code from WhatsApp → Linked devices.
- Supports file upload, camera/mic permissions, downloads (saved to Downloads) and an unread-count notification.
- Build: `ANDROID_HOME=/path/to/sdk gradle assembleDebug` (JDK 17+, Android SDK 34). minSdk 29.
