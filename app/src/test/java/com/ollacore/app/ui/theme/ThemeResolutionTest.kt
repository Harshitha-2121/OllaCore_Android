package com.ollacore.app.ui.theme

import com.ollacore.app.data.local.ThemeMode
import org.junit.Assert.*
import org.junit.Test

class ThemeResolutionTest {

    @Test
    fun `dark forces dark regardless of system`() {
        assertTrue(resolveDarkTheme(ThemeMode.DARK, systemDark = false))
        assertTrue(resolveDarkTheme(ThemeMode.DARK, systemDark = true))
    }

    @Test
    fun `light forces light regardless of system`() {
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, systemDark = false))
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, systemDark = true))
    }

    @Test
    fun `system follows device`() {
        assertFalse(resolveDarkTheme(ThemeMode.SYSTEM, systemDark = false))
        assertTrue(resolveDarkTheme(ThemeMode.SYSTEM, systemDark = true))
    }

    @Test
    fun `accent modes follow device`() {
        listOf(ThemeMode.BLUE, ThemeMode.GREEN, ThemeMode.PURPLE).forEach { mode ->
            assertFalse(resolveDarkTheme(mode, systemDark = false))
            assertTrue(resolveDarkTheme(mode, systemDark = true))
        }
    }
}
