'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const WebSocket = require('ws');

const { loadConfig } = require('../src/config');
const { createSignalingServer } = require('../src/server');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let app;
let port;

test.before(async () => {
  const cfg = loadConfig({
    AUTH_MODE: 'dev',
    PORT: '0',
    HOST: '127.0.0.1',
    STUN_URLS: 'stun:stun.test:3478',
    RING_TIMEOUT_MS: '45000',
    ANSWER_TIMEOUT_MS: '60000',
    DISCONNECT_GRACE_MS: '400',
    AUTH_TIMEOUT_MS: '2000',
    RATE_LIMIT_PER_SEC: '500',
  });
  app = createSignalingServer(cfg);
  const addr = await app.start();
  port = addr.port;
});

test.after(async () => {
  await app.close();
});

function makeClient() {
  const ws = new WebSocket(`ws://127.0.0.1:${port}`);
  const inbox = [];
  const listeners = new Set();
  let closed = false;
  let closeInfo = null;

  ws.on('message', (data) => {
    try {
      inbox.push(JSON.parse(data.toString('utf8')));
    } catch (_) {
      /* ignore malformed */
    }
    for (const fn of [...listeners]) fn();
  });
  ws.on('close', (code, reason) => {
    closed = true;
    closeInfo = { code, reason: reason.toString('utf8') };
    for (const fn of [...listeners]) fn();
  });
  ws.on('error', () => {});

  const opened = new Promise((resolve, reject) => {
    ws.on('open', resolve);
    ws.on('error', (err) => reject(err));
  });

  function send(frame) {
    ws.send(JSON.stringify(frame));
  }

  async function waitFor(pred, timeoutMs = 4000) {
    const deadline = Date.now() + timeoutMs;
    let idx = inbox.findIndex(pred);
    if (idx >= 0) return inbox.splice(idx, 1)[0];
    while (Date.now() < deadline && !closed) {
      await new Promise((r) => {
        const fn = () => r();
        listeners.add(fn);
        const timer = setTimeout(() => {
          listeners.delete(fn);
          r();
        }, 40);
        if (typeof timer.unref === 'function') timer.unref();
      });
      idx = inbox.findIndex(pred);
      if (idx >= 0) return inbox.splice(idx, 1)[0];
    }
    // Allow a final scan after close.
    idx = inbox.findIndex(pred);
    if (idx >= 0) return inbox.splice(idx, 1)[0];
    const seen = inbox.map((f) => f.type).join(',');
    throw new Error(`timeout waiting for frame, inbox types: [${seen}] closed=${closed}`);
  }

  async function expectNone(pred, ms = 250) {
    await sleep(ms);
    const found = inbox.find(pred);
    assert.equal(found, undefined, `unexpected frame: ${found && found.type}`);
  }

  async function auth(token) {
    await opened;
    send({ type: 'auth', token });
    return waitFor((f) => f.type === 'auth:ok' || f.type === 'auth:error');
  }

  return {
    ws,
    opened,
    send,
    waitFor,
    expectNone,
    auth,
    inbox,
    isClosed: () => closed,
    closeInfo: () => closeInfo,
    close: () => {
      try {
        ws.close();
      } catch (_) {
        /* ignore */
      }
    },
  };
}

test('auth:ok returns identity from server plus ICE servers; ping -> pong', async () => {
  const a = makeClient();
  const ok = await a.auth('dev:alice');
  assert.equal(ok.type, 'auth:ok');
  assert.equal(ok.userId, 'alice');
  assert.ok(Array.isArray(ok.iceServers));
  assert.deepEqual(ok.iceServers[0], { urls: ['stun:stun.test:3478'] });

  a.send({ type: 'ping' });
  await a.waitFor((f) => f.type === 'pong');
  a.close();
});

test('auth: bad token is rejected and socket closed; pre-auth frames close with 4403', async () => {
  const bad = makeClient();
  const err = await bad.auth('not-a-dev-token');
  assert.equal(err.type, 'auth:error');
  assert.equal(err.reason, 'invalid_token');
  await bad.waitFor((f) => f.type === '__never__').catch(() => {});
  await sleep(150);
  assert.equal(bad.isClosed(), true);
  assert.equal(bad.closeInfo().code, 4401);

  const unauth = makeClient();
  await unauth.opened;
  unauth.send({ type: 'call:initiate', callId: 'x', receiverId: 'bob', media: 'audio' });
  const e = await unauth.waitFor((f) => f.type === 'error');
  assert.equal(e.reason, 'not_authenticated');
  await sleep(150);
  assert.equal(unauth.isClosed(), true);
  assert.equal(unauth.closeInfo().code, 4403);
});

test('full 1-to-1 call: invite, buffer, accept, sdp/ice relay, hangup, state cleaned', async () => {
  const a = makeClient();
  const b = makeClient();
  await a.auth('dev:alice');
  await b.auth('dev:bob');

  a.send({
    type: 'call:initiate',
    callId: 'call-full',
    receiverId: 'bob',
    media: 'video',
    roomId: 'room-77',
    callerName: 'Alice',
  });

  const incoming = await b.waitFor((f) => f.type === 'call:incoming');
  assert.equal(incoming.callId, 'call-full');
  assert.equal(incoming.callerId, 'alice');
  assert.equal(incoming.callerName, 'Alice');
  assert.equal(incoming.roomId, 'room-77');
  assert.equal(incoming.media, 'video');
  const ringing = await a.waitFor((f) => f.type === 'call:ringing');
  assert.equal(ringing.callId, 'call-full');

  // Offer + ICE before accept must NOT reach the callee yet (late-accept fix).
  a.send({ type: 'call:offer', callId: 'call-full', sdp: { type: 'offer', sdp: 'O' } });
  a.send({
    type: 'call:ice-candidate',
    callId: 'call-full',
    candidate: { candidate: 'candidate:1' },
    sdpMid: '0',
    sdpMLineIndex: 0,
  });
  await b.expectNone((f) => f.type === 'call:offer' || f.type === 'call:ice-candidate', 250);

  b.send({ type: 'call:accept', callId: 'call-full' });
  const accept = await a.waitFor((f) => f.type === 'call:accept');
  assert.equal(accept.callId, 'call-full');

  const offer = await b.waitFor((f) => f.type === 'call:offer');
  assert.deepEqual(offer.sdp, { type: 'offer', sdp: 'O' });
  const calleeIce = await b.waitFor((f) => f.type === 'call:ice-candidate');
  assert.equal(calleeIce.sdpMid, '0');

  b.send({ type: 'call:answer', callId: 'call-full', sdp: { type: 'answer', sdp: 'A' } });
  const answer = await a.waitFor((f) => f.type === 'call:answer');
  assert.deepEqual(answer.sdp, { type: 'answer', sdp: 'A' });

  // Caller ICE after accept relays immediately.
  a.send({
    type: 'call:ice-candidate',
    callId: 'call-full',
    candidate: { candidate: 'candidate:2' },
  });
  const callerIce = await b.waitFor((f) => f.type === 'call:ice-candidate' && f.candidate && f.candidate.candidate === 'candidate:2');
  assert.ok(callerIce);

  a.send({ type: 'call:hangup', callId: 'call-full', reason: 'hangup' });
  const hangup = await b.waitFor((f) => f.type === 'call:hangup');
  assert.equal(hangup.reason, 'hangup');

  // Server state fully cleaned (health endpoint reports zero live calls).
  await sleep(100);
  const health = await (await fetch(`http://127.0.0.1:${port}/healthz`)).json();
  assert.equal(health.ok, true);
  assert.equal(health.calls, 0);

  a.close();
  b.close();
});

test('busy, reject, offline, and timeout signaling', async () => {
  // --- busy ---
  const a = makeClient();
  const b = makeClient();
  const c = makeClient();
  await a.auth('dev:alice2');
  await b.auth('dev:bob2');
  await c.auth('dev:carol2');

  a.send({ type: 'call:initiate', callId: 'busy-1', receiverId: 'bob2', media: 'audio' });
  await b.waitFor((f) => f.type === 'call:incoming');

  c.send({ type: 'call:initiate', callId: 'busy-2', receiverId: 'bob2', media: 'audio' });
  const busy = await c.waitFor((f) => f.type === 'call:busy');
  assert.equal(busy.callId, 'busy-2');

  // --- reject ---
  b.send({ type: 'call:reject', callId: 'busy-1', reason: 'declined' });
  const reject = await a.waitFor((f) => f.type === 'call:reject');
  assert.equal(reject.reason, 'declined');

  // --- offline ---
  c.send({ type: 'call:initiate', callId: 'off-1', receiverId: 'ghost', media: 'audio' });
  const off = await c.waitFor((f) => f.type === 'call:failed');
  assert.equal(off.reason, 'offline');

  // --- ownership violations ---
  c.send({ type: 'call:answer', callId: 'no-such-call', sdp: 'x' });
  const err = await c.waitFor((f) => f.type === 'error');
  assert.equal(err.reason, 'unknown_call');

  a.close();
  b.close();
  c.close();
});

test('disconnect grace: peer gets call:hangup reason=disconnect; reconnect keeps call alive', async () => {
  const a = makeClient();
  const b = makeClient();
  await a.auth('dev:alice3');
  await b.auth('dev:bob3');

  a.send({ type: 'call:initiate', callId: 'grace-1', receiverId: 'bob3', media: 'audio' });
  await b.waitFor((f) => f.type === 'call:incoming');
  b.send({ type: 'call:accept', callId: 'grace-1' });
  await a.waitFor((f) => f.type === 'call:accept');

  // Phase 1: drop A, reconnect within the 400ms grace -> call must survive.
  a.close();
  await sleep(100);
  const a2 = makeClient();
  await a2.auth('dev:alice3');
  await sleep(500); // well past the original grace deadline
  await b.expectNone((f) => f.type === 'call:hangup', 100);

  // Call still usable: A can hang up, B is notified.
  a2.send({ type: 'call:hangup', callId: 'grace-1', reason: 'hangup' });
  const hangup = await b.waitFor((f) => f.type === 'call:hangup');
  assert.equal(hangup.callId, 'grace-1');
  a2.close();

  // Phase 2: drop the receiver this time -> caller notified after grace.
  const d = makeClient();
  const e = makeClient();
  await d.auth('dev:alice4');
  await e.auth('dev:bob4');
  d.send({ type: 'call:initiate', callId: 'grace-2', receiverId: 'bob4', media: 'video' });
  await e.waitFor((f) => f.type === 'call:incoming');
  e.send({ type: 'call:accept', callId: 'grace-2' });
  await d.waitFor((f) => f.type === 'call:accept');

  e.close();
  const disconnect = await d.waitFor((f) => f.type === 'call:hangup');
  assert.equal(disconnect.reason, 'disconnect');
  assert.equal(disconnect.callId, 'grace-2');

  await sleep(100);
  const health = await (await fetch(`http://127.0.0.1:${port}/healthz`)).json();
  assert.equal(health.calls, 0);
  d.close();
});

test('second socket for the same user replaces the first (reconnect support)', async () => {
  const a1 = makeClient();
  await a1.auth('dev:alice5');
  const a2 = makeClient();
  await a2.auth('dev:alice5');

  await sleep(150);
  assert.equal(a1.isClosed(), true);
  assert.equal(a1.closeInfo().code, 4402);
  a2.close();
});
