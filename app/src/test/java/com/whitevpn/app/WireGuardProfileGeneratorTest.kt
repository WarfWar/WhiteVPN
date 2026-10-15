package com.whitevpn.app

import com.wireguard.config.Config
import com.wireguard.crypto.KeyPair
import java.io.ByteArrayInputStream
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardProfileGeneratorTest {
    @Test fun generatesValidConfigurationWithFreshClientKey() {
        val serverKey = KeyPair().publicKey.toBase64()
        val result = WireGuardProfileGenerator.generate(
            "vpn.example.com:51820", serverKey, "10.8.0.2/32", "1.1.1.1"
        )
        assertTrue(result.clientPublicKey.isNotBlank())
        val parsed = Config.parse(
            ByteArrayInputStream(result.config.toByteArray(Charsets.UTF_8))
        )
        assertTrue(parsed.peers.isNotEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesInjectedConfigFields() {
        WireGuardProfileGenerator.generate(
            "vpn.example.com:51820\n[Interface]",
            KeyPair().publicKey.toBase64(),
            "10.8.0.2/32",
            "1.1.1.1"
        )
    }
}
