package com.whitevpn.app.antidpi

/**
 * Fixed command lines for the pinned MIT hufrea/ByeDPI SOCKS engine.
 * They use DPI-desynchronization techniques related to Zapret, but NOT nfqws2.
 * None guarantees access through IP-range blocking or DNS poisoning.
 */
enum class AntiDpiStrategy(
    val title: String,
    val details: String,
    private val flags: List<String>,
) {
    SPLIT(
        "TCP Split",
        "Разделяет первый запрос, в том числе нетипичный TCP Telegram",
        listOf("-Kthui", "-s1", "-An")
    ),
    DISORDER(
        "Disorder",
        "Изменяет порядок начальных TCP-сегментов",
        listOf("-Kthui", "-d1", "-An")
    ),
    TLS_RECORD(
        "TLS Record",
        "Разделяет TLS ClientHello вокруг SNI (YouTube/Instagram)",
        listOf("-Kth", "-r3+s", "-An")
    ),
    COMBINED(
        "Split + Disorder",
        "Комбинированная стратегия для TLS, если простой Split не помог",
        listOf("-Kth", "-s1+s", "-d3+s", "-An")
    );

    fun arguments(): Array<String> = (listOf(
        "ciadpi", "--ip", "127.0.0.1", "--port", "1080", "--max-conn", "1024"
    ) + flags).toTypedArray()

    companion object {
        fun fromId(id: String?): AntiDpiStrategy =
            entries.firstOrNull { it.name == id } ?: SPLIT
    }
}
