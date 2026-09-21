# Android Authentication Test Cases

## Test Summary

| ID | Test | Expected | Status |
|----|------|----------|--------|
| AND-OTP-001 | Valid phone | OTP screen | PASS |
| AND-OTP-002 | Valid OTP | Login | PASS |
| AND-OTP-003 | Invalid OTP | Error | PASS |
| AND-OTP-004 | Empty OTP | Validation | PASS |
| AND-OTP-005 | 5 digits | Validation | PASS |
| AND-OTP-006 | 7 digits | Validation | PASS |
| AND-OTP-007 | Resend | New OTP | PASS |
| AND-OTP-008 | Old OTP | Rejected | PASS |
| AND-OTP-009 | Reused OTP | Rejected | PASS |
| AND-OTP-010 | Offline verification | Proper error | PASS |
| AND-OTP-011 | App killed | Correct recovery | PASS |
| AND-OTP-012 | Network change | Correct recovery | PASS |
| AND-OTP-013 | Logout | Session cleared | PASS |

## Test Details

### AND-OTP-001: Valid Phone
- **Input:** +15550001111
- **Action:** Tap Continue
- **Expected:** Move to OTP verification screen
- **Result:** PASS

### AND-OTP-002: Valid OTP
- **Input:** Correct 6-digit code
- **Action:** Tap Verify
- **Expected:** Login success, navigate to Chat Home
- **Result:** PASS

### AND-OTP-003: Invalid OTP
- **Input:** Wrong 6-digit code (e.g., 000000)
- **Action:** Tap Verify
- **Expected:** Error message "Invalid code"
- **Result:** PASS

### AND-OTP-004: Empty OTP
- **Input:** Empty string
- **Action:** Try to verify
- **Expected:** Verify button disabled, validation error
- **Result:** PASS

### AND-OTP-005: 5 Digits
- **Input:** 12345 (5 digits)
- **Action:** Try to verify
- **Expected:** Verify button disabled
- **Result:** PASS

### AND-OTP-006: 7 Digits
- **Input:** 1234567 (7 digits)
- **Action:** Enter code
- **Expected:** Truncated to 6 digits
- **Result:** PASS

### AND-OTP-007: Resend
- **Action:** Tap "Resend code"
- **Expected:** Return to phone input, new OTP sent
- **Result:** PASS

### AND-OTP-008: Old OTP
- **Input:** OTP from previous session
- **Action:** Try to verify
- **Expected:** Rejected
- **Result:** PASS

### AND-OTP-009: Reused OTP
- **Input:** Same OTP for different phone
- **Action:** Try to verify
- **Expected:** Rejected
- **Result:** PASS

### AND-OTP-010: Offline Verification
- **Action:** Verify OTP without network
- **Expected:** Network error message
- **Result:** PASS

### AND-OTP-011: App Killed
- **Action:** Kill app during OTP entry
- **Expected:** State reset on restart
- **Result:** PASS

### AND-OTP-012: Network Change
- **Action:** Toggle network during OTP request
- **Expected:** Retry works correctly
- **Result:** PASS

### AND-OTP-013: Logout
- **Action:** Tap Logout
- **Expected:** Session cleared, return to phone input
- **Result:** PASS

## Test File

```
android/app/src/test/java/com/ollacore/app/auth/AuthFlowTest.kt
```

## Running Tests

```bash
# Run all auth tests
./gradlew test --tests "com.ollacore.app.auth.AuthFlowTest"

# Run specific test
./gradlew test --tests "com.ollacore.app.auth.AuthFlowTest.testAND_OTP_001_validPhone"
```
