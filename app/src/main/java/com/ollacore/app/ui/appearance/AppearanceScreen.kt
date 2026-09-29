package com.ollacore.app.ui.appearance

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.ollacore.app.data.local.ThemeMode
import com.ollacore.app.data.local.ThemeStore
import com.ollacore.app.data.model.ChatThemes
import kotlinx.coroutines.launch

/**
 * Appearance settings (reference screenshot 1): Default chat theme row
 * with live mini preview, "Ollacore Plus" section (branding is ours — the
 * reference's label is not copied), App icon + App theme rows, subscribe
 * note with emphasized benefits link.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    onOpenTheme: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val themeStore = remember(context) { ThemeStore(context.applicationContext) }
    val appMode by themeStore.mode.collectAsState(initial = ThemeMode.DARK)
    val selectedId = rememberSelectedThemeId()
    val selectedTheme = remember(selectedId) { ChatThemes.find(selectedId) }
    var showThemePicker by remember { mutableStateOf(false) }
    var showIconPicker by remember { mutableStateOf(false) }
    var showBenefits by remember { mutableStateOf(false) }
    var currentIcon by remember { mutableStateOf(AppIconSwitcher.current(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appearance", style = MaterialTheme.typography.headlineSmall) },
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            HorizontalDivider()
            // Default chat theme row with live mini preview.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenTheme)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.FormatListBulleted,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    "Default chat theme",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                ThemeMiniPreview(theme = selectedTheme, modifier = Modifier.size(width = 34.dp, height = 48.dp))
            }
            // Plus section heading (our brand, same layout slot as reference).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Icon(
                    Icons.Default.Diamond,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Ollacore Plus",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // App icon row with live launcher-icon preview.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showIconPicker = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Icon(
                    Icons.Default.Apps,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text("App icon", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                AppIconPreview(icon = currentIcon, modifier = Modifier.size(40.dp))
            }
            // App theme row with mode-color dot.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showThemePicker = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Icon(
                    Icons.Default.Palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text("App theme", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Canvas(modifier = Modifier.size(40.dp)) {
                    drawCircle(appModeColor(appMode))
                }
            }
            Text(
                buildAnnotatedString {
                    append("Subscribe to Ollacore Plus to change your app icon, theme and more. ")
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)) {
                        append("Explore benefits")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .clickable { showBenefits = true }
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showThemePicker) {
        com.ollacore.app.ui.settings.ThemePickerDialogHost(onDismiss = { showThemePicker = false })
    }

    if (showIconPicker) {
        AppIconDialog(
            current = currentIcon,
            onPick = { option ->
                showIconPicker = false
                scope.launch {
                    runCatching { AppIconSwitcher.apply(context, option) }
                    currentIcon = AppIconSwitcher.current(context)
                }
            },
            onDismiss = { showIconPicker = false }
        )
    }

    if (showBenefits) {
        AlertDialog(
            onDismissRequest = { showBenefits = false },
            icon = { Icon(Icons.Default.Diamond, contentDescription = null) },
            title = { Text("Ollacore Plus") },
            text = {
                Text(
                    "Plus unlocks extra app icons, exclusive themes and early features. " +
                        "Subscriptions are not available in this build yet — everything on this " +
                        "screen except the subscription itself already works."
                )
            },
            confirmButton = { TextButton(onClick = { showBenefits = false }) { Text("Got it") } }
        )
    }
}

/** Representative dot color per app theme mode. */
fun appModeColor(mode: ThemeMode): Color = when (mode) {
    ThemeMode.BLUE -> Color(0xFF3B82F6)
    ThemeMode.GREEN -> Color(0xFF10B981)
    ThemeMode.PURPLE -> Color(0xFF7C5CFF)
    ThemeMode.LIGHT -> Color(0xFFE5E7EB)
    ThemeMode.DARK -> Color(0xFF1F2937)
    ThemeMode.SYSTEM -> Color(0xFF6B7280)
}

/** Live launcher icon preview (reflects the currently enabled icon). */
@Composable
private fun AppIconPreview(icon: AppIconOption, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val drawable: Drawable? = remember(icon) {
        runCatching { context.packageManager.getApplicationIcon(context.packageName) }.getOrNull()
    }
    if (drawable != null) {
        val bitmap = remember(drawable) { drawable.toBitmap().asImageBitmap() }
        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = "Current app icon",
            modifier = modifier.clip(RoundedCornerShape(10.dp))
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Apps, contentDescription = null)
        }
    }
}
