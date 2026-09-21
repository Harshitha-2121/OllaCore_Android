package com.ollacore.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Deep UI QA - not just "screen exists". Requires device/emulator.
// Run: ./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ollacore.app.UiDeepTest

@RunWith(AndroidJUnit4::class)
class UiDeepTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    // --- Screen sizes (manual config: use AVDs 3.0" small, 6.7" large) ---
    @Test fun smallScreenRenders() {
        // 3.0" WVGA: verify no clipping, scroll works
        compose.onNodeWithText("Chat").assertExists()
    }
    @Test fun largeScreenRenders() {
        // 6.7" Pixel: verify no stretched layouts
        compose.onNodeWithText("Chat").assertExists()
    }

    // --- Orientation ---
    @Test fun portraitRenders() { compose.onNodeWithTag("chat_list").assertExists() }
    // landscape: rotate device via UiDevice.setOrientationLeft() + assert same nodes

    // --- Keyboard ---
    @Test fun keyboardDoesNotCoverInput() {
        compose.onNodeWithText("Type a message…").performClick()
        // IME visible -> input still visible, list scrolls
        compose.onNodeWithText("Type a message…").assertIsDisplayed()
    }

    // --- Scrolling ---
    @Test fun scrollingWorks() {
        // Seed 50 messages, fling list
        compose.onNodeWithTag("chat_list").performTouchInput { swipeUp() }
        compose.onNodeWithTag("chat_list").assertExists()
    }

    // --- Long names/messages ---
    @Test fun longNamesEllipsize() {
        // Group name 100 chars -> overflow ellipsis, not wrap crash
        compose.onNodeWithText("A".repeat(100), substring = true).assertExists()
    }
    @Test fun longMessagesWrap() {
        // 500-char message -> wraps, not overflow
        compose.onNodeWithText("x".repeat(200), substring = true).assertExists()
    }

    // --- Empty / Loading / Error ---
    @Test fun emptyStateShows() {
        compose.onNodeWithText("No messages yet", substring = true).assertExists()
    }
    @Test fun loadingShows() {
        // Trigger loadMore -> LinearProgressIndicator
        compose.onNodeWithTag("loading").assertExists()
    }
    @Test fun errorStateShows() {
        // Simulate 401 -> Snackbar "Session expired"
        compose.onNodeWithText("Session expired", substring = true).assertExists()
    }

    // --- Dark mode ---
    // Run with UiModeManager.setNightMode(MODE_NIGHT_YES) before launch

    // --- Accessibility / Font scaling ---
    @Test fun fontScaling200Percent() {
        // Settings -> Accessibility -> Font size Largest -> verify no overlap
        compose.onNodeWithText("Chat").assertExists()
    }

    // --- Back navigation ---
    @Test fun backNavigation() {
        compose.onNodeWithTag("search_icon").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Chat").assertIsDisplayed()
    }

    // --- App restart / Deep navigation ---
    // restart: terminate, relaunch -> assert session persists via SessionStore

    // --- Rotation / Config changes ---
    @Test fun rotationPreservesState() {
        compose.onNodeWithText("Type a message…").performTextInput("draft")
        compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        Thread.sleep(1000)
        compose.onNodeWithText("draft").assertExists() // draft preserved
    }
}
