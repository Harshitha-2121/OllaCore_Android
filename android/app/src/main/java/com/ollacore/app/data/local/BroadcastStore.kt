package com.ollacore.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.broadcastStore by preferencesDataStore(name = "ollacore_broadcasts")

/** One-to-many broadcast list (members only; sending needs the backend service). */
@Serializable
data class BroadcastList(
    val id: String,
    val name: String,
    val memberIds: List<String>
)

/**
 * Local broadcast-list store (CLIENT-ONLY until the broadcast backend
 * lands). Lists + membership persist and are fully manageable; SENDING
 * stays disabled with an honest note - never faked.
 */
class BroadcastStore(private val context: Context) {
    companion object {
        private val KEY_LISTS = stringPreferencesKey("broadcast_lists")
        private val json = Json { ignoreUnknownKeys = true }
    }

    val lists: Flow<List<BroadcastList>> = context.broadcastStore.data.map { prefs ->
        runCatching {
            prefs[KEY_LISTS]?.let { json.decodeFromString(ListSerializer(BroadcastList.serializer()), it) }
        }.getOrNull() ?: emptyList()
    }

    suspend fun createList(name: String, memberIds: List<String>): BroadcastList? {
        val clean = name.trim()
        if (clean.isEmpty() || memberIds.isEmpty()) return null
        val entry = BroadcastList(UUID.randomUUID().toString(), clean, memberIds.distinct())
        context.broadcastStore.edit { prefs ->
            val current = runCatching {
                prefs[KEY_LISTS]?.let { json.decodeFromString(ListSerializer(BroadcastList.serializer()), it) }
            }.getOrNull() ?: emptyList()
            prefs[KEY_LISTS] = json.encodeToString(ListSerializer(BroadcastList.serializer()), current + entry)
        }
        return entry
    }

    suspend fun deleteList(id: String) {
        context.broadcastStore.edit { prefs ->
            val current = runCatching {
                prefs[KEY_LISTS]?.let { json.decodeFromString(ListSerializer(BroadcastList.serializer()), it) }
            }.getOrNull() ?: emptyList()
            prefs[KEY_LISTS] = json.encodeToString(
                ListSerializer(BroadcastList.serializer()),
                current.filterNot { it.id == id }
            )
        }
    }
}
