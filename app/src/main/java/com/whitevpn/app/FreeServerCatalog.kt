package com.whitevpn.app

/** Published VPNBook WireGuard endpoints (not reusable client credentials).
 * https://www.vpnbook.com/ru/freevpn/wireguard-vpn — checked 2026-10-16.
 * This is a directory, not a real-time availability check.
 */
data class FreeServer(val country: String, val flag: String, val name: String, val host: String)

object FreeServerCatalog {
    const val generatorUrl = "https://www.vpnbook.com/ru/freevpn/wireguard-vpn"
    const val privacyUrl = "https://www.vpnbook.com/contact"

    val servers = listOf(
        FreeServer("США", "🇺🇸", "США #1", "us16.vpnbook.com"),
        FreeServer("США", "🇺🇸", "США #2", "us178.vpnbook.com"),
        FreeServer("Канада", "🇨🇦", "Канада #1", "ca149.vpnbook.com"),
        FreeServer("Канада", "🇨🇦", "Канада #2", "ca196.vpnbook.com"),
        FreeServer("Великобритания", "🇬🇧", "Британия #1", "uk205.vpnbook.com"),
        FreeServer("Великобритания", "🇬🇧", "Британия #2", "uk68.vpnbook.com"),
        FreeServer("Германия", "🇩🇪", "Германия #1", "de20.vpnbook.com"),
        FreeServer("Германия", "🇩🇪", "Германия #2", "de220.vpnbook.com"),
        FreeServer("Франция", "🇫🇷", "Франция #1", "fr200.vpnbook.com"),
        FreeServer("Франция", "🇫🇷", "Франция #2", "fr231.vpnbook.com")
    )

    // Search only a standard Endpoint directive, never the config's private keys.
    fun matchConfiguration(config: String): FreeServer? {
        val host = config.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.matches(Regex("(?i)^Endpoint\\s*=.*")) }
            ?.substringAfter('=')
            ?.trim()
            ?.substringBeforeLast(':')
            ?: return null
        return servers.firstOrNull { it.host.equals(host, ignoreCase = true) }
    }
}
