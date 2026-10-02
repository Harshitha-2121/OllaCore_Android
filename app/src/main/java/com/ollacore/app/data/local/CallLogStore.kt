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
enum class CallStatus { COMPLETED, MISSED, CANCELLED, DECLINED, BUSY, FAILED }

/**
 * Why the call ended (persisted with each record; stable machine-readable
 * strings, never user content). Old records decode with "" (pre-existing data).
 */
object CallEndReason {
    const val COMPLETED = "completed"
    const val LOCAL_END = "local_end"
    const val REMOTE_END = "remote_end"
    const val DECLINED = "declined"
    const val REMOTE_DECLINED = "remote_declined"
    const val BUSY = "busy"
    const val NO_ANSWER = "no_answer"
    const val TIMEOUT = "timeout"
    const val ICE_FAILED = "ice_failed"
    const val SIGNALING_LOST = "signaling_lost"
    const val AUTH_FAILED = "auth_failed"
    const val JOIN_FAILED = "join_failed"
    const val MEDIA_ERROR = "media_error"
    const val ABORTED = "aborted"
}

@Serializable
data class CallLogEntry(
    val id: String,
    val roomId: String,
    val peerName: String = "",
    val direction: CallDirection = CallDirection.OUTGOING,
    val audioOnly: Boolean? = null,
    val startedAt: Long = 0L,
    val durationSec: Long = 0L,
    val status: CallStatus = CallStatus.COMPLETED,
    // Extended record (v2): defaults keep pre-existing JSON entries decoding.
    val callId: String = "",
    /** Local user id when known (self = caller for outgoing, self = callee for incoming). */
    val callerId: String = "",
    val calleeId: String = "",
    /** Epoch ms the media path came up (0 = never answered/connected). */
    val answeredAt: Long = 0L,
    /** Epoch ms the call finished (ended/failed/missed). */
    val endedAt: Long = 0L,
    /** Machine-readable end reason, see [CallEndReason]. */
    val endedReason: String = ""
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
