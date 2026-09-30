package com.ollacore.app.ui.groups

import com.ollacore.app.data.local.GroupEvent
import com.ollacore.app.data.model.Participant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class GroupMembersTest {

    private val me = Participant("u-me", "member", "Me", "+1000")
    private val admin = Participant("u-ad", "admin", "Puri", "+1001")
    private val akash = Participant("u-ak", "member", "Akash", "+1002")
    private val anamika = Participant("u-an", null, null, "+1003")
    private val all = listOf(akash, me, anamika, admin)

    @Test
    fun `subtitle distinguishes you and admins`() {
        assertEquals("You · Group admin", memberSubtitle(admin.copy(principalId = "u-me"), "u-me"))
        assertEquals("You", memberSubtitle(me, "u-me"))
        assertEquals("Group admin", memberSubtitle(admin, "u-me"))
        assertEquals("+1002", memberSubtitle(akash, "u-me"))
        assertEquals("+1003", memberSubtitle(anamika, "u-me"))
    }

    @Test
    fun `you first then admins then alphabetical`() {
        val sorted = sortMembersYouFirst(all, "u-me").map { it.principalId }
        assertEquals(listOf("u-me", "u-ad", "u-ak", "u-an"), sorted)
    }

    @Test
    fun `member search matches name phone and id`() {
        assertEquals(4, filterMembers(all, "").size)
        assertEquals(listOf("u-ak"), filterMembers(all, "akas").map { it.principalId })
        assertEquals(listOf("u-an"), filterMembers(all, "1003").map { it.principalId })
        assertEquals(listOf("u-ad"), filterMembers(all, "U-AD").map { it.principalId })
        assertTrue(filterMembers(all, "nobody here").isEmpty())
    }

    @Test
    fun `self admin by id with admin role`() {
        assertTrue(isSelfAdmin(all, "u-ad", "+1999"))
        assertTrue(isSelfAdmin(all, "u-me", "+1000").not())
    }

    @Test
    fun `self admin accepts owner creator roles and phone fallback`() {
        val owner = Participant("u-ox", "owner", "Puri", "+1001")
        assertTrue(isSelfAdmin(listOf(owner), "u-ox", null))
        val creator = Participant("u-cx", "Creator", "Puri", "+1001")
        assertTrue(isSelfAdmin(listOf(creator), "u-cx", null))
        // Principal id format skew: match by session phone instead.
        val phoneOnly = Participant("da_other", "admin", "Puri", "+1000")
        assertTrue(isSelfAdmin(listOf(phoneOnly), "u-me", "+1000"))
    }

    @Test
    fun `null role or plain member is never self admin`() {
        assertFalse(isSelfAdmin(all, "u-me", "+1000"))
        assertFalse(isSelfAdmin(all, "u-an", "+1003"))
        assertFalse(isSelfAdmin(all, null, null))
        assertFalse(isSelfAdmin(emptyList(), "u-me", "+1000"))
    }

    @Test
    fun `group events survive json round-trip`() {
        val json = Json { ignoreUnknownKeys = true }
        val ser = ListSerializer(GroupEvent.serializer())
        val original = listOf(
            GroupEvent("e1", "r1", "You", "Member added", "+1002", 1_700_000_000_000L),
            GroupEvent("e2", "r1", "You", "Made admin", "+1002", 1_700_000_060_000L)
        )
        val restored: List<GroupEvent> =
            json.decodeFromString(ser, json.encodeToString(ser, original))
        assertEquals(original, restored)
    }
}
