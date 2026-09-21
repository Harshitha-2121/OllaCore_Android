package com.ollacore.app.data.util

import android.webkit.MimeTypeMap
import java.io.File
import java.security.MessageDigest

object MimeValidator {

    private val blockedTypes = setOf(
        "application/x-msdownload",
        "application/x-executable",
        "application/x-msdos-program",
        "application/x-bat",
        "application/x-sh",
        "application/x-php",
        "application/x-httpd-php",
        "text/html",
        "application/javascript",
        "application/x-javascript",
        "text/javascript",
        "application/x-shellscript",
        "application/x-perl",
        "application/x-ruby",
        "application/x-python",
        "application/x-java-archive",
        "application/x-dex",
        "application/x-sharedlib",
        "application/x-object",
        "application/x-executable-elf"
    )

    private val magicBytes = mapOf(
        "image/jpeg" to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
        "image/png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
        "image/gif" to byteArrayOf(0x47, 0x49, 0x46, 0x38),
        "image/webp" to byteArrayOf(0x52, 0x49, 0x46, 0x46), // RIFF
        "image/bmp" to byteArrayOf(0x42, 0x4D),
        "image/tiff" to byteArrayOf(0x49, 0x49, 0x2A, 0x00),
        "application/pdf" to byteArrayOf(0x25, 0x50, 0x44, 0x46),
        "application/zip" to byteArrayOf(0x50, 0x4B, 0x03, 0x04),
        "application/x-rar-compressed" to byteArrayOf(0x52, 0x61, 0x72, 0x21),
        "application/gzip" to byteArrayOf(0x1F, 0x8B.toByte()),
        "video/mp4" to null, // Requires ftyp box search
        "video/webm" to byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xFF.toByte()),
        "video/quicktime" to byteArrayOf(0x00, 0x00, 0x00, 0x14, 0x66, 0x74, 0x79, 0x70),
        "audio/mpeg" to byteArrayOf(0xFF.toByte(), 0xFB.toByte()),
        "audio/ogg" to byteArrayOf(0x4F, 0x67, 0x67, 0x53),
        "audio/wav" to byteArrayOf(0x52, 0x49, 0x46, 0x46),
        "audio/flac" to byteArrayOf(0x66, 0x4C, 0x61, 0x43),
        "application/x-msdownload" to byteArrayOf(0x4D, 0x5A), // MZ executable header
        "text/plain" to null, // No reliable magic bytes
        "text/csv" to null
    )

    // Extension-to-MIME fallback mapping
    private val extensionMimeMap = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "tiff" to "image/tiff",
        "tif" to "image/tiff",
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "rar" to "application/x-rar-compressed",
        "gz" to "application/gzip",
        "tar" to "application/x-tar",
        "mp4" to "video/mp4",
        "mov" to "video/quicktime",
        "webm" to "video/webm",
        "avi" to "video/x-msvideo",
        "mkv" to "video/x-matroska",
        "mp3" to "audio/mpeg",
        "ogg" to "audio/ogg",
        "wav" to "audio/wav",
        "flac" to "audio/flac",
        "aac" to "audio/aac",
        "txt" to "text/plain",
        "csv" to "text/csv",
        "json" to "application/json",
        "xml" to "application/xml",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    )

    // Maximum file size (50MB default)
    var maxFileSize: Long = 50L * 1024 * 1024

    fun validate(file: File, declaredMime: String): MimeValidationResult {
        // 1. Check file size
        if (!file.exists()) {
            return MimeValidationResult(false, "File does not exist")
        }
        if (file.length() == 0L) {
            return MimeValidationResult(false, "File is empty")
        }
        if (file.length() > maxFileSize) {
            return MimeValidationResult(false, "File too large: ${file.length()} bytes (max: $maxFileSize)")
        }

        // 2. Check if MIME type is blocked
        if (declaredMime in blockedTypes) {
            return MimeValidationResult(false, "File type not allowed: $declaredMime")
        }

        // 3. Extension-to-MIME validation
        val ext = file.extension.lowercase().trim()
        val extMime = extensionMimeMap[ext] ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        
        // Extension exists but doesn't match declared MIME
        if (extMime != null && extMime != declaredMime) {
            // Allow some known safe mismatches (e.g., jpg vs jpeg)
            if (!isSafeMimeMismatch(extMime, declaredMime)) {
                return MimeValidationResult(
                    false, 
                    "Extension mismatch: file=.${ext} suggests $extMime but declared $declaredMime"
                )
            }
        }

        // 4. Magic bytes validation
        val header = ByteArray(16)
        try {
            file.inputStream().use { it.read(header) }
        } catch (e: Exception) {
            return MimeValidationResult(false, "Cannot read file: ${e.message}")
        }

        // Check magic bytes if we have them for this MIME type
        val expectedMagic = magicBytes[declaredMime]
        if (expectedMagic != null) {
            if (!header.startsWith(expectedMagic)) {
                return MimeValidationResult(
                    false, 
                    "File content doesn't match $declaredMime (magic bytes: ${header.take(8).joinToString(" ") { "%02X".format(it) }})"
                )
            }
        }

        // 5. Content-based detection for common types
        val detectedMime = detectMimeType(header, ext)
        if (detectedMime != null && detectedMime != declaredMime) {
            return MimeValidationResult(
                false,
                "Content doesn't match declared type: detected=$detectedMime, declared=$declaredMime"
            )
        }

        // 6. Check for double extensions (e.g., "malware.exe.jpg")
        if (hasSuspiciousExtension(file.name)) {
            return MimeValidationResult(false, "Suspicious filename: ${file.name}")
        }

        return MimeValidationResult(true, null)
    }

    fun detectMimeType(header: ByteArray, extension: String): String? {
        // Try magic bytes first
        for ((mime, magic) in magicBytes) {
            if (magic != null && header.startsWith(magic)) {
                return mime
            }
        }

        // MP4 detection (ftyp box)
        if (header.size >= 12) {
            val ftyp = String(header, 4, 4)
            if (ftyp == "ftyp") {
                val brand = String(header, 8, 4)
                return when (brand) {
                    "isom", "mp41", "mp42", "avc1", "hev1", "mmp4" -> "video/mp4"
                    "qt  " -> "video/quicktime"
                    else -> "video/mp4"
                }
            }
        }

        // WebP detection (RIFF + WEBP)
        if (header.size >= 12) {
            val riff = String(header, 0, 4)
            val webp = String(header, 8, 4)
            if (riff == "RIFF" && webp == "WEBP") {
                return "image/webp"
            }
        }

        // Fallback to extension
        return extensionMimeMap[extension]
    }

    fun isBlockedType(mime: String): Boolean {
        return mime in blockedTypes
    }

    fun getAllowedTypes(): Set<String> {
        return magicBytes.keys + extensionMimeMap.values.toSet() - blockedTypes
    }

    private fun isSafeMimeMismatch(mime1: String, mime2: String): Boolean {
        val safeMismatches = setOf(
            setOf("image/jpeg", "image/jpg"),
            setOf("video/quicktime", "video/mp4")
        )
        return safeMismatches.any { it.containsAll(setOf(mime1, mime2)) }
    }

    private fun hasSuspiciousExtension(filename: String): Boolean {
        val suspicious = listOf(
            ".exe", ".bat", ".cmd", ".com", ".msi", ".scr", ".pif",
            ".sh", ".bash", ".csh", ".ksh", ".zsh",
            ".php", ".php3", ".php4", ".php5", ".phtml",
            ".pl", ".py", ".rb", ".js", ".vbs", ".vbe", ".wsf",
            ".jar", ".class", ".dex", ".so", ".dll", ".dylib",
            ".apk", ".app", ".command"
        )
        val lowerName = filename.lowercase()
        if (suspicious.any { lowerName.endsWith(it) }) return true
        // Double-extension attacks (script.sh.txt, virus.exe.jpg): an executable
        // segment ahead of the final extension is never legitimate.
        val segments = lowerName.split(".")
        if (segments.size > 2) {
            val bare = suspicious.map { it.removePrefix(".") }.toSet()
            if (segments.dropLast(1).drop(1).any { it in bare }) return true
        }
        return false
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (this.size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }

    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

data class MimeValidationResult(
    val isValid: Boolean,
    val error: String?
)
