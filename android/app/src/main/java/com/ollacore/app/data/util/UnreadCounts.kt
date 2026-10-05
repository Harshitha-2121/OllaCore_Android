package com.ollacore.app.data.util

import com.ollacore.app.data.model.InboxItem

/**
 * Unread semantics (two levels, WhatsApp-style):
 * - PER-CHAT: InboxItem.unreadCount as-is (messages unread in that chat).
 * - GLOBAL (bottom Chats badge): NUMBER OF DISTINCT CONVERSATIONS with
 *   unreadCount > 0 - never the sum of messages. Archived chats excluded,
 *   duplicate rows deduped by roomId.
 */
fun unreadConversationCount(
    items: List<InboxItem>,
    archivedRooms: Set<String> = emptySet()
): Int {
    return items
        .filter { it.roomId !in archivedRooms && it.unreadCount > 0 }
        .distinctBy { it.roomId }
        .size
}
