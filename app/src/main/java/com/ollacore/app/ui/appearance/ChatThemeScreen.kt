package com.ollacore.app.ui.appearance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ollacore.app.data.model.ChatTheme
import com.ollacore.app.data.model.ChatThemes
import com.ollacore.app.data.model.ResolvedChatStyle
import kotlinx.coroutines.launch

private sealed interface GridItem {
    data class Theme(val theme: ChatTheme) : GridItem
    data object Ai : GridItem
}

/**
 * Chat theme picker (reference screenshots 2-4): 2-row horizontal card
 * grid (selected card first, AI card second-top), single-select checkmark,
 * "both change" note, Customise rows, functional overflow menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatThemeScreen(
    onBack: () -> Unit,
    onOpenBubbles: () -> Unit,
    onOpenWallpaper: () -> Unit,
    modifier: Modifier = Modifier
) {
    val store = rememberChatThemeStore()
    val scope = rememberCoroutineScope()
    val selectedId = rememberSelectedThemeId()
    var menuOpen by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showAi by remember { mutableStateOf(false) }

    // Column-major fill puts even indices on the top row: AI lands
    // top-row second, right after the selected default card.
    val items: List<GridItem> = remember {
        val t = ChatThemes.all
        listOf(GridItem.Theme(t[0]), GridItem.Theme(t[1]), GridItem.Ai) +
            t.drop(2).map { GridItem.Theme(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat theme") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Reset theme") },
                                onClick = {
                                    menuOpen = false
                                    scope.launch { runCatching { store.selectTheme(ChatThemes.default.id) } }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Reset customization") },
                                onClick = {
                                    menuOpen = false
                                    scope.launch { runCatching { store.resetCustomization() } }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("About themes") },
                                onClick = { menuOpen = false; showAbout = true }
                            )
                        }
                    }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            HorizontalDivider()
            Text(
                "Themes",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val cardW = ((maxWidth - 32.dp - 24.dp) / 3.3f).coerceIn(104.dp, 150.dp)
                LazyHorizontalGrid(
                    rows = GridCells.Fixed(2),
                    modifier = Modifier.height(cardW * 1.9f * 2 + 12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(items, key = { itemKey(it) }) { item ->
                        when (item) {
                            is GridItem.Ai -> AiCard(cardW = cardW, onClick = { showAi = true })
                            is GridItem.Theme -> ThemeCard(
                                theme = item.theme,
                                selected = item.theme.id == selectedId,
                                cardW = cardW,
                                onClick = {
                                    scope.launch { runCatching { store.selectTheme(item.theme.id) } }
                                }
                            )
                        }
                    }
                }
            }
            Text(
                "The chat bubble and wallpaper will both change.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
            )
            Text(
                "Customise",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            CustomizeRow(
                icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                title = "Chat bubble",
                onClick = onOpenBubbles
            )
            CustomizeRow(
                icon = Icons.Default.Image,
                title = "Wallpaper",
                onClick = onOpenWallpaper
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("About themes") },
            text = {
                Text(
                    "A theme sets the chat wallpaper plus incoming and outgoing bubble " +
                        "colors together. Tweak either side afterwards from Customise; " +
                        "picking a new theme replaces both. Everything is stored on " +
                        "this device."
                )
            },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("Got it") } }
        )
    }

    if (showAi) {
        AiThemeSheet(onDismiss = { showAi = false })
    }
}

private fun itemKey(item: GridItem): String = when (item) {
    is GridItem.Ai -> "ai"
    is GridItem.Theme -> item.theme.id
}

/**
 * One theme card: wallpaper + incoming/outgoing mini bubbles with tails.
 * Selected card gets the white ring + bottom-center check.
 */
@Composable
fun ThemeCard(
    theme: ChatTheme,
    selected: Boolean,
    cardW: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(cardW)
            .aspectRatio(0.53f)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black)
            .then(
                if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(20.dp))
                else Modifier.border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(20.dp))
            )
            .clickable(onClick = onClick)
    ) {
        ChatWallpaperView(wallpaper = theme.wallpaper, modifier = Modifier.fillMaxSize())
        Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            PreviewBubble(
                color = theme.incomingBubble,
                tailLeft = true,
                modifier = Modifier.fillMaxWidth(0.62f).height(26.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            PreviewBubble(
                color = theme.outgoingBubble,
                tailLeft = false,
                modifier = Modifier.fillMaxWidth(0.62f).height(26.dp).align(Alignment.End)
            )
        }
        if (selected) {
            Surface(
                color = Color.White,
                shape = CircleShape,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).size(30.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Default.Check, contentDescription = "Selected", tint = Color.Black, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** Compact non-interactive card used as the Appearance-screen preview. */
@Composable
fun ThemeMiniPreview(theme: ChatTheme, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
    ) {
        ChatWallpaperView(wallpaper = theme.wallpaper, modifier = Modifier.fillMaxSize())
        Column(modifier = Modifier.fillMaxSize().padding(4.dp)) {
            PreviewBubble(color = theme.incomingBubble, tailLeft = true, modifier = Modifier.fillMaxWidth(0.66f).height(8.dp))
            Spacer(modifier = Modifier.height(3.dp))
            PreviewBubble(
                color = theme.outgoingBubble, tailLeft = false,
                modifier = Modifier.fillMaxWidth(0.66f).height(8.dp).align(Alignment.End)
            )
        }
    }
}

/** Mini chat bubble with a side tail notch. */
@Composable
private fun PreviewBubble(color: Color, tailLeft: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                if (tailLeft) {
                    moveTo(6.dp.toPx(), 0f)
                    lineTo(w, 0f)
                    lineTo(w, h)
                    lineTo(6.dp.toPx(), h)
                    lineTo(6.dp.toPx(), 10.dp.toPx())
                    lineTo(0f, 7.dp.toPx())
                    close()
                } else {
                    moveTo(0f, 0f)
                    lineTo(w - 6.dp.toPx(), 0f)
                    lineTo(w, 7.dp.toPx())
                    lineTo(w - 6.dp.toPx(), 10.dp.toPx())
                    lineTo(w - 6.dp.toPx(), h)
                    lineTo(0f, h)
                    close()
                }
            }
            drawPath(path, color)
        }
    }
}

@Composable
private fun CustomizeRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/** Live mini-chat preview (bubble + wallpaper screens + AI draft). */
@Composable
fun PreviewChat(style: ResolvedChatStyle, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
    ) {
        ChatWallpaperView(wallpaper = style.wallpaper, modifier = Modifier.fillMaxSize())
        val (own, peer) = remember(style.corner) {
            com.ollacore.app.data.model.bubbleShapes(style.corner)
        }
        Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Center) {
            Box(
                modifier = Modifier.fillMaxWidth(0.7f).clip(peer).background(style.incomingBubble).padding(10.dp)
            ) {
                Text("Hey, are we still on for tonight?", color = style.incomingText, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier.fillMaxWidth(0.7f).align(Alignment.End).clip(own).background(style.outgoingBubble).padding(10.dp)
            ) {
                Text("Yes! See you at 7.", color = style.outgoingText, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Gradient "Create with AI" action card (second card, top row). */
@Composable
private fun AiCard(cardW: Dp, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(cardW)
            .aspectRatio(0.53f)
            .clip(RoundedCornerShape(20.dp))
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(Color(0xFF2B1B4E), Color(0xFF4C1D95), Color(0xFF1E3A8A))
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(30.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Create\nwith AI",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}
