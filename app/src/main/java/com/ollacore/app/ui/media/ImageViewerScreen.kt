package com.ollacore.app.ui.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

/**
 * Spec 42 Media Viewer: fullscreen image with pinch zoom, tap toggles
 * chrome, share + external open. No new dependencies (foundation gestures,
 * FileProvider, Coil already present).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    imageUrl: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var chromeVisible by remember { mutableStateOf(true) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var sharedFile by remember(imageUrl) { mutableStateOf<File?>(null) }
    var working by remember(imageUrl) { mutableStateOf(false) }
    var shareRequested by remember { mutableStateOf(false) }

    val zoomState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        if (scale > 1f) {
            offset += panChange
        } else {
            offset = Offset.Zero
        }
    }

    suspend fun ensureFile(): File? {
        sharedFile?.takeIf { it.exists() }?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val out = File(context.cacheDir, "view_${System.currentTimeMillis()}.img")
                URL(imageUrl).openConnection().apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                }.getInputStream().use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
                sharedFile = out
                out
            }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { chromeVisible = !chromeVisible }
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = "Shared image",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
                .transformable(state = zoomState)
        )

        if (chromeVisible) {
            TopAppBar(
                title = { Text("Photo", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    if (working) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                    } else {
                        IconButton(onClick = {
                            working = true
                            shareRequested = true
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                        }
                        IconButton(onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(imageUrl)))
                            }
                        }) {
                            Icon(Icons.Default.Download, contentDescription = "Open externally", tint = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.6f)),
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }

    // Share flow runs once per tap (state-driven, no stray launches).
    LaunchedEffect(shareRequested) {
        if (!shareRequested) return@LaunchedEffect
        val file = ensureFile()
        working = false
        shareRequested = false
        if (file != null) {
            runCatching {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "image/*"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        "Share photo"
                    )
                )
            }
        }
    }
}
