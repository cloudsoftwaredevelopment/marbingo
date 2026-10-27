// Daily purge of raw GPS points and device events older than 365 days (agent_stops are kept).
const prisma = require('../db');
const { manilaParts } = require('../lib/tracking');

const KEEP_MS = 365 * 86400000;

async function run() {
  const cutoff = new Date(Date.now() - KEEP_MS);
  const points = await prisma.locationPoint.deleteMany({ where: { recordedAt: { lt: cutoff } } });
  const events = await prisma.deviceEvent.deleteMany({ where: { at: { lt: cutoff } } });
  const cache = await prisma.trackCache.deleteMany({ where: { day: { lt: manilaParts(cutoff).day } } });
  console.log(`retention: deleted ${points.count} location points, ${events.count} device events, ${cache.count} track cache rows`);
}

function start() {
  const go = () => run().catch((e) => console.error('retention failed', e));
  go();
  setInterval(go, 86400000).unref();
}

module.exports = { start, run };
