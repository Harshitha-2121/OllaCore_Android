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
}
