'use strict';

// Session-token verification. The userId ALWAYS comes from the backend response -
// clients never get to choose their own identity.
//   AUTH_MODE=backend : GET {API_BASE}/directory/me with the app's Bearer token
//                       (same endpoint/profile the app already uses for "my profile").
//   AUTH_MODE=dev     : token "dev:<userId>" - local testing only.

const CACHE_TTL_MS = 5 * 60 * 1000;
const CACHE_MAX = 1000;

class TokenVerifier {
  constructor(cfg, fetchImpl) {
    this.cfg = cfg;
    this.fetch = fetchImpl || ((...args) => globalThis.fetch(...args));
    this.cache = new Map(); // token -> { userId, expiresAt } (in-memory only)
  }

  async verify(token) {
    if (typeof token !== 'string' || token.length === 0 || token.length > 4096) return null;

    if (this.cfg.authMode === 'dev') {
      if (!token.startsWith('dev:')) return null;
      const userId = token.slice(4).trim();
      return /^[A-Za-z0-9_.@:\-]{1,128}$/.test(userId) ? userId : null;
    }

    const cached = this.cache.get(token);
    const now = Date.now();
    if (cached && cached.expiresAt > now) return cached.userId;

    try {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 8000);
      const res = await this.fetch(`${this.cfg.apiBase}/directory/me`, {
        headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
        signal: controller.signal,
      });
      clearTimeout(timer);
      if (!res.ok) return null;
      const profile = await res.json();
      const userId = profile && (profile.id || profile.user_id || profile.userId);
      if (typeof userId !== 'string' || userId.length === 0) return null;

      if (this.cache.size >= CACHE_MAX) this.cache.clear();
      this.cache.set(token, { userId, expiresAt: now + CACHE_TTL_MS });
      return userId;
    } catch (_) {
      return null;
    }
  }

  invalidate(token) {
    this.cache.delete(token);
  }
}

module.exports = { TokenVerifier };
