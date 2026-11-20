# Push notifications (FCM) — setup and current status

## Honest status

Push delivery is **not functional** in this repository as shipped, and the app
says so in Settings → Linked Devices ("Push is not configured in this build
(Firebase missing). Add google-services.json and reinstall.").

The app contains the complete *client* integration (token retrieval, backend
registration, logout cleanup, notification rendering, tap routing). What is
missing is **external configuration and a backend sender** — nothing in the
repo can fabricate those.

There is **no end-to-end encryption (E2EE/MLS)**. Message transport is
protected by TLS/HTTPS and WSS only.

## What the client already does

- `PushRegistrationManager` (this directory) obtains the FCM token and calls
  `POST /v1/directory/devices` with `{platform: "android", push_token: ...}`:
  - after a successful OTP login (`AuthViewModel.verifyOtp`),
  - on app start for an existing session (`MainActivity.onCreate`),
  - whenever FCM rotates the token (`onNewToken`).
- Logout calls `DELETE /v1/directory/devices` for this token before clearing
  the session (`AuthViewModel.logout`), so a signed-out account stops
  receiving notifications.
- `OllacoreFirebaseMessagingService` renders content-free notifications for
  `message` / `message.created` / `call.started` / `call.ended` data pushes.
  Notification IDs are per room/call (`roomId.hashCode()` / `callId.hashCode()`),
  so re-delivered pushes update the existing notification instead of stacking.
- Android 13+ `POST_NOTIFICATIONS` is requested at startup (`MainActivity`).
- Tap routing already exists: `room_id` + `open_chat`, or `call_id` +
  `open_call` extras open the right screen.

Every Firebase call is failure-tolerant: without configuration the app logs
nothing to the UI, shows the honest Linked Devices notice, and messaging keeps
working over the live WebSocket.

## Required external configuration (not vendored — do NOT commit secrets)

1. **Firebase project + Android apps**
   - Create/download `google-services.json` from the Firebase console.
   - It must contain **two** Android apps, because debug builds use the
     `com.ollacore.app.debug` application ID:
     - package `com.ollacore.app`
     - package `com.ollacore.app.debug`
   - Place the file at `android/app/google-services.json`.

2. **Gradle plugin**
   - `libs.versions.toml` already pins `google-services = "4.4.2"`
     (kept commented while the JSON file is absent, because applying the plugin
     without it fails the build).
   - In `android/app/build.gradle.kts`:
     - add `alias(libs.plugins.google.services)` to `plugins { }`
     - (the `firebase-messaging` dependency is already present).

3. **Backend sender**
   - The server must store the tokens registered by
     `POST /v1/directory/devices` and send FCM **data** messages with keys:
     - `type`: `message` (or legacy `message.created`), `call.started`,
       `call.ended`
     - `room_id`, optional `call_id`
     - `sender` (display label used in notifications)
     - `preview` (only for non content-free rooms)
     - `encrypted` (`"true"` when the room is flagged content-free; the client
       then hides the body)
     - `sent_at` (epoch millis; call pushes older than 45 s are dropped)
   - `DELETE /v1/directory/devices` must revoke the token.

## Verification checklist (after the three items above exist)

1. Build debug, log in, open Linked Devices: the push notice disappears and
   this handset can be registered.
2. Kill the app (swipe away), send a message from another account: a
   content-free notification arrives; tapping it opens the room.
3. Rotate the token (uninstall/reinstall or Firebase console) and confirm the
   backend row updates via `onNewToken`.
4. Log out: the device disappears from Linked Devices and no further pushes
   are delivered to it.
5. Incoming call push rings full-screen while the app is dead; `call.ended`
   cancels the notification.

Until items 1–3 exist, treat push as **UNVERIFIED** in any QA report.
