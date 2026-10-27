// Pure helpers for the tablet device log (no DB). Events are state changes; gaps are open→close pairs per kind,
// clipped to the work window of a Manila day. See docs/tracking-api.md "Device log".
const { parseIso, startOfDay } = require('./periods');
const { manilaParts, parseHM } = require('./tracking');

const DAY_MS = 86400000;
const MAX_AGE_MS = 30 * DAY_MS;
const MAX_FUTURE_MS = 600000;
const MIN_SIGNAL_GAP_MS = 120000; // brief coverage drops are normal on the road
const SKEW_ALERT_MS = 300000;

// type → [kind, opens a gap?]
const TYPES = {
  power_off: ['power', true], power_on: ['power', false],
  app_stopped: ['app', true], app_started: ['app', false],
  data_off: ['data', true], data_on: ['data', false],
  airplane_on: ['airplane', true], airplane_off: ['airplane', false],
  no_signal: ['signal', true], signal_back: ['signal', false],
  gps_off: ['gps', true], gps_on: ['gps', false],
  permission_revoked: ['permission', true], permission_granted: ['permission', false],
  mock_on: ['mock', true], mock_off: ['mock', false],
  auto_time_off: ['clock', true], auto_time_on: ['clock', false],
};
// One-off events, not part of a gap.
const INSTANT = new Set(['clock_changed', 'clock_skew']);

// No trustworthy location is being recorded during these.
const NO_LOCATION = ['power', 'app', 'gps', 'permission', 'mock'];
// Location is still recorded (and uploaded later) during these; the tablet just can't be reached.
const OFFLINE = ['data', 'airplane', 'signal'];
// Most serious first: what the live list shows when several are open.
const ALERT_ORDER = ['power', 'app', 'mock', 'gps', 'permission', 'airplane', 'data'];

const isType = (t) => Object.prototype.hasOwnProperty.call(TYPES, t) || INSTANT.has(t);

const int = (v) => (typeof v === 'number' && Number.isFinite(v) ? Math.round(v) : null);

// Keeps only the known detail keys. Returns null when nothing is left.
function cleanDetail(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const d = {};
  const battery = int(raw.battery);
  if (battery !== null) d.battery = Math.max(0, Math.min(100, battery));
  if (raw.estimated === true) d.estimated = true;
  const shift = int(raw.shiftMs);
  if (shift !== null) d.shiftMs = shift;
  return Object.keys(d).length ? d : null;
}

// Returns { event } (normalised for storage) or { reason }.
function validateEvent(raw, now = new Date()) {
  if (!raw || typeof raw !== 'object') return { reason: 'invalid' };
  if (!isType(raw.type) || raw.type === 'clock_skew') return { reason: 'type' }; // clock_skew is server-made
  const at = new Date(raw.at);
  if (typeof raw.at !== 'string' || Number.isNaN(at.getTime())) return { reason: 'time' };
  const age = now.getTime() - at.getTime();
  if (age < -MAX_FUTURE_MS) return { reason: 'future' };
  if (age > MAX_AGE_MS) return { reason: 'old' };
  return { event: { type: raw.type, at, detail: cleanDetail(raw.detail) } };
}

// The device's clock vs ours, from the upload's sentAt. Returns ms (device ahead > 0) or null when unknown/small.
function clockSkew(sentAt, now = new Date()) {
  const t = new Date(sentAt);
  if (typeof sentAt !== 'string' || Number.isNaN(t.getTime())) return null;
  const skew = t.getTime() - now.getTime();
  return Math.abs(skew) >= SKEW_ALERT_MS ? skew : null;
}

const ms = (v) => (v instanceof Date ? v.getTime() : new Date(v).getTime());

// Pairs open/close events per kind. A second "open" while one is open is ignored, as is a "close" with nothing open.
// Returns [{ kind, start, end (ms or null while still open), detail }] ordered by start.
function buildGaps(events) {
  const sorted = [...events].sort((a, b) => ms(a.at) - ms(b.at));
  const open = {};
  const out = [];
  for (const e of sorted) {
    const def = TYPES[e.type];
    if (!def) continue;
    const [kind, opens] = def;
    if (opens) {
      if (!open[kind]) open[kind] = { kind, start: ms(e.at), end: null, detail: e.detail || null };
    } else if (open[kind]) {
      open[kind].end = ms(e.at);
      out.push(open[kind]);
      delete open[kind];
    }
  }
  out.push(...Object.values(open));
  return out.sort((a, b) => a.start - b.start);
}

// The [start, end) work window of a Manila day in ms, or null on a day off.
function workWindow(day, schedule) {
  const dn = parseIso(day);
  if (dn === null) return null;
  const t0 = startOfDay(dn).getTime();
  if (!schedule.days.includes(manilaParts(new Date(t0)).dow)) return null;
  return [t0 + parseHM(schedule.start) * 60000, t0 + parseHM(schedule.end) * 60000];
}

// Total seconds covered by possibly overlapping [a, b] intervals.
function unionS(intervals) {
  const list = [...intervals].sort((x, y) => x[0] - y[0]);
  let total = 0;
  let cur = null;
  for (const [a, b] of list) {
    if (!cur || a > cur[1]) { if (cur) total += cur[1] - cur[0]; cur = [a, b]; } else cur[1] = Math.max(cur[1], b);
  }
  if (cur) total += cur[1] - cur[0];
  return Math.round(total / 1000);
}

// A Manila day's device log: gaps clipped to its work window, one-off events, and totals.
function dayLog(events, { day, schedule, now = new Date() }) {
  const win = workWindow(day, schedule);
  const empty = { gaps: [], instants: [], summary: { noLocationS: 0, offlineS: 0, byKind: {} } };
  if (!win) return empty;
  const nowMs = now.getTime();
  const hi = Math.min(win[1], nowMs);
  if (hi <= win[0]) return empty;

  const gaps = [];
  for (const g of buildGaps(events)) {
    const end = g.end === null ? nowMs : g.end;
    if (g.kind === 'signal' && end - g.start < MIN_SIGNAL_GAP_MS) continue;
    const a = Math.max(g.start, win[0]);
    const b = Math.min(end, hi);
    if (b <= a) continue;
    gaps.push({
      kind: g.kind, startedAt: new Date(a), endedAt: new Date(b), open: g.end === null && b === nowMs,
      durationS: Math.round((b - a) / 1000), detail: g.detail,
    });
  }
  const instants = events
    .filter((e) => INSTANT.has(e.type) && ms(e.at) >= win[0] && ms(e.at) < hi)
    .map((e) => ({ type: e.type, at: new Date(ms(e.at)), detail: e.detail || null }))
    .sort((a, b) => a.at - b.at);

  const span = (kinds) => unionS(gaps.filter((g) => kinds.includes(g.kind)).map((g) => [g.startedAt.getTime(), g.endedAt.getTime()]));
  const byKind = {};
  for (const k of new Set(gaps.map((g) => g.kind))) byKind[k] = span([k]);
  return { gaps, instants, summary: { noLocationS: span(NO_LOCATION), offlineS: span(OFFLINE), byKind } };
}

// The most serious gap open right now ({ kind, since }), or null. Signal loss is left to the live status.
function currentAlert(events) {
  const open = buildGaps(events).filter((g) => g.end === null && ALERT_ORDER.includes(g.kind));
  if (!open.length) return null;
  open.sort((a, b) => ALERT_ORDER.indexOf(a.kind) - ALERT_ORDER.indexOf(b.kind));
  return { kind: open[0].kind, since: new Date(open[0].start), detail: open[0].detail };
}

module.exports = {
  TYPES, INSTANT, NO_LOCATION, OFFLINE, SKEW_ALERT_MS,
  validateEvent, cleanDetail, clockSkew, buildGaps, workWindow, unionS, dayLog, currentAlert,
};
