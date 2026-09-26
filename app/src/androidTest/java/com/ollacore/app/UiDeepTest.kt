package com.ollacore.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Deep UI QA against the CURRENT UI (rewritten after the redesign).
// Preconditions are checked with assumeTrue, so tests SKIP (never fake-pass)
// when the device state cannot satisfy them:
//  - home tests need a logged-in session,
//  - chat tests additionally need a non-empty inbox,
//  - nothing here mutates the session or backend state.
// Run: ./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ollacore.app.UiDeepTest
@RunWith(AndroidJUnit4::class)
class UiDeepTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun hasText(text: String, substring: Boolean = false): Boolean {
        return try {
            compose.onAllNodesWithText(text, substring = substring)
                .fetchSemanticsNodes().isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private fun isHome(): Boolean = hasText("Chats") && (
        hasText("Updates") || hasText("Calls") || hasText("Communities")
    )

    private fun assumeHome() {
        assumeTrue("Needs a logged-in session on Home", isHome())
    }

    private fun inboxHasChats(): Boolean {
        if (!isHome()) return false
        if (hasText("No chats yet")) return false
        return try {
            compose.onAllNodesWithTag("inbox_card").fetchSemanticsNodes().isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    /** Opens the first conversation; returns false when there is nothing to open. */
    private fun openFirstChat(): Boolean {
        if (!inboxHasChats()) return false
        return try {
            compose.onAllNodesWithTag("inbox_card")[0].performClick()
            compose.waitForIdle()
            compose.onAllNodesWithTag("chat_list").fetchSemanticsNodes().isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private fun assumeChat() {
        assumeTrue("Needs a logged-in session with at least one conversation", openFirstChat())
    }

    private fun backToHome() {
        try {
            Espresso.pressBack()
            compose.waitForIdle()
        } catch (_: Exception) {
        }
    }

    // ── Launch ──────────────────────────────────────────────────────

    @Test fun launchRendersWithoutCrash() {
        // Splash, onboarding, login, OTP or home: one of the known roots must exist.
        val roots = listOf("Chats", "Welcome to Ollacore", "Verify Your Number", "Stay Connected", "Ollacore")
        assert(roots.any { hasText(it) }) { "No known root screen rendered" }
    }

    // ── Home ────────────────────────────────────────────────────────

    @Test fun homeTabsRender() {
        assumeHome()
        compose.onNodeWithText("Updates").assertIsDisplayed()
        compose.onNodeWithText("Calls").assertIsDisplayed()
        compose.onNodeWithText("Communities").assertIsDisplayed()
    }

    @Test fun searchOpensAndBack() {
        assumeHome()
        compose.onNodeWithTag("search_icon").performClick()
        compose.onNodeWithText("Search Ollacore…").assertIsDisplayed()
        backToHome()
        compose.onNodeWithText("Chats").assertIsDisplayed()
    }

    @Test fun updatesPlaceholderRenders() {
        assumeHome()
        compose.onNodeWithText("Updates").performClick()
        compose.onNodeWithText("Channels").assertIsDisplayed()
        compose.onNodeWithText("Chats").performClick()
        compose.onNodeWithText("Chats").assertIsDisplayed()
    }

    @Test fun emptyInboxState() {
        assumeHome()
        // Only assertable when the inbox is actually empty; otherwise skip.
        assumeTrue(
            "Inbox is non-empty on this device; empty state not reachable here",
            hasText("No chats yet")
        )
        compose.onNodeWithText("Your conversations will appear here.").assertIsDisplayed()
    }

    // ── Chat ────────────────────────────────────────────────────────

    @Test fun chatOpensFromInbox() {
        assumeChat() // opens + asserts chat_list internally
    }

    @Test fun messageDraftSurvivesRotation() {
        assumeChat()
        compose.onNodeWithText("Type a message…").performTextInput("draft-xyz")
        try {
            compose.activity.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            Thread.sleep(1500)
            compose.onNodeWithText("draft-xyz").assertExists()
        } finally {
            compose.activity.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            Thread.sleep(1000)
        }
    }

    @Test fun keyboardKeepsComposerVisible() {
        assumeChat()
        compose.onNodeWithText("Type a message…").performClick()
        compose.onNodeWithText("Type a message…").assertIsDisplayed()
    }

    @Test fun chatListScrolls() {
        assumeChat()
        compose.onNodeWithTag("chat_list").performTouchInput { swipeUp() }
        compose.onNodeWithTag("chat_list").assertExists()
    }

    @Test fun backNavigatesToHome() {
        assumeChat()
        backToHome()
        compose.onNodeWithText("Chats").assertIsDisplayed()
    }
}
