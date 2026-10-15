package com.whitevpn.app

import com.wireguard.config.Config
import com.wireguard.crypto.KeyPair
import java.io.ByteArrayInputStream

data class GeneratedWireGuardProfile(
    val config: String,
    val clientPublicKey: String
)

/**
 * This constructs a client profile for a server the user controls.
 * It cannot register the client public key on a third-party server;
 * the server administrator must explicitly authorize the generated peer.
 */
object WireGuardProfileGenerator {
    fun generate(
        endpoint: String,
        serverPublicKey: String,
        clientAddress: String,
        dns: String
    ): GeneratedWireGuardProfile {
        val inputs = listOf(endpoint, serverPublicKey, clientAddress, dns)
        require(inputs.all { it.isNotBlank() && it.length < 256 && !it.contains('\n') && !it.contains('\r') }) {
            "Invalid input"
        }

        val pair = KeyPair()
        val raw = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = " + pair.privateKey.toBase64())
            appendLine("Address = " + clientAddress.trim())
            appendLine("DNS = " + dns.trim())
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = " + serverPublicKey.trim())
            appendLine("Endpoint = " + endpoint.trim())
            appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
            appendLine("PersistentKeepalive = 25")
        }

        val validated = Config.parse(
            ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8))
        )
        return GeneratedWireGuardProfile(
            config = validated.toWgQuickString(),
            clientPublicKey = pair.publicKey.toBase64()
        )
    }
}
