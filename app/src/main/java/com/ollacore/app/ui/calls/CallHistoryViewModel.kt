package com.ollacore.app.ui.calls

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ollacore.app.OllacoreApp
import com.ollacore.app.data.local.CallLogEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Calls tab: CLIENT-ONLY history (CallLogStore, no backend).
 * Tap an entry to call back; per-entry delete + clear-all.
 */
class CallHistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as OllacoreApp).container
    private val callLogStore = container.callLogStore

    val callLog: StateFlow<List<CallLogEntry>> = callLogStore.callLog.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    fun delete(id: String) {
        viewModelScope.launch { runCatching { callLogStore.delete(id) } }
    }

    fun clearAll() {
        viewModelScope.launch { runCatching { callLogStore.clear() } }
    }
}
