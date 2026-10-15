package com.whitevpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VpnEndpointTest {
    @Test fun extractsHostWithoutReadingPrivateKey() {
        val config = """
            [Interface]
            PrivateKey = VERY_SECRET
            Address = 10.0.0.2/32

            [Peer]
            PublicKey = remote
            Endpoint = us16.vpnbook.com:51820
        """.trimIndent()
        assertEquals("us16.vpnbook.com", VpnEndpoint.hostFromConfig(config))
    }

    @Test fun supportsBracketedIpv6() {
        assertEquals("2001:db8::1", VpnEndpoint.hostFromConfig(
            "[Peer]\nEndpoint = [2001:db8::1]:51820"))
    }

    @Test fun refusesInvalidEndpointAndPort() {
        assertNull(VpnEndpoint.hostFromConfig("[Peer]\nEndpoint = -c:51820"))
        assertNull(VpnEndpoint.hostFromConfig("[Peer]\nEndpoint = host:70000"))
        assertNull(VpnEndpoint.hostFromConfig("[Interface]\nEndpoint = host:51820"))
        assertNull(VpnEndpoint.hostFromConfig("[Peer]\nEndpoint = foo;bar:51820"))
    }
}
