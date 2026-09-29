package com.ollacore.app.ui.chat

/**
 * Block visibility core (pure JVM logic; the ViewModel only feeds it state).
 *
 * Rule: a message is hidden ONLY when its sender is currently blocked AND the
 * message was created at/after that sender's block event. Pre-block history,
 * other senders, and everything while unblocked stays visible.
 *
 * Server instants (ISO-8601 or epoch millis strings) are the comparison
 * basis, never device time - except the block tap itself, which has no
 * server clock available (both share the NTP universe in practice).
 */

/** Server instant (ISO-8601 or epoch seconds/millis string) -> epoch ms; null when unparseable. */
fun messageCreatedAtMs(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        try {
            java.time.Instant.parse(raw).toEpochMilli()
        } catch (_: Exception) {
            val n = raw.toLong()
            if (n < 1_000_000_000_000L) n * 1000 else n
        }
    }.getOrNull()
}

/**
 * @param senderBlocked sender currently in the block set
 * @param blockedAtMs block-event epoch ms for this sender (null = legacy/unknown)
 * @param createdAtMs message creation epoch ms (null = unparseable)
 * @return true only for post-block messages from a blocked sender.
 * Unknown timestamps fail closed (hidden) while blocked.
 */
fun blockedMessageHidden(
    senderBlocked: Boolean,
    blockedAtMs: Long?,
    createdAtMs: Long?
): Boolean {
    if (!senderBlocked) return false
    if (blockedAtMs == null || createdAtMs == null) return true
    return createdAtMs >= blockedAtMs
}
