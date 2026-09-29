package com.ollacore.app.ui.appearance

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ollacore.app.data.local.ChatThemeStore
import com.ollacore.app.data.model.ChatWallpaper
import com.ollacore.app.data.model.resolveChatStyle
import kotlinx.coroutines.launch

private val SOLID_CHOICES = listOf(
    Color(0xFF000000), Color(0xFF0B141A), Color(0xFF1F2C34),
    Color(0xFF0C4A6E), Color(0xFF3B0764), Color(0xFF431407),
    Color(0xFFFFFBEB), Color(0xFFF1F5F9)
)

/**
 * Wallpaper picker: built-in procedural wallpapers + solid colors + a
 * gallery photo (persisted Uri), all with a live chat preview on top.
 * Taps apply immediately and persist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpaperScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = rememberChatThemeStore()
    val scope = rememberCoroutineScope()
    val live = rememberChatStyle()

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            scope.launch { runCatching { store.saveWallpaper(ChatWallpaper.Photo(uri.toString())) } }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wallpaper") },
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
            PreviewChat(style = live, modifier = Modifier.fillMaxWidth().height(300.dp).padding(16.dp))

            Text("Built-in wallpapers", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            // Fixed-height grid inside the page scroll (uniform thumbs).
            val ids = remember { listOf("theme_default") + ChatThemeStore.BUILTIN_WALLPAPER_IDS }
            val themeWallpaper = remember(live.themeId) {
                com.ollacore.app.data.model.ChatThemes.find(live.themeId).wallpaper
            }
            Box(modifier = Modifier.height(420.dp).padding(horizontal = 8.dp)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(ids, key = { it }) { id ->
                        // "theme_default" follows the selected theme; the rest
                        // are explicit overrides (even the default doodle).
                        val wp = if (id == "theme_default") themeWallpaper else remember(id) { ChatThemeStore.builtinWallpaper(id) }
                        val isCurrent = if (id == "theme_default") {
                            live.wallpaper == themeWallpaper
                        } else {
                            live.wallpaper == wp
                        }
                        Box(
                            modifier = Modifier
                                .aspectRatio(0.62f)
                                .clip(RoundedCornerShape(12.dp))
                                .then(
                                    if (isCurrent) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                                    else Modifier.border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                )
                                .clickable {
                                    scope.launch {
                                        runCatching {
                                            if (id == "theme_default") store.saveWallpaper(null)
                                            else store.saveWallpaper(wp)
                                        }
                                    }
                                }
                        ) {
                            ChatWallpaperView(wallpaper = wp, modifier = Modifier.fillMaxSize())
                            if (id == "theme_default") {
                                Surface(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp)
                                ) {
                                    Text(
                                        "Theme",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Text("Colors", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SOLID_CHOICES.forEach { color ->
                    val isCurrent = (live.wallpaper as? ChatWallpaper.Solid)?.color == color
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(color)
                            .then(
                                if (isCurrent) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                else Modifier.border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape)
                            )
                            .clickable {
                                scope.launch { runCatching { store.saveWallpaper(ChatWallpaper.Solid(color)) } }
                            }
                    )
                }
            }

            OutlinedButton(
                onClick = { runCatching { galleryLauncher.launch("image/*") } },
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Choose from gallery")
            }
            if (live.wallpaper is ChatWallpaper.Photo) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    Text(
                        "Using your gallery photo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        scope.launch { runCatching { store.saveWallpaper(null) } }
                    }) { Text("Remove") }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
