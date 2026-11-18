package com.whitevpn.app.antidpi

/**
 * Small, fixed, auditable set of hufrea/ByeDPI command-line strategies.
 * These work on a local SOCKS5 proxy, NOT the root-only Zapret nfqws process.
 * A strategy may fail on a given provider. No remote server is involved.
 */
enum class AntiDpiStrategy(
    val title: String,
    val details: String,
    private val flags: List<String>,
) {
    SPLIT("TCP Split", "Разделение начала HTTP/TLS-потока", listOf("-Kth", "-s1", "-An")),
    DISORDER("Disorder", "Изменение порядка начальных TCP-данных", listOf("-Kth", "-d1", "-An")),
    TLS_RECORD("TLS Record", "Разделение TLS record около SNI", listOf("-Kth", "-r1+s", "-An")),
    COMBINED("Split + Record", "Комбинированный профиль для проверки", listOf("-Kth", "-s1", "-r1+s", "-An"));

    fun arguments(): Array<String> = (listOf(
        "ciadpi", "--ip", "127.0.0.1", "--port", "1080"
    ) + flags).toTypedArray()

    companion object {
        fun fromId(id: String?): AntiDpiStrategy =
            entries.firstOrNull { it.name == id } ?: SPLIT
    }
}
