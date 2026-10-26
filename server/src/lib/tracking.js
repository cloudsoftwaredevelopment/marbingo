// Pure GPS tracking helpers (no DB, no network). Manila time is UTC+8 with no DST.
const { dayOf, startOfDay } = require('./periods');

const R = 6371000;
const DAY_MS = 86400000;
const DEFAULT_SCHEDULE = { days: [1, 2, 3, 4, 5, 6], start: '07:00', end: '18:00' };

const STOP_RADIUS_M = 60;
const STOP_MIN_S = 180;
const MAX_ACCURACY_M = 100;
const STORE_MATCH_M = 75;
const MAX_GAP_S = 600;

const rad = (d) => (d * Math.PI) / 180;

function haversine(lat1, lng1, lat2, lng2) {
  const dLat = rad(lat2 - lat1);
  const dLng = rad(lng2 - lng1);
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
}

// Time of a point in ms. Accepts { t } or { recordedAt } as Date, ISO string or number.
const tMs = (p) => {
  const v = p.t !== undefined ? p.t : p.recordedAt;
  return v instanceof Date ? v.getTime() : typeof v === 'number' ? v : new Date(v).getTime();
};

const parseHM = (s) => {
  const m = /^(\d{2}):(\d{2})$/.exec(String(s || ''));
  if (!m || +m[1] > 23 || +m[2] > 59) return null;
  return +m[1] * 60 + +m[2];
};

// Manila calendar day (YYYY-MM-DD), ISO weekday (1 = Mon … 7 = Sun) and minutes since midnight.
function manilaParts(date) {
  const dn = dayOf(date);
  const sun0 = (dn + 4) % 7; // epoch day 0 was a Thursday
  return {
    day: new Date(dn * DAY_MS).toISOString().slice(0, 10),
    dow: sun0 === 0 ? 7 : sun0,
    minutes: Math.floor((date.getTime() - startOfDay(dn).getTime()) / 60000),
  };
}

// Inside the work window: start <= time < end on a work day.
function inSchedule(date, schedule = DEFAULT_SCHEDULE) {
  const p = manilaParts(date);
  if (!schedule.days.includes(p.dow)) return false;
  return p.minutes >= parseHM(schedule.start) && p.minutes < parseHM(schedule.end);
}

// Returns { schedule } or { fields } for a PUT body.
function validateSchedule(b) {
  const fields = {};
  const days = b && b.days;
  let list = null;
  if (!Array.isArray(days) || !days.length) fields.days = 'Choose at least one work day.';
  else if (!days.every((d) => Number.isInteger(d) && d >= 1 && d <= 7) || new Set(days).size !== days.length) {
    fields.days = 'Days must be unique numbers from 1 (Mon) to 7 (Sun).';
  } else list = [...days].sort((a, c) => a - c);
  const s = parseHM(b && b.start);
  const e = parseHM(b && b.end);
  if (s === null) fields.start = 'Use a time like 07:00.';
  if (e === null) fields.end = 'Use a time like 18:00.';
  if (s !== null && e !== null && s >= e) fields.end = 'End time must be after the start time.';
  if (Object.keys(fields).length) return { fields };
  return { schedule: { days: list, start: b.start, end: b.end } };
}

function parseSchedule(raw) {
  try {
    const r = validateSchedule(JSON.parse(raw));
    return r.schedule || DEFAULT_SCHEDULE;
  } catch {
    return DEFAULT_SCHEDULE;
  }
}

async function readSchedule(prisma) {
  const row = await prisma.setting.findUnique({ where: { key: 'tracking_schedule' } });
  return row ? parseSchedule(row.value) : DEFAULT_SCHEDULE;
}

const num = (v) => (v === undefined || v === null || v === '' || !Number.isFinite(Number(v)) ? null : Number(v));

// Returns { point } (normalised for storage) or { reason } when the point must be rejected.
function validatePoint(raw, schedule = DEFAULT_SCHEDULE, now = new Date()) {
  if (!raw || typeof raw !== 'object') return { reason: 'invalid' };
  const recordedAt = new Date(raw.recordedAt);
  if (typeof raw.recordedAt !== 'string' || Number.isNaN(recordedAt.getTime())) return { reason: 'time' };
  const lat = num(raw.lat);
  const lng = num(raw.lng);
  if (lat === null || lng === null || lat < -90 || lat > 90 || lng < -180 || lng > 180) return { reason: 'range' };
  const accuracy = num(raw.accuracy);
  if (accuracy !== null && (accuracy < 0 || accuracy > 500)) return { reason: 'accuracy' };
  const age = now.getTime() - recordedAt.getTime();
  if (age < -600000) return { reason: 'future' };
  if (age > 30 * DAY_MS) return { reason: 'old' };
  if (!inSchedule(recordedAt, schedule)) return { reason: 'schedule' };
  const battery = num(raw.battery);
  return {
    point: {
      recordedAt, lat, lng, accuracy,
      speed: num(raw.speed),
      bearing: num(raw.bearing),
      battery: battery === null ? null : Math.max(0, Math.min(100, Math.round(battery))),
    },
  };
}

const goodFix = (p) => p.accuracy === null || p.accuracy === undefined || p.accuracy <= MAX_ACCURACY_M;

// Stops from time-sorted points: a cluster staying within 60 m of its running centroid for >= 3 min.
// Time gaps never break a cluster, so a long gap at the same place stays one stop.
function detectStops(points) {
  const pts = points.filter(goodFix);
  const stops = [];
  let cl = null;
  const close = () => {
    if (!cl) return;
    const durationS = Math.round((cl.lastT - cl.firstT) / 1000);
    if (durationS >= STOP_MIN_S) {
      stops.push({
        startedAt: new Date(cl.firstT), endedAt: new Date(cl.lastT), durationS,
        lat: cl.sumLat / cl.n, lng: cl.sumLng / cl.n, pointCount: cl.n,
      });
    }
  };
  for (const p of pts) {
    const t = tMs(p);
    if (cl && haversine(p.lat, p.lng, cl.sumLat / cl.n, cl.sumLng / cl.n) <= STOP_RADIUS_M) {
      cl.sumLat += p.lat; cl.sumLng += p.lng; cl.n++; cl.lastT = t;
    } else {
      close();
      cl = { firstT: t, lastT: t, sumLat: p.lat, sumLng: p.lng, n: 1 };
    }
  }
  close();
  return stops;
}

// Nearest store with coordinates within 75 m of the stop, or null.
function matchStore(stop, stores) {
  let best = null;
  let bestD = STORE_MATCH_M;
  for (const s of stores) {
    if (s.lat === null || s.lat === undefined || s.lng === null || s.lng === undefined) continue;
    const d = haversine(stop.lat, stop.lng, s.lat, s.lng);
    if (d <= bestD) { best = s; bestD = d; }
  }
  return best;
}

const xy = (p, lat0, lng0) => {
  const [la, ln] = Array.isArray(p) ? p : [p.lat, p.lng];
  return [rad(ln - lng0) * Math.cos(rad(lat0)) * R, rad(la - lat0) * R];
};

// Douglas-Peucker on [{lat,lng}] or [[lat,lng]]; tolerance in metres. Keeps both endpoints.
function simplify(points, toleranceM) {
  if (points.length < 3) return points.slice();
  const first = Array.isArray(points[0]) ? points[0] : [points[0].lat, points[0].lng];
  const P = points.map((p) => xy(p, first[0], first[1]));
  const keep = new Uint8Array(points.length);
  keep[0] = keep[points.length - 1] = 1;
  const stack = [[0, points.length - 1]];
  while (stack.length) {
    const [a, b] = stack.pop();
    let maxD = 0;
    let idx = -1;
    const [ax, ay] = P[a];
    const dx = P[b][0] - ax;
    const dy = P[b][1] - ay;
    const len2 = dx * dx + dy * dy;
    for (let i = a + 1; i < b; i++) {
      let d;
      if (len2 === 0) d = Math.hypot(P[i][0] - ax, P[i][1] - ay);
      else {
        const t = Math.max(0, Math.min(1, ((P[i][0] - ax) * dx + (P[i][1] - ay) * dy) / len2));
        d = Math.hypot(P[i][0] - (ax + t * dx), P[i][1] - (ay + t * dy));
      }
      if (d > maxD) { maxD = d; idx = i; }
    }
    if (idx !== -1 && maxD > toleranceM) {
      keep[idx] = 1;
      stack.push([a, idx], [idx, b]);
    }
  }
  return points.filter((_, i) => keep[i]);
}

// Distance (only while moving) and moving/stopped seconds. Gaps over 10 min count as signal lost.
function summarise(points, stops) {
  const pts = points.filter(goodFix);
  const spans = stops.map((s) => [tMs({ t: s.startedAt }), tMs({ t: s.endedAt })]);
  const inside = (t, [a, b]) => t >= a && t <= b;
  let distanceM = 0;
  let movingS = 0;
  for (let i = 1; i < pts.length; i++) {
    const a = pts[i - 1], b = pts[i];
    const ta = tMs(a), tb = tMs(b);
    const dt = (tb - ta) / 1000;
    if (dt <= 0 || dt > MAX_GAP_S) continue;
    if (spans.some((sp) => inside(ta, sp) && inside(tb, sp))) continue;
    const d = haversine(a.lat, a.lng, b.lat, b.lng);
    if (d / dt > 50) continue; // GPS teleport
    distanceM += d;
    movingS += dt;
  }
  const stoppedS = stops.reduce((n, s) => n + s.durationS, 0);
  return { distanceM: Math.round(distanceM), movingS: Math.round(movingS), stoppedS };
}

module.exports = {
  DEFAULT_SCHEDULE, haversine, manilaParts, parseHM, inSchedule, validateSchedule, parseSchedule, readSchedule,
  validatePoint, detectStops, matchStore, simplify, summarise, tMs,
};
