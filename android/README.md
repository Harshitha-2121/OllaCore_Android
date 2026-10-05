# OllaChat Android App

## Architecture

```
Android App
    │
    ▼
Ollacore API (api.ollacore.com/v1)
    │
    ▼
OTP/Auth Service
    │
    ▼
Simulator (test environment only)
```

**Important:** The Android app does NOT contain:
- SIM_TOKEN
- Direct simulator calls
- Any test environment credentials

All authentication goes through the Ollacore API.

## Screens

### 1. Phone Input Screen
```
┌─────────────────────────────┐
│                             │
│         OllaChat            │
│                             │
│   Enter your phone number   │
│                             │
│   +91  [______________]     │
│                             │
│       [ Continue ]          │
│                             │
└─────────────────────────────┘
```

### 2. OTP Verification Screen
```
┌─────────────────────────────┐
│                             │
│       Verify your number    │
│                             │
│  Code sent to your phone    │
│                             │
│      [ _ _ _ _ _ _ ]        │
│                             │
│       Resend code           │
│                             │
│        [ Verify ]           │
│                             │
└─────────────────────────────┘
```

### 3. Chat Home Screen
After successful authentication, user sees the chat home screen.

## File Structure

```
android/app/src/main/java/com/ollacore/app/
├── auth/
│   ├── OllacoreAuthClient.kt    # API client (no SIM_TOKEN)
│   ├── AuthViewModel.kt         # Authentication state management
│   ├── PhoneInputScreen.kt      # Phone number input UI
│   ├── OtpVerificationScreen.kt # OTP verification UI
│   ├── ChatHomeScreen.kt        # Chat home (post-login)
│   └── MainActivity.kt          # Main activity with navigation
└── ui/theme/
    ├── Color.kt                 # Color definitions
    ├── Theme.kt                 # Material theme
    └── Type.kt                  # Typography
```

## Security Notes

1. **No SIM_TOKEN in code** - All authentication goes through Ollacore API
2. **No direct simulator calls** - Android only talks to api.ollacore.com
3. **Session tokens are long-lived** - Client should store securely
4. **Logout is client-side** - Clear token from SharedPreferences/Keystore

## Building

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

## Testing

Use the test phone numbers:
- +15550001111 (QA1)
- +15550002222 (QA2)
- +15550003333 (QA1-Partial)

OTP codes are intercepted by the simulator in test environment.
