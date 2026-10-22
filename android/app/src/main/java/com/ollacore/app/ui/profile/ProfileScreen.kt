package com.ollacore.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ollacore.app.data.remote.AttachmentUploader
import com.ollacore.app.data.di.AppContainer
import java.io.File
import androidx.compose.ui.layout.ContentScale
import android.content.Context

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    displayName: String?,
    phone: String?,
    about: String? = null,
    avatarUrl: String? = null,
    onBack: () -> Unit,
    onUpdateName: (String) -> Unit = {},
    onUpdateProfile: ((String?, String?) -> Unit)? = null,
    onUpdateAvatar: ((String) -> Unit)? = null,
    onLinkedDevices: () -> Unit = {},
    onPrivacy: () -> Unit = {},
    onNotifications: () -> Unit = {},
    onLogout: () -> Unit
) {
    var name by remember(displayName) { mutableStateOf(displayName ?: "") }
    var aboutText by remember(about) { mutableStateOf(about ?: "") }
    var isEditing by remember { mutableStateOf(false) }
    var avatarUrlState by remember(avatarUrl) { mutableStateOf(avatarUrl) }
    var uploadError by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val app = context.applicationContext as com.ollacore.app.OllacoreApp
    val container = app.container
    val api = container.api

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { picked ->
            scope.launch {
                val token = container.sessionStore.sessionToken.first() ?: ""
                uploadProfilePhoto(picked, context, api, token, container.sessionStore)
            }
        }
    }

    val actualUpdate: (String?, String?) -> Unit = onUpdateProfile ?: { n, _ -> onUpdateName(n ?: "") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Avatar / Photo
            val currentAvatarUrl = avatarUrlState
            Box(contentAlignment = Alignment.BottomEnd) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(com.ollacore.app.ui.theme.BrandGradient)
                ) {
                    if (currentAvatarUrl != null && currentAvatarUrl.isNotBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(currentAvatarUrl!!)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Profile photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                        )
                    } else {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = "Profile photo",
                            modifier = Modifier.size(32.dp),
                            tint = Color.White
                        )
                    }
                }
                if (isEditing) {
                    FilledIconButton(
                        onClick = {
                            galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = "Change photo", modifier = Modifier.size(16.dp))
                    }
                }
            }

            if (uploadError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Upload failed: ${uploadError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (isEditing) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display Name") },
                    placeholder = { Text("Your name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = aboutText,
                    onValueChange = { aboutText = it },
                    label = { Text("About") },
                    placeholder = { Text("Hey there! I'm using Ollacore") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Phone: ${phone ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    FilledTonalButton(onClick = {
                        name = displayName ?: ""
                        aboutText = about ?: ""
                        isEditing = false
                    }, modifier = Modifier.weight(1f)) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        actualUpdate(name.trim().ifBlank { null }, aboutText.trim().ifBlank { null })
                        isEditing = false
                    }, modifier = Modifier.weight(1f)) {
                        Text("Save")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "API: PATCH /v1/directory/me (display_name, about, avatar_url) -> OllacoreApi.kt:84",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = displayName?.ifBlank { null } ?: "No name set",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = phone ?: "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (!about.isNullOrBlank()) {
                    Text(
                        text = about,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Tap Edit to add About",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                FilledTonalButton(onClick = { isEditing = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Edit Profile")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // Settings -> Linked Devices (Category 1 - devices API YES)
            ListItem(
                headlineContent = { Text("Linked devices") },
                supportingContent = { Text("Manage where you use Ollacore") },
                leadingContent = { Icon(Icons.Default.Devices, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().clickable(onClick = onLinkedDevices)
            )
            // Spec 23 sections: Privacy, Notifications, Security (dedicated screen).
            ListItem(
                headlineContent = { Text("Privacy & Security") },
                supportingContent = { Text("App lock, encryption info") },
                leadingContent = { Icon(Icons.Default.Lock, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().clickable(onClick = onPrivacy)
            )
            ListItem(
                headlineContent = { Text("Notifications") },
                supportingContent = { Text("Message, call and security alerts") },
                leadingContent = { Icon(Icons.Default.Notifications, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().clickable(onClick = onNotifications)
            )

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Logout")
            }
        }
    }
}

suspend fun uploadProfilePhoto(
    uri: android.net.Uri,
    context: Context,
    api: com.ollacore.app.data.remote.OllacoreApi,
    token: String,
    sessionStore: com.ollacore.app.data.local.SessionStore
): Pair<String?, String?> {
    // Returns Pair<newAvatarUrl, errorMessage>
    try {
        // Copy content URI to a temporary file (content:// URIs don't have file paths)
        val tempFile = File(context.cacheDir, "profile_upload_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        val file = tempFile
        val extension = file.extension.lowercase()
        val mimeType = when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }

        val initResponse = api.initAttachment(
            roomToken = token,
            roomId = "profile",
            filename = file.name,
            mimeType = mimeType,
            byteSize = file.length()
        )

        val uploader = AttachmentUploader()
        uploader.uploadToPresignedUrl(initResponse.uploadUrl, file, mimeType)

        api.completeAttachment(roomToken = token, roomId = "profile", attachmentId = initResponse.attachmentId)

        // Update avatar URL - use the download endpoint
        val newAvatarUrl = "${api.baseUrl}/v1/rooms/profile/attachments/${initResponse.attachmentId}/download"

        // Clean up temp file
        tempFile.delete()

        return Pair(newAvatarUrl, null)

    } catch (e: Exception) {
        return Pair(null, e.message)
    }
}