package com.whitevpn.app

import com.arcansecurity.vpn.l2tpipsec.core.crypto.DhGroup
import com.arcansecurity.vpn.l2tpipsec.core.crypto.EspEncryption
import com.arcansecurity.vpn.l2tpipsec.core.crypto.EspIntegrity
import com.arcansecurity.vpn.l2tpipsec.core.crypto.IkeEncryption
import com.arcansecurity.vpn.l2tpipsec.core.crypto.IkeHash
import com.arcansecurity.vpn.l2tpipsec.core.tunnel.Phase1Proposal
import com.arcansecurity.vpn.l2tpipsec.core.tunnel.Phase2Proposal
import com.arcansecurity.vpn.l2tpipsec.core.tunnel.VpnConfig

/**
 * The default strong proposal fails against many volunteer SoftEther peers:
 * their IKEv1 implementation advertises SHA1 and DH1/2/5 instead of
 * SHA256/DH14. The legacy proposal is opt-in only and never silently used.
 *
 * SHA1, 1024-bit MODP and public/shared PSKs are weak for authenticated VPN.
 */
enum class L2tpCryptoMode {
    STRONG,
    SOFTETHER_LEGACY
}

object L2tpCryptoProfiles {
    fun create(profile: L2tpProfile, mode: L2tpCryptoMode): VpnConfig {
        require(L2tpProfileValidation.isValid(profile)) { "Invalid server profile" }
        val strong = VpnConfig(
            serverHost = profile.host,
            username = profile.username,
            password = profile.password,
            presharedKey = profile.preSharedKey
        )
        if (mode == L2tpCryptoMode.STRONG) return strong
        return strong.copy(
            phase1 = Phase1Proposal(
                encryption = IkeEncryption.AES_CBC_128,
                hash = IkeHash.SHA1,
                dhGroup = DhGroup.MODP_1024
            ),
            phase2 = Phase2Proposal(
                encryption = EspEncryption.ESP_AES_CBC_128,
                integrity = EspIntegrity.HMAC_SHA1_96
            )
        )
    }
}
