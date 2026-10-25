const test = require('node:test');
const assert = require('node:assert');
const { resolvePeriod } = require('../src/lib/periods');
const { turnover } = require('../src/lib/turnover');

const NOW = new Date('2026-10-25T04:00:00Z'); // Sun Oct 25 2026, 12:00 Manila
const labels = (p) => p.buckets.map((b) => b.label);

test('month: Oct 2026 has 5 chunks', () => {
  const p = resolvePeriod('month', {}, NOW);
  assert.deepStrictEqual(labels(p), ['Oct 1–7', 'Oct 8–14', 'Oct 15–21', 'Oct 22–28', 'Oct 29–31']);
  assert.strictEqual(p.label, 'Oct 1 – 31, 2026');
  assert.strictEqual(p.from.toISOString(), '2026-09-30T16:00:00.000Z');
  assert.strictEqual(p.to.toISOString(), '2026-10-31T16:00:00.000Z');
  assert.strictEqual(p.unit, 'By week · dashed = days still ahead');
  assert.strictEqual(p.bucket, 'week');
});

test('upcoming flags', () => {
  const p = resolvePeriod('month', {}, NOW);
  assert.deepStrictEqual(p.buckets.map((b) => b.upcoming), [false, false, false, false, true]);
});

test('last month', () => {
  const p = resolvePeriod('last', {}, NOW);
  assert.strictEqual(p.label, 'Sep 1 – 30, 2026');
  assert.strictEqual(p.buckets.at(-1).label, 'Sep 29–30');
  assert.strictEqual(p.unit, 'By week');
});

test('last month across a year boundary', () => {
  const p = resolvePeriod('last', {}, new Date('2027-01-10T00:00:00Z'));
  assert.strictEqual(p.label, 'Dec 1 – 31, 2026');
});

test('week on a Sunday runs Mon Oct 19 - Sun Oct 25', () => {
  const p = resolvePeriod('week', {}, NOW);
  assert.strictEqual(p.label, 'Oct 19 – 25, 2026');
  assert.deepStrictEqual(labels(p), ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']);
  assert.strictEqual(p.unit, 'By day');
  assert.ok(p.buckets.every((b) => !b.upcoming));
});

test('week on a Monday starts that day, crosses month', () => {
  const p = resolvePeriod('week', {}, new Date('2026-10-26T02:00:00Z'));
  assert.strictEqual(p.label, 'Oct 26 – Nov 1, 2026');
  assert.deepStrictEqual(p.buckets.map((b) => b.upcoming), [false, true, true, true, true, true, true]);
  assert.strictEqual(p.unit, 'By day · dashed = days still ahead');
});

test('Manila midnight boundary: 16:30Z Oct 25 is already Oct 26', () => {
  const p = resolvePeriod('today', {}, new Date('2026-10-25T16:30:00Z'));
  assert.strictEqual(p.label, 'Mon, Oct 26, 2026');
  assert.strictEqual(p.from.toISOString(), '2026-10-25T16:00:00.000Z');
  const w = resolvePeriod('week', {}, new Date('2026-10-25T16:30:00Z'));
  assert.strictEqual(w.label, 'Oct 26 – Nov 1, 2026');
  const before = resolvePeriod('today', {}, new Date('2026-10-25T15:59:00Z'));
  assert.strictEqual(before.label, 'Sun, Oct 25, 2026');
});

test('today: hourly buckets 7a..6p plus off-hours, covering the whole day', () => {
  const p = resolvePeriod('today', {}, new Date('2026-10-26T03:00:00Z')); // 11:00 Manila
  assert.deepStrictEqual(labels(p), ['Before 7a', '7a', '8a', '9a', '10a', '11a', '12p', '1p', '2p', '3p', '4p', '5p', '6p', 'After 7p']);
  assert.strictEqual(p.buckets[0].start.toISOString(), p.from.toISOString());
  assert.strictEqual(p.buckets[13].end.toISOString(), p.to.toISOString());
  assert.strictEqual(p.buckets[1].start.toISOString(), '2026-10-25T23:00:00.000Z');
  assert.strictEqual(p.buckets[12].end.toISOString(), '2026-10-26T11:00:00.000Z');
  assert.deepStrictEqual(p.buckets.filter((b) => b.offHours).map((b) => b.label), ['Before 7a', 'After 7p']);
  assert.strictEqual(p.unit, 'By hour' + ' · dashed = days still ahead');
  assert.strictEqual(p.buckets.filter((b) => b.upcoming).length, 8);
});

test('custom: invalid ranges', () => {
  const msg = 'Choose a start date before the end date';
  assert.deepStrictEqual(resolvePeriod('custom', { from: '2026-10-10', to: '2026-10-01' }, NOW), { error: msg });
  assert.deepStrictEqual(resolvePeriod('custom', { from: '', to: '2026-10-01' }, NOW), { error: msg });
  assert.deepStrictEqual(resolvePeriod('custom', { from: '2026-02-31', to: '2026-03-05' }, NOW), { error: msg });
});

test('custom: range crossing months', () => {
  const p = resolvePeriod('custom', { from: '2026-09-15', to: '2026-10-15' }, NOW);
  assert.strictEqual(p.label, 'Sep 15, 2026 – Oct 15, 2026');
  assert.deepStrictEqual(labels(p), ['Sep 15–21', 'Sep 22–28', 'Sep 29–Oct 5', 'Oct 6–12', 'Oct 13–15']);
  assert.strictEqual(p.fromDate, '2026-09-15');
  assert.strictEqual(p.toDate, '2026-10-15');
  assert.strictEqual(p.unit, 'By week');
});

test('custom: single-day trailing chunk and single day', () => {
  const p = resolvePeriod('custom', { from: '2026-09-15', to: '2026-09-22' }, NOW);
  assert.deepStrictEqual(labels(p), ['Sep 15–21', 'Sep 22']);
  assert.deepStrictEqual(labels(resolvePeriod('custom', { from: '2026-09-15', to: '2026-09-15' }, NOW)), ['Sep 15']);
});

test('custom: over 366 days is an error, year crossing label', () => {
  assert.strictEqual(resolvePeriod('custom', { from: '2025-01-01', to: '2026-01-01' }, NOW).toDate, '2026-01-01');
  assert.deepStrictEqual(resolvePeriod('custom', { from: '2025-01-01', to: '2026-01-02' }, NOW), { error: 'Choose a range of 366 days or less' });
  const q = resolvePeriod('custom', { from: '2025-12-29', to: '2026-01-04' }, NOW);
  assert.strictEqual(q.label, 'Dec 29, 2025 – Jan 4, 2026');
  assert.deepStrictEqual(labels(q), ['Dec 29–Jan 4']);
});

test('turnover math', () => {
  assert.strictEqual(turnover(50, 100, 80), 0.6); // avg 90 -> 0.555
  assert.strictEqual(turnover(90, 100, 80), 1);
  assert.strictEqual(turnover(28, 10, 0), 5.6);
});

test('turnover: zero stock edge cases', () => {
  assert.strictEqual(turnover(5, 0, 0), 5); // avg 0 -> max(0,0,1)=1
  assert.strictEqual(turnover(0, 0, 0), 0);
  assert.strictEqual(turnover(4, -2, -6), 4); // negative avg falls back to 1
  assert.strictEqual(turnover(6, 0, 3), 4); // avg 1.5
});
