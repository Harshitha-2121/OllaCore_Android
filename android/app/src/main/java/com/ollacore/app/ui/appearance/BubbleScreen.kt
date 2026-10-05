package com.ollacore.app.ui.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ollacore.app.data.model.ChatTextMode
import com.ollacore.app.data.model.resolveChatStyle
import kotlinx.coroutines.launch

private val SWATCHES = listOf(
    Color(0xFF1F2C34), Color(0xFF111B21), Color(0xFF000000),
    Color(0xFF00A884), Color(0xFF009688), Color(0xFF1971C2),
    Color(0xFF3B82F6), Color(0xFF7C5CFF), Color(0xFFC93BAE),
    Color(0xFFD6336C), Color(0xFFE03131), Color(0xFFE8712B),
    Color(0xFFF1F5F9), Color(0xFFFFFBEB), Color.White
)

/**
 * Chat-bubble workshop: swatches + text mode + corner + opacity with a
 * live preview. Save persists overrides onto the current theme; Reset
 * drops bubble tweaks (wallpaper choice is untouched).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BubbleScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val store = rememberChatThemeStore()
    val scope = rememberCoroutineScope()
    val live = rememberChatStyle()
    val baseTheme = remember(live.themeId) {
        com.ollacore.app.data.model.ChatThemes.find(live.themeId)
    }

    var inColor by remember(live) { mutableStateOf(live.incomingBubble) }
    var outColor by remember(live) { mutableStateOf(live.outgoingBubble) }
    var textMode by remember { mutableStateOf(ChatTextMode.AUTO) }
    var corner by remember(live) { mutableStateOf(live.corner.value) }
    var opacity by remember { mutableStateOf(1f) }
    var saving by remember { mutableStateOf(false) }

    val draft = remember(inColor, outColor, textMode, corner, opacity, baseTheme) {
        resolveChatStyle(
            baseTheme,
            com.ollacore.app.data.model.ChatCustom(
                incomingBubble = inColor,
                outgoingBubble = outColor,
                textMode = textMode,
                corner = corner.dp,
                opacity = opacity
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat bubble") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
        ) {
            HorizontalDivider()
            PreviewChat(style = draft, modifier = Modifier.fillMaxWidth().height(240.dp).padding(16.dp))

            SwatchSection("Incoming bubble color", inColor) { inColor = it }
            SwatchSection("Outgoing bubble color", outColor) { outColor = it }

            Text("Text color", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChatTextMode.entries.forEach { mode ->
                    FilterChip(
                        selected = textMode == mode,
                        onClick = { textMode = mode },
                        label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }

            Text(
                "Corners  •  ${corner.toInt()}dp",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Slider(
                value = corner, onValueChange = { corner = it }, valueRange = 4f..24f, steps = 19,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text(
                "Opacity  •  ${(opacity * 100).toInt()}%",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Slider(
                value = opacity, onValueChange = { opacity = it }, valueRange = 0.35f..1f,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    enabled = !saving,
                    onClick = {
                        saving = true
                        scope.launch {
                            runCatching {
                                store.saveBubbles(null, null, ChatTextMode.AUTO, null, null)
                            }
                            saving = false
                            onBack()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Reset") }
                Button(
                    enabled = !saving,
                    onClick = {
                        saving = true
                        scope.launch {
                            runCatching {
                                store.saveBubbles(inColor, outColor, textMode, corner, opacity)
                            }
                            saving = false
                            onBack()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (saving) "Saving…" else "Save") }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SwatchSection(title: String, selected: Color, onPick: (Color) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Wrap to two lines on narrow screens.
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SWATCHES.chunked(8).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { color ->
                        val isSel = color == selected
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(color)
                                .then(
                                    if (isSel) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                    else Modifier.border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape)
                                )
                                .clickable { onPick(color) }
                        )
                    }
                }
            }
        }
    }
}
