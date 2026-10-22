# Ollacore Call Signaling Server

Self-hosted WebSocket signaling for 1:1 audio/video calls. In-memory state only —
no database, no persistent call records (per spec). Media stays in the app via
WebRTC; this server only brokers presence, ringing, and SDP/ICE exchange.

## Quick start

```bash
cd android/signaling
npm install
cp .env.example .env          # edit as needed
npm start                     # ws://localhost:8080
npm test                      # 19 unit + integration tests (node --test)
```

Health check: `GET http://localhost:8080/healthz` -> `{"ok":true,"calls":0}`

## Environment (.env)

| Variable | Default | Meaning |
|---|---|---|
| `PORT` / `HOST` | `8080` / `0.0.0.0` | listen address |
| `AUTH_MODE` | `backend` | `backend` = verify via API, `dev` = `dev:<userId>` tokens only, `dual` = backend with dev fallback (testing) |
| `API_BASE` | `https://api.ollacore.com` | `GET {API_BASE}/directory/me` with `Authorization: Bearer <session token>` -> profile.id |
| `STUN_URLS` | (empty) | comma-separated, e.g. `stun:stun.l.google.com:19302` |
| `TURN_URLS` | (empty) | comma-separated TURN/TURNs URLs |
| `TURN_SECRET` | (empty) | HMAC-SHA1 secret -> ephemeral REST-style creds per user |
| `TURN_USERNAME` / `TURN_PASSWORD` | (empty) | static TURN creds alternative |
| `RING_TIMEOUT_MS` | `45000` | unanswered ring -> `call:timeout` both sides |
| `ANSWER_TIMEOUT_MS` | `60000` | accepted but no `call:offer` -> `call:failed answer_timeout` |
| `DISCONNECT_GRACE_MS` | `15000` | socket drop tolerated this long before `call:hangup` |
| `AUTH_TIMEOUT_MS` | `10000` | first frame must be `auth` within this window |
| `MAX_FRAME_BYTES` | `262144` | oversize frame -> close 1009 |
| `RATE_LIMIT_PER_SEC` | `60` | per-socket frame budget |
| `TLS_KEY` / `TLS_CERT` | (empty) | serve WSS when both set |

## Protocol

Every connection must send `{"t":"auth","token":"..."}` first (else close 4403;
bad token -> `auth:error` + close 4401). One live socket per user; a new
connection replaces the old (close 4402 on the stale one).

Client -> Server

| t | fields | notes |
|---|---|---|
| `auth` | `token` | session token (or `dev:<userId>` in dev/dual mode) |
| `call:initiate` | `callId, receiverId, media("audio"\|"video"), roomId, callerName` | `callId` is client-generated idempotency key |
| `call:ringing` | `callId` | callee accepted the OS ring |
| `call:accept` | `callId` | starts media phase; caller's buffered offer/ICE flushed |
| `call:reject` | `callId, reason("declined"\|"busy")` | |
| `call:offer` | `callId, sdp:{type,sdp}` | sent by caller after accept |
| `call:answer` | `callId, sdp` | sent by callee |
| `call:ice-candidate` | `callId, candidate:{...}` | before or after SDP |
| `call:hangup` | `callId, reason` | normal end |
| `ping` | | keepalive -> `pong` |

Server -> Client

| t | fields | notes |
|---|---|---|
| `auth:ok` | `userId, iceServers[]` | identity comes from the backend, never from the client |
| `auth:error` | `reason` | |
| `call:incoming` | `callId, callerId, callerName, roomId, media` | to receiver |
| `call:ringing` | `callId` | relayed to caller |
| `call:accept` | `callId` | relayed to caller (after offer flush) |
| `call:reject` | `callId, reason` | relayed to caller; `reason:"busy"` -> caller UI busy |
| `call:busy` | `callId` | receiver already in a call |
| `call:offer` / `call:answer` / `call:ice-candidate` | as sent | relayed to the peer only |
| `call:hangup` | `callId, reason` | relayed; also emitted on grace expiry |
| `call:timeout` | `callId` | ring timeout (no accept in `RING_TIMEOUT_MS`) |
| `call:failed` | `callId, reason` | `offline`, `self_call`, `answer_timeout`, `not_in_call`, ... |
| `error` | `reason` | malformed/illegal frame for current state |
| `pong` | | |

Semantics worth knowing:

- While a call is **ringing**, the caller's `call:offer` and ICE candidates are
  buffered (latest offer wins) and flushed to the callee right before
  `call:accept` is relayed — so the callee never needs to wait for a re-send.
- Ownership checks: only participants of the call may send its frames; a socket
  may hold at most one live call (`call:busy` otherwise).
- Disconnect: if either socket drops, a 15s grace timer starts; reconnect within
  the window keeps the call, otherwise both sides get `call:hangup` (reason
  `disconnect`). All state is dropped when the process exits — there is no
  call history by design.

## Android integration

The app reads `BuildConfig.SIGNALING_URL` (set via `local.properties`
`signalingUrl=...` or Gradle property `signalingUrl`). Point it at your machine:

```properties
# android/local.properties (git-ignored)
signalingUrl=ws://127.0.0.1:8080
```

For a USB device, reverse the port so `127.0.0.1:8080` on the phone reaches the
PC:

```bash
adb reverse tcp:8080 tcp:8080
```

(Emulators use `ws://10.0.2.2:8080` instead — allowed by
`res/xml/network_security_config.xml`, which permits cleartext WS only to
localhost/loopback/RFC1918 ranges.)

Flow: `AppContainer` connects as soon as a session exists and authenticates with
the real session token (verified against `/directory/me`). The chat/contact call
buttons then route through this server; incoming calls auto-open the call screen
via `MainActivity`. Media (WebRTC, getUserMedia, state machine) is unchanged.

## Testing

```bash
npm test        # server: call lifecycle unit tests + 2-party integration tests
```

On-device smoke (one phone, honest scope):

1. Start the server, `adb reverse tcp:8080 tcp:8080`, install the debug APK.
2. Open the app -> logcat `[CALL_SIG]: READY user=<uuid> ice=<n>` proves
   token auth + ICE delivery end to end.
3. Call an offline contact -> server replies `call:failed offline` and the app
   shows the honest failure (no fake ringing).

A full two-party call needs two logged-in accounts (second device or a `dual`
mode scripted peer in `test/`).

## Limitations & security

- **In-app ringing only**: pushes arrive while the app process holds the
  socket. Background/killed ringing needs FCM (spec'd in
  `OLLACORE-BACKEND-SPEC.txt`, not implemented here).
- 1:1 only, no group calls, no call history, no call links.
- Identity is always the backend-verified profile id; `dev:` tokens are rejected
  unless `AUTH_MODE=dev|dual`.
- Frames are size-limited and rate-limited per socket; cross-user frames are
  dropped server-side (ownership checks), so one user cannot inject into
  another's call.
- Keep `TURN_SECRET` / static TURN credentials in `.env` only (git-ignored);
  never log tokens or SDP bodies at info level.
