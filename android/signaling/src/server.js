'use strict';

const http = require('http');
const https = require('https');
const fs = require('fs');
const { WebSocketServer } = require('ws');

const { CallManager } = require('./callManager');
const { TokenVerifier } = require('./auth');
const { buildIceServers } = require('./ice');

const WS_OPEN = 1; // WebSocket readyState OPEN (avoid relying on instance constants)

function log(...args) {
  // No tokens, no SDP, no candidates are ever logged.
  console.log(new Date().toISOString(), ...args);
}

function createSignalingServer(cfg, opts = {}) {
  const fetchImpl = opts.fetch;
  const verifier = opts.verifier || new TokenVerifier(cfg, fetchImpl);

  // ---- presence: one live socket per user --------------------------------
  const connected = new Map(); // userId -> socket
  const iceCache = new Map(); // userId -> iceServers (rebuilt on auth; TURN creds are time-limited per auth)

  function sendTo(userId, frame) {
    const socket = connected.get(userId);
    if (!socket || socket.readyState !== WS_OPEN) return false;
    try {
      socket.send(JSON.stringify(frame));
      return true;
    } catch (_) {
      return false;
    }
  }

  const calls = new CallManager({
    sendTo,
    isOnline: (userId) => connected.has(userId),
    ringTimeoutMs: cfg.ringTimeoutMs,
    answerTimeoutMs: cfg.answerTimeoutMs,
    disconnectGraceMs: cfg.disconnectGraceMs,
    log,
  });

  // ---- http(s) + ws ------------------------------------------------------
  let server;
  if (cfg.tlsEnabled) {
    server = https.createServer({
      key: fs.readFileSync(cfg.tlsKey),
      cert: fs.readFileSync(cfg.tlsCert),
    });
  } else {
    server = http.createServer((req, res) => {
      if (req.url === '/healthz') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: true, calls: calls.stats().activeCalls }));
        return;
      }
      res.writeHead(404);
      res.end();
    });
  }

  const wss = new WebSocketServer({
    server,
    maxPayload: cfg.maxFrameBytes,
  });

  wss.on('connection', (socket) => {
    const state = {
      userId: null,
      authed: false,
      authTimer: setTimeout(() => {
        closeWith(socket, 4401, 'auth_timeout');
      }, cfg.authTimeoutMs),
      windowStart: Date.now(),
      windowCount: 0,
    };

    socket.on('message', async (raw, isBinary) => {
      if (isBinary) {
        closeWith(socket, 4400, 'binary_not_supported');
        return;
      }

      // Simple per-socket rate limit.
      const now = Date.now();
      if (now - state.windowStart >= 1000) {
        state.windowStart = now;
        state.windowCount = 0;
      }
      if (++state.windowCount > cfg.rateLimitPerSec) {
        closeWith(socket, 4429, 'rate_limited');
        return;
      }

      let frame;
      try {
        frame = JSON.parse(raw.toString('utf8'));
      } catch (_) {
        safeSend(socket, { type: 'error', reason: 'bad_json' });
        return;
      }
      if (!frame || typeof frame !== 'object' || typeof frame.type !== 'string') {
        safeSend(socket, { type: 'error', reason: 'bad_request' });
        return;
      }

      try {
        await handleFrame(socket, state, frame);
      } catch (err) {
        log('frame error:', err && err.message);
        safeSend(socket, { type: 'error', reason: 'internal' });
      }
    });

    socket.on('close', () => {
      clearTimeout(state.authTimer);
      if (state.authed) retireSocket(state.userId, socket);
    });
    socket.on('error', () => socket.terminate());
  });

  async function handleFrame(socket, state, frame) {
    if (frame.type === 'ping') {
      safeSend(socket, { type: 'pong' });
      return;
    }

    if (!state.authed) {
      if (frame.type !== 'auth') {
        closeWith(socket, 4403, 'not_authenticated');
        return;
      }
      const token = frame.token;
      const userId = await verifier.verify(token);
      if (!userId) {
        safeSend(socket, { type: 'auth:error', reason: 'invalid_token' });
        closeWith(socket, 4401, 'invalid_token');
        return;
      }
      state.userId = userId;
      state.authed = true;
      clearTimeout(state.authTimer);

      // Presence: the newest socket wins (reconnect after network blip).
      const previous = connected.get(userId);
      if (previous && previous !== socket) {
        closeWith(previous, 4402, 'replaced');
      }
      connected.set(userId, socket);
      calls.reconnect(userId);

      if (!iceCache.has(userId)) iceCache.set(userId, buildIceServers(cfg, userId));
      safeSend(socket, { type: 'auth:ok', userId, iceServers: iceCache.get(userId) });
      log(`user connected: ${userId} (auth=${cfg.authMode}, sockets=${connected.size})`);
      return;
    }

    // Authenticated routing (identity is state.userId - never frame.userId).
    const u = state.userId;
    switch (frame.type) {
      case 'call:initiate':
        calls.initiate(u, frame);
        break;
      case 'call:ringing':
        calls.relayRinging(u, frame.callId);
        break;
      case 'call:accept':
        calls.accept(u, frame.callId);
        break;
      case 'call:reject':
        calls.reject(u, frame.callId, frame.reason);
        break;
      case 'call:offer':
        calls.offer(u, frame.callId, frame);
        break;
      case 'call:answer':
        calls.answer(u, frame.callId, frame);
        break;
      case 'call:ice-candidate':
        calls.candidate(u, frame.callId, frame);
        break;
      case 'call:hangup':
        calls.hangup(u, frame.callId, frame.reason);
        break;
      case 'auth':
        // Re-auth on a live socket: treat as reconnect (refresh ICE creds).
        safeSend(socket, { type: 'auth:error', reason: 'already_authenticated' });
        break;
      default:
        safeSend(socket, { type: 'error', reason: 'unknown_frame' });
    }
  }

  function retireSocket(userId, socket) {
    if (connected.get(userId) === socket) {
      connected.delete(userId);
      iceCache.delete(userId);
      // In-call: grace period so a brief blip does not kill the call.
      calls.disconnect(userId);
      log(`user disconnected: ${userId} (sockets=${connected.size})`);
    }
  }

  function closeWith(socket, code, reason) {
    safeSend(socket, { type: 'error', reason });
    try {
      socket.close(code, reason);
    } catch (_) {
      try {
        socket.terminate();
      } catch (_) {
        /* already gone */
      }
    }
  }

  function safeSend(socket, frame) {
    try {
      if (socket.readyState === WS_OPEN) socket.send(JSON.stringify(frame));
    } catch (_) {
      /* peer gone */
    }
  }

  return {
    server,
    wss,
    calls,
    connected,
    start() {
      return new Promise((resolve) => {
        server.listen(cfg.port, cfg.host, () => {
          const addr = server.address();
          log(
            `signaling server up (${cfg.tlsEnabled ? 'https/wss' : 'http/ws'}) on ` +
              `${cfg.host}:${addr.port} auth=${cfg.authMode} ` +
              `stun=${cfg.stunUrls.length} turn=${cfg.turnUrls.length ? 'configured' : 'none'} ` +
              `ring=${cfg.ringTimeoutMs}ms grace=${cfg.disconnectGraceMs}ms`
          );
          resolve(addr);
        });
      });
    },
    close() {
      calls.dispose();
      for (const socket of [...connected.values()]) {
        try {
          socket.close(1001, 'server_shutdown');
        } catch (_) {
          /* ignore */
        }
      }
      connected.clear();
      iceCache.clear();
      return new Promise((resolve) => {
        wss.close(() => {
          server.close(() => resolve());
        });
        // Terminate any lingering sockets so server.close can complete.
        for (const client of wss.clients) client.terminate();
      });
    },
  };
}

module.exports = { createSignalingServer };
