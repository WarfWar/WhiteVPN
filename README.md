# WhiteVPN

Android WireGuard VPN client. Kotlin + Jetpack Compose.

Functions: encrypted local config storage (AES-GCM with Android Keystore); WireGuard .conf file import and validation; Android VPN system permission; real WireGuard GoBackend tunnel on/off; dark UI.

Requires a real WireGuard server/profile. This repository contains no keys, credentials, or server provisioning. The UI reports that the tunnel was started, not that a remote handshake succeeded.

## APK build

Open GitHub Actions > Build Android APK > Run workflow. After success, download artifact WhiteVPN-debug-apk.

Local requirements: Android SDK 35, JDK 17, Gradle 8.11.1. Build command: gradle :app:assembleDebug.

Debug APKs are not Google Play-distributed signed releases. Do not disable Play Protect. Full production verification and real-server testing remain future tasks.
