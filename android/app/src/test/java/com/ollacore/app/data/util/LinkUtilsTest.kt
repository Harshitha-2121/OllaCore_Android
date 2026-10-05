package com.ollacore.app.data.util

import org.junit.Assert.*
import org.junit.Test

class LinkUtilsTest {

    @Test
    fun `finds https url with surrounding text`() {
        assertEquals(
            "https://example.com",
            firstUrl("check\nhttps://example.com link-preview\n")
        )
    }

    @Test
    fun `finds www url and upgrades to https`() {
        assertEquals("https://www.example.com/x", firstUrl("see www.example.com/x today"))
    }

    @Test
    fun `ignores trailing punctuation`() {
        assertEquals("https://example.com", firstUrl("open https://example.com."))
        assertEquals("https://example.com/a", firstUrl("(https://example.com/a)"))
    }

    @Test
    fun `returns null without url`() {
        assertNull(firstUrl("just some words"))
        assertNull(firstUrl(""))
    }

    @Test
    fun `allUrls reports ranges and urls`() {
        val hits = allUrls("a https://one.test/x b www.two.test/y.")
        assertEquals(2, hits.size)
        assertEquals("https://one.test/x", hits[0].second)
        assertEquals("https://www.two.test/y", hits[1].second)
        hits.forEach { (range, url) ->
            assertTrue(range.first >= 0)
            assertTrue(range.last >= range.first)
            assertTrue(url.isNotBlank())
        }
    }

    @Test
    fun `linkHost strips www`() {
        assertEquals("example.com", linkHost("https://www.example.com/a?b=1"))
        assertEquals("example.com", linkHost("https://example.com"))
    }
}
