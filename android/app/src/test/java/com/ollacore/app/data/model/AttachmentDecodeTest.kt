package com.ollacore.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression: single-part init returns "upload_expires_at" (not
 * "expires_at") plus "required_headers". Decoding must succeed with and
 * without the expiry field; the old mapping crashed every image upload
 * with MissingFieldException.
 */
class AttachmentDecodeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `init decodes actual server shape`() {
        val body = """
            {
              "attachment_id": "01a0d298-f7ef-72e2-a8ec-36fb337a7f8f",
              "upload_url": "https://api.ollacore.com:9443/x?sig=abc",
              "upload_expires_at": "2026-09-24T08:52:15.445637903Z",
              "required_headers": [["content-type", "application/octet-stream"]]
            }
        """.trimIndent()
        val res = json.decodeFromString<AttachmentInitResponse>(body)
        assertEquals("01a0d298-f7ef-72e2-a8ec-36fb337a7f8f", res.attachmentId)
        assertTrue(res.uploadUrl.startsWith("https://"))
        assertEquals("2026-09-24T08:52:15.445637903Z", res.uploadExpiresAt)
        assertEquals(listOf(listOf("content-type", "application/octet-stream")), res.requiredHeaders)
    }

    @Test
    fun `init decodes without any expiry field`() {
        val body = """
            {
              "attachment_id": "att_1",
              "upload_url": "https://example.com/up"
            }
        """.trimIndent()
        val res = json.decodeFromString<AttachmentInitResponse>(body)
        assertEquals("att_1", res.attachmentId)
        assertNull(res.uploadExpiresAt)
    }

    @Test
    fun `multipart init keeps its expires_at shape`() {
        val body = """
            {
              "attachment_id": "att_2",
              "upload_id": "up_1",
              "part_size": 8388608,
              "part_urls": ["https://example.com/p1"],
              "expires_at": "2026-09-24T08:53:16Z"
            }
        """.trimIndent()
        val res = json.decodeFromString<MultipartInitResponse>(body)
        assertEquals("att_2", res.attachmentId)
        assertEquals(1, res.partUrls.size)
        assertEquals("2026-09-24T08:53:16Z", res.expiresAt)
    }

    @Test
    fun `download decodes without expiry`() {
        val body = """{"download_url": "https://example.com/dl"}"""
        val res = json.decodeFromString<AttachmentDownloadResponse>(body)
        assertEquals("https://example.com/dl", res.downloadUrl)
        assertNull(res.expiresAt)
    }

    @Test
    fun `history attachments with id-only shape resolve refs`() {
        val body = """
            {
              "messages": [
                {
                  "id": "m3",
                  "room_id": "r1",
                  "sender_id": "u1",
                  "kind": "image",
                  "body": {"text": "pic", "mime": "image/png"},
                  "created_at": "2026-09-24T08:51:53Z",
                  "event_seq": 3381,
                  "attachments": [
                    {"id": "01a0d29c", "original_name": "e2e_probe.png",
                     "mime": "image/png", "byte_size": 242327,
                     "status": "ready", "width": 1080, "height": 2400,
                     "has_thumbnail": true}
                  ]
                }
              ],
              "has_more": false
            }
        """.trimIndent()
        val res = json.decodeFromString<MessageListResponse>(body)
        val msg = res.messages[0]
        assertTrue(msg.attachmentIds.isEmpty())
        assertEquals(listOf("01a0d29c"), attachmentRefIds(msg))
    }

    @Test
    fun `ref ids prefer explicit attachment_ids and dedupe`() {
        val msg = MessageResponse(
            id = "m4",
            roomId = "r1",
            senderId = "u1",
            kind = "image",
            body = emptyMap(),
            createdAt = "2026-09-24T08:51:53Z",
            eventSeq = 1,
            attachmentIds = listOf("a1", "a2"),
            attachments = listOf(
                AttachmentInfo(attachmentId = "a2"),
                AttachmentInfo(id = "a3")
            )
        )
        assertEquals(listOf("a1", "a2", "a3"), attachmentRefIds(msg))
    }
}
