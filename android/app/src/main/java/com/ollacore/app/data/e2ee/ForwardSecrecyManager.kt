package com.ollacore.app.data.e2ee

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.security.SecureRandom

class ForwardSecrecyManager {

    private val _sessionState = MutableStateFlow(ForwardSecrecyState())
    val sessionState: StateFlow<ForwardSecrecyState> = _sessionState.asStateFlow()

    // Track which members have access to which epoch
    private val epochMembers = mutableMapOf<Int, MutableSet<String>>()

    // Track key material per epoch (in real impl, this would be crypto keys)
    private val epochKeys = mutableMapOf<Int, EpochKey>()

    // Track removed members and when they were removed
    private val removedMemberHistory = mutableMapOf<String, RemovalRecord>()

    // Current epoch
    private var currentEpoch = 0

    // Secure random for key generation
    private val secureRandom = SecureRandom()

    init {
        // Initialize epoch 0 with empty members
        epochMembers[0] = mutableSetOf()
        epochKeys[0] = EpochKey(
            epoch = 0,
            keyId = generateKeyId(),
            createdAt = System.currentTimeMillis()
        )
    }

    /**
     * Add a member to the current epoch.
     * This grants them access to current and future messages until removed.
     */
    fun onMemberAdded(principalId: String) {
        // Re-admission clears the revocation: without this, a re-added member
        // stays in revokedMembers and is excluded from every future epoch.
        removedMemberHistory.remove(principalId)
        _sessionState.update { state ->
            val newMembers = state.currentMembers + principalId
            
            // Add to current epoch
            epochMembers[currentEpoch]?.add(principalId)
            
            state.copy(
                currentMembers = newMembers,
                revokedMembers = state.revokedMembers - principalId,
                pendingRotation = true,
                lastAction = SessionAction.MEMBER_ADDED,
                lastActionTarget = principalId
            )
        }
    }

    /**
     * Remove a member from the group.
     * CRITICAL: This triggers session rotation to ensure forward secrecy.
     * The removed member will NOT be able to decrypt messages in the new epoch.
     */
    fun onMemberRemoved(principalId: String, reason: RemovalReason = RemovalReason.REMOVED) {
        val removalRecord = RemovalRecord(
            memberId = principalId,
            removedAtEpoch = currentEpoch,
            removedAt = System.currentTimeMillis(),
            reason = reason
        )

        removedMemberHistory[principalId] = removalRecord

        _sessionState.update { state ->
            val newMembers = state.currentMembers - principalId
            val newRevoked = state.revokedMembers + principalId
            
            state.copy(
                currentMembers = newMembers,
                revokedMembers = newRevoked,
                pendingRotation = true,
                lastAction = SessionAction.MEMBER_REMOVED,
                lastActionTarget = principalId,
                removalReason = reason
            )
        }
    }

    /**
     * Rotate the encryption session.
     * Creates a new epoch with new keys, excluding revoked members.
     * This is the core of forward secrecy.
     */
    fun rotateSession(): RotationResult {
        val newEpoch = currentEpoch + 1
        val newKeyId = generateKeyId()

        // Get current active members (excluding revoked)
        val activeMembers = _sessionState.value.currentMembers
        val revokedMembers = _sessionState.value.revokedMembers

        // Create new epoch with only active members
        val newEpochMembers = mutableSetOf<String>()
        activeMembers.forEach { memberId ->
            if (memberId !in revokedMembers) {
                newEpochMembers.add(memberId)
            }
        }

        // Store new epoch
        epochMembers[newEpoch] = newEpochMembers
        epochKeys[newEpoch] = EpochKey(
            epoch = newEpoch,
            keyId = newKeyId,
            createdAt = System.currentTimeMillis()
        )

        // Mark old epoch keys as potentially compromised
        epochKeys[currentEpoch]?.let { oldKey ->
            epochKeys[currentEpoch] = oldKey.copy(
                revokedAt = System.currentTimeMillis(),
                revokedReason = "Session rotated"
            )
        }

        currentEpoch = newEpoch

        _sessionState.update { state ->
            state.copy(
                currentEpoch = newEpoch,
                currentKeyId = newKeyId,
                pendingRotation = false,
                lastRotationAt = System.currentTimeMillis(),
                rotationCount = state.rotationCount + 1,
                lastAction = SessionAction.SESSION_ROTATED,
                epochMembers = newEpochMembers.toSet()
            )
        }

        return RotationResult(
            success = true,
            newEpoch = newEpoch,
            newKeyId = newKeyId,
            activeMembers = newEpochMembers.toSet(),
            revokedMembers = revokedMembers
        )
    }

    /**
     * Check if a member can decrypt messages at a given epoch.
     * Returns true if the member was in the group at that epoch.
     */
    fun canDecrypt(memberId: String, epoch: Int): Boolean {
        // If member was never in the group, they can't decrypt
        val memberEpochs = epochMembers.filter { it.value.contains(memberId) }
        if (memberEpochs.isEmpty()) return false

        // Check if they were in the group at the specified epoch
        return epochMembers[epoch]?.contains(memberId) == true
    }

    /**
     * Check if a member is currently active (not revoked).
     */
    fun isMemberActive(memberId: String): Boolean {
        return memberId in _sessionState.value.currentMembers &&
               memberId !in _sessionState.value.revokedMembers
    }

    /**
     * Check if a member was revoked and when.
     */
    fun getRemovalRecord(memberId: String): RemovalRecord? {
        return removedMemberHistory[memberId]
    }

    /**
     * Get all members who were revoked before a given epoch.
     * These members cannot decrypt messages from that epoch onwards.
     */
    fun getRevokedBeforeEpoch(epoch: Int): Set<String> {
        return removedMemberHistory
            .filter { it.value.removedAtEpoch < epoch }
            .keys
    }

    /**
     * Get the key ID for a given epoch.
     */
    fun getKeyForEpoch(epoch: Int): EpochKey? {
        return epochKeys[epoch]
    }

    /**
     * Get current epoch number.
     */
    fun getCurrentEpoch(): Int = currentEpoch

    /**
     * Get current key ID.
     */
    fun getCurrentKeyId(): String = _sessionState.value.currentKeyId

    /**
     * Get all active members.
     */
    fun getActiveMembers(): Set<String> = _sessionState.value.currentMembers

    /**
     * Get all revoked members.
     */
    fun getRevokedMembers(): Set<String> = _sessionState.value.revokedMembers

    /**
     * Check if session needs rotation.
     */
    fun needsRotation(): Boolean = _sessionState.value.pendingRotation

    /**
     * Get rotation count.
     */
    fun getRotationCount(): Int = _sessionState.value.rotationCount

    /**
     * Reset all state (for testing or logout).
     */
    fun reset() {
        _sessionState.update { ForwardSecrecyState() }
        epochMembers.clear()
        epochKeys.clear()
        removedMemberHistory.clear()
        currentEpoch = 0
    }

    private fun generateKeyId(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

data class ForwardSecrecyState(
    val currentEpoch: Int = 0,
    val currentKeyId: String = "",
    val currentMembers: Set<String> = emptySet(),
    val revokedMembers: Set<String> = emptySet(),
    val epochMembers: Set<String> = emptySet(),
    val pendingRotation: Boolean = false,
    val lastRotationAt: Long = 0,
    val rotationCount: Int = 0,
    val lastAction: SessionAction? = null,
    val lastActionTarget: String? = null,
    val removalReason: RemovalReason? = null
)

data class EpochKey(
    val epoch: Int,
    val keyId: String,
    val createdAt: Long,
    val revokedAt: Long? = null,
    val revokedReason: String? = null
)

data class RemovalRecord(
    val memberId: String,
    val removedAtEpoch: Int,
    val removedAt: Long,
    val reason: RemovalReason
)

data class RotationResult(
    val success: Boolean,
    val newEpoch: Int,
    val newKeyId: String,
    val activeMembers: Set<String>,
    val revokedMembers: Set<String>
)

enum class SessionAction {
    MEMBER_ADDED,
    MEMBER_REMOVED,
    SESSION_ROTATED
}

enum class RemovalReason {
    REMOVED,
    LEFT,
    BANNED,
    DEVICE_REVOKED
}
