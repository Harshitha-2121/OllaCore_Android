package com.ollacore.app.ui.chat

import com.ollacore.app.data.model.MessageResponse

/**
 * Pure duplicate suppression for the message list (BUG-02).
 *
 * Every ingestion path (WS message.created, own echo, history/resync reload)
 * funnels through [mergeIncomingMessage] / [dedupeById] so a late-arriving or
 * repeated frame can no longer create a second row:
 *  - server message id is the primary key: a re-delivered frame REPLACES the
 *    row in place (never appended) when its event_seq is not older, so
 *    delivery/read ticks, edits, and reactions on the newer frame survive;
 *  - client_message_id is the optimistic-send key: a server frame sharing an
 *    existing row's client_message_id (or an optimistic row whose local id IS
 *    that client id) replaces that row, and the server id becomes authoritative.
 *
 * Rows that merely share identical TEXT but have distinct ids are legitimate
 * distinct messages and are NEVER merged — all matching is id-based.
 */

/** Replace-or-append preserving order (stable id upsert, no duplicate rows). */
fun mergeIncomingMessage(
    existing: List<MessageResponse>,
    incoming: MessageResponse
): List<MessageResponse> {
    val byServerId = existing.indexOfFirst { it.id == incoming.id }
    if (byServerId >= 0) {
        // Never let a delayed frame roll back content/ticks carried by a newer one.
        if (incoming.eventSeq < existing[byServerId].eventSeq) return existing
        return existing.toMutableList().also { it[byServerId] = incoming }
    }

    val clientId = incoming.clientMessageId?.takeIf { it.isNotBlank() }
    if (clientId != null) {
        // Optimistic/local shell: its id is the client id it was sent with.
        val byClientId = existing.indexOfFirst {
            it.clientMessageId == clientId || it.id == clientId
        }
        if (byClientId >= 0) {
            // The server row is authoritative (real id + seq), always adopt it.
            return existing.toMutableList().also { it[byClientId] = incoming }
        }
    }

    return existing + incoming
}

/**
 * One scan, order-preserving stable upsert: each id keeps its FIRST-seen
 * position, but the content stored there is the frame with the newest
 * event_seq (so re-delivered history/sync pages upgrade ticks/edits in place
 * instead of rendering the same row twice).
 */
fun dedupeById(messages: List<MessageResponse>): List<MessageResponse> {
    val order = LinkedHashMap<String, MessageResponse>(messages.size * 2)
    for (m in messages) {
        val prev = order[m.id]
        if (prev == null || m.eventSeq >= prev.eventSeq) order[m.id] = m
    }
    return ArrayList(order.values)
}
