const router = require('express').Router();
const prisma = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');
const { fail, wrap } = require('../lib/http');
const { parseIso, startOfDay } = require('../lib/periods');
const T = require('../lib/tracking');
const D = require('../lib/deviceEvents');
const { matchTrack } = require('../lib/osrm');

router.use(authenticate);

const DAY_MS = 86400000;
const MAX_POINTS = 500;
const LOOKBACK_MS = 7 * DAY_MS; // a gap may have opened before the day being read
const RECOMPUTE_MS = 60000;
const uploaders = requireRole('AGENT', 'MANAGER');
const manager = requireRole('MANAGER');

const userRef = (u) => ({ id: u.id, firstName: u.firstName, lastName: u.lastName });
const dayStart = (day) => startOfDay(parseIso(day));
const dayEnd = (day) => new Date(dayStart(day).getTime() + DAY_MS);
const today = () => T.manilaParts(new Date()).day;
const dayList = (a, b) => {
  const out = [];
  for (let d = parseIso(a); d <= parseIso(b); d++) out.push(new Date(d * DAY_MS).toISOString().slice(0, 10));
  return out;
};

// ---- stop recomputation ----------------------------------------------------

const timers = new Map(); // "agent:day" → pending debounce timer
const ran = new Map(); // "agent:day" → { at, latest } of the last recompute in this process

const inflight = new Map(); // "agent:day" → running recompute, so concurrent callers share one run

// Replaces the agent-day's stops from its raw points and matches them to stores.
function recomputeDay(agentId, day) {
  const key = `${agentId}:${day}`;
  if (!inflight.has(key)) inflight.set(key, runRecompute(agentId, day).finally(() => inflight.delete(key)));
  return inflight.get(key);
}

async function runRecompute(agentId, day) {
  const points = await prisma.locationPoint.findMany({
    where: { agentId, recordedAt: { gte: dayStart(day), lt: dayEnd(day) } },
    orderBy: { recordedAt: 'asc' },
    select: { recordedAt: true, lat: true, lng: true, accuracy: true },
  });
  const stores = await prisma.customer.findMany({
    where: { lat: { not: null }, lng: { not: null } }, select: { id: true, lat: true, lng: true },
  });
  const stops = T.detectStops(points).map((s) => {
    const store = T.matchStore(s, stores);
    return { ...s, agentId, day, customerId: store ? store.id : null };
  });
  await prisma.$transaction([
    prisma.agentStop.deleteMany({ where: { agentId, day } }),
    prisma.agentStop.createMany({ data: stops, skipDuplicates: true }),
  ]);
  ran.set(`${agentId}:${day}`, { at: Date.now(), latest: points.length ? points[points.length - 1].recordedAt.getTime() : 0 });
  return stops.length;
}

function scheduleRecompute(agentId, day) {
  const key = `${agentId}:${day}`;
  if (timers.has(key)) return;
  const timer = setTimeout(() => {
    timers.delete(key);
    recomputeDay(agentId, day).catch((e) => console.error('recomputeDay failed', e));
  }, RECOMPUTE_MS);
  timer.unref();
  timers.set(key, timer);
}

// Recomputes an agent-day synchronously when new points arrived since its stops were built.
async function ensureFresh(agentId, day) {
  const key = `${agentId}:${day}`;
  const where = { agentId, recordedAt: { gte: dayStart(day), lt: dayEnd(day) } };
  const last = await prisma.locationPoint.findFirst({ where, orderBy: { recordedAt: 'desc' }, select: { recordedAt: true } });
  if (!last) return;
  const rec = ran.get(key);
  if (rec) {
    if (last.recordedAt.getTime() > rec.latest && Date.now() - rec.at >= RECOMPUTE_MS) await recomputeDay(agentId, day);
    return;
  }
  const stop = await prisma.agentStop.findFirst({ where: { agentId, day }, orderBy: { endedAt: 'desc' }, select: { endedAt: true } });
  if (!stop || last.recordedAt > stop.endedAt) await recomputeDay(agentId, day);
}

async function freshenDays(days, agentId) {
  const recent = days.filter((d) => d === today() || d === T.manilaParts(new Date(Date.now() - DAY_MS)).day);
  for (const day of recent) {
    let ids = [agentId];
    if (!agentId) {
      const rows = await prisma.locationPoint.groupBy({
        by: ['agentId'], where: { recordedAt: { gte: dayStart(day), lt: dayEnd(day) } },
      });
      ids = rows.map((r) => r.agentId);
    }
    for (const id of ids) await ensureFresh(id, day);
  }
}

// ---- upload & consent ------------------------------------------------------

router.post('/points', uploaders, wrap(async (req, res) => {
  const list = req.body && req.body.points;
  if (!Array.isArray(list)) return fail(res, 400, 'points must be a list.');
  // Data Privacy Act: no location is stored until the agent has acknowledged the tracking notice.
  if (!req.user.trackingConsentAt) return fail(res, 403, 'Accept the location sharing notice in the app first.');
  if (list.length > MAX_POINTS) return fail(res, 400, `Send at most ${MAX_POINTS} points at a time.`);
  const schedule = await T.readSchedule(prisma);
  const now = new Date();
  const valid = [];
  for (const raw of list) {
    const r = T.validatePoint(raw, schedule, now);
    if (r.point) valid.push({ ...r.point, agentId: req.user.id });
  }
  const created = valid.length ? (await prisma.locationPoint.createMany({ data: valid, skipDuplicates: true })).count : 0;
  if (created) {
    const days = new Set(valid.map((p) => T.manilaParts(p.recordedAt).day));
    for (const day of days) scheduleRecompute(req.user.id, day);
  }
  res.json({ accepted: created, duplicates: valid.length - created, rejected: list.length - valid.length });
}));

router.post('/events', uploaders, wrap(async (req, res) => {
  const list = req.body && req.body.events;
  if (!Array.isArray(list)) return fail(res, 400, 'events must be a list.');
  if (!req.user.trackingConsentAt) return fail(res, 403, 'Accept the location sharing notice in the app first.');
  if (list.length > MAX_POINTS) return fail(res, 400, `Send at most ${MAX_POINTS} events at a time.`);
  const now = new Date();
  const valid = [];
  for (const raw of list) {
    const r = D.validateEvent(raw, now);
    if (r.event) valid.push({ ...r.event, agentId: req.user.id, detail: r.event.detail ?? undefined });
  }
  // The tablet's clock is off: note it (at most hourly), since its timestamps are then off too.
  const skew = D.clockSkew(req.body.sentAt, now);
  if (skew !== null) {
    const recent = await prisma.deviceEvent.findFirst({
      where: { agentId: req.user.id, type: 'clock_skew', at: { gt: new Date(now.getTime() - 3600000) } }, select: { id: true },
    });
    if (!recent) await prisma.deviceEvent.create({ data: { agentId: req.user.id, type: 'clock_skew', at: now, detail: { shiftMs: skew } } });
  }
  const created = valid.length ? (await prisma.deviceEvent.createMany({ data: valid, skipDuplicates: true })).count : 0;
  res.json({ accepted: created, duplicates: valid.length - created, rejected: list.length - valid.length });
}));

router.post('/consent', uploaders, wrap(async (req, res) => {
  await prisma.user.updateMany({ where: { id: req.user.id, trackingConsentAt: null }, data: { trackingConsentAt: new Date() } });
  const u = await prisma.user.findUnique({ where: { id: req.user.id }, select: { trackingConsentAt: true } });
  res.json({ trackingConsentAt: u.trackingConsentAt });
}));

// ---- live ------------------------------------------------------------------

router.get('/live', manager, wrap(async (req, res) => {
  const agents = await prisma.user.findMany({
    where: { role: 'AGENT', active: true }, orderBy: [{ firstName: 'asc' }, { id: 'asc' }],
  });
  const schedule = await T.readSchedule(prisma);
  const now = new Date();
  const day = today();
  const out = [];
  for (const a of agents) {
    await ensureFresh(a.id, day);
    const where = { agentId: a.id, recordedAt: { gte: dayStart(day), lt: dayEnd(day) } };
    const [last, points, stops, events] = await Promise.all([
      prisma.locationPoint.findFirst({ where: { agentId: a.id }, orderBy: { recordedAt: 'desc' } }),
      prisma.locationPoint.findMany({
        where, orderBy: { recordedAt: 'asc' }, select: { recordedAt: true, lat: true, lng: true, accuracy: true },
      }),
      prisma.agentStop.findMany({ where: { agentId: a.id, day } }),
      prisma.deviceEvent.findMany({ where: { agentId: a.id, at: { gte: new Date(dayStart(day).getTime() - LOOKBACK_MS) } } }),
    ]);
    const log = D.dayLog(events, { day, schedule, now });
    let status = 'off_duty';
    if (last && now - last.recordedAt < 300000) status = 'tracking';
    else if (T.inSchedule(now, schedule)) status = 'signal_lost';
    out.push({
      agent: userRef(a),
      status,
      last: last ? {
        recordedAt: last.recordedAt, lat: last.lat, lng: last.lng, accuracy: last.accuracy, speed: last.speed, battery: last.battery,
      } : null,
      todayStops: stops.length,
      todayDistanceM: T.summarise(points, stops).distanceM,
      alert: T.inSchedule(now, schedule) ? D.currentAlert(events) : null,
      todayNoLocationS: log.summary.noLocationS,
      todayOfflineS: log.summary.offlineS,
    });
  }
  res.json(out);
}));

// ---- device log ------------------------------------------------------------

router.get('/device-log', manager, wrap(async (req, res) => {
  const agentId = Number(req.query.agentId);
  if (!Number.isInteger(agentId) || agentId < 1) return fail(res, 400, 'Choose an agent.', { agentId: 'Choose an agent.' });
  const day = req.query.date ? String(req.query.date) : today();
  if (parseIso(day) === null) return fail(res, 400, 'Date must be YYYY-MM-DD.', { date: 'Date must be YYYY-MM-DD.' });
  const schedule = await T.readSchedule(prisma);
  const events = await prisma.deviceEvent.findMany({
    where: { agentId, at: { gte: new Date(dayStart(day).getTime() - LOOKBACK_MS), lt: dayEnd(day) } },
    orderBy: { at: 'asc' }, select: { type: true, at: true, detail: true },
  });
  const win = D.workWindow(day, schedule);
  res.json({
    agentId, date: day,
    workStart: win ? new Date(win[0]) : null, workEnd: win ? new Date(win[1]) : null,
    ...D.dayLog(events, { day, schedule, now: new Date() }),
  });
}));

// ---- track -----------------------------------------------------------------

const thin = (pts, max) => {
  if (pts.length <= max) return pts;
  const step = pts.length / max;
  const out = [];
  for (let i = 0; i < max; i++) out.push(pts[Math.floor(i * step)]);
  out[out.length - 1] = pts[pts.length - 1];
  return out;
};

router.get('/track', manager, wrap(async (req, res) => {
  const agentId = Number(req.query.agentId);
  if (!Number.isInteger(agentId) || agentId < 1) return fail(res, 400, 'Choose an agent.', { agentId: 'Choose an agent.' });
  const day = req.query.date ? String(req.query.date) : today();
  if (parseIso(day) === null) return fail(res, 400, 'Date must be YYYY-MM-DD.', { date: 'Date must be YYYY-MM-DD.' });
  const from = req.query.from ? T.parseHM(req.query.from) : null;
  const to = req.query.to ? T.parseHM(req.query.to) : null;
  if ((req.query.from && from === null) || (req.query.to && to === null)) {
    return fail(res, 400, 'Times must be HH:MM.', { from: 'Use HH:MM.', to: 'Use HH:MM.' });
  }
  const clipped = from !== null || to !== null;

  await ensureFresh(agentId, day);
  const t0 = dayStart(day).getTime();
  const lo = new Date(t0 + (from === null ? 0 : from * 60000));
  const hi = new Date(t0 + (to === null ? 1440 : to) * 60000);
  const points = await prisma.locationPoint.findMany({
    where: { agentId, recordedAt: { gte: lo, lt: to === null ? hi : new Date(hi.getTime() + 60000) } },
    orderBy: { recordedAt: 'asc' },
    select: { recordedAt: true, lat: true, lng: true, accuracy: true },
  });
  let stops = await prisma.agentStop.findMany({ where: { agentId, day }, orderBy: { startedAt: 'asc' } });
  if (clipped) stops = stops.filter((s) => s.endedAt >= lo && s.startedAt <= hi);

  let geo = null;
  if (!clipped) {
    const cache = await prisma.trackCache.findUnique({ where: { agentId_day: { agentId, day } } });
    // An unsnapped entry (from an OSRM outage) is retried once OSRM is configured again.
    if (cache && cache.pointCount === points.length && (cache.snapped || !process.env.OSRM_URL)) geo = { snapped: cache.snapped, route: cache.geometry };
  }
  if (!geo) {
    const m = await matchTrack(points);
    geo = { snapped: m.snapped, route: m.route };
    if (!clipped && points.length && m.snapped) {
      const data = { pointCount: points.length, geometry: m.route, snapped: m.snapped, distanceM: m.distanceM, computedAt: new Date() };
      // Prisma upsert isn't atomic: a concurrent request may have just created the row.
      await prisma.trackCache.upsert({ where: { agentId_day: { agentId, day } }, update: data, create: { agentId, day, ...data } })
        .catch((e) => { if (e.code !== 'P2002') throw e; });
    }
  }

  const sum = T.summarise(points, stops);
  res.json({
    agentId, date: day, snapped: geo.snapped, route: geo.route,
    points: thin(points, 2000).map((p) => ({ t: p.recordedAt, lat: p.lat, lng: p.lng, accuracy: p.accuracy })),
    summary: {
      ...sum,
      stops: stops.length,
      storesVisited: new Set(stops.filter((s) => s.customerId).map((s) => s.customerId)).size,
      firstAt: points.length ? points[0].recordedAt : null,
      lastAt: points.length ? points[points.length - 1].recordedAt : null,
    },
  });
}));

// ---- stops & visits --------------------------------------------------------

const stopInclude = {
  agent: { select: { id: true, firstName: true, lastName: true } },
  customer: { select: { id: true, name: true } },
};

const shapeStop = (s) => ({
  id: s.id, agent: s.agent, day: s.day, startedAt: s.startedAt, endedAt: s.endedAt, durationS: s.durationS,
  lat: s.lat, lng: s.lng, customer: s.customer, pointCount: s.pointCount,
});

// Reads from/to dates (defaults per `fallback`); returns { error } or { from, to } as YYYY-MM-DD strings or null.
function dateRange(q, fallback) {
  const from = q.from ? String(q.from) : fallback;
  const to = q.to ? String(q.to) : fallback;
  if ((from && parseIso(from) === null) || (to && parseIso(to) === null)) return { error: 'Dates must be YYYY-MM-DD.' };
  if (from && to && parseIso(to) < parseIso(from)) return { error: 'Choose a start date before the end date.' };
  if (from && to && parseIso(to) - parseIso(from) > 365) return { error: 'Choose a range of 366 days or less.' };
  return { from, to };
}

router.get('/stops', manager, wrap(async (req, res) => {
  const q = req.query;
  const r = dateRange(q, today());
  if (r.error) return fail(res, 400, r.error);
  const where = { day: { gte: r.from, lte: r.to } };
  if (q.agentId !== undefined) {
    const id = Number(q.agentId);
    if (!Number.isInteger(id) || id < 1) return fail(res, 400, 'agentId must be a number.');
    where.agentId = id;
  }
  if (q.customerId !== undefined) {
    const id = Number(q.customerId);
    if (!Number.isInteger(id) || id < 1) return fail(res, 400, 'customerId must be a number.');
    where.customerId = id;
  }
  const minMinutes = q.minMinutes === undefined ? 3 : Number(q.minMinutes);
  if (!Number.isFinite(minMinutes) || minMinutes < 0) return fail(res, 400, 'minMinutes must be a number.');
  where.durationS = { gte: Math.round(minMinutes * 60) };

  let near = null;
  if (q.near !== undefined) {
    const [lat, lng] = String(q.near).split(',').map(Number);
    const radius = q.radius === undefined ? 300 : Number(q.radius);
    if (![lat, lng, radius].every(Number.isFinite) || Math.abs(lat) > 90 || Math.abs(lng) > 180 || radius < 0) {
      return fail(res, 400, 'near must be "lat,lng" and radius a number of metres.');
    }
    near = { lat, lng, radius };
    const dLat = (radius / 111320) * 1.01;
    const dLng = dLat / Math.max(0.01, Math.cos((lat * Math.PI) / 180));
    where.lat = { gte: lat - dLat, lte: lat + dLat };
    where.lng = { gte: lng - dLng, lte: lng + dLng };
  }
  const tf = q.timeFrom ? T.parseHM(q.timeFrom) : null;
  const tt = q.timeTo ? T.parseHM(q.timeTo) : null;
  if ((q.timeFrom && tf === null) || (q.timeTo && tt === null)) return fail(res, 400, 'Times must be HH:MM.');

  await freshenDays(dayList(r.from, r.to), where.agentId);
  let rows = await prisma.agentStop.findMany({ where, include: stopInclude, orderBy: { startedAt: 'asc' }, take: 5000 });
  if (near) rows = rows.filter((s) => T.haversine(s.lat, s.lng, near.lat, near.lng) <= near.radius);
  if (tf !== null || tt !== null) {
    rows = rows.filter((s) => {
      const m = T.manilaParts(s.startedAt).minutes;
      return (tf === null || m >= tf) && (tt === null || m <= tt);
    });
  }
  res.json(rows.map(shapeStop));
}));

router.get('/visits', manager, wrap(async (req, res) => {
  const customerId = Number(req.query.customerId);
  if (!Number.isInteger(customerId) || customerId < 1) return fail(res, 400, 'Choose a store.', { customerId: 'Choose a store.' });
  const r = dateRange(req.query, null);
  if (r.error) return fail(res, 400, r.error);
  const where = { customerId };
  if (r.from || r.to) where.day = { ...(r.from ? { gte: r.from } : {}), ...(r.to ? { lte: r.to } : {}) };
  await freshenDays([today(), T.manilaParts(new Date(Date.now() - DAY_MS)).day]);
  const [rows, agg, newest] = await Promise.all([
    prisma.agentStop.findMany({ where, include: stopInclude, orderBy: { startedAt: 'desc' }, take: 200 }),
    prisma.agentStop.aggregate({ where, _count: true, _sum: { durationS: true } }),
    prisma.agentStop.findFirst({ where, orderBy: { startedAt: 'desc' }, select: { startedAt: true } }),
  ]);
  res.json({
    visits: rows.map(shapeStop),
    summary: { visits: agg._count, totalS: agg._sum.durationS || 0, lastVisitAt: newest ? newest.startedAt : null },
  });
}));

module.exports = router;
module.exports.recomputeDay = recomputeDay;
