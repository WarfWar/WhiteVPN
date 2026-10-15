package com.whitevpn.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PingHostValidationTest {
    @Test fun allowsStoredWireGuardPeers() {
        assertTrue(ServerPing.validHost("myvpn.example.com"))
        assertTrue(ServerPing.validHost("198.51.100.30"))
        assertTrue(ServerPing.validHost("2001:db8::1"))
    }

    @Test fun blocksCliInjectionAndUnknownHosts() {
        assertFalse(ServerPing.validHost("-c"))
        assertFalse(ServerPing.validHost("--help"))
        assertFalse(ServerPing.validHost("vpn.example.com;id"))
        assertFalse(ServerPing.validHost("vpn.example.com something"))
        assertFalse(ServerPing.validHost(""))
    }
}
