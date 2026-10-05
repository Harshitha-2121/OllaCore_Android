package com.ollacore.app.data.e2ee

import com.ollacore.app.data.model.EncryptedMessageKind
import com.ollacore.app.data.model.MessageResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*

class MlsMessageHandler {

    private val _state = MutableStateFlow(MlsState())
    val state: StateFlow<MlsState> = _state.asStateFlow()

    // Track pending proposals
    private val pendingProposals = mutableMapOf<String, MlsProposal>()

    // Track committed epochs
    private val committedEpochs = mutableMapOf<String, Int>()

    fun processMessage(message: MessageResponse): MlsMessageResult {
        val kind = EncryptedMessageKind.fromValue(message.kind)
            ?: return MlsMessageResult.Ignored

        val senderId = message.senderId
        val roomId = message.roomId
        val body = message.body

        return when (kind) {
            EncryptedMessageKind.MLS_COMMIT -> processCommit(roomId, senderId, body, message)
            EncryptedMessageKind.MLS_PROPOSAL -> processProposal(roomId, senderId, body, message)
            EncryptedMessageKind.MLS_APPLICATION -> processApplication(roomId, senderId, body, message)
            EncryptedMessageKind.MLS_WELCOME -> processWelcome(roomId, senderId, body, message)
        }
    }

    private fun processCommit(
        roomId: String,
        senderId: String,
        body: Map<String, JsonElement>,
        message: MessageResponse
    ): MlsMessageResult {
        val epoch = body["epoch"]?.jsonPrimitive?.intOrNull ?: 0
        val hash = body["hash"]?.jsonPrimitive?.content ?: ""

        // Update state
        _state.update { state ->
            state.copy(
                lastCommitEpoch = epoch,
                lastCommitHash = hash,
                lastCommitSenderId = senderId,
                needsRekey = true
            )
        }

        committedEpochs[roomId] = epoch

        // Process any pending proposals
        val processedProposals = pendingProposals.values.toList()
        pendingProposals.clear()

        return MlsMessageResult.CommitProcessed(
            epoch = epoch,
            hash = hash,
            processedProposals = processedProposals
        )
    }

    private fun processProposal(
        roomId: String,
        senderId: String,
        body: Map<String, JsonElement>,
        message: MessageResponse
    ): MlsMessageResult {
        val proposalId = message.id
        val proposalType = body["type"]?.jsonPrimitive?.content ?: "unknown"
        val target = body["target"]?.jsonPrimitive?.content

        val proposal = MlsProposal(
            id = proposalId,
            type = proposalType,
            senderId = senderId,
            target = target,
            timestamp = message.createdAt
        )

        pendingProposals[proposalId] = proposal

        _state.update { state ->
            state.copy(
                pendingProposalCount = pendingProposals.size,
                lastProposalType = proposalType
            )
        }

        return MlsMessageResult.ProposalReceived(
            proposal = proposal
        )
    }

    private fun processApplication(
        roomId: String,
        senderId: String,
        body: Map<String, JsonElement>,
        message: MessageResponse
    ): MlsMessageResult {
        val ciphertext = body["ciphertext"]?.jsonPrimitive?.content ?: ""
        val aad = body["aad"]?.jsonPrimitive?.content

        // Mark as needing decryption
        _state.update { state ->
            state.copy(
                pendingDecryptionCount = state.pendingDecryptionCount + 1,
                lastApplicationSenderId = senderId
            )
        }

        return MlsMessageResult.ApplicationReceived(
            ciphertext = ciphertext,
            aad = aad,
            senderId = senderId,
            message = message
        )
    }

    private fun processWelcome(
        roomId: String,
        senderId: String,
        body: Map<String, JsonElement>,
        message: MessageResponse
    ): MlsMessageResult {
        val groupInfo = body["group_info"]?.jsonPrimitive?.content ?: ""
        val keyPackageHash = body["key_package_hash"]?.jsonPrimitive?.content

        _state.update { state ->
            state.copy(
                hasPendingWelcome = true,
                welcomeSenderId = senderId,
                welcomeGroupId = body["group_id"]?.jsonPrimitive?.content
            )
        }

        return MlsMessageResult.WelcomeReceived(
            senderId = senderId,
            groupInfo = groupInfo,
            keyPackageHash = keyPackageHash
        )
    }

    fun markDecrypted(messageId: String) {
        _state.update { state ->
            state.copy(
                pendingDecryptionCount = maxOf(0, state.pendingDecryptionCount - 1)
            )
        }
    }

    fun markRekeyComplete() {
        _state.update { it.copy(needsRekey = false) }
    }

    fun markWelcomeProcessed() {
        _state.update { it.copy(hasPendingWelcome = false) }
    }

    fun shouldProcessAsEncrypted(kind: String): Boolean {
        return EncryptedMessageKind.isEncryptedKind(kind)
    }

    fun getEpochForRoom(roomId: String): Int {
        return committedEpochs[roomId] ?: 0
    }

    fun hasPendingProposals(): Boolean {
        return pendingProposals.isNotEmpty()
    }

    fun getPendingProposals(): List<MlsProposal> {
        return pendingProposals.values.toList()
    }

    fun reset() {
        _state.update { MlsState() }
        pendingProposals.clear()
        committedEpochs.clear()
    }
}

data class MlsState(
    val lastCommitEpoch: Int = 0,
    val lastCommitHash: String = "",
    val lastCommitSenderId: String = "",
    val needsRekey: Boolean = false,
    val pendingProposalCount: Int = 0,
    val lastProposalType: String = "",
    val pendingDecryptionCount: Int = 0,
    val lastApplicationSenderId: String = "",
    val hasPendingWelcome: Boolean = false,
    val welcomeSenderId: String = "",
    val welcomeGroupId: String? = null
)

data class MlsProposal(
    val id: String,
    val type: String,
    val senderId: String,
    val target: String?,
    val timestamp: String
)

sealed class MlsMessageResult {
    data object Ignored : MlsMessageResult()
    
    data class CommitProcessed(
        val epoch: Int,
        val hash: String,
        val processedProposals: List<MlsProposal>
    ) : MlsMessageResult()
    
    data class ProposalReceived(
        val proposal: MlsProposal
    ) : MlsMessageResult()
    
    data class ApplicationReceived(
        val ciphertext: String,
        val aad: String?,
        val senderId: String,
        val message: MessageResponse
    ) : MlsMessageResult()
    
    data class WelcomeReceived(
        val senderId: String,
        val groupInfo: String,
        val keyPackageHash: String?
    ) : MlsMessageResult()
}
