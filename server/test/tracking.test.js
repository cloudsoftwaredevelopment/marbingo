const test = require('node:test');
const assert = require('node:assert/strict');
const T = require('../src/lib/tracking');
const { matchTrack, routeBetween } = require('../src/lib/osrm');

const BASE = Date.UTC(2026, 9, 26, 1, 0, 0); // 09:00 Manila
const M_PER_DEG = 111195;
const at = (s) => new Date(BASE + s * 1000);
const pt = (s, lat, lng, accuracy = 8) => ({ recordedAt: at(s), lat, lng, accuracy });

// Drives east at 5 m/s and dwells for 5 minutes at 0.01 and 0.02 degrees of longitude.
function walk() {
  const pts = [];
  let s = 0;
  const lat = 10.72;
  let lng = 122.55;
  const dwell = (n) => { for (let i = 0; i < n; i++, s += 15) pts.push(pt(s, lat + ((i % 3) - 1) * 0.00003, lng + ((i % 2) - 0.5) * 0.00004)); };
  const move = (toLng) => {
    while (lng < toLng) { pts.push(pt(s, lat, lng)); lng += (5 * 15) / (M_PER_DEG * Math.cos((lat * Math.PI) / 180)); s += 15; }
  };
  move(122.551); dwell(21); move(122.553); dwell(21); move(122.555);
  return pts;
}

test('haversine matches known distances', () => {
  assert.equal(T.haversine(10, 122, 10, 122), 0);
  assert.ok(Math.abs(T.haversine(0, 0, 1, 0) - 111195) < 200);
  assert.ok(Math.abs(T.haversine(10.72, 122.55, 10.72, 122.551) - 109.5) < 2);
});

test('inSchedule uses Manila time with Sunday off', () => {
  const sch = T.DEFAULT_SCHEDULE;
  const manila = (d, h, m) => new Date(Date.UTC(2026, 9, d, h - 8, m));
  assert.equal(T.inSchedule(manila(26, 7, 0), sch), true); // Monday 07:00
  assert.equal(T.inSchedule(manila(26, 6, 59), sch), false);
  assert.equal(T.inSchedule(manila(26, 17, 59), sch), true);
  assert.equal(T.inSchedule(manila(26, 18, 0), sch), false);
  assert.equal(T.inSchedule(manila(25, 10, 0), sch), false); // Sunday
  assert.equal(T.inSchedule(manila(31, 10, 0), sch), true); // Saturday
  // 23:30 UTC on Sunday is Monday 07:30 in Manila
  assert.equal(T.inSchedule(new Date(Date.UTC(2026, 9, 25, 23, 30)), sch), true);
  assert.equal(T.manilaParts(new Date(Date.UTC(2026, 9, 25, 23, 30))).day, '2026-10-26');
  // Same wall-clock offset in January: no DST shift
  assert.equal(T.inSchedule(new Date(Date.UTC(2027, 0, 4, 23, 0)), sch), true);
});

test('validateSchedule', () => {
  assert.deepEqual(T.validateSchedule({ days: [3, 1], start: '08:00', end: '17:30' }).schedule, { days: [1, 3], start: '08:00', end: '17:30' });
  assert.ok(T.validateSchedule({ days: [1, 1], start: '08:00', end: '17:00' }).fields.days);
  assert.ok(T.validateSchedule({ days: [8], start: '08:00', end: '17:00' }).fields.days);
  assert.ok(T.validateSchedule({ days: [1], start: '8:00', end: '17:00' }).fields.start);
  assert.ok(T.validateSchedule({ days: [1], start: '18:00', end: '07:00' }).fields.end);
});

test('validatePoint rejects bad, future, old and out-of-hours points', () => {
  const now = new Date(Date.UTC(2026, 9, 26, 4, 0)); // 12:00 Manila Monday
  const ok = { recordedAt: new Date(now - 60000).toISOString(), lat: 10.7, lng: 122.5, accuracy: 10 };
  assert.ok(T.validatePoint(ok, T.DEFAULT_SCHEDULE, now).point);
  assert.equal(T.validatePoint({ ...ok, lat: 91 }, T.DEFAULT_SCHEDULE, now).reason, 'range');
  assert.equal(T.validatePoint({ ...ok, accuracy: 501 }, T.DEFAULT_SCHEDULE, now).reason, 'accuracy');
  assert.equal(T.validatePoint({ ...ok, recordedAt: new Date(now.getTime() + 11 * 60000).toISOString() }, T.DEFAULT_SCHEDULE, now).reason, 'future');
  assert.equal(T.validatePoint({ ...ok, recordedAt: new Date(now - 31 * 86400000).toISOString() }, T.DEFAULT_SCHEDULE, now).reason, 'old');
  assert.equal(T.validatePoint({ ...ok, recordedAt: new Date(Date.UTC(2026, 9, 26, 12, 0)).toISOString() }, T.DEFAULT_SCHEDULE, new Date(Date.UTC(2026, 9, 26, 12, 5))).reason, 'schedule');
  assert.equal(T.validatePoint({ ...ok, recordedAt: 'nope' }, T.DEFAULT_SCHEDULE, now).reason, 'time');
});

test('detectStops finds two 5-minute stops on a walk', () => {
  const stops = T.detectStops(walk());
  assert.equal(stops.length, 2);
  for (const s of stops) assert.ok(Math.abs(s.durationS - 300) <= 15, `duration ${s.durationS}`);
  assert.ok(Math.abs(stops[0].lng - 122.551) < 0.001);
  assert.ok(Math.abs(stops[1].lng - 122.553) < 0.001);
  assert.ok(stops[0].pointCount >= 20);
});

test('GPS jitter for 2 minutes is not a stop; neither is a pause under 3 minutes', () => {
  const jitter = [];
  for (let i = 0; i < 9; i++) jitter.push(pt(i * 15, 10.72 + Math.sin(i) * 0.00012, 122.55 + Math.cos(i) * 0.00012));
  assert.equal(T.detectStops(jitter).length, 0);
  const pause = [];
  for (let i = 0; i < 12; i++) pause.push(pt(i * 15, 10.72, 122.55 + i * 0.0005)); // moving
  for (let i = 0; i < 11; i++) pause.push(pt(180 + i * 15, 10.72, 122.556)); // 150 s
  for (let i = 0; i < 12; i++) pause.push(pt(345 + i * 15, 10.72, 122.556 + (i + 1) * 0.0005));
  assert.equal(T.detectStops(pause).length, 0);
});

test('detectStops ignores poor fixes and keeps one stop across a long gap', () => {
  const pts = [pt(0, 10.72, 122.55), pt(30, 10.72, 122.55), pt(60, 10.73, 122.56, 300), pt(900, 10.72, 122.55001), pt(1000, 10.72, 122.55)];
  const stops = T.detectStops(pts);
  assert.equal(stops.length, 1);
  assert.equal(stops[0].durationS, 1000);
  assert.equal(stops[0].pointCount, 4);
});

test('matchStore picks the nearest store within 75 m', () => {
  const stores = [{ id: 1, lat: 10.72, lng: 122.55 }, { id: 2, lat: 10.7201, lng: 122.55 }, { id: 3, lat: null, lng: null }];
  const north = (m) => ({ lat: 10.72 + m / M_PER_DEG, lng: 122.55 });
  assert.equal(T.matchStore(north(-60), stores).id, 1);
  assert.equal(T.matchStore(north(-120), stores), null);
  assert.equal(T.matchStore({ lat: 10.72003, lng: 122.55 }, stores).id, 1);
  assert.equal(T.matchStore({ lat: 10.72008, lng: 122.55 }, stores).id, 2);
});

test('simplify keeps endpoints and reduces the count', () => {
  const line = [];
  for (let i = 0; i <= 100; i++) line.push({ lat: 10.72, lng: 122.55 + i * 0.0001, t: i });
  line[50] = { ...line[50], lat: 10.7203 }; // 33 m bump
  const out = T.simplify(line, 10);
  assert.ok(out.length < line.length && out.length >= 3);
  assert.equal(out[0], line[0]);
  assert.equal(out[out.length - 1], line[100]);
  assert.ok(out.includes(line[50]));
  assert.equal(T.simplify(line, 100).length, 2);
  assert.equal(T.simplify([[10, 122], [10.1, 122.1]], 5).length, 2);
});

test('summarise counts distance while moving and stopped time from stops', () => {
  const pts = walk();
  const stops = T.detectStops(pts);
  const sum = T.summarise(pts, stops);
  assert.equal(sum.stoppedS, stops[0].durationS + stops[1].durationS);
  assert.ok(Math.abs(sum.distanceM - 548) < 60, `distance ${sum.distanceM}`);
  assert.ok(sum.movingS > 0);
});

test('osrm falls back to raw geometry when OSRM_URL is unset', async () => {
  const saved = process.env.OSRM_URL;
  delete process.env.OSRM_URL;
  try {
    const m = await matchTrack(walk());
    assert.equal(m.snapped, false);
    assert.ok(m.route.length >= 2);
    assert.ok(m.distanceM > 300);
    const r = await routeBetween({ lat: 10.72, lng: 122.55 }, { lat: 10.72, lng: 122.551 });
    assert.equal(r.snapped, false);
    assert.equal(r.coords.length, 2);
    assert.ok(Math.abs(r.distanceM - 110) <= 2);
  } finally {
    if (saved !== undefined) process.env.OSRM_URL = saved;
  }
});
