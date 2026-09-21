package com.ollacore.app.data.e2ee

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ForwardSecrecyManagerTest {

    private lateinit var manager: ForwardSecrecyManager

    @Before
    fun setup() {
        manager = ForwardSecrecyManager()
    }

    // ════════════════════════════════════════════════════════════
    // BASIC MEMBER MANAGEMENT
    // ════════════════════════════════════════════════════════════

    @Test
    fun `initial state has epoch 0`() {
        assertEquals(0, manager.getCurrentEpoch())
        assertTrue(manager.getActiveMembers().isEmpty())
    }

    @Test
    fun `adding member updates state`() {
        manager.onMemberAdded("user-1")

        assertTrue(manager.isMemberActive("user-1"))
        assertEquals(1, manager.getActiveMembers().size)
        assertTrue(manager.needsRotation())
    }

    @Test
    fun `adding multiple members`() {
        manager.onMemberAdded("user-1")
        manager.onMemberAdded("user-2")
        manager.onMemberAdded("user-3")

        assertEquals(3, manager.getActiveMembers().size)
        assertTrue(manager.isMemberActive("user-1"))
        assertTrue(manager.isMemberActive("user-2"))
        assertTrue(manager.isMemberActive("user-3"))
    }

    @Test
    fun `removing member marks as revoked`() {
        manager.onMemberAdded("user-1")
        manager.onMemberAdded("user-2")

        manager.onMemberRemoved("user-1")

        assertFalse(manager.isMemberActive("user-1"))
        assertTrue(manager.isMemberActive("user-2"))
        assertTrue(manager.getRevokedMembers().contains("user-1"))
        assertTrue(manager.needsRotation())
    }

    @Test
    fun `removal record is stored`() {
        manager.onMemberAdded("user-1")
        manager.onMemberRemoved("user-1", RemovalReason.LEFT)

        val record = manager.getRemovalRecord("user-1")
        assertNotNull(record)
        assertEquals("user-1", record?.memberId)
        assertEquals(RemovalReason.LEFT, record?.reason)
        assertEquals(0, record?.removedAtEpoch)
    }

    // ════════════════════════════════════════════════════════════
    // SESSION ROTATION
    // ════════════════════════════════════════════════════════════

    @Test
    fun `rotation increments epoch`() {
        manager.onMemberAdded("user-1")
        val result = manager.rotateSession()

        assertEquals(1, manager.getCurrentEpoch())
        assertTrue(result.success)
        assertEquals(1, result.newEpoch)
    }

    @Test
    fun `rotation generates new key`() {
        manager.onMemberAdded("user-1")
        val result = manager.rotateSession()

        assertTrue(result.newKeyId.isNotEmpty())
        assertNotNull(manager.getKeyForEpoch(1))
    }

    @Test
    fun `rotation preserves active members`() {
        manager.onMemberAdded("user-1")
        manager.onMemberAdded("user-2")

        val result = manager.rotateSession()

        assertTrue(result.activeMembers.contains("user-1"))
        assertTrue(result.activeMembers.contains("user-2"))
    }

    @Test
    fun `rotation excludes revoked members`() {
        manager.onMemberAdded("user-1")
        manager.onMemberAdded("user-2")
        manager.onMemberRemoved("user-1")

        val result = manager.rotateSession()

        assertFalse(result.activeMembers.contains("user-1"))
        assertTrue(result.activeMembers.contains("user-2"))
        assertTrue(result.revokedMembers.contains("user-1"))
    }

    @Test
    fun `rotation count increases`() {
        manager.onMemberAdded("user-1")
        manager.rotateSession()
        manager.rotateSession()

        assertEquals(2, manager.getRotationCount())
    }

    @Test
    fun `rotation clears pending flag`() {
        manager.onMemberAdded("user-1")
        assertTrue(manager.needsRotation())

        manager.rotateSession()
        assertFalse(manager.needsRotation())
    }

    // ════════════════════════════════════════════════════════════
    // FORWARD SECRECY - CRITICAL SECURITY TESTS
    // ════════════════════════════════════════════════════════════

    @Test
    fun `removed member cannot decrypt future messages`() {
        // Setup: user-1 and user-2 in group
        manager.onMemberAdded("user-1")
        manager.onMemberAdded("user-2")

        // Both can decrypt epoch 0
        assertTrue(manager.canDecrypt("user-1", 0))
        assertTrue(manager.canDecrypt("user-2", 0))

        // Remove user-2
        manager.onMemberRemoved("user-2")

        // Rotate session
        manager.rotateSession()

        // user-1 can still decrypt epoch 0 and 1
        assertTrue(manager.canDecrypt("user-1", 0))
        assertTrue(manager.canDecrypt("user-1", 1))

        // user-2 can decrypt epoch 0 but NOT epoch 1
        assertTrue(manager.canDecrypt("user-2", 0))
        assertFalse(manager.canDecrypt("user-2", 1))
    }

    @Test
    fun `multiple rotations maintain forward secrecy`() {
        // epoch 0: user-1, user-2, user-3
        manager.onMemberAdded("user-1")
        manager.onMemberAdded("user-2")
        manager.onMemberAdded("user-3")

        // Remove user-3
        manager.onMemberRemoved("user-3")
        manager.rotateSession() // epoch 1

        // Remove user-2
        manager.onMemberRemoved("user-2")
        manager.rotateSession() // epoch 2

        // user-1 can decrypt all epochs
        assertTrue(manager.canDecrypt("user-1", 0))
        assertTrue(manager.canDecrypt("user-1", 1))
        assertTrue(manager.canDecrypt("user-1", 2))

        // user-2 can decrypt epoch 0 and 1, but NOT 2
        assertTrue(manager.canDecrypt("user-2", 0))
        assertTrue(manager.canDecrypt("user-2", 1))
        assertFalse(manager.canDecrypt("user-2", 2))

        // user-3 can only decrypt epoch 0
        assertTrue(manager.canDecrypt("user-3", 0))
        assertFalse(manager.canDecrypt("user-3", 1))
        assertFalse(manager.canDecrypt("user-3", 2))
    }

    @Test
    fun `re-added member gets new epoch access`() {
        manager.onMemberAdded("user-1")
        manager.onMemberAdded("user-2")

        // Remove user-2
        manager.onMemberRemoved("user-2")
        manager.rotateSession() // epoch 1

        // user-2 cannot decrypt epoch 1
        assertFalse(manager.canDecrypt("user-2", 1))

        // Re-add user-2
        manager.onMemberAdded("user-2")
        manager.rotateSession() // epoch 2

        // user-2 can now decrypt epoch 2
        assertTrue(manager.canDecrypt("user-2", 2))
    }

    @Test
    fun `removed member can still decrypt old messages`() {
        manager.onMemberAdded("user-1")
        manager.onMemberAdded("user-2")

        // user-2 can decrypt epoch 0
        assertTrue(manager.canDecrypt("user-2", 0))

        // Remove user-2
        manager.onMemberRemoved("user-2")
        manager.rotateSession()

        // user-2 can STILL decrypt epoch 0 (old message)
        assertTrue(manager.canDecrypt("user-2", 0))
    }

    // ════════════════════════════════════════════════════════════
    // REMOVAL REASONS
    // ════════════════════════════════════════════════════════════

    @Test
    fun `removed reason is stored`() {
        manager.onMemberAdded("user-1")
        manager.onMemberRemoved("user-1", RemovalReason.REMOVED)

        val record = manager.getRemovalRecord("user-1")
        assertEquals(RemovalReason.REMOVED, record?.reason)
    }

    @Test
    fun `left reason is stored`() {
        manager.onMemberAdded("user-1")
        manager.onMemberRemoved("user-1", RemovalReason.LEFT)

        val record = manager.getRemovalRecord("user-1")
        assertEquals(RemovalReason.LEFT, record?.reason)
    }

    @Test
    fun `banned reason is stored`() {
        manager.onMemberAdded("user-1")
        manager.onMemberRemoved("user-1", RemovalReason.BANNED)

        val record = manager.getRemovalRecord("user-1")
        assertEquals(RemovalReason.BANNED, record?.reason)
    }

    @Test
    fun `device revoked reason is stored`() {
        manager.onMemberAdded("user-1")
        manager.onMemberRemoved("user-1", RemovalReason.DEVICE_REVOKED)

        val record = manager.getRemovalRecord("user-1")
        assertEquals(RemovalReason.DEVICE_REVOKED, record?.reason)
    }

    // ════════════════════════════════════════════════════════════
    // EPOCH KEY MANAGEMENT
    // ════════════════════════════════════════════════════════════

    @Test
    fun `each epoch has unique key`() {
        manager.onMemberAdded("user-1")

        val key0 = manager.getKeyForEpoch(0)
        manager.rotateSession()
        val key1 = manager.getKeyForEpoch(1)
        manager.rotateSession()
        val key2 = manager.getKeyForEpoch(2)

        assertNotNull(key0)
        assertNotNull(key1)
        assertNotNull(key2)
        assertNotEquals(key0?.keyId, key1?.keyId)
        assertNotEquals(key1?.keyId, key2?.keyId)
    }

    @Test
    fun `old epoch keys are marked revoked`() {
        manager.onMemberAdded("user-1")
        manager.rotateSession()

        val oldKey = manager.getKeyForEpoch(0)
        assertNotNull(oldKey?.revokedAt)
        assertNotNull(oldKey?.revokedReason)
    }

    // ════════════════════════════════════════════════════════════
    // EDGE CASES
    // ════════════════════════════════════════════════════════════

    @Test
    fun `removing non-existent member does not crash`() {
        manager.onMemberRemoved("nonexistent")
        // Should not throw
    }

    @Test
    fun `adding already active member does not duplicate`() {
        manager.onMemberAdded("user-1")
        manager.onMemberAdded("user-1")

        assertEquals(1, manager.getActiveMembers().size)
    }

    @Test
    fun `reset clears all state`() {
        manager.onMemberAdded("user-1")
        manager.onMemberRemoved("user-1")
        manager.rotateSession()

        manager.reset()

        assertEquals(0, manager.getCurrentEpoch())
        assertTrue(manager.getActiveMembers().isEmpty())
        assertTrue(manager.getRevokedMembers().isEmpty())
        assertEquals(0, manager.getRotationCount())
    }

    @Test
    fun `cannot decrypt epoch before member was added`() {
        manager.rotateSession() // epoch 1
        manager.onMemberAdded("user-1") // added at epoch 1
        manager.rotateSession() // epoch 2

        // user-1 cannot decrypt epoch 0 (before they joined)
        assertFalse(manager.canDecrypt("user-1", 0))
        // user-1 can decrypt epoch 1 and 2
        assertTrue(manager.canDecrypt("user-1", 1))
        assertTrue(manager.canDecrypt("user-1", 2))
    }

    // ════════════════════════════════════════════════════════════
    // REVOKED BEFORE EPOCH QUERY
    // ════════════════════════════════════════════════════════════

    @Test
    fun `getRevokedBeforeEpoch returns correct members`() {
        manager.onMemberAdded("user-1")
        manager.onMemberAdded("user-2")
        manager.onMemberAdded("user-3")

        manager.onMemberRemoved("user-1") // revoked at epoch 0
        manager.rotateSession() // epoch 1

        manager.onMemberRemoved("user-2") // revoked at epoch 1
        manager.rotateSession() // epoch 2

        val revokedBefore2 = manager.getRevokedBeforeEpoch(2)
        assertTrue(revokedBefore2.contains("user-1"))
        assertTrue(revokedBefore2.contains("user-2"))
        assertFalse(revokedBefore2.contains("user-3"))
    }
}
