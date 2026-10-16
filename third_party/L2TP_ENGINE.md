# Native L2TP/IPsec Android experimental integration

The Kotlin IKEv1 + ESP + L2TPv2 + PPP protocol stack is included as a git submodule:
https://github.com/Flo354/L2TP-IPSec-VPN-for-Android at commit a9073671291bbececfb4cb6ee9abb521d4f7da9b.

Upstream MIT license: `third_party/l2tp/LICENSE`. Two Android platform adapters, `AndroidUdpSocketFactory.kt` and `AndroidTunProvider.kt`, are redistributed unchanged under the same MIT license with their original headers in this repository.

**Security:** upstream publicly says this stack is AI-generated and lacks independent human security review. It has been tested against one hardware router and strongSwan labs; it has not been tested against the public relays in WhiteVPN. It is experimental, disabled until the user starts a connection and gives Android VPN consent, and not recommended for sensitive traffic. IKEv1 L2TP PSK and public shared keys do not guarantee confidentiality or endpoint identity.

Do not hardcode credential secrets in source or logs. WhiteVPN loads the encrypted profile on the service worker thread and never passes passwords/PSK via Intent.
