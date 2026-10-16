package com.whitevpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnGateL2tpCatalogTest {
    @Test fun filterOnlyL2tpCompatibleServers() {
        val html = """
            <table id='vg_hosts_table_id'>
              <tr>
                <td>Japan</td><td><b>example.opengw.net</b><br>219.100.37.81</td>
                <td>32 sessions<br>13 days</td><td>Ping: 8 ms</td>
                <td><a>SSL</a></td>
                <td><a href='howto_l2tp.aspx'><img src='../images/yes_33.png'></a></td>
                <td>OpenVPN</td><td>MS-SSTP</td><td>Operator</td><td>Score</td>
              </tr>
              <tr>
                <td>USA</td><td>example2 198.51.100.7</td><td>12 days</td><td>Ping: 6 ms</td>
                <td>SSL</td><td><img src='../images/no_33.png'></td>
                <td>OpenVPN</td><td>MS-SSTP</td><td>Operator</td><td>Score</td>
              </tr>
              <tr>
                <td>Japan</td><td>example3 999.999.999.999</td><td>5 days</td><td>Ping: 1 ms</td>
                <td>SSL</td><td><img src='../images/yes_33.png'></td>
                <td>OpenVPN</td><td>MS-SSTP</td><td>Operator</td><td>Score</td>
              </tr>
            </table>
        """.trimIndent()
        val servers = VpnGateL2tpCatalog.parse(html)
        assertEquals(1, servers.size)
        assertEquals("219.100.37.81", servers[0].host)
        assertEquals("Япония", servers[0].country)
        assertEquals("8 мс (сайт)", servers[0].sitePing)
    }

    @Test fun legitimatePublicCredentials() {
        assertTrue(L2tpProfileValidation.isValid(VpnGateL2tpCatalog.profile(
            VpnGateL2tpCatalog.fallback.first())))
        assertEquals("vpn", VpnGateL2tpCatalog.psk)
    }
}
