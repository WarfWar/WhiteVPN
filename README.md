# WhiteVPN

Android WireGuard VPN client. Kotlin + Jetpack Compose.

Functions: encrypted local config storage (AES-GCM with Android Keystore); WireGuard .conf file import and validation; Android VPN system permission; real WireGuard GoBackend tunnel on/off; dark UI.

Requires a real WireGuard server/profile. This repository contains no keys, credentials, or server provisioning. The UI reports that the tunnel was started, not that a remote handshake succeeded.

## APK build

Open GitHub Actions > Build Android APK > Run workflow. After success, download artifact WhiteVPN-debug-apk.

Local requirements: Android SDK 35, JDK 17, Gradle 8.11.1. Build command: gradle :app:assembleDebug.

Debug APKs are not Google Play-distributed signed releases. Do not disable Play Protect. Full production verification and real-server testing remain future tasks.

## Free VPNBook WireGuard servers (v0.2.0)

WhiteVPN includes a catalog of **10 public WireGuard endpoints** listed by VPNBook
(US, CA, GB, DE, FR). These are **not usable shared WireGuard keys**: clients
must generate a unique profile themselves via the provider's webpage.

1. Open WhiteVPN > Free servers > choose a server > Get free profile.
2. On the official VPNBook website choose **the same hostname** and a port.
3. Accept the provider conditions and generate/download your **own** .conf file.
4. Return to WhiteVPN and tap **Import .conf**.
5. The imported profile will be selected; tap Connect VPN.
6. The profile expires after 7 days; generate a new one when it expires.

Source: https://www.vpnbook.com/ru/freevpn/wireguard-vpn

Privacy: https://www.vpnbook.com/contact — according to VPNBook they log the
source IP address and connection timestamp and delete logs weekly.
Service is for personal use; third-party availability, security and performance
are not guaranteed. WhiteVPN is not affiliated with VPNBook.

Profiles are encrypted at rest using Android Keystore and AES-256-GCM. On app
upgrade, the legacy 0.1.0 single profile is migrated automatically into the
multi-profile encrypted store. Old encrypted data is retained as a migration
backup, and app backup is disabled.

No remote website is sent stored private keys by WhiteVPN itself.
