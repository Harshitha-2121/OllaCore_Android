'use strict';

const crypto = require('crypto');

// Builds the ICE server list sent to clients after auth.
// STUN/TURN endpoints come from environment configuration only - never from clients,
// never hard-coded in the mobile app. TURN credentials can be:
//   - static (TURN_USERNAME/TURN_CREDENTIAL), or
//   - short-lived (TURN_SECRET): HMAC-SHA1(time-limited username), coturn use-auth-secret style.
function buildIceServers(cfg, userId) {
  const servers = [];
  if (cfg.stunUrls.length > 0) servers.push({ urls: cfg.stunUrls });

  if (cfg.turnUrls.length > 0) {
    if (cfg.turnSecret) {
      const expiry = Math.floor(Date.now() / 1000) + cfg.turnTtlSec;
      const username = `${expiry}:${userId || 'anon'}`;
      const credential = crypto.createHmac('sha1', cfg.turnSecret).update(username).digest('base64');
      servers.push({ urls: cfg.turnUrls, username, credential });
    } else if (cfg.turnUsername || cfg.turnCredential) {
      servers.push({
        urls: cfg.turnUrls,
        username: cfg.turnUsername,
        credential: cfg.turnCredential,
      });
    } else {
      // TURN URLs configured but without credentials: an unauthenticated TURN is useless
      // (and some servers reject it), so skip it rather than send a broken entry.
    }
  }
  return servers;
}

module.exports = { buildIceServers };
