package com.ollacore.app.ui.docs

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

/**
 * Spec 27: document preview - back/filename/download/share/more on top,
 * rounded preview container, download/share/more actions at the bottom.
 * PDFs render in-app (framework PdfRenderer); other types show file info
 * with external open + share via FileProvider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentViewerScreen(
    downloadUrl: String,
    filename: String,
    mime: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var localFile by remember(downloadUrl) { mutableStateOf<File?>(null) }
    var downloading by remember(downloadUrl) { mutableStateOf(true) }
    var downloadError by remember(downloadUrl) { mutableStateOf<String?>(null) }
    var showInfo by remember { mutableStateOf(false) }

    LaunchedEffect(downloadUrl, filename) {
        downloading = true
        downloadError = null
        localFile = withContext(Dispatchers.IO) {
            runCatching { downloadToCache(context, downloadUrl, filename) }.getOrElse { null }
        }
        if (localFile == null) downloadError = "Could not download the file."
        downloading = false
    }

    fun shareFile() {
        val file = localFile ?: return
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime.ifBlank { "application/octet-stream" }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share document"))
        }
    }

    fun openExternal() {
        val file = localFile
        runCatching {
            if (file != null) {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime.ifBlank { "application/octet-stream" })
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } else {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(filename.ifBlank { "Document" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { openExternal() }) {
                        Icon(Icons.Default.Download, contentDescription = "Download / open externally")
                    }
                    IconButton(onClick = { shareFile() }, enabled = localFile != null) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = { showInfo = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar {
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = { openExternal() }) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Download")
                }
                TextButton(onClick = { shareFile() }, enabled = localFile != null) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share")
                }
                TextButton(onClick = { showInfo = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("More")
                }
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when {
                downloading -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Downloading…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                localFile == null -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Text("Preview unavailable", style = MaterialTheme.typography.titleMedium)
                        Text(
                            downloadError ?: "The file could not be loaded.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = { openExternal() }) { Text("Try external viewer") }
                    }
                }
                isPdf(filename, mime) -> {
                    PdfPages(file = localFile!!, modifier = Modifier.fillMaxSize())
                }
                else -> {
                    NonPdfPreview(
                        file = localFile!!,
                        mime = mime,
                        onOpenExternal = { openExternal() }
                    )
                }
            }
        }
    }

    if (showInfo) {
        val file = localFile
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("File details") },
            text = {
                Column {
                    Text("Name: ${filename.ifBlank { "document" }}")
                    Text("Type: ${mime.ifBlank { "unknown" }}")
                    if (file != null) Text("Size: ${formatBytes(file.length())}")
                }
            },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Close") } }
        )
    }
}

private fun isPdf(filename: String, mime: String): Boolean {
    return mime.equals("application/pdf", ignoreCase = true) || filename.endsWith(".pdf", ignoreCase = true)
}

private suspend fun downloadToCache(context: Context, url: String, filename: String): File {
    val safe = filename.ifBlank { "doc_${System.currentTimeMillis()}" }
        .replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
    val out = File(context.cacheDir, "preview_${System.currentTimeMillis()}_$safe")
    URL(url).openConnection().apply {
        connectTimeout = 30_000
        readTimeout = 60_000
    }.getInputStream().use { input ->
        FileOutputStream(out).use { output -> input.copyTo(output) }
    }
    return out
}

@Composable
private fun NonPdfPreview(file: File, mime: String, onOpenExternal: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(16.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(32.dp)
    ) {
        Icon(
            Icons.Default.Description,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(file.name, style = MaterialTheme.typography.titleSmall)
        Text(mime.ifBlank { "Unknown type" }, style = MaterialTheme.typography.bodySmall)
        Text(formatBytes(file.length()), style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onOpenExternal) { Text("Open") }
    }
}

@Composable
private fun PdfPages(file: File, modifier: Modifier = Modifier) {
    var pageCount by remember(file) { mutableStateOf(0) }
    var renderer by remember(file) { mutableStateOf<PdfRenderer?>(null) }
    var pfd by remember(file) { mutableStateOf<ParcelFileDescriptor?>(null) }

    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            runCatching {
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pfd = descriptor
                val r = PdfRenderer(descriptor)
                renderer = r
                pageCount = r.pageCount.coerceAtMost(50)
            }
        }
    }
    DisposableEffect(file) {
        onDispose {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
        }
    }

    if (pageCount == 0) {
        CircularProgressIndicator()
    } else {
        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(pageCount) { index ->
                PdfPage(renderer = renderer, index = index)
            }
        }
    }
}

@Composable
private fun PdfPage(renderer: PdfRenderer?, index: Int) {
    var bitmap by remember(renderer, index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(renderer, index) {
        withContext(Dispatchers.IO) {
            runCatching {
                val r = renderer ?: return@runCatching
                synchronized(r) {
                    r.openPage(index).use { page ->
                        val scale = 2f
                        val bmp = Bitmap.createBitmap(
                            (page.width * scale).toInt(),
                            (page.height * scale).toInt(),
                            Bitmap.Config.ARGB_8888
                        )
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap = bmp
                    }
                }
            }
        }
    }
    Card(
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            )
        } else {
            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    return String.format("%.1f MB", kb / 1024.0)
}
