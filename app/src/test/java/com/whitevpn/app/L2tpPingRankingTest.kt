package com.whitevpn.app

import org.junit.Assert.assertEquals
import org.junit.Test

class L2tpPingRankingTest {
    @Test fun reachableBeforeUntestedBeforeTimeout() {
        val profiles = listOf("a","b","c","d").map {
            L2tpProfile(id = it, name = it, host = "example.com", username = "vpn",
                password = "vpn", preSharedKey = "vpn")
        }
        val ping = mapOf<String, Double?>("a" to null, "b" to 96.0, "c" to 24.0)
        assertEquals(listOf("c", "b", "d", "a"),
            L2tpPingRanking.sortProfiles(profiles, ping).map { it.id })
    }

    @Test fun explicitNoReplyIsLast() {
        val pings = mapOf<String, Double?>("bad" to null, "fast" to 7.0)
        assertEquals(2, L2tpPingRanking.group("bad", pings))
        assertEquals(1, L2tpPingRanking.group("untested", pings))
        assertEquals(0, L2tpPingRanking.group("fast", pings))
    }
}
