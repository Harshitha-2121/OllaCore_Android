package com.ollacore.app.ui.appearance

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ollacore.app.data.model.ChatTheme
import com.ollacore.app.data.model.ChatWallpaper
import com.ollacore.app.data.model.resolveChatStyle
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * "Create with AI" — on-device draft generator.
 *
 * There is no cloud model in this build, so the architecture is split:
 * [AiThemeGenerator] is the seam a server model plugs into later
 * (same input prompt, same [ChatTheme] output — no screen redesign),
 * and [PromptHashGenerator] is the working local implementation that
 * deterministically derives a palette + wallpaper from the prompt words.
 * The sheet labels this honestly.
 */
interface AiThemeGenerator {
    fun generate(prompt: String): ChatTheme
}

object PromptHashGenerator : AiThemeGenerator {
    override fun generate(prompt: String): ChatTheme {
        val h = abs(prompt.trim().lowercase().hashCode())
        val hue = (h % 360).toFloat()
        val hue2 = ((h / 7) % 360).toFloat()
        val deep = Color.hsv(hue, 0.75f, 0.28f)
        val mid = Color.hsv(hue, 0.65f, 0.55f)
        val glow = Color.hsv(hue2, 0.5f, 0.9f)
        val out = Color.hsv(hue, 0.8f, 0.62f)
        return ChatTheme(
            id = "ai_draft",
            name = prompt.trim().take(24).ifBlank { "AI draft" },
            wallpaper = ChatWallpaper.GradientWash(
                colors = listOf(deep, mid, Color.hsv(hue2, 0.6f, 0.35f)),
                blob = glow
            ),
            incomingBubble = Color(0xFF1F2937),
            incomingText = Color.White,
            outgoingBubble = out,
            outgoingText = Color.White
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiThemeSheet(
    generator: AiThemeGenerator = PromptHashGenerator,
    onDismiss: () -> Unit
) {
    val store = rememberChatThemeStore()
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<ChatTheme?>(null) }
    var applying by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("Create with AI", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Describe a mood — the on-device generator drafts a wallpaper + bubbles from it. " +
                    "A cloud model can plug into the same slot later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("e.g. calm ocean evening") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                enabled = prompt.isNotBlank(),
                onClick = { draft = generator.generate(prompt) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Generate") }
            draft?.let { d ->
                Spacer(modifier = Modifier.height(12.dp))
                PreviewChat(style = resolveChatStyle(d), modifier = Modifier.fillMaxWidth().height(220.dp))
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    enabled = !applying,
                    onClick = {
                        applying = true
                        scope.launch {
                            runCatching {
                                store.saveWallpaper(d.wallpaper)
                                store.saveBubbles(
                                    incoming = d.incomingBubble,
                                    outgoing = d.outgoingBubble,
                                    textMode = com.ollacore.app.data.model.ChatTextMode.AUTO,
                                    cornerDp = null,
                                    opacity = null
                                )
                            }
                            applying = false
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (applying) "Applying…" else "Use this theme") }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
