package com.ollacore.app.ui.privacy

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ollacore.app.OllacoreApp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PrivacySecurityUiState(
    val appLock: Boolean = false,
    val securityNotifications: Boolean = true,
    val deviceSecure: Boolean = false
)

/**
 * Spec 25: E2EE info (real, local), app lock (real device-credential gate),
 * security notifications (stored toggle). Last seen / photo / about /
 * receipts / blocks / disappearing need the backend privacy store, so those
 * rows are honestly marked instead of faked.
 */
class PrivacySecurityViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as OllacoreApp).container
    private val prefs = container.chatPrefsStore

    private val deviceSecure: Boolean = runCatching {
        val km = application.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        km.isDeviceSecure
    }.getOrElse { false }

    val uiState: StateFlow<PrivacySecurityUiState> = combine(
        prefs.appLock,
        prefs.notifSecurity
    ) { appLock, security ->
        PrivacySecurityUiState(appLock, security, deviceSecure)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PrivacySecurityUiState(deviceSecure = deviceSecure))

    fun setAppLock(enabled: Boolean) {
        // Without a device credential the gate cannot work - refuse honestly.
        if (enabled && !deviceSecure) return
        viewModelScope.launch { runCatching { prefs.setAppLock(enabled) } }
    }

    fun setSecurityNotifications(enabled: Boolean) {
        viewModelScope.launch { runCatching { prefs.setNotif("security", enabled) } }
    }
}
