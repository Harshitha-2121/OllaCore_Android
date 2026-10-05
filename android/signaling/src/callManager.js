'use strict';

// In-memory call lifecycle manager. NO database, NO persistence:
// every call exists only in these Maps and disappears on hangup/timeout/disconnect.
// All identity comes from the authenticated socket (userId passed in by server.js).

const CALL_ID_RE = /^[A-Za-z0-9_.:\-]{1,64}$/;
const USER_ID_RE = /^[A-Za-z0-9_.@:\-]{1,128}$/;
const MAX_PENDING_FRAMES = 100;
const MAX_PENDING_BYTES = 512 * 1024;
const MAX_NAME_CHARS = 128;

const MEDIA_TYPES = new Set(['audio', 'video']);

function sanitizeString(value, max) {
  if (typeof value !== 'string') return '';
  return value.slice(0, max);
}

class CallManager {
  /**
   * @param {object} opts
   * @param {(userId: string, frame: object) => boolean} opts.sendTo deliver frame, false if offline
   * @param {(userId: string) => boolean} opts.isOnline
   * @param {number} opts.ringTimeoutMs
   * @param {number} opts.answerTimeoutMs
   * @param {number} opts.disconnectGraceMs
   * @param {(msg: string) => void} [opts.log]
   */
  constructor(opts) {
    this.sendTo = opts.sendTo;
    this.isOnline = opts.isOnline;
    this.ringTimeoutMs = opts.ringTimeoutMs;
    this.answerTimeoutMs = opts.answerTimeoutMs;
    this.disconnectGraceMs = opts.disconnectGraceMs;
    this.log = opts.log || (() => {});

    this.activeCalls = new Map(); // callId -> session
    this.busyIndex = new Map(); // userId -> callId
  }

  stats() {
    return { activeCalls: this.activeCalls.size, busyUsers: this.busyIndex.size };
  }

  sessionOf(userId) {
    const callId = this.busyIndex.get(userId);
    return callId ? this.activeCalls.get(callId) || null : null;
  }

  _error(userId, reason) {
    this.sendTo(userId, { type: 'error', reason });
  }

  _cleanup(session) {
    if (!session || session.ended) return;
    session.ended = true;
    if (session.ringTimer) clearTimeout(session.ringTimer);
    if (session.answerTimer) clearTimeout(session.answerTimer);
    if (session.disconnectTimers) {
      for (const t of session.disconnectTimers.values()) clearTimeout(t);
      session.disconnectTimers.clear();
    }
    // Delete only if the index still points at THIS call (a newer call may exist).
    if (this.busyIndex.get(session.callerId) === session.callId) this.busyIndex.delete(session.callerId);
    if (this.busyIndex.get(session.receiverId) === session.callId) this.busyIndex.delete(session.receiverId);
    this.activeCalls.delete(session.callId);
  }

  _other(session, userId) {
    return userId === session.callerId ? session.receiverId : session.callerId;
  }

  _validSessionCallId(userId, callId) {
    if (typeof callId !== 'string' || !CALL_ID_RE.test(callId)) {
      this._error(userId, 'bad_request');
      return null;
    }
    const session = this.activeCalls.get(callId);
    if (!session) {
      this._error(userId, 'unknown_call');
      return null;
    }
    if (userId !== session.callerId && userId !== session.receiverId) {
      // Never leak call details to non-participants.
      this._error(userId, 'forbidden');
      return null;
    }
    return session;
  }

  // ---- C -> S operations -------------------------------------------------

  initiate(callerId, frame) {
    const callId = frame.callId;
    const receiverId = frame.receiverId;
    const media = MEDIA_TYPES.has(frame.media) ? frame.media : 'audio';

    if (!CALL_ID_RE.test(String(callId || '')) || !USER_ID_RE.test(String(receiverId || ''))) {
      this._error(callerId, 'bad_request');
      return;
    }
    if (receiverId === callerId) {
      this._error(callerId, 'self_call');
      return;
    }
    if (this.activeCalls.has(callId)) {
      this.sendTo(callerId, { type: 'call:failed', callId, reason: 'duplicate_call_id' });
      return;
    }
    if (this.busyIndex.has(callerId)) {
      this._error(callerId, 'caller_busy');
      return;
    }
    if (this.busyIndex.has(receiverId)) {
      this.sendTo(callerId, { type: 'call:busy', callId });
      return;
    }
    if (!this.isOnline(receiverId)) {
      this.sendTo(callerId, { type: 'call:failed', callId, reason: 'offline' });
      return;
    }

    const session = {
      callId,
      callerId,
      receiverId,
      media,
      roomId: sanitizeString(frame.roomId, 128),
      callerName: sanitizeString(frame.callerName, MAX_NAME_CHARS),
      status: 'ringing',
      createdAt: Date.now(),
      ended: false,
      pending: [],
      pendingBytes: 0,
      ringTimer: null,
      answerTimer: null,
      disconnectTimers: new Map(),
    };

    const delivered = this.sendTo(receiverId, {
      type: 'call:incoming',
      callId,
      callerId,
      callerName: session.callerName,
      roomId: session.roomId,
      media,
    });
    if (!delivered) {
      // Raced with a disconnect.
      this.sendTo(callerId, { type: 'call:failed', callId, reason: 'offline' });
      return;
    }

    this.activeCalls.set(callId, session);
    this.busyIndex.set(callerId, callId);
    this.busyIndex.set(receiverId, callId);

    // Caller sees RINGING as soon as the invite reached the callee's device.
    this.sendTo(callerId, { type: 'call:ringing', callId });

    session.ringTimer = setTimeout(() => this._onRingTimeout(session), this.ringTimeoutMs);
    this.log(`call ${callId}: ${callerId} -> ${receiverId} (${media}) ringing`);
  }

  relayRinging(userId, callId) {
    const session = this._validSessionCallId(userId, callId);
    if (!session) return;
    // Only the receiver may ack ringing; idempotent for the caller.
    if (userId === session.receiverId && session.status === 'ringing') {
      this.sendTo(session.callerId, { type: 'call:ringing', callId });
    }
  }

  accept(userId, callId) {
    const session = this._validSessionCallId(userId, callId);
    if (!session) return;
    if (userId !== session.receiverId) {
      this._error(userId, 'forbidden');
      return;
    }
    if (session.status !== 'ringing') return; // duplicate accept: no-op

    if (session.ringTimer) {
      clearTimeout(session.ringTimer);
      session.ringTimer = null;
    }
    session.status = 'accepted';

    // Flush the offer + ICE buffered while ringing so the callee can answer.
    const buffered = session.pending;
    session.pending = [];
    session.pendingBytes = 0;
    for (const frame of buffered) this.sendTo(session.receiverId, frame);

    this.sendTo(session.callerId, { type: 'call:accept', callId });
    session.answerTimer = setTimeout(() => this._onAnswerTimeout(session), this.answerTimeoutMs);
    this.log(`call ${callId}: accepted`);
  }

  reject(userId, callId, reason) {
    const session = this._validSessionCallId(userId, callId);
    if (!session) return;
    if (userId !== session.receiverId) {
      this._error(userId, 'forbidden');
      return;
    }
    if (session.status !== 'ringing') {
      // Reject after accept is just a hangup.
      this.hangup(userId, callId, 'hangup');
      return;
    }
    this.sendTo(session.callerId, {
      type: 'call:reject',
      callId,
      reason: sanitizeString(reason, 64) || 'declined',
    });
    this._cleanup(session);
    this.log(`call ${callId}: rejected`);
  }

  offer(userId, callId, frame) {
    const session = this._validSessionCallId(userId, callId);
    if (!session) return;
    if (userId !== session.callerId) {
      this._error(userId, 'forbidden');
      return;
    }
    const out = { type: 'call:offer', callId, sdp: frame.sdp };
    if (session.status === 'ringing') {
      this._buffer(session, out);
    } else {
      this.sendTo(session.receiverId, out);
    }
  }

  answer(userId, callId, frame) {
    const session = this._validSessionCallId(userId, callId);
    if (!session) return;
    if (userId !== session.receiverId) {
      this._error(userId, 'forbidden');
      return;
    }
    if (session.status !== 'accepted') {
      this._error(userId, 'not_accepted');
      return;
    }
    if (session.answerTimer) {
      clearTimeout(session.answerTimer);
      session.answerTimer = null;
    }
    this.sendTo(session.callerId, { type: 'call:answer', callId, sdp: frame.sdp });
  }

  candidate(userId, callId, frame) {
    const session = this._validSessionCallId(userId, callId);
    if (!session) return;
    // Relay ICE between participants whenever the call exists (caller buffers
    // its candidates while ringing; receiver's flow straight through).
    const out = { type: 'call:ice-candidate', callId };
    for (const key of Object.keys(frame)) {
      if (key !== 'type' && key !== 'callId') out[key] = frame[key];
    }
    if (session.status === 'ringing' && userId === session.callerId) {
      this._buffer(session, out);
    } else {
      this.sendTo(this._other(session, userId), out);
    }
  }

  hangup(userId, callId, reason) {
    const session = this._validSessionCallId(userId, callId);
    if (!session) return;
    this.sendTo(this._other(session, userId), {
      type: 'call:hangup',
      callId,
      reason: sanitizeString(reason, 64) || 'hangup',
    });
    this._cleanup(session);
    this.log(`call ${callId}: hung up by ${userId}`);
  }

  // ---- presence ----------------------------------------------------------

  disconnect(userId) {
    const session = this.sessionOf(userId);
    if (!session || session.ended) return;
    if (session.disconnectTimers.has(userId)) return;
    const timer = setTimeout(() => {
      session.disconnectTimers.delete(userId);
      const other = this._other(session, userId);
      this.sendTo(other, { type: 'call:hangup', callId: session.callId, reason: 'disconnect' });
      this._cleanup(session);
      this.log(`call ${session.callId}: cleaned up after ${userId} disconnect grace`);
    }, this.disconnectGraceMs);
    session.disconnectTimers.set(userId, timer);
  }

  reconnect(userId) {
    const session = this.sessionOf(userId);
    if (!session) return;
    const timer = session.disconnectTimers.get(userId);
    if (timer) {
      clearTimeout(timer);
      session.disconnectTimers.delete(userId);
      this.log(`call ${session.callId}: ${userId} reconnected within grace`);
    }
  }

  // ---- internals ---------------------------------------------------------

  _buffer(session, frame) {
    if (frame.type === 'call:offer') {
      // While ringing the LATEST offer wins (caller re-offers must not stack
      // into multiple remote offers the callee would try to answer).
      session.pending = session.pending.filter((f) => f.type !== 'call:offer');
      session.pendingBytes = 0;
      for (const f of session.pending) {
        try {
          session.pendingBytes += JSON.stringify(f).length;
        } catch (_) {
          /* ignore */
        }
      }
    }
    if (session.pending.length >= MAX_PENDING_FRAMES || session.pendingBytes > MAX_PENDING_BYTES) {
      this._error(session.callerId, 'buffer_overflow');
      return;
    }
    session.pending.push(frame);
    try {
      session.pendingBytes += JSON.stringify(frame).length;
    } catch (_) {
      /* frame not serializable - will fail on send anyway */
    }
  }

  _onRingTimeout(session) {
    if (session.ended || session.status !== 'ringing') return;
    this.sendTo(session.callerId, { type: 'call:timeout', callId: session.callId });
    this.sendTo(session.receiverId, { type: 'call:timeout', callId: session.callId });
    this._cleanup(session);
    this.log(`call ${session.callId}: ring timeout`);
  }

  _onAnswerTimeout(session) {
    if (session.ended || session.status !== 'accepted') return;
    this.sendTo(session.callerId, { type: 'call:failed', callId: session.callId, reason: 'answer_timeout' });
    this.sendTo(session.receiverId, { type: 'call:failed', callId: session.callId, reason: 'answer_timeout' });
    this._cleanup(session);
    this.log(`call ${session.callId}: answer timeout`);
  }

  dispose() {
    for (const session of [...this.activeCalls.values()]) this._cleanup(session);
  }
}

module.exports = { CallManager };
