package com.whitevpn.app

import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Best-effort ICMP echo. Some hosts or networks block ICMP even if WireGuard
 * works. Never equate a failed ping with an offline VPN service.
 *
 * Hostnames MUST come from our fixed, reviewed catalog. We invoke the executable
 * directly (no shell) and never accept arbitrary commands or user-controlled args.
 */
object ServerPing {
    private val rttRegex = Regex("""time[=<]\s*(\d+(?:[.,]\d+)?)\s*ms""", RegexOption.IGNORE_CASE)

    fun parseRtt(output: String): Double? =
        rttRegex.find(output)?.groupValues?.getOrNull(1)
            ?.replace(',', '.')?.toDoubleOrNull()

    fun probe(host: String): Double? {
        if (FreeServerCatalog.servers.none { it.host == host }) return null
        var process: Process? = null
        return try {
            process = ProcessBuilder(
                "ping", "-n", "-c", "1", "-W", "2", host
            ).redirectErrorStream(true).start()

            if (!process.waitFor(3500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                null
            } else if (process.exitValue() != 0) {
                null
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }
                parseRtt(output)
            }
        } catch (_: Exception) {
            null
        } finally {
            process?.destroy()
        }
    }

    fun label(value: Double?): String =
        if (value == null) "ICMP: нет ответа"
        else "Ping: " + String.format(Locale.US, "%.0f", value) + " мс"
}
