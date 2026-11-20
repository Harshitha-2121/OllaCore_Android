package com.ollacore.app.ui.settings

import java.io.File
import java.io.IOException

/** Blocking file operations; callers must use the IO dispatcher. */
internal fun settingsCacheSize(directory: File): Long {
    if (!directory.exists()) return 0L
    if (directory.isFile) return directory.length()
    val children = directory.listFiles() ?: throw IOException("Cannot read cache directory")
    return children.sumOf(::settingsCacheSize)
}

/** Preserve the cache root and fail rather than reporting a partial deletion as success. */
internal fun clearSettingsCache(directory: File) {
    if (!directory.exists()) return
    val children = directory.listFiles() ?: throw IOException("Cannot read cache directory")
    var complete = true
    children.forEach { if (!it.deleteRecursively()) complete = false }
    if (!complete) throw IOException("Some cache files could not be removed")
}
