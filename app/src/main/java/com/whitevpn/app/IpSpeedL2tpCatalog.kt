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
    const val snapshotUpdated = "16.10.2026 17:00 UTC"
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
        entry("Croatia", "150.40.105.16", "16 days", "1 ms"),
        entry("Croatia", "150.40.105.7", "16 days", "1 ms"),
        entry("Croatia", "150.40.105.21", "36 days", "1 ms"),
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
        entry("Japan", "128.27.29.161", "20 hours", "7 ms")
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

    /**
     * A same-site HTTPS redirect is allowed (e.g. localized content), but we
     * never follow a redirect to a third party or to an insecure URL.
     * Every response is capped at 1 MiB.
     */
    private fun download(url: String): String {
        var current = URL(url)
        repeat(3) {
            require(current.protocol == "https" &&
                current.host in setOf("ipspeed.info", "www.ipspeed.info") &&
                current.path.endsWith("/free-l2tpipsec.php")) {
                "IPSpeed перенаправил на неизвестный адрес"
            }
            val conn = current.openConnection() as HttpURLConnection
            conn.connectTimeout = 7500
            conn.readTimeout = 7500
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "text/html")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) WhiteVPN/1.0")
            try {
                val status = conn.responseCode
                if (status in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: error("HTTP $status без адреса перехода")
                    current = URL(current, location)
                    return@repeat
                }
                require(status == 200) { "IPSpeed: HTTP $status" }
                val output = java.io.ByteArrayOutputStream()
                conn.inputStream.use { stream ->
                    val block = ByteArray(4096)
                    var count = 0
                    while (true) {
                        val size = stream.read(block)
                        if (size < 0) break
                        count += size
                        require(count <= 1024 * 1024) { "Страница IPSpeed слишком большая" }
                        output.write(block, 0, size)
                    }
                }
                return String(output.toByteArray(), Charsets.UTF_8)
            } finally {
                conn.disconnect()
            }
        }
        throw java.io.IOException("IPSpeed: слишком много перенаправлений")
    }

    /** Try English and Russian versions; never fabricate live data on error. */
    fun fetch(): List<IpSpeedL2tpServer> {
        var lastFailure: Exception? = null
        for (address in listOf(pageUrl, "https://ipspeed.info/ru/free-l2tpipsec.php")) {
            try {
                val latest = parse(download(address))
                require(latest.isNotEmpty()) { "IPSpeed: список серверов не распознан" }
                return latest
            } catch (e: Exception) {
                lastFailure = e
            }
        }
        throw java.io.IOException(
            "IPSpeed: " + (lastFailure?.message ?: "сетевая ошибка"), lastFailure
        )
    }
}
