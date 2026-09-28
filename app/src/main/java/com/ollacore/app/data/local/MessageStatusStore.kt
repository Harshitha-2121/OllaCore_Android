package com.ollacore.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.msgStatusPrefs by preferencesDataStore(name = "ollacore_msg_status")

/**
 * Persistent tick states (CLIENT-ONLY cache of server receipt facts).
 * Keyed by message id -> status rank (1 SENT, 2 DELIVERED, 3 READ).
 * Live WS receipts stay the source of truth; this only restores what the UI
 * knew before a restart, merged monotonically (never downgrades).
 */
@Serializable
data class StoredStatus(val id: String, val rank: Int)

class MessageStatusStore(private val context: Context) {

    companion object {
        private val KEY_STATUSES = stringPreferencesKey("message_status_json")
        private const val MAX_ENTRIES = 2000
        private val json = Json { ignoreUnknownKeys = true }
        private val listSer = ListSerializer(StoredStatus.serializer())

        /** Monotonic merge: keep the highest known rank per message id. */
        fun mergeRanks(
            current: Map<String, Int>,
            incoming: Map<String, Int>
        ): Map<String, Int> {
            if (incoming.isEmpty()) return current
            val merged = LinkedHashMap(current)
            incoming.forEach { (id, rank) ->
                val cur = merged[id]
                if (cur == null || rank > cur) merged[id] = rank
            }
            return merged
        }
    }

    val statuses: Flow<Map<String, Int>> = context.msgStatusPrefs.data.map { prefs ->
        val raw = prefs[KEY_STATUSES] ?: return@map emptyMap()
        runCatching {
            json.decodeFromString(listSer, raw).associate { it.id to it.rank }
        }.getOrElse { emptyMap() }
    }

    /** Record a rank; lower ranks never overwrite higher ones. */
    suspend fun upsert(id: String, rank: Int) {
        context.msgStatusPrefs.edit { prefs ->
            val current = readLocked(prefs[KEY_STATUSES])
            val merged = mergeRanks(current, mapOf(id to rank))
            // Cap: drop oldest-inserted entries (LinkedHashMap preserves order).
            val trimmed = if (merged.size > MAX_ENTRIES) {
                merged.entries.drop(merged.size - MAX_ENTRIES).associate { it.key to it.value }
            } else merged
            prefs[KEY_STATUSES] = json.encodeToString(
                listSer,
                trimmed.map { (key, value) -> StoredStatus(key, value) }
            )
        }
    }

    suspend fun snapshot(): Map<String, Int> = statuses.first()

    private fun readLocked(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString(listSer, raw).associate { it.id to it.rank }
        }.getOrElse { emptyMap() }
    }
}
