package com.ollacore.app.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ollacore.app.OllacoreApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    enum class AuthStep { PHONE_INPUT, OTP_VERIFICATION, AUTHENTICATED }

    data class AuthUiState(
        val step: AuthStep = AuthStep.PHONE_INPUT,
        val phone: String = "",
        val otpCode: String = "",
        val displayName: String? = null,
        val about: String? = null,
        val avatarUrl: String? = null,
        val isLoading: Boolean = false,
        val error: String? = null
    )

    private val container = (application as OllacoreApp).container
    private val repository = container.directoryRepository
    private val sessionStore = container.sessionStore

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val _sessionToken = MutableStateFlow<String?>(null)
    val sessionToken: StateFlow<String?> = _sessionToken.asStateFlow()

    private val _onboardingDone = MutableStateFlow<Boolean?>(null)
    val onboardingDone: StateFlow<Boolean?> = _onboardingDone.asStateFlow()

    fun completeOnboarding() {
        viewModelScope.launch {
            sessionStore.setOnboardingDone(true)
            _onboardingDone.value = true
        }
    }

    init {
        viewModelScope.launch {
            _onboardingDone.value = sessionStore.onboardingDone.first()
            val token = sessionStore.sessionToken.first()
            val phone = sessionStore.phone.first()
            val name = sessionStore.displayName.first()
            val about = sessionStore.about.first()
            val avatar = sessionStore.avatarUrl.first()
            if (!token.isNullOrBlank()) {
                _sessionToken.value = token
                _uiState.update {
                    it.copy(
                        step = AuthStep.AUTHENTICATED,
                        phone = phone.orEmpty(),
                        displayName = name,
                        about = about,
                        avatarUrl = avatar
                    )
                }
            }
        }
    }

    fun updatePhone(phone: String) {
        _uiState.update { it.copy(phone = phone.filter { ch -> ch.isDigit() || ch == '+' || ch == ' ' || ch == '-' || ch == '(' || ch == ')' }, error = null) }
    }

    fun updateOtpCode(code: String) {
        _uiState.update { it.copy(otpCode = code.filter(Char::isDigit).take(6), error = null) }
    }

    fun requestOtp() {
        val phone = _uiState.value.phone.trim()
        if (phone.isBlank()) {
            _uiState.update { it.copy(error = "Enter a phone number") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.requestOtp(phone)
                .onSuccess {
                    _uiState.update {
                        it.copy(step = AuthStep.OTP_VERIFICATION, otpCode = "", isLoading = false)
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message ?: "Unable to send OTP") }
                }
        }
    }

    fun verifyOtp() {
        val state = _uiState.value
        if (state.otpCode.length != 6) {
            _uiState.update { it.copy(error = "Enter the 6-digit OTP") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.verifyOtp(state.phone.trim(), state.otpCode)
                .onSuccess { response ->
                    sessionStore.saveSession(
                        token = response.sessionToken,
                        userId = response.userId,
                        phone = state.phone.trim(),
                        displayName = response.displayName
                    )
                    _sessionToken.value = response.sessionToken
                    _uiState.update {
                        it.copy(
                            step = AuthStep.AUTHENTICATED,
                            displayName = response.displayName,
                            isLoading = false,
                            error = null
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message ?: "OTP verification failed") }
                }
        }
    }

    fun resendOtp() {
        val phone = _uiState.value.phone
        _uiState.update { it.copy(step = AuthStep.PHONE_INPUT, otpCode = "", error = null) }
        if (phone.isNotBlank()) {
            // Keep the phone number visible; the user can tap Request OTP again.
        }
    }

    fun updateProfile(displayName: String?, about: String?, avatarUrl: String? = null) {
        viewModelScope.launch {
            val token = _sessionToken.value ?: return@launch
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching { container.api.updateProfile(token, displayName = displayName, about = about, avatarUrl = avatarUrl) }
                .onSuccess { profile ->
                    sessionStore.updateProfile(profile.displayName, profile.about, profile.avatarUrl ?: profile.photoUrl)
                    _uiState.update {
                        it.copy(
                            displayName = profile.displayName ?: displayName,
                            about = profile.about ?: about,
                            avatarUrl = profile.avatarUrl ?: profile.photoUrl ?: avatarUrl,
                            isLoading = false
                        )
                    }
                }
                .onFailure { e ->
                    // fallback: save locally even if API ignores extra fields (profile photo/about may be client-only until backend extends UpdateProfileRequest)
                    sessionStore.updateProfile(displayName, about, avatarUrl)
                    _uiState.update { it.copy(displayName = displayName, about = about, avatarUrl = avatarUrl, isLoading = false, error = e.message) }
                }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun logout() {
        val token = _sessionToken.value
        viewModelScope.launch {
            if (!token.isNullOrBlank()) {
                runCatching { container.api.logout(token) }
            }
            sessionStore.clear()
            _sessionToken.value = null
            _uiState.value = AuthUiState()
        }
    }
}
