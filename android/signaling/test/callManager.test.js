'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { CallManager } = require('../src/callManager');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function makeHarness(opts = {}) {
  const delivered = [];
  const online = new Set(['alice', 'bob', 'carol']);
  const failSend = new Set(); // users whose delivery fails (offline race simulation)

  const mgr = new CallManager({
    sendTo(userId, frame) {
      if (!online.has(userId) || failSend.has(userId)) return false;
      delivered.push({ userId, frame });
      return true;
    },
    isOnline: (userId) => online.has(userId),
    ringTimeoutMs: opts.ringTimeoutMs ?? 45000,
    answerTimeoutMs: opts.answerTimeoutMs ?? 60000,
    disconnectGraceMs: opts.disconnectGraceMs ?? 15000,
  });

  const to = (userId, type) =>
    delivered.filter((d) => d.userId === userId && (!type || d.frame.type === type));

  return { mgr, delivered, online, failSend, to };
}

function initiate(mgr, callId, receiverId, extra = {}) {
  mgr.initiate('alice', {
    type: 'call:initiate',
    callId,
    receiverId,
    media: 'video',
    roomId: 'room-1',
    callerName: 'Alice',
    ...extra,
  });
}

test('initiate to offline receiver -> call:failed offline', (t) => {
  const h = makeHarness();
  t.after(() => h.mgr.dispose());
  h.online.delete('bob');

  initiate(h.mgr, 'c1', 'bob');

  assert.equal(h.to('alice', 'call:failed').length, 1);
  assert.equal(h.to('alice')[0].frame.reason, 'offline');
  assert.equal(h.mgr.stats().activeCalls, 0);
});

test('initiate to busy receiver -> call:busy', (t) => {
  const h = makeHarness();
  t.after(() => h.mgr.dispose());

  initiate(h.mgr, 'c1', 'bob');
  h.mgr.accept('bob', 'c1');

  h.mgr.initiate('carol', {
    type: 'call:initiate',
    callId: 'c2',
    receiverId: 'bob',
    media: 'audio',
  });

  const busy = h.to('carol', 'call:busy');
  assert.equal(busy.length, 1);
  assert.equal(busy[0].frame.callId, 'c2');
  assert.equal(h.to('bob', 'call:incoming').length, 1); // only the first call rang
});

test('self-call and duplicate callId are rejected', (t) => {
  const h = makeHarness();
  t.after(() => h.mgr.dispose());

  initiate(h.mgr, 'c1', 'alice');
  assert.equal(h.to('alice', 'error')[0].frame.reason, 'self_call');

  initiate(h.mgr, 'c2', 'bob');
  initiate(h.mgr, 'c2', 'bob'); // duplicate
  const failed = h.to('alice', 'call:failed');
  assert.equal(failed.length, 1);
  assert.equal(failed[0].frame.reason, 'duplicate_call_id');
  assert.equal(h.mgr.stats().activeCalls, 1);
});

test('happy path: ring, buffered offer flush on accept, answer, hangup', (t) => {
  const h = makeHarness();
  t.after(() => h.mgr.dispose());

  initiate(h.mgr, 'c1', 'bob');
  assert.equal(h.to('bob', 'call:incoming').length, 1);
  assert.equal(h.to('bob')[0].frame.media, 'video');
  assert.equal(h.to('alice', 'call:ringing').length, 1);

  // Offer + ICE sent while ringing must be buffered, not delivered.
  h.mgr.offer('alice', 'c1', { type: 'call:offer', callId: 'c1', sdp: { type: 'offer', sdp: 'O' } });
  h.mgr.candidate('alice', 'c1', {
    type: 'call:ice-candidate',
    callId: 'c1',
    candidate: { candidate: 'candidate:1' },
    sdpMid: '0',
    sdpMLineIndex: 0,
  });
  assert.equal(h.to('bob', 'call:offer').length, 0);
  assert.equal(h.to('bob', 'call:ice-candidate').length, 0);

  h.mgr.accept('bob', 'c1');
  assert.equal(h.to('alice', 'call:accept').length, 1);
  assert.equal(h.to('bob', 'call:offer').length, 1);
  assert.deepEqual(h.to('bob')[1].frame.sdp, { type: 'offer', sdp: 'O' });
  assert.equal(h.to('bob', 'call:ice-candidate').length, 1);

  h.mgr.answer('bob', 'c1', { type: 'call:answer', callId: 'c1', sdp: { type: 'answer', sdp: 'A' } });
  const answers = h.to('alice', 'call:answer');
  assert.equal(answers.length, 1);
  assert.deepEqual(answers[0].frame.sdp, { type: 'answer', sdp: 'A' });

  // Post-accept ICE relays directly both ways.
  h.mgr.candidate('bob', 'c1', { type: 'call:ice-candidate', callId: 'c1', candidate: { candidate: 'c2' } });
  assert.equal(h.to('alice', 'call:ice-candidate').length, 1);

  h.mgr.hangup('alice', 'c1', 'hangup');
  const hangups = h.to('bob', 'call:hangup');
  assert.equal(hangups.length, 1);
  assert.equal(hangups[0].frame.reason, 'hangup');
  assert.equal(h.mgr.stats().activeCalls, 0);
  assert.equal(h.mgr.stats().busyUsers, 0);

  // Busyness cleared: bob can be called again immediately.
  h.mgr.initiate('carol', { type: 'call:initiate', callId: 'c9', receiverId: 'bob', media: 'audio' });
  assert.equal(h.to('bob', 'call:incoming').length, 2);
});

test('reject notifies caller and frees both users', (t) => {
  const h = makeHarness();
  t.after(() => h.mgr.dispose());

  initiate(h.mgr, 'c1', 'bob');
  h.mgr.reject('bob', 'c1', 'declined');

  const rejects = h.to('alice', 'call:reject');
  assert.equal(rejects.length, 1);
  assert.equal(rejects[0].frame.reason, 'declined');
  assert.equal(h.mgr.stats().activeCalls, 0);

  h.mgr.initiate('carol', { type: 'call:initiate', callId: 'c2', receiverId: 'alice', media: 'audio' });
  assert.equal(h.to('alice', 'call:incoming').length, 1);
});

test('reject after accept behaves as hangup', (t) => {
  const h = makeHarness();
  t.after(() => h.mgr.dispose());

  initiate(h.mgr, 'c1', 'bob');
  h.mgr.accept('bob', 'c1');
  h.mgr.reject('bob', 'c1', 'declined');

  assert.equal(h.to('alice', 'call:reject').length, 0);
  assert.equal(h.to('alice', 'call:hangup').length, 1);
  assert.equal(h.mgr.stats().activeCalls, 0);
});

test('ring timeout notifies both sides and cleans up', async (t) => {
  const h = makeHarness({ ringTimeoutMs: 40 });
  t.after(() => h.mgr.dispose());

  initiate(h.mgr, 'c1', 'bob');
  await sleep(120);

  assert.equal(h.to('alice', 'call:timeout').length, 1);
  assert.equal(h.to('bob', 'call:timeout').length, 1);
  assert.equal(h.mgr.stats().activeCalls, 0);
});

test('answer timeout after accept notifies both sides', async (t) => {
  const h = makeHarness({ answerTimeoutMs: 40 });
  t.after(() => h.mgr.dispose());

  initiate(h.mgr, 'c1', 'bob');
  h.mgr.accept('bob', 'c1');
  await sleep(120);

  const fails = h.to('alice', 'call:failed');
  assert.equal(fails.length, 1);
  assert.equal(fails[0].frame.reason, 'answer_timeout');
  assert.equal(h.to('bob', 'call:failed').length, 1);
  assert.equal(h.mgr.stats().activeCalls, 0);
});

test('ownership: non-participants and wrong roles get forbidden', (t) => {
  const h = makeHarness();
  t.after(() => h.mgr.dispose());

  initiate(h.mgr, 'c1', 'bob');
  h.mgr.accept('bob', 'c1');

  // Receiver cannot send an offer (caller's role).
  h.mgr.offer('bob', 'c1', { type: 'call:offer', callId: 'c1', sdp: 'evil' });
  const bobErr = h.to('bob', 'error');
  assert.equal(bobErr.length, 1);
  assert.equal(bobErr[0].frame.reason, 'forbidden');
  assert.equal(h.to('alice', 'call:offer').length, 0);

  // Third user cannot answer, reject, hang up or read the call.
  h.mgr.answer('carol', 'c1', { type: 'call:answer', callId: 'c1', sdp: 'x' });
  h.mgr.hangup('carol', 'c1', 'hangup');
  const carolErrs = h.to('carol', 'error');
  assert.equal(carolErrs.length, 2);
  assert.equal(carolErrs[0].frame.reason, 'forbidden');
  assert.equal(h.mgr.stats().activeCalls, 1); // call untouched

  // Unknown call id -> unknown_call (no state leak).
  h.mgr.hangup('alice', 'nope', 'hangup');
  assert.equal(h.to('alice', 'error')[0].frame.reason, 'unknown_call');
});

test('disconnect grace: peer notified after grace, cleanup happens', async (t) => {
  const h = makeHarness({ disconnectGraceMs: 50 });
  t.after(() => h.mgr.dispose());

  initiate(h.mgr, 'c1', 'bob');
  h.mgr.accept('bob', 'c1');
  h.mgr.disconnect('alice'); // socket lost

  await sleep(20);
  assert.equal(h.to('bob', 'call:hangup').length, 0); // still within grace
  assert.equal(h.mgr.stats().activeCalls, 1);

  await sleep(80);
  const hangups = h.to('bob', 'call:hangup');
  assert.equal(hangups.length, 1);
  assert.equal(hangups[0].frame.reason, 'disconnect');
  assert.equal(h.mgr.stats().activeCalls, 0);
  assert.equal(h.mgr.stats().busyUsers, 0);
});

test('reconnect within grace cancels cleanup', async (t) => {
  const h = makeHarness({ disconnectGraceMs: 120 });
  t.after(() => h.mgr.dispose());

  initiate(h.mgr, 'c1', 'bob');
  h.mgr.accept('bob', 'c1');

  h.mgr.disconnect('alice');
  await sleep(30);
  h.mgr.reconnect('alice'); // back before grace expires
  await sleep(200);

  assert.equal(h.to('bob', 'call:hangup').length, 0);
  assert.equal(h.mgr.stats().activeCalls, 1);

  // Call still fully usable afterwards.
  h.mgr.hangup('bob', 'c1', 'hangup');
  assert.equal(h.to('alice', 'call:hangup').length, 1);
  assert.equal(h.mgr.stats().activeCalls, 0);
});

test('delivery race during initiate reports offline', (t) => {
  const h = makeHarness();
  t.after(() => h.mgr.dispose());

  // isOnline says yes, but send fails (socket dropped mid-flight).
  h.failSend.add('bob');
  initiate(h.mgr, 'c1', 'bob');

  const failed = h.to('alice', 'call:failed');
  assert.equal(failed.length, 1);
  assert.equal(failed[0].frame.reason, 'offline');
  assert.equal(h.mgr.stats().activeCalls, 0);
  assert.equal(h.mgr.stats().busyUsers, 0);
});

test('receiver ringing ack is relayed to caller (idempotent)', (t) => {
  const h = makeHarness();
  t.after(() => h.mgr.dispose());

  initiate(h.mgr, 'c1', 'bob');
  h.mgr.relayRinging('bob', 'c1');
  h.mgr.relayRinging('bob', 'c1');

  // 1 from delivery + 2 relays
  assert.equal(h.to('alice', 'call:ringing').length, 3);

  // Callers cannot ack their own ringing; non-participants get forbidden.
  h.mgr.relayRinging('alice', 'c1');
  h.mgr.relayRinging('carol', 'c1');
  assert.equal(h.to('carol', 'error').length, 1);
});
