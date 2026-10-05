# Ollacore Android - Build Architecture

Build from capability buckets, NOT from the WhatsApp feature list.
A screen is only built when its bucket has backing: existing API, or
client-only storage. Everything else is a backend spec first.

```
                 OLLACORE ANDROID
                       |
        +--------------+---------------+
        |              |               |
     EXISTING       CLIENT-ONLY      BACKEND
       API           FEATURES       REQUIRED
        |               |               |
  Chat/media        Archive          Status
  Groups            Mute             Channels
  Calls             Pin              Communities
  E2EE              Call history     Polls
  Push              App lock         Voice notes
  Contacts          Local settings   Video notes
  Devices           Unread/clear     Events
  Search (room)     Block (local*)   Privacy store
                                     Disappearing TTL
                                     Backup/restore
```

* Block list needs a server filter to mean anything cross-device;
  client can hide locally as a stopgap (not yet built).

## Bucket rules

1. **EXISTING API** - wire `OllacoreApi` / `ChatWebSocket` / `RtcWebSocket`
   first, then Compose UI, then functional + real-device test.
2. **CLIENT-ONLY** - `data/local/` DataStore JSON stores (no Room/KSP).
   `CallLogStore` is the pattern; archive/mute/pin follow it.
3. **BACKEND REQUIRED** - spec in `OLLACORE-BACKEND-SPEC.txt` first.
   Android ships an honest placeholder, never a faked screen.

## Module map (code pointers)

| Area | Bucket | Status |
|---|---|---|
| Auth OTP/session/profile | Existing | Done (`auth/`, `SessionStore`) |
| Home Chats/Calls/Settings tabs | Existing + client | Done (`ui/home/`, `ui/calls/`, `ui/settings/`) |
| Updates tab | Backend | Placeholder (`ui/updates/`) |
| 1-to-1 chat, ticks, retry | Existing | Done (`ui/chat/`) |
| Media bubbles + picker | Existing | Done |
| Contacts lookup + DM open | Existing | Done (`ui/contacts/`) |
| Groups create/participants | Existing | Done (`ui/groups/`) |
| Group add/remove/rename/icon/role/invite/leave | Backend-check | UI built, rejects surface as backend-check |
| Voice/video calls, incoming, share | Existing | Done (`ui/call/`) |
| Call history | Client-only | Done (`data/local/CallLogStore`) |
| Push tap routing | Existing | Done (`MainActivity`, push/) |
| Linked devices | Existing | Done (`ui/devices/`) |
| Global search + media tabs | Existing (fan-out) | Done (`ui/globalsearch/`) |
| Security UX | Later | E2EE untouched |
| Chat mgmt archive/mute/pin | Client-only | Not built (same DataStore pattern) |
| App lock/PIN | Client-only | Not built (BiometricPrompt) |
| Status / voice-note / video-note | Backend | Spec written, UI queued |
| Polls / events / communities / channels / privacy / disappearing / backup / block | Backend | Spec written, UI queued |

## Phase order

1. Core UI (Home + Chat) - DONE this round
2. Complete messaging (forward/copy/star/ticks/media) - DONE earlier
3. Contacts + Groups - DONE earlier
4. Calling (+ client call history) - DONE this round
5. Settings / Devices / Privacy - devices + settings DONE; privacy BACKEND
6. Advanced (status/notes/polls/events/communities/channels/disappearing/backup) - BACKEND SPECS
