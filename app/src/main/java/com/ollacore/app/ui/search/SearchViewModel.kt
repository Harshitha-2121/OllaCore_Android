package com.ollacore.app.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ollacore.app.OllacoreApp
import com.ollacore.app.data.model.MessageResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val results: List<MessageResponse> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val hasSearched: Boolean = false,
    val selectedIndex: Int? = null
)

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as OllacoreApp).container
    private val chatRepo = container.chatRepository
    private val sessionStore = container.sessionStore

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var currentRoomToken: String = ""
    private var currentRoomId: String = ""

    fun setRoom(roomToken: String, roomId: String) {
        currentRoomToken = roomToken
        currentRoomId = roomId
    }

    fun updateQuery(query: String) {
        _uiState.update { it.copy(query = query, error = null) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(results = emptyList(), hasSearched = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300) // debounce
            search(query)
        }
    }

    fun searchImmediate(query: String) {
        searchJob?.cancel()
        viewModelScope.launch {
            search(query)
        }
    }

    private suspend fun search(query: String) {
        if (currentRoomToken.isBlank()) {
            _uiState.update { it.copy(error = "Not connected to room") }
            return
        }

        val sanitized = sanitizeQuery(query)
        if (sanitized.isBlank()) {
            _uiState.update { it.copy(results = emptyList(), hasSearched = true, error = "Invalid search query") }
            return
        }

        _uiState.update { it.copy(isLoading = true, error = null) }

        chatRepo.searchMessages(currentRoomToken, currentRoomId, sanitized)
            .onSuccess { response ->
                val sorted = response.messages.sortedByDescending { it.eventSeq }
                _uiState.update {
                    it.copy(
                        results = sorted,
                        isLoading = false,
                        hasSearched = true
                    )
                }
            }
            .onFailure { e ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = mapSearchError(e),
                        hasSearched = true
                    )
                }
            }
    }

    fun selectResult(index: Int) {
        _uiState.update { it.copy(selectedIndex = index) }
    }

    fun clearSearch() {
        _uiState.update { SearchUiState() }
        searchJob?.cancel()
    }

    private fun sanitizeQuery(query: String): String {
        // Remove special characters that could break search
        return query
            .replace(Regex("[<>{}\\[\\]]"), "")
            .trim()
            .take(200) // limit length
    }

    private fun mapSearchError(e: Throwable): String {
        return when {
            e.message?.contains("401") == true -> "Session expired. Please log in again."
            e.message?.contains("403") == true -> "You don't have access to this room."
            e.message?.contains("404") == true -> "Room not found."
            e.message?.contains("429") == true -> "Too many requests. Please wait."
            e.message?.contains("timeout", ignoreCase = true) == true -> "Search timed out. Try a shorter query."
            e.message?.contains("network", ignoreCase = true) == true -> "No network connection."
            else -> "Search failed: ${e.message ?: "Unknown error"}"
        }
    }
}
