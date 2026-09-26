package com.ollacore.app.data.util

/**
 * Maps technical failures to human copy (pure Kotlin, unit-tested).
 * The chat/call error screen renders these; raw exceptions never reach UI.
 */
fun isNetworkError(raw: String?): Boolean {
    if (raw.isNullOrBlank()) return false
    val lower = raw.lowercase()
    return listOf(
        "unable to resolve host",
        "no address associated",
        "network is unreachable",
        "failed to connect",
        "connection refused",
        "connection reset",
        "connection timed out",
        "broken pipe",
        "timeout",
        "timed out",
        "eofexception",
        "stream was reset",
        "ssl",
        "handshake",
        "certificate",
        "protocol error"
    ).any { it in lower }
}

fun isAuthError(raw: String?): Boolean {
    if (raw.isNullOrBlank()) return false
    val lower = raw.lowercase()
    return ("401" in lower || "unauthorized" in lower || "forbidden" in lower ||
        "session" in lower || "token" in lower || "logged out" in lower) &&
        "no address associated" !in lower
}

fun friendlyError(raw: String?): String {
    if (raw.isNullOrBlank()) return "Something went wrong."
    if (isNetworkError(raw)) {
        val hint = if ("ssl" in raw.lowercase() || "handshake" in raw.lowercase() ||
            "certificate" in raw.lowercase()
        ) {
            " If the date and time look wrong on this device, fix them first."
        } else ""
        return "Please check your connection and try again.$hint"
    }
    if (isAuthError(raw)) return "Your session expired. Please log in again."
    val lower = raw.lowercase()
    if ("404" in lower || "not found" in lower) return "This is not available yet on the server."
    if ("429" in lower || "rate" in lower) return "Too many tries. Please wait a moment."
    if (raw.length > 140) return "Something went wrong. Please try again."
    return raw
}
