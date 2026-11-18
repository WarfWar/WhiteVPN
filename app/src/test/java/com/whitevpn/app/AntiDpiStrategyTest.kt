package com.whitevpn.app

import com.whitevpn.app.antidpi.AntiDpiStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiDpiStrategyTest {
    @Test fun fallbackIsSafeAndDeterministic() {
        assertEquals(AntiDpiStrategy.SPLIT, AntiDpiStrategy.fromId("unknown"))
        assertEquals(AntiDpiStrategy.DISORDER, AntiDpiStrategy.fromId("DISORDER"))
    }

    @Test fun argumentsOnlyBindToLocalhost() {
        AntiDpiStrategy.entries.forEach {
            val flags = it.arguments().toList()
            assertEquals("ciadpi", flags.first())
            assertTrue(flags.windowed(2).any { pair ->
                pair[0] == "--ip" && pair[1] == "127.0.0.1"
            })
            assertTrue(flags.windowed(2).any { pair ->
                pair[0] == "--port" && pair[1] == "1080"
            })
            assertFalse(flags.contains("-D"))
            // ByeDPI -K requires commas: -Kth is invalid and broke v0.11.0.
            val protocol = flags.single { flag -> flag.startsWith("-K") }
            assertTrue("Invalid ByeDPI protocol list: $protocol",
                Regex("""^-K[thui](,[thui])*$""").matches(protocol))

        }
    }
}
