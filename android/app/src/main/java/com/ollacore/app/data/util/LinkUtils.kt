package com.ollacore.app.data.util

/**
 * Client-side link detection (no unfurl backend). Pure Kotlin so it is
 * unit-testable; UI in ChatScreen consumes these helpers.
 */
private val UrlRegex = Regex("""(https?://[^\s]+|www\.[^\s]+)""", RegexOption.IGNORE_CASE)

private const val TRAILERS = ".,)]:!?'\";"

fun firstUrl(text: String): String? {
    val m = UrlRegex.find(text) ?: return null
    val raw = m.value.trimEnd(*TRAILERS.toCharArray())
    if (raw.isBlank()) return null
    return if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
}

fun allUrls(text: String): List<Pair<IntRange, String>> {
    return UrlRegex.findAll(text).mapNotNull { m ->
        val raw = m.value.trimEnd(*TRAILERS.toCharArray())
        if (raw.isBlank()) return@mapNotNull null
        val url = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
        val start = m.range.first
        (start until start + raw.length) to url
    }.toList()
}

fun linkHost(url: String): String {
    return runCatching {
        java.net.URI(url).host?.removePrefix("www.") ?: url
    }.getOrNull() ?: url
}
