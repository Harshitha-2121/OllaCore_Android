package com.ollacore.app.data.e2ee

import com.ollacore.app.data.model.EncryptedMessageKind
import com.ollacore.app.data.model.MessageResponse
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MlsMessageHandlerTest {

    private lateinit var handler: MlsMessageHandler

    @Before
    fun setup() {
        handler = MlsMessageHandler()
    }

    // Monotonic ids: two messages created in the same millisecond must NOT
    // collide, or the handler map keeps only one (flaked `multiple proposals`).
    private var nextTestId = 0

    private fun createMlsMessage(
        kind: String,
        senderId: String = "user-123",
        roomId: String = "room-456",
        body: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()
    ): MessageResponse {
        return MessageResponse(
            id = "msg-${System.currentTimeMillis()}-${nextTestId++}",
            roomId = roomId,
            senderId = senderId,
            kind = kind,
            body = body,
            createdAt = "2024-01-01T00:00:00Z",
            eventSeq = 1,
            clientMessageId = null,
            replyTo = null,
            editedAt = null
        )
    }

    // ════════════════════════════════════════════════════════════
    // MLS COMMIT
    // ════════════════════════════════════════════════════════════

    @Test
    fun `commit message is processed correctly`() {
        val message = createMlsMessage(
            kind = "mls.commit",
            body = mapOf(
                "epoch" to JsonPrimitive(42),
                "hash" to JsonPrimitive("abc123")
            )
        )

        val result = handler.processMessage(message)

        assertTrue(result is MlsMessageResult.CommitProcessed)
        val commit = result as MlsMessageResult.CommitProcessed
        assertEquals(42, commit.epoch)
        assertEquals("abc123", commit.hash)
        assertEquals("user-123", commit.processedProposals.firstOrNull()?.senderId ?: "user-123")
    }

    @Test
    fun `commit triggers rekey`() {
        val message = createMlsMessage(kind = "mls.commit")
        handler.processMessage(message)

        assertTrue(handler.state.value.needsRekey)
    }

    @Test
    fun `commit epoch is stored`() {
        val message = createMlsMessage(
            kind = "mls.commit",
            body = mapOf("epoch" to JsonPrimitive(100))
        )
        handler.processMessage(message)

        assertEquals(100, handler.getEpochForRoom("room-456"))
    }

    // ════════════════════════════════════════════════════════════
    // MLS PROPOSAL
    // ════════════════════════════════════════════════════════════

    @Test
    fun `proposal message is processed`() {
        val message = createMlsMessage(
            kind = "mls.proposal",
            body = mapOf(
                "type" to JsonPrimitive("add"),
                "target" to JsonPrimitive("new-user-id")
            )
        )

        val result = handler.processMessage(message)

        assertTrue(result is MlsMessageResult.ProposalReceived)
        val proposal = result as MlsMessageResult.ProposalReceived
        assertEquals("add", proposal.proposal.type)
        assertEquals("new-user-id", proposal.proposal.target)
    }

    @Test
    fun `proposal is added to pending`() {
        val message = createMlsMessage(
            kind = "mls.proposal",
            body = mapOf("type" to JsonPrimitive("remove"))
        )
        handler.processMessage(message)

        assertTrue(handler.hasPendingProposals())
        assertEquals(1, handler.getPendingProposals().size)
    }

    @Test
    fun `multiple proposals are tracked`() {
        val msg1 = createMlsMessage(
            kind = "mls.proposal",
            body = mapOf("type" to JsonPrimitive("add"))
        )
        val msg2 = createMlsMessage(
            kind = "mls.proposal",
            body = mapOf("type" to JsonPrimitive("remove"))
        )

        handler.processMessage(msg1)
        handler.processMessage(msg2)

        assertEquals(2, handler.getPendingProposals().size)
    }

    // ════════════════════════════════════════════════════════════
    // MLS APPLICATION
    // ════════════════════════════════════════════════════════════

    @Test
    fun `application message is processed`() {
        val message = createMlsMessage(
            kind = "mls.application",
            body = mapOf(
                "ciphertext" to JsonPrimitive("encrypted-data"),
                "aad" to JsonPrimitive("additional-data")
            )
        )

        val result = handler.processMessage(message)

        assertTrue(result is MlsMessageResult.ApplicationReceived)
        val app = result as MlsMessageResult.ApplicationReceived
        assertEquals("encrypted-data", app.ciphertext)
        assertEquals("additional-data", app.aad)
        assertEquals("user-123", app.senderId)
    }

    @Test
    fun `application increments decryption count`() {
        val message = createMlsMessage(kind = "mls.application")
        handler.processMessage(message)

        assertEquals(1, handler.state.value.pendingDecryptionCount)
    }

    @Test
    fun `markDecrypted decrements count`() {
        val message = createMlsMessage(kind = "mls.application")
        handler.processMessage(message)

        handler.markDecrypted(message.id)

        assertEquals(0, handler.state.value.pendingDecryptionCount)
    }

    // ════════════════════════════════════════════════════════════
    // MLS WELCOME
    // ════════════════════════════════════════════════════════════

    @Test
    fun `welcome message is processed`() {
        val message = createMlsMessage(
            kind = "mls.welcome",
            body = mapOf(
                "group_info" to JsonPrimitive("group-info-data"),
                "key_package_hash" to JsonPrimitive("hash-123"),
                "group_id" to JsonPrimitive("group-456")
            )
        )

        val result = handler.processMessage(message)

        assertTrue(result is MlsMessageResult.WelcomeReceived)
        val welcome = result as MlsMessageResult.WelcomeReceived
        assertEquals("user-123", welcome.senderId)
        assertEquals("group-info-data", welcome.groupInfo)
        assertEquals("hash-123", welcome.keyPackageHash)
    }

    @Test
    fun `welcome sets state`() {
        val message = createMlsMessage(
            kind = "mls.welcome",
            body = mapOf("group_id" to JsonPrimitive("group-789"))
        )
        handler.processMessage(message)

        assertTrue(handler.state.value.hasPendingWelcome)
        assertEquals("user-123", handler.state.value.welcomeSenderId)
        assertEquals("group-789", handler.state.value.welcomeGroupId)
    }

    @Test
    fun `markWelcomeProcessed clears state`() {
        val message = createMlsMessage(kind = "mls.welcome")
        handler.processMessage(message)

        handler.markWelcomeProcessed()

        assertFalse(handler.state.value.hasPendingWelcome)
    }

    // ════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ════════════════════════════════════════════════════════════

    @Test
    fun `shouldProcessAsEncrypted returns true for MLS kinds`() {
        assertTrue(handler.shouldProcessAsEncrypted("mls.commit"))
        assertTrue(handler.shouldProcessAsEncrypted("mls.proposal"))
        assertTrue(handler.shouldProcessAsEncrypted("mls.application"))
        assertTrue(handler.shouldProcessAsEncrypted("mls.welcome"))
    }

    @Test
    fun `shouldProcessAsEncrypted returns false for regular kinds`() {
        assertFalse(handler.shouldProcessAsEncrypted("text"))
        assertFalse(handler.shouldProcessAsEncrypted("image"))
        assertFalse(handler.shouldProcessAsEncrypted("file"))
    }

    @Test
    fun `reset clears all state`() {
        // Add some state
        val commit = createMlsMessage(kind = "mls.commit")
        handler.processMessage(commit)

        val proposal = createMlsMessage(kind = "mls.proposal")
        handler.processMessage(proposal)

        // Reset
        handler.reset()

        assertEquals(0, handler.state.value.lastCommitEpoch)
        assertFalse(handler.hasPendingProposals())
        assertEquals(0, handler.state.value.pendingDecryptionCount)
    }

    // ════════════════════════════════════════════════════════════
    // EDGE CASES
    // ════════════════════════════════════════════════════════════

    @Test
    fun `commit clears pending proposals`() {
        // Add proposals
        val proposal1 = createMlsMessage(
            kind = "mls.proposal",
            body = mapOf("type" to JsonPrimitive("add"))
        )
        handler.processMessage(proposal1)

        // Process commit
        val commit = createMlsMessage(kind = "mls.commit")
        val result = handler.processMessage(commit)

        // Proposals should be cleared
        assertFalse(handler.hasPendingProposals())
        val commitResult = result as MlsMessageResult.CommitProcessed
        assertEquals(1, commitResult.processedProposals.size)
    }

    @Test
    fun `non-encrypted kind returns Ignored`() {
        val message = createMlsMessage(kind = "text")
        val result = handler.processMessage(message)

        assertTrue(result is MlsMessageResult.Ignored)
    }

    @Test
    fun `commit with missing epoch defaults to 0`() {
        val message = createMlsMessage(kind = "mls.commit")
        val result = handler.processMessage(message)

        val commit = result as MlsMessageResult.CommitProcessed
        assertEquals(0, commit.epoch)
    }

    @Test
    fun `application with missing ciphertext defaults to empty`() {
        val message = createMlsMessage(kind = "mls.application")
        val result = handler.processMessage(message)

        val app = result as MlsMessageResult.ApplicationReceived
        assertEquals("", app.ciphertext)
        assertNull(app.aad)
    }
}
