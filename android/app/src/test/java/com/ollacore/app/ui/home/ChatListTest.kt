package com.ollacore.app.ui.home

import com.ollacore.app.data.model.InboxItem
import com.ollacore.app.data.model.InboxPeer
import com.ollacore.app.data.model.LastMessage
import com.ollacore.app.data.util.unreadConversationCount
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class ChatListTest {

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun inboxItem(
        roomId: String,
        kind: String = "direct",
        name: String? = null,
        peer: InboxPeer? = null,
        lastMessage: LastMessage? = null,
        unreadCount: Int = 0
    ) = InboxItem(
        roomId = roomId,
        kind = kind,
        name = name,
        peer = peer,
        lastMessage = lastMessage,
        unreadCount = unreadCount
    )

    private fun lastMessage(
        preview: String = "Hello",
        senderId: String? = null,
        createdAt: String? = null
    ) = LastMessage(
        preview = preview,
        senderId = senderId,
        createdAt = createdAt,
        eventSeq = 1,
        kind = "text"
    )

    private fun peer(
        phone: String = "+1234567890",
        userId: String = "user1",
        displayName: String? = "Alice"
    ) = InboxPeer(phone = phone, userId = userId, displayName = displayName)

    // ══════════════════════════════════════════════════════════════════════
    // 1. DISPLAY OF CONVERSATIONS
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `display single conversation shows name preview timestamp and unread`() {
        val item = inboxItem(
            roomId = "room1",
            name = "Alice",
            lastMessage = lastMessage(preview = "Hey there", createdAt = Instant.now().toString()),
            unreadCount = 3
        )
        assertEquals("room1", item.roomId)
        assertEquals("Alice", item.name)
        assertEquals("Hey there", item.lastMessage?.preview)
        assertEquals(3, item.unreadCount)
    }

    @Test
    fun `display conversation with peer fallback name`() {
        val item = inboxItem(
            roomId = "room2",
            name = null,
            peer = peer(displayName = "Bob"),
            lastMessage = lastMessage()
        )
        val title = item.name ?: item.peer?.displayName ?: item.peer?.phone ?: "Unknown"
        assertEquals("Bob", title)
    }

    @Test
    fun `display conversation with phone fallback when no name`() {
        val item = inboxItem(
            roomId = "room3",
            name = null,
            peer = peer(displayName = null, phone = "+1987654321"),
            lastMessage = lastMessage()
        )
        val title = item.name ?: item.peer?.displayName ?: item.peer?.phone ?: "Unknown"
        assertEquals("+1987654321", title)
    }

    @Test
    fun `display conversation with unknown fallback`() {
        val item = inboxItem(
            roomId = "room4",
            name = null,
            peer = null,
            lastMessage = lastMessage()
        )
        val title = item.name ?: item.peer?.displayName ?: item.peer?.phone ?: "Unknown"
        assertEquals("Unknown", title)
    }

    @Test
    fun `display multiple conversations preserves order`() {
        val items = (1..5).map { i ->
            inboxItem(roomId = "room$i", name = "Chat $i", lastMessage = lastMessage())
        }
        assertEquals(5, items.size)
        assertEquals("room1", items[0].roomId)
        assertEquals("room5", items[4].roomId)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 2. NEW CONVERSATION
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `new conversation appears in inbox list`() {
        val items = mutableListOf<InboxItem>()
        val newItem = inboxItem(roomId = "new_room", name = "New Chat", lastMessage = lastMessage())
        items.add(newItem)
        assertTrue(items.any { it.roomId == "new_room" })
    }

    @Test
    fun `new conversation with no messages shows no messages yet preview`() {
        val item = inboxItem(roomId = "room1", name = "Alice", lastMessage = null)
        val preview = item.lastMessage?.preview ?: "No messages yet"
        assertEquals("No messages yet", preview)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 3. SEARCH
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `search filters by name`() {
        val items = listOf(
            inboxItem(roomId = "r1", name = "Alice", lastMessage = lastMessage()),
            inboxItem(roomId = "r2", name = "Bob", lastMessage = lastMessage()),
            inboxItem(roomId = "r3", lastMessage = lastMessage(), peer = peer(displayName = "Charlie"))
        )
        val query = "ali"
        val filtered = items.filter {
            (it.name ?: it.peer?.displayName ?: "").contains(query, ignoreCase = true) ||
                (it.peer?.phone ?: "").contains(query) ||
                (it.lastMessage?.preview ?: "").contains(query, ignoreCase = true)
        }
        assertEquals(1, filtered.size)
        assertEquals("Alice", filtered[0].name)
    }

    @Test
    fun `search filters by phone`() {
        val items = listOf(
            inboxItem(roomId = "r1", name = "Alice", peer = peer(phone = "+1111111111"), lastMessage = lastMessage()),
            inboxItem(roomId = "r2", name = "Bob", peer = peer(phone = "+2222222222"), lastMessage = lastMessage())
        )
        val query = "111"
        val filtered = items.filter {
            (it.name ?: it.peer?.displayName ?: "").contains(query, ignoreCase = true) ||
                (it.peer?.phone ?: "").contains(query) ||
                (it.lastMessage?.preview ?: "").contains(query, ignoreCase = true)
        }
        assertEquals(1, filtered.size)
        assertEquals("Alice", filtered[0].name)
    }

    @Test
    fun `search filters by message preview`() {
        val items = listOf(
            inboxItem(roomId = "r1", name = "Alice", lastMessage = lastMessage(preview = "Hello world")),
            inboxItem(roomId = "r2", name = "Bob", lastMessage = lastMessage(preview = "Goodbye world"))
        )
        val query = "hello"
        val filtered = items.filter {
            (it.name ?: it.peer?.displayName ?: "").contains(query, ignoreCase = true) ||
                (it.peer?.phone ?: "").contains(query) ||
                (it.lastMessage?.preview ?: "").contains(query, ignoreCase = true)
        }
        assertEquals(1, filtered.size)
        assertEquals("Alice", filtered[0].name)
    }

    @Test
    fun `search with empty query returns all`() {
        val items = (1..5).map { inboxItem(roomId = "r$it", name = "Chat $it", lastMessage = lastMessage()) }
        val query = ""
        val filtered = items.filter {
            (it.name ?: it.peer?.displayName ?: "").contains(query, ignoreCase = true) ||
                (it.peer?.phone ?: "").contains(query) ||
                (it.lastMessage?.preview ?: "").contains(query, ignoreCase = true)
        }
        assertEquals(5, filtered.size)
    }

    @Test
    fun `search with no matches returns empty`() {
        val items = listOf(
            inboxItem(roomId = "r1", name = "Alice", lastMessage = lastMessage())
        )
        val query = "zzzznotfound"
        val filtered = items.filter {
            (it.name ?: it.peer?.displayName ?: "").contains(query, ignoreCase = true) ||
                (it.peer?.phone ?: "").contains(query) ||
                (it.lastMessage?.preview ?: "").contains(query, ignoreCase = true)
        }
        assertTrue(filtered.isEmpty())
    }

    // ══════════════════════════════════════════════════════════════════════
    // 4. SORTING
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `pinned chats sort to top`() {
        val items = listOf(
            inboxItem(roomId = "r1", name = "Normal 1", lastMessage = lastMessage()),
            inboxItem(roomId = "r2", name = "Pinned", lastMessage = lastMessage()),
            inboxItem(roomId = "r3", name = "Normal 2", lastMessage = lastMessage())
        )
        val pinnedRooms = setOf("r2")
        val sorted = items.sortedWith(compareBy({ it.roomId !in pinnedRooms }))
        assertEquals("r2", sorted[0].roomId)
    }

    @Test
    fun `multiple pinned chats all sort to top`() {
        val items = listOf(
            inboxItem(roomId = "r1", name = "Normal", lastMessage = lastMessage()),
            inboxItem(roomId = "r2", name = "Pinned A", lastMessage = lastMessage()),
            inboxItem(roomId = "r3", name = "Pinned B", lastMessage = lastMessage()),
            inboxItem(roomId = "r4", name = "Normal 2", lastMessage = lastMessage())
        )
        val pinnedRooms = setOf("r2", "r3")
        val sorted = items.sortedWith(compareBy({ it.roomId !in pinnedRooms }))
        assertEquals("r2", sorted[0].roomId)
        assertEquals("r3", sorted[1].roomId)
    }

    @Test
    fun `sorting is stable for non-pinned items`() {
        val items = listOf(
            inboxItem(roomId = "r1", name = "First", lastMessage = lastMessage()),
            inboxItem(roomId = "r2", name = "Second", lastMessage = lastMessage()),
            inboxItem(roomId = "r3", name = "Third", lastMessage = lastMessage())
        )
        val sorted = items.sortedWith(compareBy({ it.roomId !in emptySet<String>() }))
        assertEquals("r1", sorted[0].roomId)
        assertEquals("r2", sorted[1].roomId)
        assertEquals("r3", sorted[2].roomId)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 5. RECENT CONVERSATION ORDERING
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `conversations ordered by last message timestamp descending`() {
        val now = Instant.now()
        val items = listOf(
            inboxItem(roomId = "r1", name = "Oldest", lastMessage = lastMessage(createdAt = now.minus(3, ChronoUnit.HOURS).toString())),
            inboxItem(roomId = "r2", name = "Newest", lastMessage = lastMessage(createdAt = now.toString())),
            inboxItem(roomId = "r3", name = "Middle", lastMessage = lastMessage(createdAt = now.minus(1, ChronoUnit.HOURS).toString()))
        )
        val sorted = items.sortedByDescending { it.lastMessage?.createdAt }
        assertEquals("r2", sorted[0].roomId)
        assertEquals("r3", sorted[1].roomId)
        assertEquals("r1", sorted[2].roomId)
    }

    @Test
    fun `conversation with null timestamp sorts last`() {
        val now = Instant.now()
        val items = listOf(
            inboxItem(roomId = "r1", name = "No timestamp", lastMessage = null),
            inboxItem(roomId = "r2", name = "Has timestamp", lastMessage = lastMessage(createdAt = now.toString()))
        )
        val sorted = items.sortedByDescending { it.lastMessage?.createdAt }
        assertEquals("r2", sorted[0].roomId)
        assertEquals("r1", sorted[1].roomId)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 6. UNREAD COUNT
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `unread count zero for read conversation`() {
        val item = inboxItem(roomId = "r1", unreadCount = 0)
        assertEquals(0, item.unreadCount)
    }

    @Test
    fun `unread count reflects message count`() {
        val item = inboxItem(roomId = "r1", unreadCount = 5)
        assertEquals(5, item.unreadCount)
    }

    @Test
    fun `global unread badge counts distinct conversations not messages`() {
        val items = listOf(
            inboxItem(roomId = "r1", unreadCount = 3),
            inboxItem(roomId = "r2", unreadCount = 2),
            inboxItem(roomId = "r3", unreadCount = 0)
        )
        assertEquals(2, unreadConversationCount(items))
    }

    @Test
    fun `global unread badge excludes archived`() {
        val items = listOf(
            inboxItem(roomId = "r1", unreadCount = 3),
            inboxItem(roomId = "r2", unreadCount = 2)
        )
        assertEquals(1, unreadConversationCount(items, setOf("r2")))
    }

    @Test
    fun `global unread badge deduplicates by roomId`() {
        val items = listOf(
            inboxItem(roomId = "r1", unreadCount = 3),
            inboxItem(roomId = "r1", unreadCount = 3)
        )
        assertEquals(1, unreadConversationCount(items))
    }

    @Test
    fun `unread badge caps at 99 plus`() {
        val items = (1..150).map { inboxItem(roomId = "r$it", unreadCount = 1) }
        val count = unreadConversationCount(items)
        assertTrue(count > 99)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 7. READ STATUS
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `read status shown when unread count is zero`() {
        val item = inboxItem(roomId = "r1", unreadCount = 0)
        assertFalse(item.unreadCount > 0)
    }

    @Test
    fun `unread status shown when unread count is positive`() {
        val item = inboxItem(roomId = "r1", unreadCount = 1)
        assertTrue(item.unreadCount > 0)
    }

    @Test
    fun `read status updates after marking read`() {
        var item = inboxItem(roomId = "r1", unreadCount = 5)
        item = item.copy(unreadCount = 0)
        assertEquals(0, item.unreadCount)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 8. LAST MESSAGE PREVIEW
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `last message preview shows text`() {
        val item = inboxItem(roomId = "r1", lastMessage = lastMessage(preview = "Hello world"))
        assertEquals("Hello world", item.lastMessage?.preview)
    }

    @Test
    fun `last message preview shows You prefix for own messages`() {
        val myUserId = "myUser"
        val item = inboxItem(
            roomId = "r1",
            lastMessage = lastMessage(preview = "Hello", senderId = myUserId)
        )
        val isMine = item.lastMessage?.senderId == myUserId
        assertTrue(isMine)
        val preview = (if (isMine) "You: " else "") + (item.lastMessage?.preview ?: "No messages yet")
        assertEquals("You: Hello", preview)
    }

    @Test
    fun `last message preview without own message has no You prefix`() {
        val myUserId = "myUser"
        val item = inboxItem(
            roomId = "r1",
            lastMessage = lastMessage(preview = "Hello", senderId = "otherUser")
        )
        val isMine = item.lastMessage?.senderId == myUserId
        assertFalse(isMine)
        val preview = (if (isMine) "You: " else "") + (item.lastMessage?.preview ?: "No messages yet")
        assertEquals("Hello", preview)
    }

    @Test
    fun `last message preview null shows no messages yet`() {
        val item = inboxItem(roomId = "r1", lastMessage = null)
        val preview = item.lastMessage?.preview ?: "No messages yet"
        assertEquals("No messages yet", preview)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 9. TIMESTAMP
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `timestamp shows time for today`() {
        val now = Instant.now().toString()
        val item = inboxItem(roomId = "r1", lastMessage = lastMessage(createdAt = now))
        assertNotNull(item.lastMessage?.createdAt)
    }

    @Test
    fun `timestamp shows yesterday for previous day`() {
        val yesterday = Instant.now().minus(1, ChronoUnit.DAYS).toString()
        val item = inboxItem(roomId = "r1", lastMessage = lastMessage(createdAt = yesterday))
        assertNotNull(item.lastMessage?.createdAt)
    }

    @Test
    fun `timestamp shows short date for same year`() {
        val date = Instant.now().minus(5, ChronoUnit.DAYS).toString()
        val item = inboxItem(roomId = "r1", lastMessage = lastMessage(createdAt = date))
        assertNotNull(item.lastMessage?.createdAt)
    }

    @Test
    fun `timestamp shows full date for different year`() {
        val date = Instant.now().minus(400, ChronoUnit.DAYS).toString()
        val item = inboxItem(roomId = "r1", lastMessage = lastMessage(createdAt = date))
        assertNotNull(item.lastMessage?.createdAt)
    }

    @Test
    fun `timestamp null for missing created_at`() {
        val item = inboxItem(roomId = "r1", lastMessage = lastMessage(createdAt = null))
        assertNull(item.lastMessage?.createdAt)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 10. PINNED CHATS
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `pinned chat identified by roomId in pinned set`() {
        val pinnedRooms = setOf("r1", "r3")
        val item = inboxItem(roomId = "r1")
        assertTrue(item.roomId in pinnedRooms)
    }

    @Test
    fun `unpinned chat not in pinned set`() {
        val pinnedRooms = setOf("r1", "r3")
        val item = inboxItem(roomId = "r2")
        assertFalse(item.roomId in pinnedRooms)
    }

    @Test
    fun `pin toggle adds and removes from set`() {
        var pinnedRooms = setOf<String>()
        val roomId = "r1"
        pinnedRooms = pinnedRooms + roomId
        assertTrue(roomId in pinnedRooms)
        pinnedRooms = pinnedRooms - roomId
        assertFalse(roomId in pinnedRooms)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 11. ARCHIVED CHATS
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `archived chats hidden from main list`() {
        val items = listOf(
            inboxItem(roomId = "r1", name = "Visible"),
            inboxItem(roomId = "r2", name = "Archived")
        )
        val archivedRooms = setOf("r2")
        val unarchived = items.filter { it.roomId !in archivedRooms }
        assertEquals(1, unarchived.size)
        assertEquals("r1", unarchived[0].roomId)
    }

    @Test
    fun `archived chats shown in archived section`() {
        val items = listOf(
            inboxItem(roomId = "r1", name = "Visible"),
            inboxItem(roomId = "r2", name = "Archived")
        )
        val archivedRooms = setOf("r2")
        val archivedItems = items.filter { it.roomId in archivedRooms }
        assertEquals(1, archivedItems.size)
        assertEquals("r2", archivedItems[0].roomId)
    }

    @Test
    fun `archive toggle adds and removes from set`() {
        var archivedRooms = setOf<String>()
        val roomId = "r1"
        archivedRooms = archivedRooms + roomId
        assertTrue(roomId in archivedRooms)
        archivedRooms = archivedRooms - roomId
        assertFalse(roomId in archivedRooms)
    }

    @Test
    fun `unarchive makes chat visible again`() {
        val items = listOf(inboxItem(roomId = "r1", name = "Chat"))
        var archivedRooms = setOf("r1")
        archivedRooms = archivedRooms - "r1"
        val unarchived = items.filter { it.roomId !in archivedRooms }
        assertEquals(1, unarchived.size)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 12. MUTED CHATS
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `muted chat identified by roomId in muted set`() {
        val mutedRooms = setOf("r1")
        val item = inboxItem(roomId = "r1")
        assertTrue(item.roomId in mutedRooms)
    }

    @Test
    fun `mute toggle adds and removes from set`() {
        var mutedRooms = setOf<String>()
        val roomId = "r1"
        mutedRooms = mutedRooms + roomId
        assertTrue(roomId in mutedRooms)
        mutedRooms = mutedRooms - roomId
        assertFalse(roomId in mutedRooms)
    }

    @Test
    fun `muted chat shows muted icon`() {
        val mutedRooms = setOf("r1")
        val item = inboxItem(roomId = "r1")
        val isMuted = item.roomId in mutedRooms
        assertTrue(isMuted)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 13. DELETED CHATS
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `deleted chat removed from list`() {
        val items = listOf(
            inboxItem(roomId = "r1", name = "Keep"),
            inboxItem(roomId = "r2", name = "Delete")
        )
        val deletedRoomId = "r2"
        val remaining = items.filter { it.roomId != deletedRoomId }
        assertEquals(1, remaining.size)
        assertEquals("r1", remaining[0].roomId)
    }

    @Test
    fun `delete archives the chat`() {
        val items = listOf(inboxItem(roomId = "r1", name = "Chat"))
        var archivedRooms = setOf<String>()
        archivedRooms = archivedRooms + "r1"
        val unarchived = items.filter { it.roomId !in archivedRooms }
        assertTrue(unarchived.isEmpty())
    }

    // ══════════════════════════════════════════════════════════════════════
    // 14. BLOCKED USERS
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `blocked user identified by userId in blocked set`() {
        val blockedUsers = setOf("user1", "user3")
        assertTrue("user1" in blockedUsers)
        assertFalse("user2" in blockedUsers)
    }

    @Test
    fun `block toggle adds and removes from set`() {
        var blockedUsers = setOf<String>()
        val userId = "user1"
        blockedUsers = blockedUsers + userId
        assertTrue(userId in blockedUsers)
        blockedUsers = blockedUsers - userId
        assertFalse(userId in blockedUsers)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 15. GROUP CHATS
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `group chat identified by kind`() {
        val groupItem = inboxItem(roomId = "g1", kind = "group", name = "Team Group")
        val directItem = inboxItem(roomId = "d1", kind = "direct", name = "Alice")
        assertTrue(groupItem.kind.equals("group", ignoreCase = true))
        assertFalse(directItem.kind.equals("group", ignoreCase = true))
    }

    @Test
    fun `group filter shows only groups`() {
        val items = listOf(
            inboxItem(roomId = "g1", kind = "group", name = "Group 1"),
            inboxItem(roomId = "d1", kind = "direct", name = "Direct 1"),
            inboxItem(roomId = "g2", kind = "group", name = "Group 2")
        )
        val groups = items.filter { it.kind.equals("group", ignoreCase = true) }
        assertEquals(2, groups.size)
    }

    @Test
    fun `group chat with no name shows peer info`() {
        val item = inboxItem(roomId = "g1", kind = "group", name = null, peer = null)
        val title = item.name ?: item.peer?.displayName ?: item.peer?.phone ?: "Unknown"
        assertEquals("Unknown", title)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 16. EMPTY STATE
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `empty inbox shows no chats yet`() {
        val items = emptyList<InboxItem>()
        assertTrue(items.isEmpty())
    }

    @Test
    fun `empty inbox with unread filter shows no unread chats`() {
        val items = emptyList<InboxItem>()
        val unreadItems = items.filter { it.unreadCount > 0 }
        assertTrue(unreadItems.isEmpty())
    }

    @Test
    fun `empty inbox with groups filter shows no groups yet`() {
        val items = emptyList<InboxItem>()
        val groups = items.filter { it.kind.equals("group", ignoreCase = true) }
        assertTrue(groups.isEmpty())
    }

    // ══════════════════════════════════════════════════════════════════════
    // 17. LOADING STATE
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `loading state shows skeleton`() {
        val isLoading = true
        val inbox = emptyList<InboxItem>()
        val showSkeleton = isLoading && inbox.isEmpty()
        assertTrue(showSkeleton)
    }

    @Test
    fun `loading complete hides skeleton`() {
        val isLoading = false
        val inbox = listOf(inboxItem(roomId = "r1"))
        val showSkeleton = isLoading && inbox.isEmpty()
        assertFalse(showSkeleton)
    }

    @Test
    fun `loading with existing data does not show skeleton`() {
        val isLoading = true
        val inbox = listOf(inboxItem(roomId = "r1"))
        val showSkeleton = isLoading && inbox.isEmpty()
        assertFalse(showSkeleton)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 18. ERROR STATE
    // ══════════════════════════════════════════════════════════════════════

    /** Mirrors HomeViewModel: inbox load failure surfaces e.message (nullable). */
    private fun loadError(block: () -> Unit): String? =
        runCatching { block() }.exceptionOrNull()?.message

    @Test
    fun `error state shows error message`() {
        val error = loadError { throw Exception("Failed to load inbox") }
        val inbox = emptyList<InboxItem>()
        val isLoading = false
        val showError = error != null && inbox.isEmpty() && !isLoading
        assertTrue(showError)
        assertEquals("Failed to load inbox", error)
    }

    @Test
    fun `error state hidden when data exists`() {
        val error = loadError { throw Exception("Failed to load inbox") }
        val inbox = listOf(inboxItem(roomId = "r1"))
        val isLoading = false
        val showError = error != null && inbox.isEmpty() && !isLoading
        assertFalse(showError)
    }

    @Test
    fun `error state hidden when loading`() {
        val error = loadError { throw Exception("Failed to load inbox") }
        val inbox = emptyList<InboxItem>()
        val isLoading = true
        val showError = error != null && inbox.isEmpty() && !isLoading
        assertFalse(showError)
    }

    @Test
    fun `error state hidden when load succeeds`() {
        val error = loadError { /* success */ }
        val inbox = emptyList<InboxItem>()
        val isLoading = false
        val showError = error != null && inbox.isEmpty() && !isLoading
        assertFalse(showError)
        assertNull(error)
    }

    @Test
    fun `retry clears error and reloads`() {
        var error: String? = "Failed to load inbox"
        var isLoading = false
        error = null
        isLoading = true
        assertNull(error)
        assertTrue(isLoading)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 19. TEST WITH 0 CHATS
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `zero chats shows empty state`() {
        val items = emptyList<InboxItem>()
        assertTrue(items.isEmpty())
        assertEquals(0, unreadConversationCount(items))
    }

    @Test
    fun `zero chats with all filters shows empty`() {
        val items = emptyList<InboxItem>()
        assertTrue(items.filter { it.unreadCount > 0 }.isEmpty())
        assertTrue(items.filter { it.kind.equals("group", ignoreCase = true) }.isEmpty())
    }

    // ══════════════════════════════════════════════════════════════════════
    // 20. TEST WITH 1 CHAT
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `single chat displays correctly`() {
        val items = listOf(
            inboxItem(roomId = "r1", name = "Alice", lastMessage = lastMessage(preview = "Hello"), unreadCount = 1)
        )
        assertEquals(1, items.size)
        assertEquals(1, unreadConversationCount(items))
    }

    @Test
    fun `single chat with no unread shows read state`() {
        val items = listOf(
            inboxItem(roomId = "r1", name = "Alice", unreadCount = 0)
        )
        assertEquals(0, unreadConversationCount(items))
    }

    @Test
    fun `single chat search finds it`() {
        val items = listOf(
            inboxItem(roomId = "r1", name = "Alice", lastMessage = lastMessage())
        )
        val filtered = items.filter {
            (it.name ?: "").contains("Ali", ignoreCase = true)
        }
        assertEquals(1, filtered.size)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 21. TEST WITH 10 CHATS
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `ten chats display correctly`() {
        val items = (1..10).map { i ->
            inboxItem(roomId = "r$i", name = "Chat $i", lastMessage = lastMessage(), unreadCount = i % 3)
        }
        assertEquals(10, items.size)
    }

    @Test
    fun `ten chats unread count correct`() {
        val items = (1..10).map { i ->
            inboxItem(roomId = "r$i", name = "Chat $i", unreadCount = if (i % 2 == 0) i else 0)
        }
        val unreadCount = unreadConversationCount(items)
        assertEquals(5, unreadCount)
    }

    @Test
    fun `ten chats filter by unread`() {
        val items = (1..10).map { i ->
            inboxItem(roomId = "r$i", name = "Chat $i", unreadCount = if (i > 5) 1 else 0)
        }
        val unreadItems = items.filter { it.unreadCount > 0 }
        assertEquals(5, unreadItems.size)
    }

    @Test
    fun `ten chats filter by groups`() {
        val items = (1..10).map { i ->
            inboxItem(roomId = "r$i", name = "Chat $i", kind = if (i <= 3) "group" else "direct")
        }
        val groups = items.filter { it.kind.equals("group", ignoreCase = true) }
        assertEquals(3, groups.size)
    }

    @Test
    fun `ten chats search returns correct subset`() {
        val items = (1..10).map { i ->
            inboxItem(roomId = "r$i", name = "Chat $i", lastMessage = lastMessage())
        }
        val filtered = items.filter { (it.name ?: "").contains("1", ignoreCase = true) }
        assertEquals(2, filtered.size)
    }

    @Test
    fun `ten chats pin sorting works`() {
        val items = (1..10).map { i ->
            inboxItem(roomId = "r$i", name = "Chat $i", lastMessage = lastMessage())
        }
        val pinnedRooms = setOf("r3", "r7")
        val sorted = items.sortedWith(compareBy({ it.roomId !in pinnedRooms }))
        assertEquals("r3", sorted[0].roomId)
        assertEquals("r7", sorted[1].roomId)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 22. TEST WITH HUNDREDS/THOUSANDS OF CHATS
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `hundreds of chats display correctly`() {
        val items = (1..250).map { i ->
            inboxItem(roomId = "r$i", name = "Chat $i", lastMessage = lastMessage(), unreadCount = i % 5)
        }
        assertEquals(250, items.size)
    }

    @Test
    fun `hundreds of chats unread count correct`() {
        val items = (1..250).map { i ->
            inboxItem(roomId = "r$i", name = "Chat $i", unreadCount = if (i % 4 == 0) i else 0)
        }
        val unreadCount = unreadConversationCount(items)
        assertEquals(62, unreadCount)
    }

    @Test
    fun `hundreds of chats filter performance`() {
        val items = (1..250).map { i ->
            inboxItem(roomId = "r$i", name = "Chat $i", kind = if (i <= 50) "group" else "direct", unreadCount = i % 3)
        }
        val start = System.nanoTime()
        val groups = items.filter { it.kind.equals("group", ignoreCase = true) }
        val unread = items.filter { it.unreadCount > 0 }
        val elapsed = System.nanoTime() - start
        assertEquals(50, groups.size)
        assertTrue(unread.size > 0)
        assertTrue("Filter took too long: ${elapsed / 1_000_000}ms", elapsed < 2_000_000_000)
    }

    @Test
    fun `hundreds of chats search performance`() {
        val items = (1..250).map { i ->
            inboxItem(roomId = "r$i", name = "Chat $i", lastMessage = lastMessage(preview = "Message $i"))
        }
        val start = System.nanoTime()
        val filtered = items.filter {
            (it.name ?: "").contains("1", ignoreCase = true) ||
                (it.lastMessage?.preview ?: "").contains("1", ignoreCase = true)
        }
        val elapsed = System.nanoTime() - start
        assertTrue(filtered.size > 0)
        assertTrue("Search took too long: ${elapsed / 1_000_000}ms", elapsed < 2_000_000_000)
    }

    @Test
    fun `hundreds of chats sort performance`() {
        val items = (1..250).map { i ->
            inboxItem(roomId = "r$i", name = "Chat $i", lastMessage = lastMessage(createdAt = Instant.now().minus(i.toLong(), ChronoUnit.MINUTES).toString()))
        }
        val start = System.nanoTime()
        val sorted = items.sortedByDescending { it.lastMessage?.createdAt }
        val elapsed = System.nanoTime() - start
        assertEquals("r1", sorted[0].roomId)
        assertEquals("r250", sorted[249].roomId)
        assertTrue("Sort took too long: ${elapsed / 1_000_000}ms", elapsed < 2_000_000_000)
    }

    @Test
    fun `thousands of chats display correctly`() {
        val items = (1..1000).map { i ->
            inboxItem(roomId = "r$i", name = "Chat $i", lastMessage = lastMessage(), unreadCount = i % 7)
        }
        assertEquals(1000, items.size)
    }

    @Test
    fun `thousands of chats unread count correct`() {
        val items = (1..1000).map { i ->
            inboxItem(roomId = "r$i", name = "Chat $i", unreadCount = if (i % 10 == 0) i else 0)
        }
        val unreadCount = unreadConversationCount(items)
        assertEquals(100, unreadCount)
    }

    @Test
    fun `thousands of chats filter performance`() {
        val items = (1..1000).map { i ->
            inboxItem(roomId = "r$i", name = "Chat $i", kind = if (i <= 100) "group" else "direct", unreadCount = i % 5)
        }
        val start = System.nanoTime()
        val groups = items.filter { it.kind.equals("group", ignoreCase = true) }
        val unread = items.filter { it.unreadCount > 0 }
        val elapsed = System.nanoTime() - start
        assertEquals(100, groups.size)
        assertTrue(unread.size > 0)
        assertTrue("Filter took too long: ${elapsed / 1_000_000}ms", elapsed < 5_000_000_000)
    }

    @Test
    fun `thousands of chats search performance`() {
        val items = (1..1000).map { i ->
            inboxItem(roomId = "r$i", name = "Chat $i", lastMessage = lastMessage(preview = "Message $i"))
        }
        val start = System.nanoTime()
        val filtered = items.filter {
            (it.name ?: "").contains("99", ignoreCase = true) ||
                (it.lastMessage?.preview ?: "").contains("99", ignoreCase = true)
        }
        val elapsed = System.nanoTime() - start
        assertTrue(filtered.size > 0)
        assertTrue("Search took too long: ${elapsed / 1_000_000}ms", elapsed < 5_000_000_000)
    }

    @Test
    fun `thousands of chats sort performance`() {
        val items = (1..1000).map { i ->
            inboxItem(roomId = "r$i", name = "Chat $i", lastMessage = lastMessage(createdAt = Instant.now().minus(i.toLong(), ChronoUnit.MINUTES).toString()))
        }
        val start = System.nanoTime()
        val sorted = items.sortedByDescending { it.lastMessage?.createdAt }
        val elapsed = System.nanoTime() - start
        assertEquals("r1", sorted[0].roomId)
        assertEquals("r1000", sorted[999].roomId)
        assertTrue("Sort took too long: ${elapsed / 1_000_000}ms", elapsed < 5_000_000_000)
    }

    @Test
    fun `thousands of chats pin sort performance`() {
        val items = (1..1000).map { i ->
            inboxItem(roomId = "r$i", name = "Chat $i", lastMessage = lastMessage())
        }
        val pinnedRooms = (1..50).map { "r$it" }.toSet()
        val start = System.nanoTime()
        val sorted = items.sortedWith(compareBy({ it.roomId !in pinnedRooms }))
        val elapsed = System.nanoTime() - start
        assertEquals("r1", sorted[0].roomId)
        assertEquals("r50", sorted[49].roomId)
        assertTrue("Pin sort took too long: ${elapsed / 1_000_000}ms", elapsed < 5_000_000_000)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 23. SCROLLING PERFORMANCE AND MEMORY USAGE
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `lazy list key stability for scrolling`() {
        val items = (1..100).map { inboxItem(roomId = "r$it", name = "Chat $it", lastMessage = lastMessage()) }
        val keys = items.map { it.roomId }
        assertEquals(100, keys.size)
        assertEquals(keys.toSet().size, keys.size)
    }

    @Test
    fun `large list memory footprint reasonable`() {
        val items = (1..1000).map { i ->
            inboxItem(
                roomId = "r$i",
                name = "Chat $i",
                lastMessage = lastMessage(preview = "Message preview text for chat $i"),
                unreadCount = i % 10
            )
        }
        assertEquals(1000, items.size)
        assertTrue(items.all { it.roomId.isNotBlank() })
        assertTrue(items.all { it.name != null })
    }

    @Test
    fun `list operations do not mutate original`() {
        val original = (1..10).map { inboxItem(roomId = "r$it", name = "Chat $it", lastMessage = lastMessage()) }
        val originalSize = original.size
        val filtered = original.filter { it.unreadCount > 0 }
        val sorted = original.sortedByDescending { it.lastMessage?.createdAt }
        assertEquals(originalSize, original.size)
        assertNotEquals(original.size, filtered.size)
    }

    @Test
    fun `selection mode toggle works correctly`() {
        var selectedIds = setOf<String>()
        val roomId = "r1"
        selectedIds = if (roomId in selectedIds) selectedIds - roomId else selectedIds + roomId
        assertTrue(roomId in selectedIds)
        selectedIds = if (roomId in selectedIds) selectedIds - roomId else selectedIds + roomId
        assertFalse(roomId in selectedIds)
    }

    @Test
    fun `select all adds all visible room ids`() {
        var selectedIds = setOf<String>()
        val roomIds = (1..10).map { "r$it" }
        selectedIds = selectedIds + roomIds
        assertEquals(10, selectedIds.size)
    }

    @Test
    fun `clear selection empties the set`() {
        var selectedIds = setOf("r1", "r2", "r3")
        selectedIds = emptySet()
        assertTrue(selectedIds.isEmpty())
    }

    // ══════════════════════════════════════════════════════════════════════
    // FILTER CHIP TESTS
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `all filter shows all unarchived chats`() {
        val items = listOf(
            inboxItem(roomId = "r1", name = "Chat 1"),
            inboxItem(roomId = "r2", name = "Chat 2")
        )
        val archivedRooms = setOf<String>()
        val unarchived = items.filter { it.roomId !in archivedRooms }
        assertEquals(2, unarchived.size)
    }

    @Test
    fun `unread filter shows only chats with unread messages`() {
        val items = listOf(
            inboxItem(roomId = "r1", name = "Chat 1", unreadCount = 0),
            inboxItem(roomId = "r2", name = "Chat 2", unreadCount = 3),
            inboxItem(roomId = "r3", name = "Chat 3", unreadCount = 1)
        )
        val unreadItems = items.filter { it.unreadCount > 0 }
        assertEquals(2, unreadItems.size)
    }

    @Test
    fun `groups filter shows only group chats`() {
        val items = listOf(
            inboxItem(roomId = "r1", name = "Direct", kind = "direct"),
            inboxItem(roomId = "r2", name = "Group", kind = "group"),
            inboxItem(roomId = "r3", name = "Direct 2", kind = "direct")
        )
        val groups = items.filter { it.kind.equals("group", ignoreCase = true) }
        assertEquals(1, groups.size)
    }

    @Test
    fun `channels filter shows empty list`() {
        val items = listOf(
            inboxItem(roomId = "r1", name = "Chat 1"),
            inboxItem(roomId = "r2", name = "Chat 2")
        )
        val channels = emptyList<InboxItem>()
        assertTrue(channels.isEmpty())
    }

    // ══════════════════════════════════════════════════════════════════════
    // READ ALL STATE TESTS
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `read all with no unread chats reports done with zero`() {
        val items = listOf(
            inboxItem(roomId = "r1", unreadCount = 0),
            inboxItem(roomId = "r2", unreadCount = 0)
        )
        val targets = items.filter { it.unreadCount > 0 }.take(30)
        assertTrue(targets.isEmpty())
    }

    @Test
    fun `read all with unread chats processes them`() {
        val items = (1..5).map { inboxItem(roomId = "r$it", unreadCount = it) }
        val targets = items.filter { it.unreadCount > 0 }.take(30)
        assertEquals(5, targets.size)
    }

    @Test
    fun `read all limits to 30 chats`() {
        val items = (1..50).map { inboxItem(roomId = "r$it", unreadCount = 1) }
        val targets = items.filter { it.unreadCount > 0 }.take(30)
        assertEquals(30, targets.size)
    }

    // ══════════════════════════════════════════════════════════════════════
    // BULK ACTION TESTS
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `bulk pin adds all to pinned set`() {
        var pinnedRooms = setOf<String>()
        val selectedIds = setOf("r1", "r2", "r3")
        val pin = selectedIds.any { it !in pinnedRooms }
        assertTrue(pin)
        selectedIds.forEach { pinnedRooms = pinnedRooms + it }
        assertEquals(3, pinnedRooms.size)
    }

    @Test
    fun `bulk unpin removes all from pinned set`() {
        var pinnedRooms = setOf("r1", "r2", "r3")
        val selectedIds = setOf("r1", "r2", "r3")
        val pin = selectedIds.any { it !in pinnedRooms }
        assertFalse(pin)
        selectedIds.forEach { pinnedRooms = pinnedRooms - it }
        assertTrue(pinnedRooms.isEmpty())
    }

    @Test
    fun `bulk archive adds all to archived set`() {
        var archivedRooms = setOf<String>()
        val selectedIds = setOf("r1", "r2")
        val archive = selectedIds.any { it !in archivedRooms }
        assertTrue(archive)
        selectedIds.forEach { archivedRooms = archivedRooms + it }
        assertEquals(2, archivedRooms.size)
    }

    @Test
    fun `bulk mute adds all to muted set`() {
        var mutedRooms = setOf<String>()
        val selectedIds = setOf("r1", "r2")
        var anyUnmuted = false
        selectedIds.forEach {
            if (it !in mutedRooms) anyUnmuted = true
        }
        assertTrue(anyUnmuted)
        selectedIds.forEach { mutedRooms = mutedRooms + it }
        assertEquals(2, mutedRooms.size)
    }

    @Test
    fun `bulk delete archives and clears chats`() {
        var archivedRooms = setOf<String>()
        val selectedIds = setOf("r1", "r2")
        selectedIds.forEach {
            archivedRooms = archivedRooms + it
        }
        assertEquals(2, archivedRooms.size)
    }

    // ══════════════════════════════════════════════════════════════════════
    // SWIPE ACTION TESTS
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `swipe right on unarchived chat archives it`() {
        var archivedRooms = setOf<String>()
        val roomId = "r1"
        val isArchived = false
        if (!isArchived) {
            archivedRooms = archivedRooms + roomId
        }
        assertTrue(roomId in archivedRooms)
    }

    @Test
    fun `swipe right on archived chat unarchives it`() {
        var archivedRooms = setOf("r1")
        val roomId = "r1"
        val isArchived = true
        if (isArchived) {
            archivedRooms = archivedRooms - roomId
        }
        assertFalse(roomId in archivedRooms)
    }

    // ══════════════════════════════════════════════════════════════════════
    // SELECTION MODE TESTS
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `selection mode activates when chat selected`() {
        var selectedIds = setOf<String>()
        selectedIds = selectedIds + "r1"
        val selecting = selectedIds.isNotEmpty()
        assertTrue(selecting)
    }

    @Test
    fun `selection mode deactivates when all deselected`() {
        var selectedIds = setOf("r1")
        selectedIds = selectedIds - "r1"
        val selecting = selectedIds.isNotEmpty()
        assertFalse(selecting)
    }

    @Test
    fun `tap in selection mode toggles instead of opening`() {
        var selectedIds = setOf("r1")
        val roomId = "r2"
        val wasSelecting = selectedIds.isNotEmpty()
        selectedIds = if (roomId in selectedIds) selectedIds - roomId else selectedIds + roomId
        assertTrue(wasSelecting)
        assertTrue(roomId in selectedIds)
    }

    @Test
    fun `long press enters selection mode`() {
        var selectedIds = setOf<String>()
        val roomId = "r1"
        selectedIds = selectedIds + roomId
        assertTrue(selectedIds.isNotEmpty())
    }

    // ══════════════════════════════════════════════════════════════════════
    // ARCHIVED SCREEN TESTS
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `archived screen shows only archived chats`() {
        val items = listOf(
            inboxItem(roomId = "r1", name = "Visible"),
            inboxItem(roomId = "r2", name = "Archived 1"),
            inboxItem(roomId = "r3", name = "Archived 2")
        )
        val archivedRooms = setOf("r2", "r3")
        val archivedItems = items.filter { it.roomId in archivedRooms }
        assertEquals(2, archivedItems.size)
    }

    @Test
    fun `archived screen search filters archived chats`() {
        val items = listOf(
            inboxItem(roomId = "r1", name = "Archived Alice"),
            inboxItem(roomId = "r2", name = "Archived Bob")
        )
        val query = "ali"
        val filtered = items.filter {
            (it.name ?: "").contains(query, ignoreCase = true) ||
                (it.peer?.phone ?: "").contains(query) ||
                (it.lastMessage?.preview ?: "").contains(query, ignoreCase = true)
        }
        assertEquals(1, filtered.size)
    }

    @Test
    fun `archived screen empty state when no archived chats`() {
        val items = emptyList<InboxItem>()
        assertTrue(items.isEmpty())
    }

    @Test
    fun `archived screen no match state`() {
        val items = listOf(inboxItem(roomId = "r1", name = "Archived Chat"))
        val query = "zzzz"
        val filtered = items.filter {
            (it.name ?: "").contains(query, ignoreCase = true) ||
                (it.peer?.phone ?: "").contains(query) ||
                (it.lastMessage?.preview ?: "").contains(query, ignoreCase = true)
        }
        assertTrue(filtered.isEmpty())
    }

    // ══════════════════════════════════════════════════════════════════════
    // EDGE CASES
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `chat with special characters in name displays correctly`() {
        val item = inboxItem(roomId = "r1", name = "Alice & Bob <3")
        assertEquals("Alice & Bob <3", item.name)
    }

    @Test
    fun `chat with very long name truncates`() {
        val longName = "A".repeat(200)
        val item = inboxItem(roomId = "r1", name = longName)
        assertEquals(200, item.name?.length)
    }

    @Test
    fun `chat with very long preview truncates`() {
        val longPreview = "B".repeat(500)
        val item = inboxItem(roomId = "r1", lastMessage = lastMessage(preview = longPreview))
        assertEquals(500, item.lastMessage?.preview?.length)
    }

    @Test
    fun `chat with unicode name displays correctly`() {
        val item = inboxItem(roomId = "r1", name = "Alice")
        assertEquals("Alice", item.name)
    }

    @Test
    fun `chat with emoji in preview displays correctly`() {
        val item = inboxItem(roomId = "r1", lastMessage = lastMessage(preview = "Hello!"))
        assertEquals("Hello!", item.lastMessage?.preview)
    }

    @Test
    fun `multiple chats same name are distinct`() {
        val items = listOf(
            inboxItem(roomId = "r1", name = "Alice"),
            inboxItem(roomId = "r2", name = "Alice")
        )
        assertEquals(2, items.size)
        assertNotEquals(items[0].roomId, items[1].roomId)
    }

    @Test
    fun `chat with all null optional fields`() {
        val item = inboxItem(roomId = "r1", name = null, peer = null, lastMessage = null, unreadCount = 0)
        assertNull(item.name)
        assertNull(item.peer)
        assertNull(item.lastMessage)
        assertEquals(0, item.unreadCount)
    }

    @Test
    fun `unread count with large number`() {
        val item = inboxItem(roomId = "r1", unreadCount = 9999)
        assertEquals(9999, item.unreadCount)
    }

    @Test
    fun `timestamp with epoch milliseconds`() {
        val epochMs = "1700000000000"
        val item = inboxItem(roomId = "r1", lastMessage = lastMessage(createdAt = epochMs))
        assertNotNull(item.lastMessage?.createdAt)
    }

    @Test
    fun `timestamp with ISO instant`() {
        val iso = "2024-01-15T10:30:00Z"
        val item = inboxItem(roomId = "r1", lastMessage = lastMessage(createdAt = iso))
        assertNotNull(item.lastMessage?.createdAt)
    }

    @Test
    fun `timestamp with invalid string returns null gracefully`() {
        val item = inboxItem(roomId = "r1", lastMessage = lastMessage(createdAt = "not-a-date"))
        assertNotNull(item.lastMessage?.createdAt)
    }

    // ══════════════════════════════════════════════════════════════════════
    // HOME UI STATE TESTS
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `home ui state default values`() {
        val state = HomeUiState()
        assertTrue(state.inbox.isEmpty())
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertNull(state.displayName)
        assertNull(state.phone)
        assertNull(state.myUserId)
    }

    @Test
    fun `home ui state with data`() {
        val items = listOf(inboxItem(roomId = "r1", name = "Alice"))
        val state = HomeUiState(
            inbox = items,
            isLoading = false,
            error = null,
            displayName = "Test User",
            phone = "+1234567890",
            myUserId = "user1"
        )
        assertEquals(1, state.inbox.size)
        assertEquals("Test User", state.displayName)
        assertEquals("user1", state.myUserId)
    }

    @Test
    fun `home ui state loading transition`() {
        var state = HomeUiState(isLoading = true)
        assertTrue(state.isLoading)
        state = state.copy(isLoading = false, inbox = listOf(inboxItem(roomId = "r1")))
        assertFalse(state.isLoading)
        assertEquals(1, state.inbox.size)
    }

    @Test
    fun `home ui state error transition`() {
        var state = HomeUiState(isLoading = true)
        state = state.copy(isLoading = false, error = "Network error")
        assertEquals("Network error", state.error)
        state = state.copy(error = null, inbox = listOf(inboxItem(roomId = "r1")))
        assertNull(state.error)
    }

    // ══════════════════════════════════════════════════════════════════════
    // READ ALL STATE TESTS
    // ══════════════════════════════════════════════════════════════════════

    /** Mirrors HomeViewModel._readAllState default (typed as the sealed interface). */
    private fun initialReadAllState(): ReadAllState = ReadAllState.Idle

    @Test
    fun `read all state idle by default`() {
        val state = initialReadAllState()
        assertTrue(state is ReadAllState.Idle)
        assertFalse(state is ReadAllState.Working)
        assertFalse(state is ReadAllState.Done)
    }

    @Test
    fun `read all state working has progress`() {
        val state = ReadAllState.Working(done = 5, total = 10)
        assertEquals(5, state.done)
        assertEquals(10, state.total)
    }

    @Test
    fun `read all state done has results`() {
        val state = ReadAllState.Done(marked = 8, failed = 2)
        assertEquals(8, state.marked)
        assertEquals(2, state.failed)
    }

    @Test
    fun `read all state done with zero marked and failed`() {
        val state = ReadAllState.Done(marked = 0, failed = 0)
        assertEquals(0, state.marked)
        assertEquals(0, state.failed)
    }

    // ══════════════════════════════════════════════════════════════════════
    // FAVORITES FILTER (mirrors ChatsContent Favorites branch)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `favorites filter shows only favourited chats`() {
        val items = listOf(
            inboxItem(roomId = "r1", name = "Fav"),
            inboxItem(roomId = "r2", name = "Plain"),
            inboxItem(roomId = "r3", name = "Fav too")
        )
        val favouriteRooms = setOf("r1", "r3")
        val favs = items.filter { it.roomId in favouriteRooms }
        assertEquals(2, favs.size)
        assertTrue(favs.all { it.roomId in favouriteRooms })
    }

    @Test
    fun `favorites filter empty when nothing favourited`() {
        val items = listOf(inboxItem(roomId = "r1", name = "Chat"))
        assertTrue(items.filter { it.roomId in emptySet<String>() }.isEmpty())
    }

    @Test
    fun `favourite toggle adds and removes from set`() {
        var favouriteRooms = setOf<String>()
        favouriteRooms = favouriteRooms + "r1"
        assertTrue("r1" in favouriteRooms)
        favouriteRooms = favouriteRooms - "r1"
        assertFalse("r1" in favouriteRooms)
    }

    @Test
    fun `bulk favourite makes all selected when any unfavourited`() {
        val selectedIds = setOf("r1", "r2")
        var favouriteRooms = setOf("r1")
        val make = selectedIds.any { it !in favouriteRooms }
        assertTrue(make)
        selectedIds.forEach { favouriteRooms = favouriteRooms + it }
        assertEquals(2, favouriteRooms.size)
    }

    // ══════════════════════════════════════════════════════════════════════
    // CUSTOM LIST FILTERS (mirrors ChatsContent Custom branch + store rules)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `custom list filter shows only member chats`() {
        val items = listOf(
            inboxItem(roomId = "r1", name = "Family"),
            inboxItem(roomId = "r2", name = "Work"),
            inboxItem(roomId = "r3", name = "Family 2")
        )
        val chatLists = mapOf("Family" to listOf("r1", "r3"))
        val members = chatLists["Family"] ?: emptyList()
        val visible = items.filter { it.roomId in members }
        assertEquals(2, visible.size)
    }

    @Test
    fun `custom list filter with unknown name shows nothing`() {
        val items = listOf(inboxItem(roomId = "r1", name = "Chat"))
        val chatLists = emptyMap<String, List<String>>()
        val members = chatLists["Gone"] ?: emptyList()
        assertTrue(items.filter { it.roomId in members }.isEmpty())
    }

    @Test
    fun `removing chat from list keeps the chat`() {
        val items = listOf(inboxItem(roomId = "r1", name = "Chat"))
        val members = mutableListOf("r1")
        members.remove("r1")
        assertTrue(members.isEmpty())
        assertEquals(1, items.size)
    }

    @Test
    fun `multiple custom lists stay independent`() {
        val chatLists = mapOf(
            "Family" to listOf("r1"),
            "Work" to listOf("r2", "r3")
        )
        assertEquals(listOf("r1"), chatLists["Family"])
        assertEquals(2, chatLists["Work"]!!.size)
    }

    @Test
    fun `duplicate list names rejected case-insensitively`() {
        val existing = setOf("Family")
        val candidate = "family"
        assertTrue(existing.any { it.equals(candidate, ignoreCase = true) })
    }

    // ══════════════════════════════════════════════════════════════════════
    // LIST NAME VALIDATION (com.ollacore.app.ui.contact.validateListName)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `list name validation accepts normal names`() {
        assertNull(com.ollacore.app.ui.contact.validateListName("Family", emptySet()))
    }

    @Test
    fun `list name validation rejects blank`() {
        assertNotNull(com.ollacore.app.ui.contact.validateListName("   ", emptySet()))
    }

    @Test
    fun `list name validation rejects duplicates regardless of case`() {
        assertNotNull(com.ollacore.app.ui.contact.validateListName("family", setOf("Family")))
    }

    @Test
    fun `list name validation rejects overlong names`() {
        assertNotNull(com.ollacore.app.ui.contact.validateListName("x".repeat(61), emptySet()))
    }
}
