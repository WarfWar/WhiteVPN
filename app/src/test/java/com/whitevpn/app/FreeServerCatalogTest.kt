package com.whitevpn.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FreeServerCatalogTest {
    @Test fun uniquePublicEndpoints() {
        assertEquals(10, FreeServerCatalog.servers.size)
        assertEquals(10, FreeServerCatalog.servers.map { it.host }.distinct().size)
        assertEquals(5, FreeServerCatalog.servers.map { it.country }.distinct().size)
    }
    @Test fun recognizesImportedProfileByEndpoint() {
        val sample = "[Interface]\nPrivateKey = example\n[Peer]\nEndpoint = de20.vpnbook.com:443"
        assertEquals("de20.vpnbook.com",
            FreeServerCatalog.matchConfiguration(sample)?.host)
    }
    @Test fun doesNotMatchUnrelatedOrMentionInComment() {
        assertNull(FreeServerCatalog.matchConfiguration("# Endpoint = us16.vpnbook.com"))
        assertNull(FreeServerCatalog.matchConfiguration("Endpoint = my.example.com:51820"))
    }
}
