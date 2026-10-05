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
import java.util.UUID

private val Context.groupEventsPrefs by preferencesDataStore(name = "ollacore_group_events")

/**
 * Group membership journal (CLIENT-ONLY until a server audit log exists).
 * Records membership actions observed on THIS device (our adds/removes/admin
 * changes, with actor + timestamp) per room, newest first, capped. Remote
 * members' actions appear once the backend fans out membership events.
 */
@Serializable
data class GroupEvent(
    val id: String,
    val roomId: String,
    val actor: String,
    val action: String,
    val detail: String? = null,
    val at: Long = 0L
)

class GroupEventStore(private val context: Context) {

    companion object {
        private val KEY_EVENTS = stringPreferencesKey("group_events_json")
        private const val MAX_ENTRIES = 100
        private val json = Json { ignoreUnknownKeys = true }
        private val listSer = ListSerializer(GroupEvent.serializer())
    }

    fun events(roomId: String): Flow<List<GroupEvent>> =
        context.groupEventsPrefs.data.map { prefs ->
            val raw = prefs[KEY_EVENTS] ?: return@map emptyList()
            runCatching {
                json.decodeFromString(listSer, raw).filter { it.roomId == roomId }
            }.getOrElse { emptyList() }
        }

    suspend fun log(roomId: String, actor: String, action: String, detail: String? = null) {
        context.groupEventsPrefs.edit { prefs ->
            val current = runCatching {
                prefs[KEY_EVENTS]?.let { json.decodeFromString(listSer, it) } ?: emptyList()
            }.getOrElse { emptyList() }
            val entry = GroupEvent(
                id = UUID.randomUUID().toString(),
                roomId = roomId,
                actor = actor.ifBlank { "You" },
                action = action,
                detail = detail,
                at = System.currentTimeMillis()
            )
            val updated = (listOf(entry) + current)
                .sortedByDescending { it.at }
                .take(MAX_ENTRIES)
            prefs[KEY_EVENTS] = json.encodeToString(listSer, updated)
        }
    }

    suspend fun snapshot(roomId: String): List<GroupEvent> = events(roomId).first()
}
