package com.ollacore.app.ui.contacts

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID

/**
 * Copy a picked contact photo into internal storage so it survives app
 * restarts (content URIs from pickers are not reliably re-resolvable later).
 * Returns the absolute file path, or null on failure (caller keeps old photo).
 */
fun copyContactPhoto(context: Context, uri: Uri): String? {
    return try {
        val dir = File(context.filesDir, "contact_photos").apply { mkdirs() }
        val target = File(dir, "${UUID.randomUUID()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        if (target.length() == 0L) {
            target.delete()
            return null
        }
        target.absolutePath
    } catch (_: Exception) {
        null
    }
}

/** Best-effort cleanup of a replaced/deleted contact photo file. */
fun deleteContactPhoto(path: String?) {
    if (path.isNullOrBlank()) return
    try {
        val file = File(path)
        if (file.exists()) file.delete()
    } catch (_: Exception) {
    }
}
