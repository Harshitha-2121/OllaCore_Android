package com.ollacore.app.ui.attachments

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * Spec 12 sheet: colorful circular icons (Camera/Gallery/Document/Audio/
 * Location/Contact) + recent media strip. Contact shares name+phone as a
 * readable text card (no contact kind exists on the backend).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentPickerSheet(
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onDocument: () -> Unit,
    onAudio: () -> Unit,
    onLocation: () -> Unit,
    onContact: () -> Unit = {},
    recentMedia: List<Uri> = emptyList(),
    onPickRecent: (Uri) -> Unit = {}
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text(
                "Share",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            // Teal/green/blue icon tiles (one brand identity; white glyphs
            // keep contrast in both themes).
            AttachmentGrid(
                options = listOf(
                    GridOption(Icons.Default.PhotoCamera, "Camera", Color(0xFF00A884), onCamera),
                    GridOption(Icons.Default.PhotoLibrary, "Gallery", Color(0xFF027EB5), onGallery),
                    GridOption(Icons.Default.Description, "Document", Color(0xFF53BDEB), onDocument),
                    GridOption(Icons.Default.AudioFile, "Audio", Color(0xFF25D366), onAudio),
                    GridOption(Icons.Default.LocationOn, "Location", Color(0xFFF15C6D), onLocation),
                    GridOption(Icons.Default.Person, "Contact", Color(0xFF0D9488), onContact)
                )
            )
            // Recent media strip (device MediaStore; needs READ_MEDIA_IMAGES grant).
            if (recentMedia.isNotEmpty()) {
                Text(
                    "Recent",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    items(recentMedia, key = { it.toString() }) { uri ->
                        AsyncImage(
                            model = uri,
                            contentDescription = "Recent photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onPickRecent(uri) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
            AttachmentOption(
                icon = Icons.Default.LocationOn,
                label = "Location",
                sub = "Backend/API check required - kind=location may need server support",
                trailing = {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                        Text("check", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            ) {
                onLocation()
            }
        }
    }
}

private data class GridOption(
    val icon: ImageVector,
    val label: String,
    val color: Color,
    val onClick: () -> Unit
)

@Composable
private fun AttachmentGrid(options: List<GridOption>) {
    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
        options.chunked(3).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { option ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable(onClick = option.onClick)
                            .padding(10.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(option.color)
                        ) {
                            Icon(option.icon, contentDescription = option.label, tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(option.label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentOption(
    icon: ImageVector,
    label: String,
    sub: String,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(sub, style = MaterialTheme.typography.bodySmall) },
        leadingContent = {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        },
        trailingContent = trailing,
        modifier = Modifier.clickable(onClick = onClick)
    )
}
