package com.whitevpn.app

import com.arcansecurity.vpn.l2tpipsec.core.crypto.DhGroup
import com.arcansecurity.vpn.l2tpipsec.core.crypto.EspIntegrity
import com.arcansecurity.vpn.l2tpipsec.core.crypto.IkeEncryption
import com.arcansecurity.vpn.l2tpipsec.core.crypto.IkeHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class L2tpCryptoProfilesTest {
    private val profile = L2tpProfile(
        name = "Test", host = "vpn.example.org", username = "user",
        password = "example-password", preSharedKey = "example-psk"
    )

    @Test fun strongRemainsDefault() {
        val config = L2tpCryptoProfiles.create(profile, L2tpCryptoMode.STRONG)
        assertEquals(DhGroup.MODP_2048, config.phase1.dhGroup)
        assertEquals(IkeHash.SHA2_256, config.phase1.hash)
    }

    @Test fun explicitSoftetherLegacyProposal() {
        val config = L2tpCryptoProfiles.create(profile, L2tpCryptoMode.SOFTETHER_LEGACY)
        assertEquals(IkeEncryption.AES_CBC_128, config.phase1.encryption)
        assertEquals(IkeHash.SHA1, config.phase1.hash)
        assertEquals(DhGroup.MODP_1024, config.phase1.dhGroup)
        assertEquals(EspIntegrity.HMAC_SHA1_96, config.phase2.integrity)
        assertNotEquals(profile.password, config.toString())
    }
}
