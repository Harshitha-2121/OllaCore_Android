package com.ollacore.app.ui.contacts

import com.ollacore.app.data.local.LocalContact
import com.ollacore.app.data.model.ContactUser
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ContactRowsTest {

    // ── Normalization ──

    @Test
    fun `normalize strips formatting but keeps leading plus`() {
        assertEquals("+15550001111", ContactBook.normalizePhone("+1 (555) 000-1111"))
        assertEquals("+15550001111", ContactBook.normalizePhone("  +1-555-000-1111  "))
    }

    @Test
    fun `normalize converts 00 prefix to plus`() {
        assertEquals("+44207946", ContactBook.normalizePhone("0044 20 7946"))
    }

    @Test
    fun `normalize drops non-digits`() {
        assertEquals("", ContactBook.normalizePhone("abc"))
        assertEquals("", ContactBook.normalizePhone("   "))
    }

    @Test
    fun `valid phones are 7-15 digits`() {
        assertTrue(ContactBook.isValidPhone("+15550001111"))
        assertTrue(ContactBook.isValidPhone("1234567"))
        assertFalse(ContactBook.isValidPhone("123456"))
        assertFalse(ContactBook.isValidPhone("+1234567890123456"))
        assertFalse(ContactBook.isValidPhone(""))
    }

    // ── Form validation ──

    private val saved = listOf(
        LocalContact(
            id = "1", firstName = "Alice", phone = "+15550001111",
            normalizedPhone = "+15550001111"
        )
    )

    @Test
    fun `add requires first name`() {
        assertEquals(
            ContactFormError.FIRST_REQUIRED,
            ContactBook.validateForm("  ", "+15550002222", saved)
        )
    }

    @Test
    fun `add requires phone`() {
        assertEquals(
            ContactFormError.PHONE_REQUIRED,
            ContactBook.validateForm("Bob", "  ", saved)
        )
    }

    @Test
    fun `add rejects invalid phone`() {
        assertEquals(
            ContactFormError.PHONE_INVALID,
            ContactBook.validateForm("Bob", "123", saved)
        )
    }

    @Test
    fun `add rejects duplicate number despite formatting`() {
        assertEquals(
            ContactFormError.DUPLICATE,
            ContactBook.validateForm("Alice2", "+1 (555) 000-1111", saved)
        )
    }

    @Test
    fun `edit exempts own record from duplicate check`() {
        assertNull(ContactBook.validateForm("Alice", "+15550001111", saved, editingId = "1"))
        assertEquals(
            ContactFormError.DUPLICATE,
            ContactBook.validateForm("Alice", "+15550001111", saved, editingId = "other")
        )
    }

    @Test
    fun `valid form passes`() {
        assertNull(ContactBook.validateForm("  Bob ", "+15550002222", saved))
    }

    // ── Search ──

    private val row = ContactRow(
        key = "uid:u1", firstName = "Alice", lastName = "Wonder",
        displayName = "Alice Wonder", phone = "+1 555-000-1111",
        normalizedPhone = "+15550001111", username = "alicew", userId = "u1"
    )

    @Test
    fun `search matches first last full phone username case-insensitively`() {
        assertTrue(ContactBook.matchesQuery(row, "alice"))
        assertTrue(ContactBook.matchesQuery(row, "WONDER"))
        assertTrue(ContactBook.matchesQuery(row, "alice wonder"))
        assertTrue(ContactBook.matchesQuery(row, "555"))
        assertTrue(ContactBook.matchesQuery(row, "000-1111"))
        assertTrue(ContactBook.matchesQuery(row, "alicew"))
        assertTrue(ContactBook.matchesQuery(row, ""))
    }

    @Test
    fun `search rejects non-matches`() {
        assertFalse(ContactBook.matchesQuery(row, "bob"))
        assertFalse(ContactBook.matchesQuery(row, "999"))
    }

    // ── Merge / sort / sections ──

    @Test
    fun `merge prefers local name and photo over inbox peer`() {
        val inbox = listOf(ContactUser(userId = "u1", phone = "+15550001111", displayName = "Al"))
        val local = listOf(
            LocalContact(
                id = "l1", firstName = "Alice", lastName = "W", phone = "+1 555 000 1111",
                normalizedPhone = "+15550001111", photoUri = "/p/a.jpg"
            )
        )
        val rows = ContactBook.mergeRows(inbox, local)
        assertEquals(1, rows.size)
        assertEquals("Alice W", rows[0].displayName)
        assertEquals("/p/a.jpg", rows[0].photoUri)
        assertEquals("u1", rows[0].userId)
        assertEquals("l1", rows[0].localId)
        assertTrue(rows[0].onOllacore)
    }

    @Test
    fun `merge keeps unresolved local contacts as their own rows`() {
        val rows = ContactBook.mergeRows(
            emptyList(),
            listOf(
                LocalContact(
                    id = "l9", firstName = "Zed", phone = "5550009999",
                    normalizedPhone = "5550009999"
                )
            )
        )
        assertEquals(1, rows.size)
        assertEquals("local:l9", rows[0].key)
        assertFalse(rows[0].onOllacore)
        assertNull(rows[0].userId)
    }

    @Test
    fun `merge marks resolved local contacts on-ollacore`() {
        val rows = ContactBook.mergeRows(
            emptyList(),
            listOf(
                LocalContact(
                    id = "l9", firstName = "Zed", phone = "5550009999",
                    normalizedPhone = "5550009999", resolvedUserId = "u9"
                )
            )
        )
        assertTrue(rows[0].onOllacore)
        assertEquals("u9", rows[0].userId)
    }

    @Test
    fun `merge sorts alphabetically case-insensitively`() {
        val inbox = listOf(
            ContactUser(userId = "u2", phone = "222", displayName = "bob"),
            ContactUser(userId = "u1", phone = "111", displayName = "Alice")
        )
        val rows = ContactBook.mergeRows(inbox, emptyList())
        assertEquals(listOf("Alice", "bob"), rows.map { it.displayName })
    }

    @Test
    fun `section keys use upper-case first letter`() {
        assertEquals("A", ContactBook.sectionKey("alice"))
        assertEquals("#", ContactBook.sectionKey("123"))
        assertEquals("#", ContactBook.sectionKey(""))
    }

    // ── Persistence shape ──

    @Test
    fun `local contacts survive json round-trip`() {
        val json = Json { ignoreUnknownKeys = true }
        val ser = ListSerializer(LocalContact.serializer())
        val original = listOf(
            LocalContact(
                id = "l1", firstName = "Alice", lastName = "W",
                phone = "+1 555-000-1111", normalizedPhone = "+15550001111",
                photoUri = "/p/a.jpg", resolvedUserId = "u1",
                createdAt = 1L, updatedAt = 2L
            )
        )
        val restored: List<LocalContact> = json.decodeFromString(ser, json.encodeToString(ser, original))
        assertEquals(original, restored)
    }

    @Test
    fun `old stored contacts without username still decode`() {
        val json = Json { ignoreUnknownKeys = true }
        val ser = ListSerializer(LocalContact.serializer())
        val legacy = """[{"id":"l1","firstName":"Al","phone":"+15550001111","normalizedPhone":"+15550001111"}]"""
        val restored: List<LocalContact> = json.decodeFromString(ser, legacy)
        assertEquals(1, restored.size)
        assertNull(restored[0].username)
        assertTrue(restored[0].syncToPhone)
    }

    // ── New-contact form helpers ──

    @Test
    fun `full number joins dial code and drops trunk zero`() {
        assertEquals("+919876543210", ContactBook.fullPhoneNumber("91", "09876543210"))
        assertEquals("+919876543210", ContactBook.fullPhoneNumber("91", "9876543210"))
        assertEquals("+15550001111", ContactBook.fullPhoneNumber("1", "+1 (555) 000-1111"))
        assertEquals("", ContactBook.fullPhoneNumber("91", ""))
    }

    @Test
    fun `merge carries local username into rows`() {
        val rows = ContactBook.mergeRows(
            emptyList(),
            listOf(
                LocalContact(
                    id = "l5", firstName = "Mia", phone = "+15550005555",
                    normalizedPhone = "+15550005555", username = "mia.m"
                )
            )
        )
        assertEquals("mia.m", rows[0].username)
        assertTrue(ContactBook.matchesQuery(rows[0], "@mia"))
        assertTrue(ContactBook.matchesQuery(rows[0], "MIA.M"))
    }
}
