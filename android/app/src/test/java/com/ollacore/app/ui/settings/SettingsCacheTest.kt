package com.ollacore.app.ui.settings

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class SettingsCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun measuresNestedFiles() {
        val root = temporary.newFolder("cache")
        File(root, "preview").writeBytes(ByteArray(12))
        val nested = File(root, "images").apply { mkdir() }
        File(nested, "photo").writeBytes(ByteArray(30))
        assertEquals(42L, settingsCacheSize(root))
    }

    @Test fun clearsOnlyCacheChildrenAndPreservesRoot() {
        val cache = temporary.newFolder("cache")
        File(cache, "preview").writeText("temporary")
        File(cache, "nested").apply { mkdir() }.resolve("image").writeText("temporary")
        val database = temporary.newFile("messages.db").apply { writeText("history") }
        clearSettingsCache(cache)
        assertTrue(cache.isDirectory)
        assertEquals(0L, settingsCacheSize(cache))
        assertEquals("history", database.readText())
    }

    @Test fun missingCacheIsEmpty() {
        val missing = File(temporary.root, "missing")
        assertEquals(0L, settingsCacheSize(missing))
        clearSettingsCache(missing)
        assertFalse(missing.exists())
    }

    @Test(expected = IOException::class)
    fun invalidCacheRootDoesNotReportSuccess() {
        clearSettingsCache(temporary.newFile("not-a-directory"))
    }
}
