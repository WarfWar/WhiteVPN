package com.whitevpn.app

import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.jsoup.Jsoup

/**
 * Free public L2TP/IPsec PSK server directory from ipspeed.info.
 * Bundled 2026-10-15 19:00 UTC snapshot; a live refresh can replace it in the UI.
 * Availability and privacy are not guaranteed by WhiteVPN.
 */
data class IpSpeedL2tpServer(
    val country: String,
    val flag: String,
    val ip: String,
    val uptime: String,
    val publishedPing: String
)

object IpSpeedL2tpCatalog {
    const val pageUrl = "https://ipspeed.info/free-l2tpipsec.php"
    const val snapshotUpdated = "15.10.2026 19:00 UTC"
    const val publicUsername = "vpn"
    const val publicPassword = "vpn"
    const val publicPsk = "vpn"

    private fun countryLabel(source: String): Pair<String, String> {
        val name = source.lowercase(Locale.US)
        return when {
            name.contains("croatia") || name.contains("hrvatska") -> "Хорватия" to "🇭🇷"
            name.contains("japan") -> "Япония" to "🇯🇵"
            name.contains("usa") || name.contains("united states") -> "США" to "🇺🇸"
            else -> source.take(60) to "🌐"
        }
    }

    private fun entry(country: String, ip: String, uptime: String, ping: String): IpSpeedL2tpServer {
        val (label, flag) = countryLabel(country)
        return IpSpeedL2tpServer(label, flag, ip, uptime, ping)
    }

    // Snapshot as published on IPSpeed (not live verified).
    val snapshot: List<IpSpeedL2tpServer> = listOf(
        entry("Croatia", "150.40.105.16", "15 days", "1 ms"),
        entry("Croatia", "150.40.105.10", "15 days", "1 ms"),
        entry("Japan", "219.100.37.176", "12 days", "8 ms"),
        entry("Japan", "219.100.37.163", "12 days", "18 ms"),
        entry("Japan", "219.100.37.31", "12 days", "18 ms"),
        entry("Japan", "219.100.37.117", "12 days", "14 ms"),
        entry("Japan", "219.100.37.94", "12 days", "27 ms"),
        entry("Japan", "219.100.37.98", "12 days", "21 ms"),
        entry("Japan", "219.100.37.209", "12 days", "24 ms"),
        entry("Japan", "219.100.37.199", "12 days", "14 ms"),
        entry("Japan", "219.100.37.100", "12 days", "21 ms"),
        entry("Japan", "219.100.37.172", "12 days", "16 ms"),
        entry("Japan", "219.100.37.110", "12 days", "13 ms"),
        entry("Japan", "219.100.37.81", "12 days", "19 ms"),
        entry("Japan", "219.100.37.125", "12 days", "13 ms"),
        entry("Japan", "219.100.37.13", "12 days", "15 ms"),
        entry("Japan", "219.100.37.119", "12 days", "18 ms"),
        entry("Japan", "160.251.62.107", "9 days", "1 ms"),
        entry("USA", "172.167.211.107", "1 day", "1 ms")
    )

    private fun validIp(host: String): Boolean {
        val parts = host.split('.')
        return parts.size == 4 && parts.all {
            it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) &&
                (it.toIntOrNull() ?: -1) in 0..255
        }
    }

    /** Parse the published public table, not credentials from arbitrary markup. */
    fun parse(html: String): List<IpSpeedL2tpServer> {
        val map = LinkedHashMap<String, IpSpeedL2tpServer>()
        for (row in Jsoup.parse(html).select("tr")) {
            val cells = row.select("td")
            if (cells.size < 5) continue
            val ip = cells[2].text().trim()
            if (!validIp(ip)) continue
            val country = cells[1].text().trim()
            if (country.isBlank()) continue
            map[ip] = entry(country, ip, cells[3].text().take(40), cells[4].text().take(25))
            if (map.size == 100) break
        }
        return map.values.toList()
    }

    fun toProfile(server: IpSpeedL2tpServer): L2tpProfile = L2tpProfile(
        name = "IPSpeed • " + server.flag + " " + server.country + " • " + server.ip,
        host = server.ip,
        username = publicUsername,
        password = publicPassword,
        preSharedKey = publicPsk
    )

    /** HTTPS only, off main thread, bounded response; no arbitrary redirect host. */
    fun fetch(): List<IpSpeedL2tpServer> {
        val connection = URL(pageUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 6000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "text/html")
        connection.setRequestProperty("User-Agent", "WhiteVPN/0.6 (Android)")
        try {
            connection.connect()
            require(connection.responseCode == 200) { "Website returned an error" }
            val content = connection.inputStream.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val block = ByteArray(4096)
                var total = 0
                while (true) {
                    val n = stream.read(block)
                    if (n < 0) break
                    total += n
                    require(total <= 1024 * 1024) { "Page too large" }
                    output.write(block, 0, n)
                }
                String(output.toByteArray(), Charsets.UTF_8)
            }
            return parse(content).also { require(it.isNotEmpty()) { "No server rows found" } }
        } finally {
            connection.disconnect()
        }
    }
}
