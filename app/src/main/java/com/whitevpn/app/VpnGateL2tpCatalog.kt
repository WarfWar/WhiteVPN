package com.whitevpn.app

import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.jsoup.Jsoup

/**
 * VPN Gate (University of Tsukuba) public volunteer relays.
 * Parse ONLY server rows with the L2TP/IPsec compatibility icon; other
 * VPN Gate hosts may support OpenVPN but not L2TP.
 *
 * https://www.vpngate.net/en/
 * Public L2TP credentials documented at:
 * https://www.vpngate.net/en/howto_l2tp.aspx
 */
data class VpnGateL2tpServer(
    val host: String,
    val country: String,
    val flag: String,
    val sitePing: String,
    val uptime: String
)

object VpnGateL2tpCatalog {
    const val pageUrl = "https://www.vpngate.net/en/"
    const val fallbackUrl = "https://download.vpngate.jp/en/"
    const val user = "vpn"
    const val password = "vpn"
    const val psk = "vpn"

    // Fallback is intentionally small and labelled a snapshot: hosts are
    // volatile and no snapshot implies a current connection works.
    val fallback = listOf(
        VpnGateL2tpServer("219.100.37.81", "Япония", "🇯🇵", "8 мс (сайт)", "13 дней"),
        VpnGateL2tpServer("219.100.37.115", "Япония", "🇯🇵", "15 мс (сайт)", "13 дней"),
        VpnGateL2tpServer("219.100.37.161", "Япония", "🇯🇵", "19 мс (сайт)", "13 дней")
    )

    private val ipv4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    private fun validIp(ip: String): Boolean =
        ip.split('.').let { parts ->
            parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
        }

    private fun location(label: String): Pair<String, String> {
        val key = label.lowercase(Locale.ROOT)
        return when {
            "japan" in key -> "Япония" to "🇯🇵"
            "korea" in key -> "Южная Корея" to "🇰🇷"
            "united states" in key || "usa" in key -> "США" to "🇺🇸"
            "canada" in key -> "Канада" to "🇨🇦"
            "germany" in key -> "Германия" to "🇩🇪"
            "france" in key -> "Франция" to "🇫🇷"
            "russian federation" in key || "russia" in key -> "Россия" to "🇷🇺"
            "united kingdom" in key -> "Великобритания" to "🇬🇧"
            "singapore" in key -> "Сингапур" to "🇸🇬"
            "netherlands" in key -> "Нидерланды" to "🇳🇱"
            "australia" in key -> "Австралия" to "🇦🇺"
            "india" in key -> "Индия" to "🇮🇳"
            "brazil" in key -> "Бразилия" to "🇧🇷"
            "turkey" in key -> "Турция" to "🇹🇷"
            else -> label.take(50) to "🌐"
        }
    }

    fun parse(html: String): List<VpnGateL2tpServer> {
        val found = LinkedHashMap<String, VpnGateL2tpServer>()
        val document = Jsoup.parse(html)
        for (row in document.select("table#vg_hosts_table_id tr")) {
            val cells = row.select("> td")
            if (cells.size < 10) continue
            val l2tpCell = cells[5]
            // A link to the general how-to does not prove a relay supports L2TP.
            // VPN Gate places a green yes_33.png icon on supported rows.
            if (l2tpCell.select("img[src*=yes_33]").isEmpty()) continue
            val ip = ipv4.find(cells[1].text())?.value ?: continue
            if (!validIp(ip)) continue
            val (country, flag) = location(cells[0].text().trim())
            val ping = Regex("""Ping:\s*(\d+)\s*ms""", RegexOption.IGNORE_CASE)
                .find(cells[3].text())?.groupValues?.getOrNull(1)?.plus(" мс (сайт)")
                ?: "не указан"
            val uptime = Regex("""\b\d+\s*(?:days?|hours?|mins?)\b""", RegexOption.IGNORE_CASE)
                .find(cells[2].text())?.value ?: "не указан"
            found[ip] = VpnGateL2tpServer(ip, country, flag, ping, uptime)
            if (found.size >= 300) break
        }
        return found.values.toList()
    }

    fun profile(server: VpnGateL2tpServer) = L2tpProfile(
        name = "VPN Gate • " + server.flag + " " + server.country + " • " + server.host,
        host = server.host,
        username = user,
        password = password,
        preSharedKey = psk
    )

    fun fetch(): List<VpnGateL2tpServer> {
        for (origin in listOf(pageUrl, fallbackUrl)) {
            try {
                val url = URL(origin)
                val connection = url.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 7000
                connection.readTimeout = 7000
                connection.setRequestProperty("User-Agent", "WhiteVPN/0.7 Android")
                connection.setRequestProperty("Accept", "text/html")
                try {
                    connection.connect()
                    require(connection.responseCode == 200) { "VPN Gate returned HTTP error" }
                    val html = connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        val bytes = java.io.ByteArrayOutputStream()
                        var size = 0
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            size += read
                            require(size <= 3 * 1024 * 1024) { "VPN Gate page too large" }
                            bytes.write(buffer, 0, read)
                        }
                        bytes.toString("UTF-8")
                    }
                    val servers = parse(html)
                    require(servers.isNotEmpty()) { "No compatible L2TP relay rows" }
                    return servers
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                // Try alternate VPN Gate mirror; never follow redirects to untrusted sites.
            }
        }
        throw IllegalStateException("VPN Gate could not be refreshed")
    }
}
