package com.whitevpn.app

/**
 * Reads only the endpoint from the first [Peer] section. Never exposes private keys.
 * ICMP ping does not test the UDP WireGuard handshake or internet connectivity.
 */
object VpnEndpoint {
    fun hostFromConfig(configuration: String): String? {
        var inPeer = false
        for (raw in configuration.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                inPeer = line.equals("[Peer]", ignoreCase = true)
                continue
            }
            if (!inPeer || !line.startsWith("Endpoint", ignoreCase = true)) continue
            val key = line.substringBefore('=').trim()
            if (!key.equals("Endpoint", ignoreCase = true)) continue
            val endpoint = line.substringAfter('=', "").trim()
            val hostname = if (endpoint.startsWith("[")) {
                val bracket = endpoint.indexOf(']')
                if (bracket < 1 || endpoint.getOrNull(bracket + 1) != ':') return null
                val port = endpoint.substring(bracket + 2).toIntOrNull() ?: return null
                if (port !in 1..65535) return null
                endpoint.substring(1, bracket)
            } else {
                val separator = endpoint.lastIndexOf(':')
                if (separator <= 0) return null
                val port = endpoint.substring(separator + 1).toIntOrNull() ?: return null
                if (port !in 1..65535) return null
                endpoint.substring(0, separator)
            }
            return hostname.takeIf(ServerPing::validHost)
        }
        return null
    }
}
