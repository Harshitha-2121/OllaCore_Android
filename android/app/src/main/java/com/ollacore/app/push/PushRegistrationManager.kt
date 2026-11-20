package com.ollacore.app.push

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.ollacore.app.data.local.SessionStore
import com.ollacore.app.data.repository.DirectoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Registers this handset's FCM token with the Ollacore backend (BUG-11).
 *
 * IMPORTANT — this is the *client half* of push only. Push cannot work until
 * the project ships external configuration that is deliberately NOT vendored:
 *  1. `google-services.json` for the Firebase project, covering BOTH package
 *     names `com.ollacore.app` and `com.ollacore.app.debug` (debug suffix);
 *  2. the `com.google.gms.google-services` Gradle plugin applied in
 *     `app/build.gradle.kts` (kept commented out until the file exists,
 *     otherwise every build without it fails);
 *  3. a backend sender that POSTs FCM data messages shaped like
 *     `{type, room_id, call_id, sender, preview, encrypted, sent_at}` to the
 *     tokens this class registers (`POST /v1/directory/devices`).
 *
 * Until (1) exists, `FirebaseMessaging.getInstance()` throws. Every call here
 * is therefore failure-tolerant: it degrades to a no-op and the Linked Devices
 * screen keeps showing the honest "Push is not configured in this build"
 * notice. Nothing here fabricates a token or claims delivery works.
 *
 * The token itself is not sensitive (it can only be used to deliver
 * notifications to this install) and is kept in plain app-private prefs.
 * Backups are disabled app-wide, so it is not exported.
 */
class PushRegistrationManager(
    context: Context,
    private val directoryRepo: DirectoryRepository,
    private val sessionStore: SessionStore
) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True when Firebase is initialized, i.e. google-services.json shipped. */
    fun isFirebaseConfigured(): Boolean =
        runCatching { FirebaseMessaging.getInstance() }.isSuccess

    /**
     * Fetch the current FCM token, or null when push is not configured /
     * Play services are unavailable. Never throws.
     */
    suspend fun currentToken(): String? = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val messaging = runCatching { FirebaseMessaging.getInstance() }.getOrNull()
            if (messaging == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            messaging.token.addOnCompleteListener { task ->
                cont.resume(if (task.isSuccessful) task.result else null)
            }
        }
    }

    /**
     * Register (or refresh) this device after login / on app start.
     * Skips the network call when the same token is already registered for the
     * same account. Returns Unit on "nothing to do" as well as success.
     */
    suspend fun registerIfNeeded(force: Boolean = false) {
        val session = sessionStore.sessionToken.first() ?: return
        val userId = sessionStore.userId.first().orEmpty()
        val token = currentToken() ?: return // push not configured: honest no-op
        if (!force &&
            prefs.getString(KEY_REGISTERED_TOKEN, null) == token &&
            prefs.getString(KEY_REGISTERED_USER, "") == userId
        ) {
            return
        }
        directoryRepo.registerDevice(session, PLATFORM, token)
            .onSuccess {
                prefs.edit()
                    .putString(KEY_REGISTERED_TOKEN, token)
                    .putString(KEY_REGISTERED_USER, userId)
                    .putString(KEY_LAST_TOKEN, token)
                    .apply()
            }
            .onFailure { e ->
                // Backend may not have the endpoint yet; do not break login.
                Log.w(TAG, "Push device registration failed: ${e.javaClass.simpleName}")
            }
    }

    /**
     * FCM rotated the token (FirebaseMessagingService.onNewToken). Persist it
     * even when logged out so the next login registers the right token.
     */
    suspend fun onNewToken(token: String) {
        prefs.edit().putString(KEY_LAST_TOKEN, token).apply()
        val session = sessionStore.sessionToken.first() ?: return
        val userId = sessionStore.userId.first().orEmpty()
        directoryRepo.registerDevice(session, PLATFORM, token)
            .onSuccess {
                prefs.edit()
                    .putString(KEY_REGISTERED_TOKEN, token)
                    .putString(KEY_REGISTERED_USER, userId)
                    .apply()
            }
            .onFailure { e ->
                Log.w(TAG, "Push token refresh registration failed: ${e.javaClass.simpleName}")
            }
    }

    /**
     * Best-effort cleanup on logout: DELETE the push registration so this
     * handset stops receiving notifications for the signed-out account.
     * Must be called BEFORE the session is cleared.
     */
    suspend fun unregister() {
        val token = prefs.getString(KEY_REGISTERED_TOKEN, null)
            ?: prefs.getString(KEY_LAST_TOKEN, null)
        val session = sessionStore.sessionToken.first()
        if (!session.isNullOrBlank() && !token.isNullOrBlank()) {
            runCatching { directoryRepo.deleteDevice(session, token) }
                .onFailure { e -> Log.w(TAG, "Push device unregister failed: ${e.javaClass.simpleName}") }
        }
        prefs.edit()
            .remove(KEY_REGISTERED_TOKEN)
            .remove(KEY_REGISTERED_USER)
            .apply()
    }

    companion object {
        private const val TAG = "PushRegistration"
        private const val PREFS_NAME = "push_registration"
        private const val KEY_REGISTERED_TOKEN = "registered_token"
        private const val KEY_REGISTERED_USER = "registered_user"
        private const val KEY_LAST_TOKEN = "last_token"
        private const val PLATFORM = "android"
    }
}
