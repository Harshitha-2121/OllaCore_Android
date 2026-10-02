package com.ollacore.app.ui.call

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * WhatsApp-style calling: voice (ringing/mute/speaker/end) + video
 * (local preview, remote tiles incl. group grid, camera on/off, switch,
 * speaker, real MediaProjection screen share, end) + incoming accept/decline.
 */
@Composable
fun CallScreen(
    uiState: CallUiState,
    peerName: String = "Call",
    onToggleMute: () -> Unit,
    onToggleVideo: () -> Unit,
    onToggleSpeaker: () -> Unit = {},
    onSwitchCamera: () -> Unit = {},
    onAccept: () -> Unit = {},
    onDecline: () -> Unit = {},
    onShareResult: (Int, Intent?) -> Unit = { _, _ -> },
    onStopShare: () -> Unit = {},
    onToggleScreenShare: () -> Unit = {},
    onEndCall: () -> Unit
) {
    val context = LocalContext.current

    // MediaProjection consent -> ViewModel starts ScreenCapturerAndroid (real share, not mock).
    val shareLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            onShareResult(result.resultCode, result.data)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when (uiState.phase) {
            CallPhase.INCOMING -> IncomingCallUi(
                peerName = peerName,
                audioOnly = uiState.audioOnly,
                onAccept = onAccept,
                onDecline = onDecline
            )
            CallPhase.ENDED, CallPhase.DECLINED, CallPhase.BUSY,
            CallPhase.FAILED, CallPhase.MISSED ->
                CallEndedUi(phase = uiState.phase, error = uiState.error, onClose = onEndCall)
            else -> {
                if (uiState.audioOnly) {
                    VoiceCallUi(
                        peerName = peerName,
                        phase = uiState.phase,
                        isMuted = uiState.isMuted,
                        isSpeakerOn = uiState.isSpeakerOn,
                        elapsedSec = uiState.elapsedSec,
                        quality = uiState.quality,
                        error = uiState.error,
                        onToggleMute = onToggleMute,
                        onToggleSpeaker = onToggleSpeaker,
                        onEndCall = onEndCall
                    )
                } else {
                    VideoCallUi(
                        peerName = peerName,
                        uiState = uiState,
                        onToggleMute = onToggleMute,
                        onToggleVideo = onToggleVideo,
                        onToggleSpeaker = onToggleSpeaker,
                        onSwitchCamera = onSwitchCamera,
                        onShareClick = {
                            if (uiState.isScreenSharing) {
                                onStopShare()
                            } else {
                                val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                runCatching { shareLauncher.launch(mgr.createScreenCaptureIntent()) }
                            }
                        },
                        onEndCall = onEndCall
                    )
                }
            }
        }
    }
}

// ── Voice call: avatar + phase + mute/speaker/end ─────────────────────

/** Status line under the peer name (duration once media is up). */
private fun phaseLabel(phase: CallPhase, peerName: String? = null): String = when (phase) {
    CallPhase.CALLING -> "Calling…"
    CallPhase.RINGING -> if (peerName != null) "Ringing $peerName…" else "Ringing…"
    CallPhase.CONNECTING -> "Connecting…"
    CallPhase.RECONNECTING -> "Reconnecting…"
    else -> ""
}

/** "00:07" once connected (elapsedSec = 0 shows plain Connected). */
private fun statusLabel(phase: CallPhase, elapsedSec: Long): String = when {
    phase == CallPhase.CONNECTED && elapsedSec > 0L -> formatCallDuration(elapsedSec)
    phase == CallPhase.CONNECTED -> "Connected"
    else -> phaseLabel(phase)
}

@Composable
private fun QualityBanner(quality: CallQualityLevel, modifier: Modifier = Modifier) {
    if (quality == CallQualityLevel.POOR) {
        Text(
            "Poor connection…",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFFFC444),
            modifier = modifier
        )
    }
}

@Composable
private fun VoiceCallUi(
    peerName: String,
    phase: CallPhase,
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    elapsedSec: Long,
    quality: CallQualityLevel,
    error: String?,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onEndCall: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Call screen: gradient avatar highlight (allowed surface).
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(com.ollacore.app.ui.theme.BrandGradient)
        ) {
            Text(
                com.ollacore.app.ui.theme.initialsFor(peerName),
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(peerName, style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            statusLabel(phase, elapsedSec),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f)
        )
        QualityBanner(quality, Modifier.padding(top = 4.dp))
        error?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.height(48.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ControlButton(
                onClick = onToggleMute,
                active = isMuted,
                icon = { Icon(if (isMuted) Icons.Default.MicOff else Icons.Default.Mic, contentDescription = "Mute") }
            )
            ControlButton(
                onClick = onToggleSpeaker,
                active = isSpeakerOn,
                icon = { Icon(Icons.Default.VolumeUp, contentDescription = "Speaker") }
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        FloatingActionButton(onClick = onEndCall, containerColor = MaterialTheme.colorScheme.error, modifier = Modifier.size(72.dp)) {
            Icon(Icons.Default.CallEnd, contentDescription = "End call", modifier = Modifier.size(32.dp))
        }
    }
}

// ── Video call: remote tiles + local PiP + full controls ──────────────

@Composable
private fun VideoCallUi(
    peerName: String,
    uiState: CallUiState,
    onToggleMute: () -> Unit,
    onToggleVideo: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onSwitchCamera: () -> Unit,
    onShareClick: () -> Unit,
    onEndCall: () -> Unit
) {
    val remoteVideos = remember(uiState.remoteStreams) {
        uiState.remoteStreams.mapNotNull { it.videoTracks.firstOrNull() }
    }
    // Spec 19: controls auto-hide after a few seconds, tap returns them.
    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(controlsVisible, uiState.phase) {
        if (controlsVisible && uiState.phase == CallPhase.CONNECTED) {
            kotlinx.coroutines.delay(4000)
            controlsVisible = false
        }
    }
    Box(
        modifier = Modifier.fillMaxSize().clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null
        ) { controlsVisible = true }
    ) {
        // Remote: fullscreen single or group grid (SFU multi-stream).
        if (remoteVideos.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        when {
                            uiState.phase == CallPhase.CONNECTED ->
                                statusLabel(uiState.phase, uiState.elapsedSec)
                            uiState.phase.isRinging || uiState.phase == CallPhase.CONNECTING ||
                                uiState.phase == CallPhase.RECONNECTING ->
                                phaseLabel(uiState.phase, peerName)
                            else -> peerName
                        },
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    QualityBanner(uiState.quality, Modifier.padding(top = 12.dp))
                }
            }
        } else if (remoteVideos.size == 1) {
            VideoRenderer(track = remoteVideos[0], modifier = Modifier.fillMaxSize())
        } else {
            // Spec 44: adaptive tiles (phones, large phones, foldables).
            LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 160.dp), modifier = Modifier.fillMaxSize().padding(8.dp)) {
                items(remoteVideos, key = { it.id() }) { track ->
                    VideoRenderer(track = track, modifier = Modifier.fillMaxWidth().aspectRatio(0.75f).padding(4.dp))
                }
            }
        }

        // Local camera / screen preview PiP.
        if (uiState.localVideoTrack != null && uiState.isVideoEnabled) {
            VideoRenderer(
                track = uiState.localVideoTrack,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(width = 120.dp, height = 160.dp)
            )
        } else {
            Box(
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(width = 120.dp, height = 160.dp)
                    .background(Color.DarkGray, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.VideocamOff, contentDescription = "Camera off", tint = Color.White)
            }
        }

        if (uiState.isScreenSharing) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
                color = MaterialTheme.colorScheme.primary,
                shape = MaterialTheme.shapes.small
            ) {
                Text("Sharing your screen", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
            }
        }

        uiState.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp))
        }
        QualityBanner(
            uiState.quality,
            Modifier.align(Alignment.TopCenter).padding(top = if (uiState.error != null) 84.dp else 56.dp)
        )

        // Controls (auto-hidden; any tap brings them back).
        androidx.compose.animation.AnimatedVisibility(
            visible = controlsVisible || uiState.phase != CallPhase.CONNECTED,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ControlButton(onClick = { onToggleMute(); controlsVisible = true }, active = uiState.isMuted) {
                        Icon(if (uiState.isMuted) Icons.Default.MicOff else Icons.Default.Mic, contentDescription = "Mute")
                    }
                    ControlButton(onClick = { onToggleVideo(); controlsVisible = true }, active = !uiState.isVideoEnabled) {
                        Icon(if (uiState.isVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff, contentDescription = "Camera on/off")
                    }
                    ControlButton(onClick = onSwitchCamera, active = false) {
                        Icon(Icons.Default.Cameraswitch, contentDescription = "Switch camera")
                    }
                    ControlButton(onClick = onToggleSpeaker, active = uiState.isSpeakerOn) {
                        Icon(Icons.Default.VolumeUp, contentDescription = "Speaker")
                    }
                    ControlButton(onClick = { onShareClick(); controlsVisible = true }, active = uiState.isScreenSharing) {
                        Icon(if (uiState.isScreenSharing) Icons.Default.StopScreenShare else Icons.Default.ScreenShare, contentDescription = "Screen share")
                    }
                }
                FloatingActionButton(onClick = onEndCall, containerColor = MaterialTheme.colorScheme.error, modifier = Modifier.size(72.dp)) {
                    Icon(Icons.Default.CallEnd, contentDescription = "End call", modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}

@Composable
private fun ControlButton(onClick: () -> Unit, active: Boolean, icon: @Composable () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        modifier = Modifier.size(52.dp)
    ) {
        icon()
    }
}

// ── Incoming / ended ──────────────────────────────────────────────────

@Composable
private fun IncomingCallUi(peerName: String, audioOnly: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Call screen: gradient ringing avatar (allowed surface).
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(com.ollacore.app.ui.theme.BrandGradient)
        ) {
            Icon(
                if (audioOnly) Icons.Default.Call else Icons.Default.Videocam,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(48.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(peerName, style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Text(
            if (audioOnly) "Incoming voice call" else "Incoming video call",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(48.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            FloatingActionButton(onClick = onDecline, containerColor = MaterialTheme.colorScheme.error, modifier = Modifier.size(64.dp)) {
                Icon(Icons.Default.CallEnd, contentDescription = "Decline")
            }
            FloatingActionButton(onClick = onAccept, containerColor = com.ollacore.app.ui.theme.OllaCallGreen, modifier = Modifier.size(64.dp)) {
                Icon(Icons.Default.Call, contentDescription = "Accept", tint = Color.White)
            }
        }
    }
}

@Composable
private fun CallEndedUi(phase: CallPhase, error: String?, onClose: () -> Unit) {
    val title = when (phase) {
        CallPhase.DECLINED -> "Call declined"
        CallPhase.BUSY -> "Busy"
        CallPhase.MISSED -> "Missed call"
        CallPhase.FAILED -> "Call failed"
        else -> "Call ended"
    }
    val subtitle = when {
        phase == CallPhase.BUSY -> "They're on another call"
        phase == CallPhase.MISSED && error == null -> "No answer"
        else -> error
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            if (phase == CallPhase.MISSED) Icons.Default.PhoneMissed else Icons.Default.CallEnd,
            contentDescription = null,
            tint = if (phase == CallPhase.FAILED || phase == CallPhase.DECLINED)
                MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(title, color = Color.White, style = MaterialTheme.typography.headlineSmall)
        subtitle?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                it,
                color = if (phase == CallPhase.FAILED) MaterialTheme.colorScheme.error
                else Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onClose) { Text("Close") }
    }
}

// ── Shared WebRTC renderer (own EGL context per view) ─────────────────

@Composable
fun VideoRenderer(track: VideoTrack, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val eglBase = remember { EglBase.create() }
    DisposableEffect(Unit) {
        onDispose { runCatching { eglBase.release() } }
    }
    val renderer = remember(track) { SurfaceViewRenderer(context) }
    DisposableEffect(track, renderer) {
        renderer.init(eglBase.eglBaseContext, null)
        renderer.setEnableHardwareScaler(true)
        track.addSink(renderer)
        onDispose {
            runCatching { track.removeSink(renderer) }
            runCatching { renderer.release() }
        }
    }
    AndroidView(factory = { renderer }, modifier = modifier)
}
