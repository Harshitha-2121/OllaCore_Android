package com.ollacore.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ollacore.app.ui.theme.OllacoreTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsHomeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun categoriesHaveApprovedOrderAndNoUnsupportedActions() {
        compose.setContent {
            OllacoreTheme {
                SettingsHome({}, {}, {}, { _ -> }, displayName = "Test User", about = "Available")
            }
        }
        val titles = listOf("Account", "Privacy", "Chats", "Notifications", "Storage and data", "Help and support", "Invite a friend")
        val positions = titles.map { title ->
            compose.onNodeWithText(title).fetchSemanticsNode().positionInRoot.y
        }
        assertTrue(positions.zipWithNext().all { (a, b) -> a < b })
        compose.onNodeWithText("Avatar").assertDoesNotExist()
        compose.onNodeWithContentDescription("QR code").assertDoesNotExist()
        compose.onNodeWithText("Invite a friend").performScrollTo().assertIsDisplayed()
    }

    @Test fun profileAndDestinationCallbacksUseSuppliedActions() {
        var profile = 0
        var privacy = 0
        var notifications = 0
        val opened = mutableListOf<String>()
        compose.setContent {
            OllacoreTheme {
                SettingsHome({ profile++ }, { privacy++ }, { notifications++ },
                    { id -> opened += id }, displayName = "Test User", about = "Available")
            }
        }
        compose.onNodeWithTag("settings_profile").performClick()
        compose.onNodeWithText("Privacy").performScrollTo().performClick()
        compose.onNodeWithText("Notifications").performScrollTo().performClick()
        listOf("Account", "Chats", "Storage and data", "Help and support").forEach {
            compose.onNodeWithText(it).performScrollTo().performClick()
        }
        compose.runOnIdle {
            assertEquals(1, profile)
            assertEquals(1, privacy)
            assertEquals(1, notifications)
            assertEquals(listOf("account", "chats", "storage", "help"), opened)
        }
    }

    @Test fun missingProfileHasHonestFallback() {
        compose.setContent {
            OllacoreTheme { SettingsProfileHeader(null, " ", null, false, null, {}) }
        }
        compose.onNodeWithText("Profile").assertIsDisplayed()
        compose.onNodeWithText("Add an About in your profile").assertIsDisplayed()
        compose.onNodeWithTag("settings_profile").assertHasClickAction()
    }

    @Test fun loadingProfileIsExplicit() {
        compose.setContent {
            OllacoreTheme { SettingsProfileHeader(null, null, null, true, null, {}) }
        }
        compose.onNodeWithText("Loading profile…").assertIsDisplayed()
    }

    @Test fun smallViewportAndLargeFontCanReachLastRowInDarkTheme() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                OllacoreTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp).height(480.dp)) {
                        SettingsHome({}, {}, {}, { _ -> },
                            displayName = "A long display name that wraps", about = "Available")
                    }
                }
            }
        }
        compose.onNodeWithText("Invite a friend").performScrollTo().assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Account").performScrollTo().assertIsDisplayed()
    }

    @Test fun accountPageSurvivesRestorationAndToolbarBackReturnsHome() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            OllacoreTheme { SettingsRoot({}, {}, {}, {}, {}) }
        }
        compose.onNodeWithText("Account").performClick()
        compose.onNodeWithText("Linked devices").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Linked devices").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("settings_profile").assertIsDisplayed()
    }
}
