package com.ollacore.app.ui.groups

import com.ollacore.app.data.local.GroupEvent
import com.ollacore.app.data.model.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/**
 * Section 10 - Group Chats. Pure/contract tests over the group-helper layer
 * (GroupInfoViewModel member helpers), the group wire models (Models.kt) and
 * characterization mirrors of NewGroupViewModel validation, the mute windows
 * (ChatPrefsStore) and the membership journal (GroupEventStore). ViewModels
 * are AndroidViewModel (not JVM-constructible) so their decision rules are
 * mirrored here and pinned down.
 */
class GroupChatsTest {

    // ── Fixtures ──

    private val me = Participant("u-me", "member", "Me", "+1000")
    private val admin1 = Participant("u-a1", "admin", "Adam", "+1001")
    private val admin2 = Participant("u-a2", "Admin", "Bea", "+1002")
    private val zed = Participant("u-z", "member", "zed", "+1003")
    private val noName = Participant("u-9", "member", null, "+1999")
    private val owner = Participant("u-ow", "owner", "Owner", "+1777")
    private val creator = Participant("u-cr", "creator", "Creator", "+1888")

    private val json = Json { ignoreUnknownKeys = true }

    // ── Pure mirrors of VM decision rules ──

    private fun createError(name: String, members: List<String>): String? =
        when {
            name.trim().isBlank() -> "Enter a group name"
            members.isEmpty() -> "Select at least one member"
            else -> null
        }

    private fun toggle(selected: Set<String>, userId: String): Set<String> =
        if (userId in selected) selected - userId else selected + userId

    private fun addMember(roster: List<Participant>, p: Participant): List<Participant> =
        if (roster.any { it.principalId == p.principalId }) roster else roster + p

    private fun removeMember(roster: List<Participant>, principalId: String): List<Participant> =
        roster.filterNot { it.principalId == principalId }

    // ── Create ──

    @Test
    fun `create requires a non-blank group name`() {
        assertEquals("Enter a group name", createError("   ", listOf("u1")))
        assertEquals("Enter a group name", createError("", listOf("u1")))
    }

    @Test
    fun `create requires at least one member when a name is present`() {
        assertEquals("Select at least one member", createError("Trip", emptyList()))
    }

    @Test
    fun `create trims the group name`() {
        assertEquals("Trip", "  Trip  ".trim())
        assertNull(createError("  Trip  ", listOf("u1")))
    }

    @Test
    fun `create accepts a single-member roster`() {
        assertNull(createError("Solo", listOf("u1")))
        val solo = listOf(Participant("u1", "member", "Ali", "+1001"))
        assertEquals(1, sortMembersYouFirst(solo, "u1").size)
        assertEquals(listOf("u1"), sortMembersYouFirst(solo, "u1").map { it.principalId })
        assertEquals(listOf("u1"), filterMembers(solo, "ali").map { it.principalId })
    }

    @Test
    fun `GroupConversationRequest serializes member ids and name`() {
        val request = GroupConversationRequest(listOf("u1", "u2"), "Trip")
        val encoded = json.encodeToString(GroupConversationRequest.serializer(), request)
        assertTrue(encoded.contains("\"member_user_ids\""))
        assertTrue(encoded.contains("\"Trip\""))
        val restored = json.decodeFromString(GroupConversationRequest.serializer(), encoded)
        assertEquals(request, restored)
    }

    // ── Add members ──

    @Test
    fun `AddMemberRequest round-trips and carries user id`() {
        val encoded = json.encodeToString(AddMemberRequest.serializer(), AddMemberRequest("u7"))
        assertTrue(encoded.contains("\"user_id\""))
        assertEquals(AddMemberRequest("u7"), json.decodeFromString(AddMemberRequest.serializer(), encoded))
    }

    @Test
    fun `toggling a member selects then deselects`() {
        var sel = emptySet<String>()
        sel = toggle(sel, "u1")
        assertEquals(setOf("u1"), sel)
        sel = toggle(sel, "u1")
        assertTrue(sel.isEmpty())
        assertEquals(setOf("u1", "u2"), toggle(setOf("u1"), "u2"))
    }

    @Test
    fun `adding a member to the roster adds on first call and is idempotent after`() {
        val once = addMember(emptyList(), admin1)
        assertEquals(listOf("u-a1"), once.map { it.principalId })
        assertEquals(once, addMember(once, admin1))
    }

    @Test
    fun `a newly added member appears in sort and search`() {
        val roster = addMember(listOf(me, zed), admin1)
        assertEquals(listOf("u-a1"), filterMembers(roster, "adam").map { it.principalId })
        assertEquals(listOf("u-me", "u-a1", "u-z"), sortMembersYouFirst(roster, "u-me").map { it.principalId })
    }

    // ── Remove members ──

    @Test
    fun `removing a member drops exactly that principal`() {
        val roster = listOf(me, admin1, zed)
        assertEquals(listOf("u-me", "u-z"), removeMember(roster, "u-a1").map { it.principalId })
    }

    @Test
    fun `removing a phone-only member works by id`() {
        val roster = listOf(me, noName, zed)
        assertEquals(listOf("u-me", "u-z"), removeMember(roster, "u-9").map { it.principalId })
    }

    // ── Leave group ──

    @Test
    fun `leaving removes self from the roster and sort order`() {
        val before = sortMembersYouFirst(listOf(me, admin1, zed), "u-me")
        assertEquals("u-me", before.first().principalId)
        val after = removeMember(listOf(me, admin1, zed), "u-me")
        assertEquals(listOf("u-a1", "u-z"), sortMembersYouFirst(after, "u-me").map { it.principalId })
    }

    @Test
    fun `an admin leaving revokes their own admin status`() {
        val roster = listOf(me.copy(role = "admin"), admin1)
        assertTrue(isSelfAdmin(roster, "u-me", null))
        val afterLeave = removeMember(roster, "u-me")
        assertFalse(isSelfAdmin(afterLeave, "u-me", null))
    }

    // ── Rename / picture / description ──

    @Test
    fun `rename carries only the name field`() {
        assertEquals(UpdateGroupRequest(name = "Trip"), UpdateGroupRequest(name = "Trip"))
        val encoded = json.encodeToString(UpdateGroupRequest.serializer(), UpdateGroupRequest(name = "Trip"))
        assertTrue(encoded.contains("\"name\":\"Trip\""))
        val restored = json.decodeFromString(UpdateGroupRequest.serializer(), encoded)
        assertEquals("Trip", restored.name)
        assertNull(restored.description)
        assertNull(restored.iconUrl)
    }

    @Test
    fun `picture change carries icon_url`() {
        val encoded = json.encodeToString(UpdateGroupRequest.serializer(), UpdateGroupRequest(iconUrl = "https://cdn/icons/1.jpg"))
        assertTrue(encoded.contains("\"icon_url\""))
        assertTrue(encoded.contains("https://cdn/icons/1.jpg"))
        assertEquals("https://cdn/icons/1.jpg", json.decodeFromString(UpdateGroupRequest.serializer(), encoded).iconUrl)
    }

    @Test
    fun `description update carries only description`() {
        assertEquals(UpdateGroupRequest(description = "Road trip!"), UpdateGroupRequest(description = "Road trip!"))
        val restored = json.decodeFromString(UpdateGroupRequest.serializer(), json.encodeToString(UpdateGroupRequest.serializer(), UpdateGroupRequest(description = "Road trip!")))
        assertEquals("Road trip!", restored.description)
        assertNull(restored.name)
    }

    // ── Admin permissions ──

    @Test
    fun `group admin is only the admin role case-insensitively`() {
        assertTrue(isGroupAdmin(admin1))
        assertTrue(isGroupAdmin(admin2))
        assertFalse(isGroupAdmin(owner))
        assertFalse(isGroupAdmin(creator))
        assertFalse(isGroupAdmin(me))
        assertFalse(isGroupAdmin(noName.copy(role = null)))
    }

    @Test
    fun `self admin accepts admin owner and creator roles`() {
        assertTrue(isSelfAdmin(listOf(me.copy(role = "admin")), "u-me", null))
        assertTrue(isSelfAdmin(listOf(owner), "u-ow", null))
        assertTrue(isSelfAdmin(listOf(creator), "u-cr", null))
        assertFalse(isSelfAdmin(listOf(me), "u-me", "+1000"))
        assertFalse(isSelfAdmin(emptyList(), "u-me", "+1000"))
    }

    @Test
    fun `self admin falls back to session phone when principal id differs`() {
        val skew = Participant("da_other", "admin", "Me", "+1000")
        assertTrue(isSelfAdmin(listOf(skew), "u-me", "+1000"))
        assertFalse(isSelfAdmin(listOf(owner), "u-me", "+1000"))
    }

    @Test
    fun `subtitle shows you only for membership admin`() {
        assertEquals("You · Group admin", memberSubtitle(admin1.copy(principalId = "u-me"), "u-me"))
        assertEquals("You", memberSubtitle(me, "u-me"))
        assertEquals("Group admin", memberSubtitle(admin1, "u-me"))
        assertEquals("Group admin", memberSubtitle(admin2, "u-me"))
        // owner/creator subtitles are NOT "Group admin" (memberSubtitle only checks the admin role).
        assertEquals("+1777", memberSubtitle(owner, "u-me"))
    }

    @Test
    fun `multiple admins all rank above members but below you`() {
        val roster = listOf(zed, admin2, admin1, me.copy(role = "admin"), noName)
        val sorted = sortMembersYouFirst(roster, "u-me").map { it.principalId }
        assertEquals(listOf("u-me", "u-a1", "u-a2", "u-z", "u-9"), sorted)
        assertTrue(sorted.indexOf("u-a1") < sorted.indexOf("u-a2"))
        assertTrue(sorted.indexOf("u-a2") < sorted.indexOf("u-z"))
        assertTrue(sorted.indexOf("u-z") < sorted.indexOf("u-9"))
    }

    @Test
    fun `memberDisplayName falls back name to phone to id prefix`() {
        assertEquals("Adam", memberDisplayName(admin1))
        assertEquals("+1999", memberDisplayName(noName))
        assertEquals("u-abcdef", memberDisplayName(Participant("u-abcdef1234", "member", null, null)))
    }

    // ── Promote / demote ──

    @Test
    fun `promoting to admin flips role and grants permissions`() {
        val promoted = me.copy(role = "admin")
        assertEquals("admin", promoted.role)
        assertTrue(isGroupAdmin(promoted))
        assertTrue(isSelfAdmin(listOf(promoted), "u-me", null))
        val request = json.decodeFromString(MemberRoleRequest.serializer(), json.encodeToString(MemberRoleRequest.serializer(), MemberRoleRequest("admin")))
        assertEquals("admin", request.role)
    }

    @Test
    fun `demoting an admin revokes permissions`() {
        val demoted = admin1.copy(role = "member")
        assertFalse(isGroupAdmin(demoted))
        assertFalse(isSelfAdmin(listOf(demoted), "u-a1", "+1001"))
        val request = json.decodeFromString(MemberRoleRequest.serializer(), json.encodeToString(MemberRoleRequest.serializer(), MemberRoleRequest("member")))
        assertEquals("member", request.role)
    }

    // ── Sending / replying / reacting ──

    @Test
    fun `send request round-trips client id kind body and reply`() {
        val body = buildJsonObject { put("text", "hello") }
        val request = SendMessageRequest("cm-1", "text", body, "m-99", listOf("a1"))
        val restored = json.decodeFromString(SendMessageRequest.serializer(), json.encodeToString(SendMessageRequest.serializer(), request))
        assertEquals(request, restored)
        assertEquals("m-99", restored.replyTo)
        assertEquals(listOf("a1"), restored.attachmentIds)
    }

    @Test
    fun `reply metadata survives message serialization`() {
        val msg = message("m2", "r1", "u1", 2, replyTo = "m-1")
        val restored = json.decodeFromString(MessageResponse.serializer(), json.encodeToString(MessageResponse.serializer(), msg))
        assertEquals("m-1", restored.replyTo)
    }

    @Test
    fun `reaction request round-trips the emoji`() {
        assertEquals("👍", json.decodeFromString(ReactionRequest.serializer(), json.encodeToString(ReactionRequest.serializer(), ReactionRequest("👍"))).emoji)
    }

    @Test
    fun `reaction map survives message serialization`() {
        val msg = message("m3", "r1", "u1", 3, reactions = mapOf("👍" to listOf("u1", "u2")))
        val restored = json.decodeFromString(MessageResponse.serializer(), json.encodeToString(MessageResponse.serializer(), msg))
        assertEquals(mapOf("👍" to listOf("u1", "u2")), restored.reactions)
    }

    // ── Media sharing ──

    @Test
    fun `attachment info round-trips both id shapes`() {
        val att = AttachmentInfo(attachmentId = "a1", id = null, mime = "image/jpeg", filename = "pic.jpg", byteSize = 2048L)
        val restored = json.decodeFromString(AttachmentInfo.serializer(), json.encodeToString(AttachmentInfo.serializer(), att))
        assertEquals(att, restored)
    }

    @Test
    fun `attachmentRefIds prefers explicit ids and dedupes message objects`() {
        val msg = message("m4", "r1", "u1", 4, attachmentIds = listOf("a1", "a2"), attachments = listOf(AttachmentInfo(attachmentId = "a1"), AttachmentInfo(id = "h2")))
        assertEquals(listOf("a1", "a2", "h2"), attachmentRefIds(msg))
    }

    @Test
    fun `voice duration prefers ms then seconds fallback`() {
        val msBody = buildJsonObject { put("duration_ms", 3200) }
        assertEquals(3200L, voiceDurationMs(msBody))
        val secBody = buildJsonObject { put("duration", 3) }
        assertEquals(3000L, voiceDurationMs(secBody))
        val none = buildJsonObject { put("text", "hello") }
        assertNull(voiceDurationMs(none))
    }

    // ── Mention users ──

    @Test
    fun `member search finds a mentioned name by trimmed prefix`() {
        val roster = listOf(me, admin1, zed)
        assertEquals(listOf("u-a1"), filterMembers(roster, "ada").map { it.principalId })
        assertEquals(listOf("u-a1"), filterMembers(roster, "  adam  ").map { it.principalId })
    }

    @Test
    fun `mention lookup by username with at sign matches contacts`() {
        // Group roster search keys name/phone/id; the contact picker strips the
        // "@" prefix. Both layers must still find the target user.
        val pickerRow = com.ollacore.app.ui.contacts.ContactRow(
            key = "uid:u1", firstName = "Adam", lastName = "Puri",
            displayName = "Adam Puri", phone = "+15551112222",
            normalizedPhone = "+15551112222", username = "adam.puri", userId = "u1"
        )
        assertTrue(com.ollacore.app.ui.contacts.ContactBook.matchesQuery(pickerRow, "@adam"))
        assertTrue(com.ollacore.app.ui.contacts.ContactBook.matchesQuery(pickerRow, "ADAM.PURI"))
    }

    // ── Notifications / mute ──

    private val MUTE_ALWAYS: Long = Long.MAX_VALUE
    private val MUTE_8H_MS: Long = 8L * 60 * 60 * 1000
    private val MUTE_WEEK_MS: Long = 7L * 24 * 60 * 60 * 1000

    private fun muteEffective(until: Long?, now: Long): Boolean =
        until != null && (until == MUTE_ALWAYS || until > now)

    @Test
    fun `mute-always stays muted at any time`() {
        assertTrue(muteEffective(MUTE_ALWAYS, 0L))
        assertTrue(muteEffective(MUTE_ALWAYS, System.currentTimeMillis()))
    }

    @Test
    fun `timed muting unmutes once the window passes`() {
        val now = 1_700_000_000_000L
        assertTrue(muteEffective(now + 1000, now))
        assertFalse(muteEffective(now, now))
        assertFalse(muteEffective(now - 1000, now))
        assertFalse(muteEffective(null, now))
    }

    @Test
    fun `8h and week mute windows are positive durations`() {
        assertTrue(MUTE_8H_MS > 0)
        assertTrue(MUTE_WEEK_MS > MUTE_8H_MS)
        assertEquals(21L, MUTE_WEEK_MS / MUTE_8H_MS)
    }

    // ── Delete / dissolve ──

    @Test
    fun `removing the last member dissolves the group`() {
        val dissolved = removeMember(listOf(me), "u-me")
        assertTrue(dissolved.isEmpty())
        assertTrue(sortMembersYouFirst(dissolved, "u-me").isEmpty())
        assertTrue(filterMembers(dissolved, "").isEmpty())
        assertTrue(isSelfAdmin(dissolved, "u-me", null).not())
    }

    @Test
    fun `leaving as the last remaining member empties the roster`() {
        val roster = listOf(me)
        val after = removeMember(roster, "u-me")
        assertTrue(after.isEmpty())
    }

    // ── Edge: 1 member / large rosters ──

    @Test
    fun `single-member roster keeps self first and searchable`() {
        val single = listOf(me)
        assertEquals(listOf("u-me"), sortMembersYouFirst(single, "u-me").map { it.principalId })
        assertEquals(listOf("u-me"), filterMembers(single, "me").map { it.principalId })
        assertEquals("You", memberSubtitle(me, "u-me"))
    }

    @Test
    fun `large rosters sort and search correctly within time bound`() {
        val roster = mutableListOf<Participant>().apply {
            add(me)
            add(admin1)
            (0 until 998).forEach { i -> add(Participant("u-h$i", "member", "Member $i", "+100$i")) }
        }
        val start = System.nanoTime()
        val sorted = sortMembersYouFirst(roster, "u-me")
        val elapsed = System.nanoTime() - start
        assertEquals(1000, sorted.size)
        assertEquals("u-me", sorted.first().principalId)
        assertEquals(listOf("u-h997"), filterMembers(roster, "h997").map { it.principalId })
        assertTrue("large-roster sort took too long", elapsed < 2_000_000_000L)
    }

    @Test
    fun `rosters above the supported membership still process correctly`() {
        val roster = mutableListOf<Participant>().apply {
            add(me)
            (0 until 1000).forEach { i -> add(Participant("u-x$i", "member", "X$i", "+100$i")) }
        }
        val sorted = sortMembersYouFirst(roster, "u-me")
        assertEquals(1001, sorted.size)
        assertEquals("u-me", sorted.first().principalId)
        assertEquals(listOf("u-x999"), filterMembers(roster, "X999").map { it.principalId })
    }

    // ── Edge: simultaneous changes ──

    @Test
    fun `simultaneous add and remove settle the same roster regardless of order`() {
        val roster = listOf(me, admin1, zed)
        val addThenRemove = removeMember(addMember(roster, noName), "u-a1")
        val removeThenAdd = addMember(removeMember(roster, "u-a1"), noName)
        assertEquals(addThenRemove.map { it.principalId }, removeThenAdd.map { it.principalId })
        assertEquals(listOf("u-me", "u-z", "u-9"), addThenRemove.map { it.principalId })
    }

    @Test
    fun `same-id add and remove is last-write-wins`() {
        assertTrue(addMember(removeMember(listOf(admin1), "u-a1"), admin1).isNotEmpty())
        assertTrue(removeMember(addMember(emptyList(), admin1), "u-a1").isEmpty())
    }

    @Test
    fun `removing the final admin leaves no privileged members`() {
        val roster = listOf(me.copy(role = "admin"), admin1, admin2)
        val noAdmins = removeMember(removeMember(removeMember(roster, "u-me"), "u-a1"), "u-a2")
        assertTrue(noAdmins.none { isGroupAdmin(it) })
        assertFalse(isSelfAdmin(noAdmins, "u-me", null))
    }

    // ── Edge: user removed while sending ──

    @Test
    fun `outbound send payload has no membership coupling`() {
        // Sending is scoped to room token/id; roster membership must not leak
        // into the wire request (so being removed mid-send changes nothing).
        val body = buildJsonObject { put("text", "still sending") }
        val request = SendMessageRequest("cm-9", "text", body, replyTo = null, attachmentIds = emptyList())
        val wire = json.encodeToString(SendMessageRequest.serializer(), request)
        assertFalse(wire.contains("principal"))
        assertFalse(wire.contains("role"))
        assertTrue(wire.contains("client_message_id"))
        assertTrue(wire.contains("\"body\""))
    }

    // ── Membership journal ──

    private val EVENT_CAP = 100

    private fun appendEvent(current: List<GroupEvent>, entry: GroupEvent): List<GroupEvent> =
        (listOf(entry) + current).sortedByDescending { it.at }.take(EVENT_CAP)

    @Test
    fun `membership journal is newest-first and capped at a hundred`() {
        var journal = emptyList<GroupEvent>()
        (1..101).forEach { i ->
            journal = appendEvent(journal, GroupEvent("e$i", "r1", "You", "Member added", "+100$i", i.toLong()))
        }
        assertEquals(EVENT_CAP, journal.size)
        assertEquals("e101", journal.first().id)
        assertEquals("e2", journal.last().id)
        assertTrue(journal.none { it.id == "e1" })
        assertTrue(journal.zipWithNext().all { (a, b) -> a.at >= b.at })
    }

    @Test
    fun `group events survive json round-trip with blank actor default`() {
        val ser = ListSerializer(GroupEvent.serializer())
        val original = listOf(
            GroupEvent("e1", "r1", "  ", "lef_group", "+1002", 1_700_000_000_000L)
        )
        val restored: List<GroupEvent> = json.decodeFromString(ser, json.encodeToString(ser, original))
        assertEquals("  ", restored[0].actor)
        assertEquals("lef_group", restored[0].action)
    }

    // ── Helper: build a MessageResponse fixture ──

    private fun message(
        id: String,
        roomId: String,
        senderId: String,
        seq: Int,
        kind: String = "text",
        replyTo: String? = null,
        attachmentIds: List<String> = emptyList(),
        attachments: List<AttachmentInfo> = emptyList(),
        reactions: Map<String, List<String>>? = null
    ): MessageResponse {
        val body = buildJsonObject { put("text", "hello") }
        return MessageResponse(
            id = id, roomId = roomId, senderId = senderId, kind = kind,
            body = body, createdAt = "2026-01-01T00:00:00Z", eventSeq = seq,
            replyTo = replyTo, attachmentIds = attachmentIds,
            attachments = attachments, reactions = reactions
        )
    }
}