package com.ollacore.app.ui.media

import android.content.Intent
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * In-app video player: fullscreen VideoView (platform, no new dependencies)
 * with playback controls. Replaces the old external ACTION_VIEW intent that
 * kicked users out to the browser (which just downloaded the file).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoViewerScreen(
    videoUrl: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var isLoading by remember(videoUrl) { mutableStateOf(true) }
    var playError by remember(videoUrl) { mutableStateOf<String?>(null) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }

    fun startPlayback(view: VideoView) {
        isLoading = true
        playError = null
        runCatching {
            view.setVideoURI(Uri.parse(videoUrl))
        }.onFailure {
            isLoading = false
            playError = "Can't play this video"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (videoUrl.isBlank()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Video unavailable", color = Color.White)
            }
        } else if (playError != null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    playError ?: "Can't play this video",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = {
                        videoViewRef?.let { startPlayback(it) }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Retry")
                    }
                    OutlinedButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(videoUrl)))
                        }
                    }) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open externally")
                    }
                }
            }
        } else {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        val controller = MediaController(ctx).also {
                            it.setAnchorView(this)
                        }
                        setMediaController(controller)
                        setOnPreparedListener { player ->
                            isLoading = false
                            player.start()
                        }
                        setOnCompletionListener {
                            isLoading = false
                        }
                        setOnErrorListener { _, _, _ ->
                            isLoading = false
                            playError = "Can't play this video"
                            true
                        }
                        videoViewRef = this
                        startPlayback(this)
                        requestFocus()
                    }
                },
                update = { view ->
                    if (videoViewRef == null) videoViewRef = view
                },
                modifier = Modifier.fillMaxSize()
            )
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Loading video…", color = Color.White.copy(alpha = 0.8f))
                    }
                }
            }
        }

        TopAppBar(
            title = { Text("Video", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            },
            actions = {
                if (videoUrl.isNotBlank()) {
                    IconButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(videoUrl)))
                        }
                    }) {
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = "Open externally",
                            tint = Color.White
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Black.copy(alpha = 0.6f)
            ),
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }

    DisposableEffect(videoUrl) {
        onDispose {
            runCatching {
                videoViewRef?.stopPlayback()
            }
            videoViewRef = null
        }
    }
}
