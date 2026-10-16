package com.whitevpn.app

/**
 * Sort reachable servers by measured device-side ICMP RTT, then untested hosts,
 * then verified ICMP nonresponders. ICMP failure is not proof a VPN is down.
 */
object L2tpPingRanking {
    fun group(host: String, results: Map<String, Double?>): Int =
        when {
            results[host] != null -> 0
            !results.containsKey(host) -> 1
            else -> 2
        }

    fun byHost(host: (String) -> String, results: Map<String, Double?>): Comparator<String> =
        compareBy<String> { group(host(it), results) }
            .thenBy { results[host(it)] ?: Double.MAX_VALUE }
            .thenBy { it }

    fun sortProfiles(profiles: List<L2tpProfile>, results: Map<String, Double?>): List<L2tpProfile> =
        profiles.sortedWith(compareBy<L2tpProfile> { group(it.id, results) }
            .thenBy { results[it.id] ?: Double.MAX_VALUE }
            .thenBy { it.name })
}
