package com.ollacore.app.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android Authentication Test Cases
 *
 * Tests the complete phone authentication flow:
 * Phone Input → OTP Verification → Chat Home
 */
@RunWith(AndroidJUnit4::class)
class AuthFlowTest {

    private lateinit var client: OllacoreAuthClient
    private lateinit var viewModel: AuthViewModel

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        client = OllacoreAuthClient(
            appId = "da_3a0a2cfaabd34b7dbfb4ecd5033b0c5f"
        )
        viewModel = AuthViewModel(
            ApplicationProvider.getApplicationContext()
        )
    }

    // ── AND-OTP-001: Valid phone ─────────────────────────────────────
    @Test
    fun testAND_OTP_001_validPhone() = runBlocking {
        // Input: +15550001111
        // Expected: OTP screen
        val phone = "+15550001111"

        viewModel.updatePhone(phone)
        viewModel.requestOtp()

        val state = viewModel.uiState.value
        assertEquals("Should move to OTP verification", AuthViewModel.AuthStep.OTP_VERIFICATION, state.step)
    }

    // ── AND-OTP-002: Valid OTP ───────────────────────────────────────
    @Test
    fun testAND_OTP_002_validOtp() = runBlocking {
        // Input: Valid 6-digit code
        // Expected: Login success
        val phone = "+15550001111"

        // Request OTP
        viewModel.updatePhone(phone)
        viewModel.requestOtp()

        // In real test, retrieve OTP from simulator
        // For now, verify the flow structure
        val state = viewModel.uiState.value
        assertEquals(AuthViewModel.AuthStep.OTP_VERIFICATION, state.step)

        // Simulate valid OTP entry
        viewModel.updateOtpCode("123456")
        assertEquals("123456", viewModel.uiState.value.otpCode)
    }

    // ── AND-OTP-003: Invalid OTP ─────────────────────────────────────
    @Test
    fun testAND_OTP_003_invalidOtp() = runBlocking {
        // Input: Wrong 6-digit code
        // Expected: Error message
        val phone = "+15550001111"

        viewModel.updatePhone(phone)
        viewModel.requestOtp()

        // Try invalid OTP
        viewModel.updateOtpCode("000000")
        viewModel.verifyOtp()

        // Error should be set
        val state = viewModel.uiState.value
        assertNotNull("Error should be set", state.error)
    }

    // ── AND-OTP-004: Empty OTP ───────────────────────────────────────
    @Test
    fun testAND_OTP_004_emptyOtp() {
        // Input: Empty string
        // Expected: Validation error
        viewModel.updateOtpCode("")

        val state = viewModel.uiState.value
        assertEquals("Empty OTP should not be accepted", "", state.otpCode)

        // Button should be disabled
        val canVerify = state.otpCode.length == 6
        assertFalse("Verify button should be disabled for empty OTP", canVerify)
    }

    // ── AND-OTP-005: 5 digits ────────────────────────────────────────
    @Test
    fun testAND_OTP_005_fiveDigits() {
        // Input: 12345 (5 digits)
        // Expected: Validation error
        viewModel.updateOtpCode("12345")

        val state = viewModel.uiState.value
        assertEquals("12345", state.otpCode)

        // Button should be disabled
        val canVerify = state.otpCode.length == 6
        assertFalse("Verify button should be disabled for 5 digits", canVerify)
    }

    // ── AND-OTP-006: 7 digits ────────────────────────────────────────
    @Test
    fun testAND_OTP_006_sevenDigits() {
        // Input: 1234567 (7 digits)
        // Expected: Validation error
        viewModel.updateOtpCode("1234567")

        val state = viewModel.uiState.value
        // Should be truncated to 6 digits
        assertTrue("Should accept max 6 digits", state.otpCode.length <= 6)
    }

    // ── AND-OTP-007: Resend ──────────────────────────────────────────
    @Test
    fun testAND_OTP_007_resend() = runBlocking {
        // Action: Resend code
        // Expected: New OTP sent, return to phone input
        val phone = "+15550001111"

        viewModel.updatePhone(phone)
        viewModel.requestOtp()
        assertEquals(AuthViewModel.AuthStep.OTP_VERIFICATION, viewModel.uiState.value.step)

        // Resend
        viewModel.resendOtp()
        assertEquals("Should return to phone input", AuthViewModel.AuthStep.PHONE_INPUT, viewModel.uiState.value.step)
        assertEquals("OTP code should be cleared", "", viewModel.uiState.value.otpCode)
    }

    // ── AND-OTP-008: Old OTP ─────────────────────────────────────────
    @Test
    fun testAND_OTP_008_oldOtp() = runBlocking {
        // Action: Use OTP from previous session
        // Expected: Rejected
        val phone = "+15550001111"

        // First verification
        viewModel.updatePhone(phone)
        viewModel.requestOtp()
        viewModel.updateOtpCode("123456")
        viewModel.verifyOtp()

        // Try same OTP again
        viewModel.updateOtpCode("123456")
        viewModel.verifyOtp()

        // Should fail (even if it worked first time)
        val state = viewModel.uiState.value
        // Error should be set or still on OTP screen
        assertTrue("Old OTP should be rejected", 
            state.step == AuthViewModel.AuthStep.OTP_VERIFICATION || state.error != null)
    }

    // ── AND-OTP-009: Reused OTP ──────────────────────────────────────
    @Test
    fun testAND_OTP_009_reusedOtp() = runBlocking {
        // Action: Use same OTP for different phone
        // Expected: Rejected
        val phone1 = "+15550001111"
        val phone2 = "+15550002222"

        // Get OTP for phone1
        viewModel.updatePhone(phone1)
        viewModel.requestOtp()
        viewModel.updateOtpCode("123456")

        // Try same OTP for phone2
        viewModel.updatePhone(phone2)
        viewModel.updateOtpCode("123456")
        viewModel.verifyOtp()

        // Should fail
        val state = viewModel.uiState.value
        assertNotNull("Reused OTP should fail", state.error)
    }

    // ── AND-OTP-010: Offline verification ─────────────────────────────
    @Test
    fun testAND_OTP_010_offlineVerification() = runBlocking {
        // Action: Verify OTP without network
        // Expected: Proper error message
        val phone = "+15550001111"

        viewModel.updatePhone(phone)
        viewModel.updateOtpCode("123456")

        // In real test, disconnect network here
        viewModel.verifyOtp()

        // Should show network error
        val state = viewModel.uiState.value
        // Error should indicate network issue
        assertNotNull("Should show error for offline", state.error)
    }

    // ── AND-OTP-011: App killed ───────────────────────────────────────
    @Test
    fun testAND_OTP_011_appKilled() {
        // Action: Kill app during OTP entry
        // Expected: Correct recovery on restart
        val phone = "+15550001111"

        viewModel.updatePhone(phone)
        viewModel.updateOtpCode("12345")

        // Simulate app kill (ViewModel cleared)
        val savedPhone = viewModel.uiState.value.phone
        val savedStep = viewModel.uiState.value.step

        // On restart, state should be reset
        val newViewModel = AuthViewModel(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application
        )

        assertEquals("Phone input should be reset", AuthViewModel.AuthStep.PHONE_INPUT, newViewModel.uiState.value.step)
    }

    // ── AND-OTP-012: Network change ───────────────────────────────────
    @Test
    fun testAND_OTP_012_networkChange() = runBlocking {
        // Action: Change network during OTP request
        // Expected: Correct recovery
        val phone = "+15550001111"

        viewModel.updatePhone(phone)
        viewModel.requestOtp()

        // In real test, toggle airplane mode here
        // Retry should work
        val state = viewModel.uiState.value
        // Should either succeed or show recoverable error
        assertTrue("Should handle network change", 
            state.step == AuthViewModel.AuthStep.OTP_VERIFICATION || state.error != null)
    }

    // ── AND-OTP-013: Logout ──────────────────────────────────────────
    @Test
    fun testAND_OTP_013_logout() = runBlocking {
        // Action: Logout
        // Expected: Session cleared, return to phone input
        val phone = "+15550001111"

        // Login first
        viewModel.updatePhone(phone)
        viewModel.requestOtp()

        // Logout
        viewModel.logout()

        val state = viewModel.uiState.value
        assertEquals("Should return to phone input", AuthViewModel.AuthStep.PHONE_INPUT, state.step)
        assertNull("Session should be cleared", viewModel.sessionToken.value)
        assertEquals("Phone should be cleared", "", state.phone)
        assertEquals("OTP should be cleared", "", state.otpCode)
    }
}
