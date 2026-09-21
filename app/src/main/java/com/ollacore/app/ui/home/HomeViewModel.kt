package com.ollacore.app.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ollacore.app.OllacoreApp
import com.ollacore.app.data.model.InboxItem
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HomeUiState(
    val inbox: List<InboxItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val displayName: String? = null,
    val phone: String? = null,
    val myUserId: String? = null
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as OllacoreApp).container
    private val directoryRepo = container.directoryRepository
    private val sessionStore = container.sessionStore

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                sessionStore.sessionToken,
                sessionStore.displayName,
                sessionStore.phone,
                sessionStore.userId
            ) { token, name, phone, userId ->
                arrayOf(token, name, phone, userId)
            }.collect { parts ->
                val token = parts[0]
                _uiState.update {
                    it.copy(
                        displayName = parts[1],
                        phone = parts[2],
                        myUserId = parts[3]
                    )
                }
                if (token != null) {
                    loadInbox(token)
                }
            }
        }
    }

    fun loadInbox(token: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            directoryRepo.getInbox(token)
                .onSuccess { response ->
                    _uiState.update {
                        it.copy(inbox = response.conversations, isLoading = false)
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = e.message ?: "Failed to load inbox")
                    }
                }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val token = sessionStore.sessionToken.first() ?: return@launch
            loadInbox(token)
        }
    }
}
