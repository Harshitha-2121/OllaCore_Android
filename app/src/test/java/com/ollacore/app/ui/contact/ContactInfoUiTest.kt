package com.ollacore.app.ui.contact

import com.ollacore.app.ui.chat.shortTimeOf
import com.ollacore.app.ui.chat.starredPreview
import com.ollacore.app.data.model.MessageResponse
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

class ContactInfoUiTest {

    @Test
    fun `formatShortDuration renders m-ss`() {
        assertEquals("0:00", formatShortDuration(0))
        assertEquals("0:05", formatShortDuration(5200))
        assertEquals("0:36", formatShortDuration(36000))
        assertEquals("1:30", formatShortDuration(90000))
        assertEquals("60:00", formatShortDuration(3_600_000))
        assertEquals("0:00", formatShortDuration(-100))
    }

    @Test
    fun `validateListName rejects blank long and duplicate`() {
        assertNotNull(validateListName("", emptySet()))
        assertNotNull(validateListName("   ", emptySet()))
        assertNotNull(validateListName("x".repeat(61), emptySet()))
        assertNotNull(validateListName("family", setOf("Family")))
        assertNull(validateListName("Family", emptySet()))
        assertNull(validateListName("  Work  ", setOf("Family")))
    }

    private fun msg(kind: String, body: Map<String, String> = emptyMap()): MessageResponse {
        val jsonBody = buildJsonObject {
            body.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }
        return MessageResponse(
            id = "m1", roomId = "r1", senderId = "u1", kind = kind,
            body = jsonBody, createdAt = "2026-09-28T08:30:00Z", eventSeq = 1
        )
    }

    @Test
    fun `starredPreview prefers text then filename then kind label`() {
        assertEquals("hello", starredPreview(msg("text", mapOf("text" to "hello"))))
        assertEquals("doc.pdf", starredPreview(msg("file", mapOf("filename" to "doc.pdf"))))
        assertEquals("Photo", starredPreview(msg("image")))
        assertEquals("Video", starredPreview(msg("video")))
        assertEquals("Voice message", starredPreview(msg("audio")))
        assertEquals("Shared file", starredPreview(msg("text")))
    }

    @Test
    fun `shortTimeOf keeps HH-mm shape and passes garbage through`() {
        assertTrue(shortTimeOf("2026-09-28T08:30:00Z").matches(Regex("\\d{2}:\\d{2}")))
        assertEquals("not-a-time", shortTimeOf("not-a-time"))
    }
}
