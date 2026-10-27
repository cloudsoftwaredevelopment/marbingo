const test = require('node:test');
const assert = require('node:assert/strict');
const D = require('../src/lib/deviceEvents');

const SCHEDULE = { days: [1, 2, 3, 4, 5, 6], start: '07:00', end: '18:00' };
const DAY = '2026-10-26'; // a Monday
const manila = (hm, day = DAY) => new Date(`${day}T${hm}:00+08:00`);
const ev = (type, hm, detail, day) => ({ type, at: manila(hm, day), detail: detail || null });

test('validateEvent normalises known types and rejects the rest', () => {
  const now = manila('12:00');
  const ok = D.validateEvent({ type: 'power_off', at: manila('10:00').toISOString(), detail: { battery: 104.4, estimated: true, junk: 1 } }, now);
  assert.deepEqual(ok.event.detail, { battery: 100, estimated: true });
  assert.equal(ok.event.type, 'power_off');
  assert.equal(D.validateEvent({ type: 'nap', at: now.toISOString() }, now).reason, 'type');
  assert.equal(D.validateEvent({ type: 'clock_skew', at: now.toISOString() }, now).reason, 'type');
  assert.equal(D.validateEvent({ type: 'data_off', at: 'yesterday' }, now).reason, 'time');
  assert.equal(D.validateEvent({ type: 'data_off', at: manila('12:30').toISOString() }, now).reason, 'future');
  assert.equal(D.validateEvent({ type: 'data_off', at: manila('12:00', '2026-09-01').toISOString() }, now).reason, 'old');
  assert.equal(D.validateEvent({ type: 'data_off', at: now.toISOString(), detail: 'x' }, now).event.detail, null);
});

test('clockSkew reports only big differences', () => {
  const now = manila('12:00');
  assert.equal(D.clockSkew(manila('12:01').toISOString(), now), null);
  assert.equal(D.clockSkew(manila('13:00').toISOString(), now), 3600000);
  assert.equal(D.clockSkew(manila('11:00').toISOString(), now), -3600000);
  assert.equal(D.clockSkew(undefined, now), null);
});

test('buildGaps pairs open/close per kind and keeps the first open', () => {
  const gaps = D.buildGaps([
    ev('data_on', '08:00'), // close with nothing open: ignored
    ev('power_off', '10:00', { battery: 60 }), ev('data_off', '09:00'), ev('data_off', '09:30'),
    ev('power_on', '11:00'), ev('data_on', '12:00'), ev('gps_off', '16:00'),
  ]);
  assert.deepEqual(gaps.map((g) => [g.kind, g.start, g.end]), [
    ['data', manila('09:00').getTime(), manila('12:00').getTime()],
    ['power', manila('10:00').getTime(), manila('11:00').getTime()],
    ['gps', manila('16:00').getTime(), null],
  ]);
  assert.deepEqual(gaps[1].detail, { battery: 60 });
});

test('workWindow follows the schedule and skips days off', () => {
  assert.deepEqual(D.workWindow(DAY, SCHEDULE), [manila('07:00').getTime(), manila('18:00').getTime()]);
  assert.equal(D.workWindow('2026-10-25', SCHEDULE), null); // Sunday
  assert.equal(D.workWindow('bad', SCHEDULE), null);
});

test('unionS merges overlaps', () => {
  assert.equal(D.unionS([[0, 10000], [5000, 20000], [30000, 40000]]), 30);
  assert.equal(D.unionS([]), 0);
});

test('dayLog clips to work hours and totals no-location vs offline time', () => {
  const events = [
    ev('power_off', '17:30', { estimated: true }, '2026-10-24'), ev('power_on', '08:00'), // overnight off → 07:00–08:00
    ev('data_off', '09:00'), ev('data_on', '10:00'),
    ev('app_stopped', '09:30'), ev('app_started', '09:45'),
    ev('no_signal', '11:00'), ev('signal_back', '11:01'), // too short: dropped
    ev('no_signal', '12:00'), ev('signal_back', '12:10'),
    ev('clock_changed', '13:00', { shiftMs: -3600000 }),
    ev('gps_off', '17:00'), // still open
  ];
  const log = D.dayLog(events, { day: DAY, schedule: SCHEDULE, now: manila('17:20') });
  assert.deepEqual(log.gaps.map((g) => [g.kind, g.durationS / 60, g.open]), [
    ['power', 60, false], ['data', 60, false], ['app', 15, false], ['signal', 10, false], ['gps', 20, true],
  ]);
  assert.equal(log.summary.noLocationS, (60 + 15 + 20) * 60);
  assert.equal(log.summary.offlineS, (60 + 10) * 60);
  assert.equal(log.summary.byKind.data, 3600);
  assert.equal(log.instants.length, 1);
  assert.equal(log.instants[0].type, 'clock_changed');
});

test('dayLog is empty on a day off and before work starts', () => {
  const events = [ev('data_off', '09:00', null, '2026-10-25')];
  assert.deepEqual(D.dayLog(events, { day: '2026-10-25', schedule: SCHEDULE, now: manila('12:00', '2026-10-25') }).gaps, []);
  assert.deepEqual(D.dayLog(events, { day: DAY, schedule: SCHEDULE, now: manila('06:00') }).gaps, []);
  // The Sunday gap is still open on Monday: it shows from 07:00.
  const mon = D.dayLog(events, { day: DAY, schedule: SCHEDULE, now: manila('08:00') });
  assert.deepEqual(mon.gaps.map((g) => [g.kind, g.durationS / 60, g.open]), [['data', 60, true]]);
});

test('currentAlert picks the most serious open gap and ignores signal', () => {
  assert.equal(D.currentAlert([ev('no_signal', '09:00')]), null);
  const a = D.currentAlert([ev('data_off', '09:00'), ev('mock_on', '10:00'), ev('gps_off', '08:00')]);
  assert.equal(a.kind, 'mock');
  assert.equal(a.since.getTime(), manila('10:00').getTime());
  assert.equal(D.currentAlert([ev('data_off', '09:00'), ev('data_on', '09:10')]), null);
});
