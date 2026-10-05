package com.ollacore.app.data.local

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MessageStatusStoreTest {

    @Test
    fun `merge keeps highest rank per message`() {
        val merged = MessageStatusStore.mergeRanks(
            current = mapOf("a" to 1, "b" to 3),
            incoming = mapOf("a" to 2, "b" to 1, "c" to 1)
        )
        assertEquals(mapOf("a" to 2, "b" to 3, "c" to 1), merged)
    }

    @Test
    fun `merge with empty incoming returns current`() {
        val current = mapOf("a" to 2)
        assertEquals(current, MessageStatusStore.mergeRanks(current, emptyMap()))
    }

    @Test
    fun `restarted client upgrades sent to delivered to read without downgrade`() {
        var state = emptyMap<String, Int>()
        state = MessageStatusStore.mergeRanks(state, mapOf("m1" to 1))
        assertEquals(1, state["m1"])
        state = MessageStatusStore.mergeRanks(state, mapOf("m1" to 2))
        assertEquals(2, state["m1"])
        // Stale/duplicate delivered receipt never knocks read back down.
        state = MessageStatusStore.mergeRanks(state, mapOf("m1" to 3))
        state = MessageStatusStore.mergeRanks(state, mapOf("m1" to 2))
        assertEquals(3, state["m1"])
    }

    @Test
    fun `stored statuses survive json round-trip`() {
        val json = Json { ignoreUnknownKeys = true }
        val ser = ListSerializer(StoredStatus.serializer())
        val original = listOf(StoredStatus("m1", 3), StoredStatus("m2", 1))
        val restored: List<StoredStatus> =
            json.decodeFromString(ser, json.encodeToString(ser, original))
        assertEquals(original, restored)
    }
}
