package com.ollacore.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ollacore.app.OllacoreApp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NotifPrefsUiState(
    val messages: Boolean = true,
    val calls: Boolean = true,
    val mentionsOnly: Boolean = false,
    val security: Boolean = true
)

/** Spec 28: per-category toggles (CLIENT-ONLY, enforced in the FCM service). */
class NotificationsSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as OllacoreApp).container
    private val prefs = container.chatPrefsStore

    val uiState: StateFlow<NotifPrefsUiState> = combine(
        prefs.notifMessages,
        prefs.notifCalls,
        prefs.notifMentionsOnly,
        prefs.notifSecurity
    ) { messages, calls, mentionsOnly, security ->
        NotifPrefsUiState(messages, calls, mentionsOnly, security)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NotifPrefsUiState())

    fun set(key: String, value: Boolean) {
        viewModelScope.launch { runCatching { prefs.setNotif(key, value) } }
    }
}
