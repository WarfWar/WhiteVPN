package com.whitevpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IpSpeedL2tpCatalogTest {
    @Test fun bundledServers() {
        assertEquals(19, IpSpeedL2tpCatalog.snapshot.size)
        assertEquals(19, IpSpeedL2tpCatalog.snapshot.map { it.ip }.distinct().size)
        assertEquals(2, IpSpeedL2tpCatalog.snapshot.count { it.country == "Хорватия" })
        assertEquals(16, IpSpeedL2tpCatalog.snapshot.count { it.country == "Япония" })
        assertEquals(1, IpSpeedL2tpCatalog.snapshot.count { it.country == "США" })
    }

    @Test fun safelyParseLiveTableRows() {
        val html = """
        <table>
          <tr><th>#</th><th>Location</th><th>IP</th><th>Uptime</th><th>Ping</th></tr>
          <tr><td>1</td><td>Japan</td><td>219.100.37.176</td><td>12 days</td><td>8 ms</td></tr>
          <tr><td>2</td><td>Croatia (LOCAL Name: Hrvatska)</td><td>150.40.105.16</td><td>15 days</td><td>1 ms</td></tr>
          <tr><td>3</td><td>Japan</td><td>999.1.1.1</td><td>1 day</td><td>1 ms</td></tr>
        </table>
        """.trimIndent()
        val parsed = IpSpeedL2tpCatalog.parse(html)
        assertEquals(2, parsed.size)
        assertEquals("Япония", parsed[0].country)
        assertEquals("Хорватия", parsed[1].country)
    }

    @Test fun profileIncludesPublishedCredentials() {
        val profile = IpSpeedL2tpCatalog.toProfile(IpSpeedL2tpCatalog.snapshot.first())
        assertTrue(L2tpProfileValidation.isValid(profile))
        assertEquals("vpn", profile.username)
        assertEquals("vpn", profile.password)
        assertEquals("vpn", profile.preSharedKey)
    }
}
