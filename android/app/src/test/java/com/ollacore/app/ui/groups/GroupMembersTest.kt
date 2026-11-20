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

    // ── BUG-09: never show bare hex principal ids ──

    @Test
    fun `id only member resolves to the directory name`() {
        val idOnly = Participant("23c7f84b-9d2e-4a11-b0c2-1234567890ab", null, null, null)
        val directory = mapOf("23c7f84b-9d2e-4a11-b0c2-1234567890ab" to "Akash")
        assertEquals("Akash", memberDisplayName(idOnly, directory))
        assertEquals("Akash", memberSubtitle(idOnly, null, directory))
    }

    @Test
    fun `unresolvable member gets a neutral label, not a truncated id`() {
        val idOnly = Participant("23c7f84b-9d2e-4a11-b0c2-1234567890ab", null, null, null)
        assertEquals("Group member", memberDisplayName(idOnly))
        assertEquals("Member", memberSubtitle(idOnly, null))
        assertFalse(memberDisplayName(idOnly).contains("23c7"))
        assertFalse(memberSubtitle(idOnly, null).contains("23c7"))
    }

    @Test
    fun `server name then phone then directory precedence`() {
        val named = Participant("u-ak", "member", "Akash S", null)
        assertEquals("Akash S", memberDisplayName(named, mapOf("u-ak" to "Directory Name")))
        val phoneOnly = Participant("u-ak", "member", null, "+1002")
        assertEquals("+1002", memberDisplayName(phoneOnly, mapOf("u-ak" to "Directory Name")))
        // Blank server fields never win over a real directory name.
        val blank = Participant("u-ak", "member", "  ", null)
        assertEquals("Directory Name", memberDisplayName(blank, mapOf("u-ak" to "Directory Name")))
    }

    @Test
    fun `contact rows produce a human label in priority order`() {
        assertEquals("Akash S", contactNameFrom("Akash S", "Akash", "S", "+1002", "+1002", null))
        assertEquals("Akash S", contactNameFrom(null, "Akash", "S", "+1002", "+1002", null))
        assertEquals("Akash", contactNameFrom(" ", "Akash", "", "+1002", "+1002", null))
        assertEquals("+1002", contactNameFrom(null, "", "", "+1002", "+1002", null))
        assertEquals("+1002", contactNameFrom(null, "", "", "", "+1002", null))
        assertEquals("akash", contactNameFrom(null, "", "", "", "", "akash"))
        assertNull(contactNameFrom(null, "", "", "", "", null))
    }
}
