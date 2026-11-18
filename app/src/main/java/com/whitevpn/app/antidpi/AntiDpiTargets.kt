package com.whitevpn.app.antidpi

/** Only installed, explicitly selected application package names are routed. */
enum class AntiDpiTarget(val title: String, val packages: List<String>, val probeHost: String) {
    TELEGRAM(
        "Telegram",
        listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram"),
        "telegram.org"
    ),
    YOUTUBE(
        "YouTube",
        listOf("com.google.android.youtube", "app.revanced.android.youtube", "com.google.android.youtube.tv"),
        "www.youtube.com"
    ),
    INSTAGRAM("Instagram", listOf("com.instagram.android"), "www.instagram.com");

    companion object {
        fun fromNames(names: Collection<String>?): Set<AntiDpiTarget> =
            if (names == null) entries.toSet()
            else entries.filterTo(linkedSetOf()) { it.name in names }

        fun packagesOf(targets: Set<AntiDpiTarget>): List<String> =
            entries.filter { it in targets }.flatMap { it.packages }.distinct()
    }
}

/** The system resolver avoids a mandatory dependency on a possibly blocked public DNS. */
enum class AntiDpiDns(val title: String, val address: String?) {
    SYSTEM("DNS текущей сети", null),
    GOOGLE("Google 8.8.8.8", "8.8.8.8"),
    CLOUDFLARE("Cloudflare 1.1.1.1", "1.1.1.1"),
    QUAD9("Quad9 9.9.9.9", "9.9.9.9");

    companion object {
        fun fromId(id: String?): AntiDpiDns = entries.firstOrNull { it.name == id } ?: SYSTEM
    }
}
