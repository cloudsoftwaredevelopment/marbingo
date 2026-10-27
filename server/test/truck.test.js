const test = require('node:test');
const assert = require('node:assert/strict');
const { expected, reconcile, uncounted, nextMonth, toMap, toRows } = require('../src/lib/truck');

const P = { 1: 6200, 2: 11000 };

test('expected = opening + topups, returns and sales; load and count_variance are ignored', () => {
  const e = expected({
    opening: { 1: 10, 2: 5 },
    moves: [
      { itemId: 1, change: 20, reason: 'topup' },
      { itemId: 1, change: -4, reason: 'return' },
      { itemId: 1, change: -6, reason: 'sale' },
      { itemId: 2, change: 99, reason: 'load' }, // already inside the snapshot
      { itemId: 2, change: -3, reason: 'count_variance' },
      { itemId: 3, change: -2, reason: 'sale' },
    ],
  });
  assert.deepEqual(e, { 1: 20, 2: 5, 3: -2 });
});

test('expected does not mutate the opening map', () => {
  const opening = { 1: 1 };
  expected({ opening, moves: [{ itemId: 1, change: 5, reason: 'sale' }] });
  assert.deepEqual(opening, { 1: 1 });
});

test('all match', () => {
  const r = reconcile({ 1: 10, 2: 5 }, { 1: 10, 2: 5 }, P);
  assert.equal(r.matched, true);
  assert.equal(r.missingQty + r.surplusQty + r.missingValue + r.surplusValue, 0);
  assert.deepEqual(r.lines.map((l) => l.status), ['ok', 'ok']);
});

test('missing only, valued at retail', () => {
  const r = reconcile({ 1: 10, 2: 5 }, { 1: 7, 2: 5 }, P);
  assert.equal(r.matched, false);
  assert.equal(r.missingQty, 3);
  assert.equal(r.missingValue, 3 * 6200);
  assert.equal(r.surplusQty, 0);
  assert.deepEqual(r.lines[0], { itemId: 1, expected: 10, counted: 7, variance: -3, status: 'missing', value: 18600 });
});

test('surplus only', () => {
  const r = reconcile({ 1: 10 }, { 1: 12 }, P);
  assert.equal(r.matched, false);
  assert.equal(r.surplusQty, 2);
  assert.equal(r.surplusValue, 12400);
  assert.equal(r.missingQty, 0);
  assert.equal(r.lines[0].status, 'surplus');
});

test('mixed missing and surplus', () => {
  const r = reconcile({ 1: 10, 2: 5 }, { 1: 8, 2: 6 }, P);
  assert.equal(r.matched, false);
  assert.equal(r.missingQty, 2);
  assert.equal(r.missingValue, 12400);
  assert.equal(r.surplusQty, 1);
  assert.equal(r.surplusValue, 11000);
});

test('an uncounted item is treated as 0 counted', () => {
  const r = reconcile({ 1: 10, 2: 5 }, { 1: 10 }, P);
  assert.equal(r.matched, false);
  assert.deepEqual(r.lines[1], { itemId: 2, expected: 5, counted: 0, variance: -5, status: 'missing', value: 55000 });
});

test('an item counted but not expected is surplus', () => {
  const r = reconcile({ 1: 10 }, { 1: 10, 2: 4 }, P);
  assert.equal(r.matched, false);
  assert.deepEqual(r.lines[1], { itemId: 2, expected: 0, counted: 4, variance: 4, status: 'surplus', value: 44000 });
});

test('negative expected after an oversell: counting 0 is surplus by the oversold amount', () => {
  const r = reconcile({ 1: -3 }, { 1: 0 }, P);
  assert.equal(r.matched, false);
  assert.equal(r.lines[0].variance, 3);
  assert.equal(r.lines[0].status, 'surplus');
  assert.equal(reconcile({ 1: -3 }, { 1: -3 }, P).matched, true);
});

test('zero expected and zero counted items are left out; unknown price is 0', () => {
  const r = reconcile({ 1: 0, 9: 2 }, { 9: 1 }, P);
  assert.deepEqual(r.lines.map((l) => l.itemId), [9]);
  assert.equal(r.lines[0].value, 0);
});

test('uncounted lists non-zero expected items without a count line', () => {
  assert.deepEqual(uncounted({ 1: 5, 2: 0, 3: -1, 4: 2 }, new Set([4])), [1, 3]);
});

test('nextMonth rolls over the year and never goes before the current month', () => {
  assert.equal(nextMonth('2026-12', '2026-12'), '2027-01');
  assert.equal(nextMonth('2026-03', '2026-03'), '2026-04');
  assert.equal(nextMonth('2026-01', '2026-10'), '2026-10');
});

test('snapshot rows round-trip', () => {
  assert.deepEqual(toRows(toMap([{ itemId: 2, qty: 3 }, { itemId: 1, qty: -1 }])), [{ itemId: 1, qty: -1 }, { itemId: 2, qty: 3 }]);
});
