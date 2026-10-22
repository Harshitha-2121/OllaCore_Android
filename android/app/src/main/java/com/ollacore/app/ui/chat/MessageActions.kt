package com.ollacore.app.ui.chat

/**
 * Pure selection-toolbar semantics (unit-tested in MessageActionsTest).
 *
 * The top-right Forward / Star buttons act on the multi-select set. Keeping
 * the decision logic here — out of the composable and the ViewModel — makes
 * the WhatsApp-style contract explicit and JVM-testable:
 *  - Star is batch-uniform: if ANY selected message is unstarred, star ALL;
 *    only when EVERY selected message is already starred, unstar all.
 *    (The old per-item toggle loop just swapped a mixed set for a mixed set,
 *    which read as "star not working".)
 *  - Forward bodies carry exactly one "[Forwarded] " prefix, falling back to
 *    a media label when there is no text.
 */

/** Batch-star decision: true = star all, false = unstar all. */
fun resolveBatchStarAction(selectedIds: Collection<String>, starredIds: Set<String>): Boolean =
    selectedIds.isNotEmpty() && selectedIds.any { it !in starredIds }

/** Single "[Forwarded] "-prefixed body for a forwarded copy. */
fun buildForwardBody(rawText: String, kind: String, filename: String?): String {
    val base = rawText.ifBlank { filename?.takeIf { it.isNotBlank() } ?: mediaLabel(kind) }
    return when {
        base.startsWith("[Forwarded]") -> base
        base.isNotBlank() -> "[Forwarded] $base"
        else -> "[Forwarded]"
    }
}

/** Honest one-line label for media kinds without a text body. */
fun mediaLabel(kind: String): String = when (kind.lowercase()) {
    "image" -> "📷 Photo"
    "video" -> "🎥 Video"
    "audio", "voice_note" -> "🎵 Voice message"
    "file" -> "📄 Document"
    else -> ""
}
