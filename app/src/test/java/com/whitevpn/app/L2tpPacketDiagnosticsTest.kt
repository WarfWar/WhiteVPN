package com.whitevpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class L2tpPacketDiagnosticsTest {
    @Test fun differentiateNoReplyAndRejectedHandshake() {
        val sentOnly = L2tpPacketCounts().record(true, 500).record(true, 500)
        assertEquals(2, sentOnly.totalSent)
        assertEquals(0, sentOnly.totalReceived)
        assertTrue(sentOnly.diagnosis().contains("нет UDP-ответа"))

        val hasReply = sentOnly.record(false, 500)
        assertEquals(1, hasReply.totalReceived)
        assertTrue(hasReply.diagnosis().contains("Ответы UDP поступают"))
    }

    @Test fun ignoreOtherPortsAndSaturateCounters() {
        val state = L2tpPacketCounts().record(true, 1701).record(false, 9999)
        assertEquals(0, state.totalSent + state.totalReceived)
        assertEquals(1, state.record(true, 4500).sent4500)
    }
}
