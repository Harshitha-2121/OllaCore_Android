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

private val Context.localStore by preferencesDataStore(name = "ollacore_local")

/**
 * CLIENT-ONLY call history (no Ollacore endpoint exists).
 * DataStore-backed JSON ring (cap 100) - no Room/KSP dependency needed.
 * Chat-management stores (archive/mute/pin) will follow this same pattern.
 */
@Serializable
enum class CallDirection { OUTGOING, INCOMING }

@Serializable
enum class CallStatus { COMPLETED, MISSED, CANCELLED, DECLINED, FAILED }

@Serializable
data class CallLogEntry(
    val id: String,
    val roomId: String,
    val peerName: String = "",
    val direction: CallDirection = CallDirection.OUTGOING,
    val audioOnly: Boolean? = null,
    val startedAt: Long = 0L,
    val durationSec: Long = 0L,
    val status: CallStatus = CallStatus.COMPLETED
)

class CallLogStore(private val context: Context) {

    companion object {
        private val KEY_CALL_LOG = stringPreferencesKey("call_log_json")
        private const val MAX_ENTRIES = 100
        private val json = Json { ignoreUnknownKeys = true }
        private val listSer = ListSerializer(CallLogEntry.serializer())
    }

    val callLog: Flow<List<CallLogEntry>> = context.localStore.data.map { prefs ->
        val raw = prefs[KEY_CALL_LOG] ?: return@map emptyList()
        runCatching {
            json.decodeFromString(listSer, raw).sortedByDescending { it.startedAt }
        }.getOrElse { emptyList() }
    }

    suspend fun log(entry: CallLogEntry) {
        context.localStore.edit { prefs ->
            val current = runCatching {
                prefs[KEY_CALL_LOG]?.let { json.decodeFromString(listSer, it) } ?: emptyList()
            }.getOrElse { emptyList() }
            val updated = (listOf(entry) + current).take(MAX_ENTRIES)
            prefs[KEY_CALL_LOG] = json.encodeToString(listSer, updated)
        }
    }

    suspend fun delete(id: String) {
        context.localStore.edit { prefs ->
            val current = runCatching {
                prefs[KEY_CALL_LOG]?.let { json.decodeFromString(listSer, it) } ?: emptyList()
            }.getOrElse { emptyList() }
            prefs[KEY_CALL_LOG] = json.encodeToString(listSer, current.filter { it.id != id })
        }
    }

    suspend fun clear() {
        context.localStore.edit { prefs ->
            prefs.remove(KEY_CALL_LOG)
        }
    }
}
