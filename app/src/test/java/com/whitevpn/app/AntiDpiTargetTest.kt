package com.whitevpn.app

import com.whitevpn.app.antidpi.AntiDpiDns
import com.whitevpn.app.antidpi.AntiDpiTarget
import org.junit.Assert.*
import org.junit.Test

class AntiDpiTargetTest {
    @Test fun defaultsSelectOnlyThreeNamedServices() {
        assertEquals(AntiDpiTarget.entries.toSet(), AntiDpiTarget.fromNames(null))
        assertEquals(setOf(AntiDpiTarget.YOUTUBE), AntiDpiTarget.fromNames(listOf("YOUTUBE")))
        assertTrue(AntiDpiTarget.fromNames(emptyList()).isEmpty())
    }

    @Test fun packagesNeverIncludeWhiteVpnOrUnknownApps() {
        val all = AntiDpiTarget.packagesOf(AntiDpiTarget.entries.toSet())
        assertTrue(all.contains("org.telegram.messenger"))
        assertTrue(all.contains("com.google.android.youtube"))
        assertTrue(all.contains("com.instagram.android"))
        assertFalse(all.contains("com.whitevpn.app"))
        assertEquals(all.size, all.distinct().size)
    }

    @Test fun dnsDefaultsToSystemNotPublicResolver() {
        assertEquals(AntiDpiDns.SYSTEM, AntiDpiDns.fromId(null))
        assertEquals(AntiDpiDns.SYSTEM, AntiDpiDns.fromId("unknown"))
        assertNull(AntiDpiDns.SYSTEM.address)
    }
}
