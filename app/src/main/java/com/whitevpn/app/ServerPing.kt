package com.whitevpn.app

import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Best-effort ICMP echo. Some hosts or networks block ICMP even if WireGuard
 * works. Never equate a failed ping with an offline VPN service.
 *
 * The host can come from a validated saved WireGuard Endpoint. The process
 * is launched directly with safe characters only; never through a shell.
 */
object ServerPing {
    private val rttRegex = Regex("""time[=<]\s*(\d+(?:[.,]\d+)?)\s*ms""", RegexOption.IGNORE_CASE)

    fun parseRtt(output: String): Double? =
        rttRegex.find(output)?.groupValues?.getOrNull(1)
            ?.replace(',', '.')?.toDoubleOrNull()

    // A hostname/IP cannot start with a dash or inject ping CLI flags/arguments.
    // Unicode names must already be converted to ASCII (IDNA) by config owners.
    private val dnsHost = Regex("^[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")
    private val ipv6Host = Regex("^[0-9A-Fa-f:]{2,39}$")

    fun validHost(host: String): Boolean =
        host.length in 1..253 && (dnsHost.matches(host) || ipv6Host.matches(host))

    fun probe(host: String): Double? {
        if (!validHost(host)) return null
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
