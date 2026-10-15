package com.whitevpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerPingTest {
    @Test fun parsesLinuxPing() {
        assertEquals(12.8, ServerPing.parseRtt(
            "64 bytes from 1.2.3.4: icmp_seq=1 ttl=51 time=12.8 ms"
        )!!, 0.01)
    }

    @Test fun parsesDecimalComma() {
        assertEquals(19.2, ServerPing.parseRtt("time=19,2 ms")!!, 0.01)
    }

    @Test fun noFakePingOnFailure() {
        assertNull(ServerPing.parseRtt("100% packet loss"))
        assertEquals("ICMP: нет ответа", ServerPing.label(null))
    }
}
