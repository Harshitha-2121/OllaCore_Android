'use strict';

const { loadConfig } = require('./config');
const { createSignalingServer } = require('./server');

const cfg = loadConfig();
const app = createSignalingServer(cfg);

app.start().catch((err) => {
  console.error('failed to start signaling server:', err.message);
  process.exit(1);
});

let shuttingDown = false;
async function shutdown(signal) {
  if (shuttingDown) return;
  shuttingDown = true;
  console.log(`received ${signal}, shutting down...`);
  const timer = setTimeout(() => process.exit(1), 5000);
  timer.unref();
  try {
    await app.close();
  } catch (_) {
    /* best effort */
  }
  process.exit(0);
}

process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
