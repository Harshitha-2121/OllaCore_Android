package com.ollacore.app.ui.attachments

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ollacore.app.OllacoreApp
import com.ollacore.app.data.remote.AttachmentUploader
import com.ollacore.app.data.util.MimeValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class AttachmentUiState(
    val isUploading: Boolean = false,
    val uploadProgress: Float = 0f,
    val uploadedCount: Int = 0,
    val totalParts: Int = 0,
    val error: String? = null,
    val uploadedAttachmentId: String? = null,
    val validationError: String? = null
)

class AttachmentViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as OllacoreApp).container
    private val chatRepo = container.chatRepository
    private val uploader = AttachmentUploader()

    private val _uiState = MutableStateFlow(AttachmentUiState())
    val uiState: StateFlow<AttachmentUiState> = _uiState.asStateFlow()

    fun validateAndUpload(
        roomToken: String,
        roomId: String,
        file: File,
        mimeType: String
    ) {
        // Validate mime type first
        val validation = MimeValidator.validate(file, mimeType)
        if (!validation.isValid) {
            _uiState.update { it.copy(validationError = validation.error) }
            return
        }

        _uiState.update { it.copy(validationError = null) }
        uploadFile(roomToken, roomId, file, mimeType)
    }

    private fun uploadFile(roomToken: String, roomId: String, file: File, mimeType: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isUploading = true, uploadProgress = 0f, error = null) }

            try {
                val byteSize = file.length()
                val sha256 = MimeValidator.computeSha256(file)

                if (byteSize > SINGLE_PUT_THRESHOLD) {
                    // Multipart upload
                    uploadMultipart(roomToken, roomId, file, mimeType, byteSize)
                } else {
                    // Single PUT upload
                    uploadSingle(roomToken, roomId, file, mimeType, byteSize)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isUploading = false,
                        error = "Upload failed: ${e.message}"
                    )
                }
            }
        }
    }

    private suspend fun uploadSingle(
        roomToken: String,
        roomId: String,
        file: File,
        mimeType: String,
        byteSize: Long
    ) {
        val initResult = chatRepo.initAttachment(roomToken, roomId, file.name, mimeType, byteSize)
        initResult.onSuccess { init ->
            _uiState.update { it.copy(uploadProgress = 0.3f) }

            val uploadResult = withContext(Dispatchers.IO) {
                uploader.uploadToPresignedUrl(init.uploadUrl, file, mimeType)
            }

            _uiState.update { it.copy(uploadProgress = 0.7f) }

            chatRepo.completeAttachment(roomToken, roomId, init.attachmentId)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isUploading = false,
                            uploadProgress = 1f,
                            uploadedAttachmentId = init.attachmentId
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isUploading = false, error = "Complete failed: ${e.message}")
                    }
                }
        }
        initResult.onFailure { e ->
            _uiState.update {
                it.copy(isUploading = false, error = "Init failed: ${e.message}")
            }
        }
    }

    private suspend fun uploadMultipart(
        roomToken: String,
        roomId: String,
        file: File,
        mimeType: String,
        byteSize: Long
    ) {
        val partSize = 8388608L // 8MB
        val initResult = chatRepo.initMultipartUpload(roomToken, roomId, file.name, mimeType, byteSize, partSize)
        initResult.onSuccess { init ->
            _uiState.update { it.copy(totalParts = init.partUrls.size, uploadedCount = 0) }

            val parts = withContext(Dispatchers.IO) {
                uploader.uploadMultipart(init.partUrls, file, partSize) { uploaded, total ->
                    val progress = 0.3f + (0.5f * uploaded.toFloat() / total)
                    _uiState.update {
                        it.copy(
                            uploadProgress = progress,
                            uploadedCount = uploaded
                        )
                    }
                }
            }

            _uiState.update { it.copy(uploadProgress = 0.85f) }

            chatRepo.completeMultipartUpload(roomToken, roomId, init.attachmentId, parts.map { (num, etag) ->
                com.ollacore.app.data.model.MultipartPart(num, etag)
            }).onSuccess {
                _uiState.update {
                    it.copy(
                        isUploading = false,
                        uploadProgress = 1f,
                        uploadedAttachmentId = init.attachmentId
                    )
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(isUploading = false, error = "Complete multipart failed: ${e.message}")
                }
            }
        }
        initResult.onFailure { e ->
            _uiState.update {
                it.copy(isUploading = false, error = "Init multipart failed: ${e.message}")
            }
        }
    }

    fun reset() {
        _uiState.update { AttachmentUiState() }
    }

    companion object {
        private const val SINGLE_PUT_THRESHOLD = 10 * 1024 * 1024L // 10MB
    }
}
