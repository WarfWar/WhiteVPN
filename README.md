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


## WhiteVPN 0.5.0 — L2TP/IPsec PSK server management

Users can now add, edit, and delete **L2TP/IPsec PSK server profiles** in the
"Другие протоколы" section of the app:

- Friendly server name and hostname/IP address (without a port).
- L2TP username and password.
- IPsec pre-shared key (PSK).
- Separate manual clipboard copying of each parameter for client setup.
- Optional device-side ICMP ping to saved L2TP server hostname.
- The list of L2TP settings is encrypted at rest using a separate Android
  Keystore AES-256-GCM key and AtomicFile. Password/PSK fields are masked.
- The L2TP server database is not included in Android automatic backups.

**Protocol limitations:** This is secure profile management, NOT a built-in
L2TP/IPsec client. In particular, Android 13+ removed the old native L2TP
option. Use a separately installed L2TP/IPsec-compatible client on these
devices; the app has a link to an available Play Store L2TP client, but no
verified deep link to import credentials or start that third-party VPN.
The user must copy and enter the credentials in the chosen client manually.
The system VPN settings shortcut is retained for older devices that still
offer L2TP. WhiteVPN never displays a fake "connected" state.

The optional L2TP app referenced in the UI is provided by an independent
publisher; WhiteVPN does not endorse its privacy practices, guarantee
compatibility or bundle any code from it. Sharing secrets via Android clipboard
is explicitly user-initiated and carries the usual clipboard-access risks.

Do not share real IPsec PSKs and VPN passwords in screenshots, issues, or
public GitHub repositories. All VPN profiles are user-supplied.


## WhiteVPN 0.6.0 — IPSpeed public L2TP/IPsec servers

The app now includes the publicly advertised **19 L2TP/IPsec PSK endpoints**
from https://ipspeed.info/free-l2tpipsec.php, as listed on 2026-10-15 19:00 UTC:
Croatia (2), Japan (16), United States (1).

- The catalog is visible inside **Other protocols > L2TP/IPsec PSK > IPSpeed**.
- When opened, the app attempts an HTTPS refresh of the source web page with a
  bounded download size, timeouts, IPv4 validation and strict HTML table parsing.
  If this fails, an explicitly labeled bundled snapshot remains visible.
- Users can filter by country, refresh, save an individual server, or import all
  listed servers. Existing profiles are never overwritten by catalog imports.
- Saved profiles include the public credentials shown on IPSpeed:
  username `vpn`, password `vpn`, shared IPsec PSK `vpn`.
  They are encrypted at rest alongside custom L2TP settings using Android
  Keystore + AES-GCM. Public data is not a secret, but the same encrypted storage
  is used consistently.
- Published site ping and uptime are shown as **site-reported** metadata, not
  a live test from the phone. Device-side ICMP checks remain available on each
  saved L2TP profile.

**Security note:** This provider uses public/shared passwords and PSK; these
endpoints are third-party infrastructure. Do not assume privacy, reliability
or secure authentication against network interception. Do not use these
configurations for sensitive information. OpenVPN and WireGuard connections
remain separate protocols. The WhiteVPN L2TP section currently manages
profiles but does not implement an embedded L2TP/IPsec tunnel; a compatible
external client is required on many recent Android devices.


## WhiteVPN 0.7.0 — Experimental native L2TP/IPsec and VPN Gate catalog

### Open L2TP/IPsec listings
- **IPSpeed**: the existing static fallback snapshot and live refresh.
- **VPN Gate, University of Tsukuba**: a second independent free directory,
  live HTML refresh from its official website (or official mirror). Only rows
  with the provider's explicit L2TP/IPsec support icon are accepted, and
  the host IP is validated. A tiny fallback snapshot is labelled as such.
- Repeated servers are identified by IP. Saving an already saved IP is blocked
  in the UI. Other unknown lists are not automatically included: server
  credentials, L2TP compatibility or provider permissions could not be
  verified. Do not treat raw VPN Gate total counts as L2TP-ready servers.
- VPN Gate explicitly publishes Username=vpn, Password=vpn, PSK=vpn.
  Public shared PSKs provide weak server identity assurances.

### Experimental embedded engine
- A pinned upstream MIT-licensed Kotlin implementation of IKEv1, IPsec
  ESP, L2TPv2, and PPP is provided as a Git submodule at
  third_party/l2tp (commit a9073671291bbececfb4cb6ee9abb521d4f7da9b).
- The submodule's core Kotlin JVM source is compiled directly into WhiteVPN.
  The two Android adapters are vendored with MIT attribution.
- A dedicated Android VpnService handles user VPN consent, foreground
  notification, local encrypted profile loading, protected UDP sockets,
  TUN interface establishment, and shutdown.
- The UI reports CONNECTED only after upstream PPP/TUN listener confirms it.
  An ordinary ICMP ping is not a successful VPN connection.
- The experimental engine does not silently downgrade to weak IKE algorithms.
  Old public VPN Gate/IPSpeed servers may reject the proposals and fail.
  Protocol-negotiation settings and safe compatibility modes require
  further work and testing on real servers.

**IMPORTANT:** Upstream explicitly states this entire cryptographic stack
was AI-written and not independently reviewed by a human security expert.
Building and passing unit tests do not prove cryptographic soundness,
interoperability or data-leak prevention. Avoid sensitive uses. Public
L2TP IPsec PSKs and IKEv1 carry additional inherent security problems.

Some Android builds may manage foreground permissions differently;
the service and the VPN permission UI require testing on the user's device.

Native L2TP and an active WireGuard/IKEv2 VPN should not be run together.


## WhiteVPN 0.8.0 — protocol tabs and L2TP responsiveness

- Main screen separates WireGuard, L2TP, IKEv2 and OpenVPN into tabs. The WireGuard
  connect button no longer appears in the L2TP tab; L2TP has its own primary
  connect/cancel button for the selected saved server.
- The L2TP tab has three separate sources: My profiles, IPSpeed, VPN Gate.
  Public source lists are not conflated with local saved profiles.
- Saved L2TP profiles are sorted by **device-side** ICMP latency ascending,
  followed by pending/not-yet-tested profiles, followed by profiles that
  did not answer ICMP. A user can explicitly select a different saved profile.
- IPSpeed and VPN Gate rows are sorted similarly using up to 48 bounded
  best-effort device-side ICMP probes per selected source; the full server
  catalogue remains visible even when a probe is skipped or fails. Provider
  ping is labelled separately; neither value verifies L2TP/IPsec availability.
- Cancel is available while IKE/IPsec/L2TP negotiation is in progress.
  A foreground-service watchdog ends a connection attempt after ~55 seconds
  if the protocol stack has not reported connection establishment; the
  Android TUN socket is closed and the worker is interrupted. The screen
  reports timeout rather than remaining indefinitely at "Connecting".
- The user should still verify end-to-end VPN traffic, routing, DNS and
  disconnect on a real device. The experimental upstream crypto code lacks
  an independent human security audit. Avoid sensitive traffic or public PSKs.


## WhiteVPN 0.9.0 — Explicit SoftEther phase-1 compatibility

**Root cause for many stalls at IKE_PHASE1:** the upstream engine's strong IKEv1
default is AES-256/SHA-256/MODP-2048 (DH14). Official SoftEther L2TP/IPsec
server specifications describe SHA-1 and DH groups 1/2/5. The strong proposals
often cannot be negotiated with volunteer VPN Gate peers.

A new **explicit, opt-in** "SoftEther compatibility" control is available on
the L2TP tab for each attempt:

- Default remains **AES-256/SHA-256/DH14**, ESP AES-256/HMAC-SHA-256.
- Compatibility sets **AES-128/SHA-1/DH2 (MODP 1024)** for IKE phase 1 and
  **ESP AES-128/HMAC-SHA-1** for phase 2. These algorithms are weak and should
  only be used for legacy interoperability, never confidential traffic.
- Configuration choice is sent to the local VPN service as a non-secret enum.
  Actual PSK and password remain encrypted in Android Keystore and are loaded
  inside the service as before. The service never falls back to weaker
  cryptography without user opt-in.
- More actionable L2TP status messages distinguish IKE no reply, proposal
  rejected, authentication failed and PPP password rejected. After 55 seconds,
  the watchdog reports which stage stalled and stops the attempt.
- **Successful IKE or ICMP does not prove full VPN security**. A VPN Gate
  server using a publicly shared PSK cannot provide trustworthy server identity.
  This is an experimental, unaudited implementation. Verify on a physical
  Android phone; public servers can disappear or block UDP 500/4500.

Sources:
- SoftEther server specifications: https://www.softether.org/spec
- Upstream core implementation: https://github.com/Flo354/L2TP-IPSec-VPN-for-Android


## WhiteVPN 0.10.0 — Diagnose IKE Phase 1 (public L2TP relays)

- Shows UDP sent/received **packet counts by port** (500 and 4500) on the
  L2TP tab, using a wrapper around the protected VPN UDP socket.
  Does NOT inspect, log, upload or store datagram payloads or VPN credentials.
- If the client sent IKE but received **zero responses from the selected
  server IP**, the screen makes this explicit. ICMP ping is not evidence
  of UDP/500 or UDP/4500 availability. Check server availability, UDP
  filtering, carrier/Wi-Fi restrictions, and network connectivity.
- If UDP answers exist but IKE does not advance, focus on crypto proposal
  compatibility or the experimental protocol implementation. It is not
  a guarantee that a valid IKE handshake occurred.
- Both the built-in 45-second protocol timeout and WhiteVPN's 55-second
  watchdog remain in place and expose useful per-port counters after failure.
- IPSpeed directory's bundled snapshot was updated from the official
  published page on 2026-10-16 (17:00 UTC): 3 Croatia + 16 Japan. The
  new URL fetch supports up to two same-site HTTPS redirects, tries
  English and Russian pages, and surfaces status/failure reason instead of
  a generic refresh error. Source pages change rapidly; neither snapshots
  nor ICMP ping prove actual VPN connectivity.
- The SoftEther SHA-1/DH2 compatibility mode remains explicitly opt-in.
  Public L2TP IPsec PSKs and a third-party unaudited IKE stack cannot be
  considered a trustworthy VPN for sensitive traffic.

This build has only compile and unit-test verification. True connection
success and leak-free routing can only be validated against a known-good
L2TP server on an actual Android phone.
