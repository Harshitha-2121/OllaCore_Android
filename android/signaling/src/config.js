'use strict';

const fs = require('fs');
const path = require('path');

function loadDotEnv(file) {
  if (!fs.existsSync(file)) return;
  const text = fs.readFileSync(file, 'utf8');
  for (const line of text.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const eq = trimmed.indexOf('=');
    if (eq <= 0) continue;
    const key = trimmed.slice(0, eq).trim();
    let value = trimmed.slice(eq + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    if (!(key in process.env)) process.env[key] = value;
  }
}

function csv(value) {
  return String(value || '')
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
}

function int(value, fallback) {
  const n = parseInt(value, 10);
  return Number.isFinite(n) && n >= 0 ? n : fallback;
}

function loadConfig(env = process.env) {
  loadDotEnv(path.join(__dirname, '..', '.env'));

  const authMode = (env.AUTH_MODE || 'backend').toLowerCase();
  if (authMode !== 'backend' && authMode !== 'dev') {
    throw new Error(`Invalid AUTH_MODE "${authMode}" (expected "backend" or "dev")`);
  }

  const tlsKey = env.TLS_KEY || '';
  const tlsCert = env.TLS_CERT || '';

  return {
    port: int(env.PORT, 8080),
    host: env.HOST || '0.0.0.0',
    authMode,
    apiBase: (env.API_BASE || 'https://api.ollacore.com/v1').replace(/\/+$/, ''),
    stunUrls: csv(env.STUN_URLS),
    turnUrls: csv(env.TURN_URLS),
    turnUsername: env.TURN_USERNAME || '',
    turnCredential: env.TURN_CREDENTIAL || '',
    turnSecret: env.TURN_SECRET || '',
    turnTtlSec: int(env.TURN_TTL_SEC, 3600),
    ringTimeoutMs: int(env.RING_TIMEOUT_MS, 45000),
    answerTimeoutMs: int(env.ANSWER_TIMEOUT_MS, 60000),
    disconnectGraceMs: int(env.DISCONNECT_GRACE_MS, 15000),
    authTimeoutMs: int(env.AUTH_TIMEOUT_MS, 10000),
    maxFrameBytes: int(env.MAX_FRAME_BYTES, 262144),
    rateLimitPerSec: int(env.RATE_LIMIT_PER_SEC, 60),
    tlsKey,
    tlsCert,
    tlsEnabled: Boolean(tlsKey && tlsCert),
  };
}

module.exports = { loadConfig };
