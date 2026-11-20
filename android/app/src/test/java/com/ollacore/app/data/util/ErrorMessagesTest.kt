package com.ollacore.app.data.util

import org.junit.Assert.*
import org.junit.Test

class ErrorMessagesTest {

    @Test
    fun `blank maps to generic`() {
        assertEquals("Something went wrong.", friendlyError(null))
        assertEquals("Something went wrong.", friendlyError(""))
        assertEquals("Something went wrong.", friendlyError("   "))
    }

    @Test
    fun `classic network failures map to connection copy`() {
        listOf(
            "Unable to resolve host \"api.ollacore.com\": No address associated with hostname",
            "Network is unreachable",
            "Connection refused",
            "Read timed out",
            "failed to connect to /93.184.216.34:443"
        ).forEach {
            assertTrue(isNetworkError(it))
            assertEquals("Please check your connection and try again.", friendlyError(it))
        }
    }

    @Test
    fun `connect failure hidden behind long message no longer falls through`() {
        val raw = "java.net.ConnectException: Failed to connect to api.ollacore.com/93.184.216.34:443 " +
            "from /10.0.2.16:51234 after 30000ms (connection attempt 1 of 3 with backoff)"
        assertTrue(raw.length > 140)
        assertEquals("Please check your connection and try again.", friendlyError(raw))
    }

    @Test
    fun `ssl failure adds date-time hint`() {
        val raw = "javax.net.ssl.SSLHandshakeException: Chain validation failed for api.ollacore.com " +
            "issued by an authority that is not trusted on this device"
        assertTrue(isNetworkError(raw))
        assertTrue(friendlyError(raw).contains("date and time"))
    }

    @Test
    fun `auth failures map to login copy and flag`() {
        listOf("HTTP 401", "unauthorized: bad token", "session expired", "token revoked").forEach {
            assertTrue(isAuthError(it))
            assertEquals("Your session expired. Please log in again.", friendlyError(it))
        }
        assertFalse(isAuthError("Please check your connection and try again."))
    }

    @Test
    fun `room scoped failures are not session expired`() {
        // Room-token exchange + 403 room-access denials must offer Retry,
        // never "Log in again" (header loads, so the session itself is valid).
        listOf(
            "Failed to get room token",
            "HTTP 403 forbidden",
            "HTTP 403 room access denied",
            "room token exchange failed",
            "token"
        ).forEach {
            assertFalse("should not be auth: $it", isAuthError(it))
            assertFalse(friendlyError(it) == "Your session expired. Please log in again.")
        }
    }

    @Test
    fun `server gaps map honestly`() {
        assertEquals(
            "This is not available yet on the server.",
            friendlyError("HTTP 404 no such route")
        )
        assertEquals(
            "Too many tries. Please wait a moment.",
            friendlyError("HTTP 429 retry after 60s")
        )
    }

    @Test
    fun `long unknown errors stay generic, short ones pass through`() {
        val long = "x".repeat(200)
        assertEquals("Something went wrong. Please try again.", friendlyError(long))
        assertEquals("Room is gone", friendlyError("Room is gone"))
    }

    // BUG-07: endpoint/file/line hints from the edit-profile dialog (and any
    // other internal trace) must never be shown to end users.
    @Test
    fun `internal api and exception hints never reach the user`() {
        listOf(
            "API: PATCH /v1/directory/me (display_name, about, avatar_url) -> OllacoreApi.kt:84",
            "OllacoreApi.kt:84 updateProfile failed",
            "java.lang.IllegalStateException: boom"
        ).forEach { raw ->
            val shown = friendlyError(raw)
            assertFalse("leaked API hint: $shown", shown.contains("OllacoreApi.kt"))
            assertFalse("leaked endpoint path: $shown", shown.contains("/v1/directory"))
            assertFalse("leaked exception: $shown", shown.contains("Exception"))
            assertTrue("should be generic copy: $shown", shown.startsWith("Something went wrong"))
        }
    }
}
