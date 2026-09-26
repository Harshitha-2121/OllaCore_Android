package com.ollacore.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression: history decode must not die when the server emits attachment
 * objects without attachment_id (MissingFieldException at
 * $.messages[].attachments[] blanked the whole chat).
 */
class HistoryDecodeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `attachments without id still decode`() {
        val body = """
            {
              "messages": [
                {
                  "id": "m1",
                  "room_id": "r1",
                  "sender_id": "u1",
                  "kind": "image",
                  "body": {"text": "pic", "mime": "image/jpeg"},
                  "created_at": "2026-09-24T08:00:00Z",
                  "event_seq": 34,
                  "attachment_ids": ["att_1"],
                  "attachments": [{"mime": "image/jpeg"}]
                }
              ],
              "has_more": false
            }
        """.trimIndent()
        val res = json.decodeFromString<MessageListResponse>(body)
        assertEquals(1, res.messages.size)
        assertEquals("m1", res.messages[0].id)
        assertNull(res.messages[0].attachments[0].attachmentId)
        assertEquals(listOf("att_1"), res.messages[0].attachmentIds)
    }

    @Test
    fun `attachments with id still decode`() {
        val body = """
            {
              "messages": [
                {
                  "id": "m2",
                  "room_id": "r1",
                  "sender_id": "u1",
                  "kind": "text",
                  "body": {"text": "hi"},
                  "created_at": "2026-09-24T08:01:00Z",
                  "event_seq": 35,
                  "attachments": [{"attachment_id": "att_9", "mime": "image/png"}]
                }
              ],
              "has_more": true
            }
        """.trimIndent()
        val res = json.decodeFromString<MessageListResponse>(body)
        assertEquals("att_9", res.messages[0].attachments[0].attachmentId)
        assertTrue(res.hasMore)
    }

    @Test
    fun `history file message without attachments still decodes (peer voice webm)`() {
        // PROVEN 2026-09-24: Alice webm voice rows ship kind=file + body mime only —
        // no attachment_ids and no attachments[]. Decode must not crash; UI shows
        // honest "audio not linked" instead of a silent play button.
        val body = """
            {
              "messages": [
                {
                  "id": "01a0d2c0-529c-70b2-8208-2521aecb6956",
                  "room_id": "r1",
                  "sender_id": "u2",
                  "kind": "file",
                  "body": {
                    "byte_size": 112605,
                    "duration": 7,
                    "filename": "voice_message_1790242208711.webm",
                    "is_voice_note": true,
                    "mime": "audio/webm;codecs=opus",
                    "text": "Voice message"
                  },
                  "created_at": "2026-09-24T09:30:14Z",
                  "event_seq": 3384
                }
              ],
              "has_more": false
            }
        """.trimIndent()
        val res = json.decodeFromString<MessageListResponse>(body)
        val msg = res.messages[0]
        assertEquals("file", msg.kind)
        assertTrue(attachmentRefIds(msg).isEmpty())
        assertEquals(7000L, voiceDurationMs(msg.body))
    }

    @Test
    fun `voiceDurationMs prefers duration_ms then duration seconds`() {
        assertEquals(
            24791L,
            voiceDurationMs(mapOf("duration_ms" to kotlinx.serialization.json.JsonPrimitive(24791)))
        )
        assertEquals(
            21000L,
            voiceDurationMs(mapOf("duration" to kotlinx.serialization.json.JsonPrimitive(21)))
        )
        assertNull(voiceDurationMs(emptyMap()))
    }
}
