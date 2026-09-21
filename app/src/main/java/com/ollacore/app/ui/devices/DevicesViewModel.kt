package com.ollacore.app.ui.devices

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import com.ollacore.app.OllacoreApp
import com.ollacore.app.data.model.DeviceResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DevicesUiState(
    val devices: List<DeviceResponse> = emptyList(),
    /** Best-effort FCM token of this handset (null when Play services unavailable). */
    val thisPushToken: String? = null,
    val isLoading: Boolean = false,
    val isWorking: Boolean = false,
    val error: String? = null,
    val note: String? = null
)

/**
 * Settings -> Linked Devices (Category 1 - POST/GET/DELETE /v1/directory/devices YES).
 * "This device" is matched by FCM push token (best-effort); there is no QR-pairing
 * endpoint, so linking = companion device installs the app, logs in, and registers
 * its own push token (backend-check if a richer pairing flow is wanted).
 */
class DevicesViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as OllacoreApp).container
    private val directoryRepo = container.directoryRepository
    private val sessionStore = container.sessionStore

    private val _uiState = MutableStateFlow(DevicesUiState())
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()

    init {
        refresh()
        // Best-effort: resolve this handset's FCM token without adding new deps.
        runCatching {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    _uiState.update { it.copy(thisPushToken = task.result) }
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val token = sessionStore.sessionToken.first() ?: return@launch
            _uiState.update { it.copy(isLoading = true, error = null, note = null) }
            directoryRepo.listDevices(token)
                .onSuccess { resp ->
                    _uiState.update { it.copy(devices = resp.devices, isLoading = false) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
        }
    }

    /** Register this handset for push (Link path when this device is missing from the list). */
    fun registerThisDevice() {
        val pushToken = _uiState.value.thisPushToken
        if (pushToken.isNullOrBlank()) {
            _uiState.update { it.copy(error = "Push token unavailable (Google Play services required)") }
            return
        }
        viewModelScope.launch {
            val token = sessionStore.sessionToken.first() ?: return@launch
            _uiState.update { it.copy(isWorking = true, error = null, note = null) }
            directoryRepo.registerDevice(token, "android", pushToken)
                .onSuccess {
                    _uiState.update { it.copy(isWorking = false, note = "This device registered") }
                    refresh()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isWorking = false, error = e.message) }
                }
        }
    }

    /** Log out a device = revoke its push registration (DELETE /v1/directory/devices). */
    fun logoutDevice(pushToken: String) {
        viewModelScope.launch {
            val token = sessionStore.sessionToken.first() ?: return@launch
            _uiState.update { it.copy(isWorking = true, error = null, note = null) }
            directoryRepo.deleteDevice(token, pushToken)
                .onSuccess {
                    val wasSelf = pushToken == _uiState.value.thisPushToken
                    _uiState.update {
                        it.copy(
                            isWorking = false,
                            note = if (wasSelf) "This device logged out - re-register to restore push" else "Device logged out"
                        )
                    }
                    refresh()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isWorking = false, error = e.message) }
                }
        }
    }

    fun clearTransient() {
        _uiState.update { it.copy(error = null, note = null) }
    }
}
