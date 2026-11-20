package com.ollacore.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class MessageContextMenuTest {

    @Test
    fun `own text message shows full menu`() {
        assertEquals(
            listOf(
                MenuActionId.REPLY,
                MenuActionId.FORWARD,
                MenuActionId.COPY,
                MenuActionId.INFO,
                MenuActionId.STAR,
                MenuActionId.PIN,
                MenuActionId.DELETE,
                MenuActionId.MORE
            ),
            menuActionsFor(isOwn = true, hasText = true, isDeleted = false)
        )
    }

    @Test
    fun `peer media without caption hides copy and delete`() {
        assertEquals(
            listOf(
                MenuActionId.REPLY,
                MenuActionId.FORWARD,
                MenuActionId.INFO,
                MenuActionId.STAR,
                MenuActionId.PIN,
                MenuActionId.MORE
            ),
            menuActionsFor(isOwn = false, hasText = false, isDeleted = false)
        )
    }

    @Test
    fun `deleted messages expose info only`() {
        assertEquals(
            listOf(MenuActionId.INFO),
            menuActionsFor(isOwn = true, hasText = true, isDeleted = true)
        )
        assertEquals(
            listOf(MenuActionId.INFO),
            menuActionsFor(isOwn = false, hasText = false, isDeleted = true)
        )
    }

    @Test
    fun `more overflow holds select and own-text edit`() {
        assertEquals(
            listOf(MoreActionId.SELECT, MoreActionId.EDIT),
            moreActionsFor(isOwn = true, isText = true)
        )
        assertEquals(
            listOf(MoreActionId.SELECT),
            moreActionsFor(isOwn = false, isText = true)
        )
        assertEquals(
            listOf(MoreActionId.SELECT),
            moreActionsFor(isOwn = true, isText = false)
        )
    }

    @Test
    fun `reaction set matches reference`() {
        assertEquals(
            listOf("👍", "❤️", "😂", "😮", "😢", "🙏", "🥹"),
            CONTEXT_REACTIONS
        )
    }

    // BUG-01: every way of leaving the overlay must actually dismiss it.
    @Test
    fun `picking an action dismisses the overlay`() {
        assertTrue(dismissMenuOn(MenuSurfaceEvent.ACTION_PICKED))
    }

    @Test
    fun `scrim tap dismisses the overlay`() {
        assertTrue(dismissMenuOn(MenuSurfaceEvent.SCRIM_TAPPED))
    }

    @Test
    fun `back press dismisses the overlay`() {
        assertTrue(dismissMenuOn(MenuSurfaceEvent.BACK_PRESSED))
    }

    @Test
    fun `collapsing the reaction row via toggle dismisses the overlay`() {
        assertTrue(dismissMenuOn(MenuSurfaceEvent.REACTION_COLLAPSED))
    }

    @Test
    fun `expanding the reaction row keeps the overlay open`() {
        assertFalse(dismissMenuOn(MenuSurfaceEvent.REACTION_EXPANDED))
    }

    @Test
    fun `opening info keeps the overlay open`() {
        assertFalse(dismissMenuOn(MenuSurfaceEvent.INFO_OPENED))
    }

    @Test
    fun `dismissal decision table classifies every event explicitly`() {
        val expected = mapOf(
            MenuSurfaceEvent.ACTION_PICKED to true,
            MenuSurfaceEvent.REACTION_EXPANDED to false,
            MenuSurfaceEvent.REACTION_COLLAPSED to true,
            MenuSurfaceEvent.INFO_OPENED to false,
            MenuSurfaceEvent.SCRIM_TAPPED to true,
            MenuSurfaceEvent.BACK_PRESSED to true
        )
        assertEquals(MenuSurfaceEvent.entries.toSet(), expected.keys)
        expected.forEach { (event, dismiss) ->
            assertEquals("event=$event", dismiss, dismissMenuOn(event))
        }
    }
}
