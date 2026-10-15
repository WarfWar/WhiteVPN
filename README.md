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


## WhiteVPN 0.3.0

- Contrast fix: Material3 uses a dark Surface with explicit light content color.
  Every card also declares light content text, instead of black-on-dark text.
- Best-effort device-side ICMP ping for the ten VPNBook hosts with refresh,
  three concurrent probes and 3.5-second timeouts. No ping response does not
  mean the UDP WireGuard port is unreachable.
- Local WireGuard wizard: enter your OWN server endpoint, its public key, the
  address assigned to the client and DNS. New client keys are generated locally
  using the official WireGuard library and the validated configuration is
  encrypted on disk. You MUST register the public key it displays with your
  server administrator. Never register or share your client private key.
- OpenVPN hand-off: select a .ovpn file and open it in a separate compatible
  VPN application through Android ACTION_VIEW with the OpenVPN profile MIME.
  WhiteVPN does NOT have an embedded OpenVPN tunneling engine yet.
- L2TP/IPsec legacy: NOT implemented. Android 12 and later no longer expose
  this option on many devices. The button opens Android VPN settings; modern
  IPsec users should prefer IKEv2/IPsec.

## Automatic configuration limitations

VPNBook requires users to accept its terms and complete a site check, then
registers a temporary WireGuard peer on its own server. There is no documented
public API to automate that safely. Client-side key generation by itself does
NOT provision VPNBook or any other third-party server. We do not attempt to
bypass the provider's check and do not advertise fake free credentials.

For OpenVPN on Android you can use a separate client such as
https://github.com/schwabe/ics-openvpn or an app that handles .ovpn files.

Measured ping is not a WireGuard handshake check, connection proof, or a
security guarantee. Test on real phones and networks before relying on it.


## WhiteVPN 0.4.0 — saved profile ping and platform IKEv2

- The **My connections** cards now display device-side best-effort ICMP latency to the
  validated WireGuard peer Endpoint of **each saved profile** and include a
  refresh button. Host-only information is exposed to the UI: private keys
  and peer key material are not logged or sent to any diagnostics service.
- The endpoint parser handles standard host:port and bracketed IPv6 endpoints.
  The ping subprocess only accepts strictly validated ASCII hostnames/IPs,
  with a strict timeout and concurrency cap; it is never run via a shell.
- ICMP and WireGuard/UDP are different. No ping reply is not a proof that a
  VPN server is down. Ping while connected might traverse the active tunnel.
- **IKEv2/IPsec (EAP username/password)** is now provisioned through Android's
  built-in VpnManager, using the system's VPN consent screen and reporting the
  **actual native profile state** on Android 13+ devices that support
  IPsec tunnels. The client restricts concurrent WireGuard and IKEv2
  connections. Its IKEv2 form credentials are handed only to Android's
  platform VPN service and are not stored in WhiteVPN's WireGuard profile
  database. No IKEv2 server, subscription, or credentials are provided.
- Compatibility: IKEv2 is disabled on Android versions without the required
  API or hardware feature. The built-in OpenVPN engine remains *not yet
  integrated*. The existing .ovpn hand-off to a separate OpenVPN client works
  as before. Legacy L2TP/IPsec is also not implemented.

References:
- Android platform VpnManager: https://developer.android.com/reference/android/net/VpnManager
- Ikev2VpnProfile: https://developer.android.com/reference/android/net/Ikev2VpnProfile.Builder
- The OpenVPN for Android implementation is GPL-licensed and not a drop-in SDK:
  https://github.com/schwabe/ics-openvpn

This Android build and its tests do not prove successful VPN handshake or
traffic protection in production; test with known working servers and inspect
Android's actual VPN state and routing on a device.
