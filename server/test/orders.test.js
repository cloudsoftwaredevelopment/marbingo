const test = require('node:test');
const assert = require('node:assert/strict');
const { fieldPrice, priceLine, mergeLines, deliveryQuantities, parseDate } = require('../src/lib/orders');

test('fieldPrice uses wholesale, falling back to selling price', () => {
  assert.equal(fieldPrice({ sellingPrice: 6200, wholesalePrice: 5600 }), 5600);
  assert.equal(fieldPrice({ sellingPrice: 6200, wholesalePrice: null }), 6200);
  assert.equal(fieldPrice({ sellingPrice: 6200 }), 6200);
});

test('priceLine totals qty at the field price', () => {
  assert.deepEqual(priceLine({ id: 1, sellingPrice: 6200, wholesalePrice: 5600 }, 20),
    { itemId: 1, qty: 20, unitPrice: 5600, lineTotal: 112000 });
  assert.equal(priceLine({ id: 2, sellingPrice: 100, wholesalePrice: null }, 3).lineTotal, 300);
});

test('mergeLines merges duplicates and validates', () => {
  assert.deepEqual(mergeLines([{ itemId: 1, qty: 2 }, { itemId: 2, qty: 1 }, { itemId: '1', qty: 3 }]).lines,
    [{ itemId: 1, qty: 5 }, { itemId: 2, qty: 1 }]);
  assert.ok(mergeLines([]).error);
  assert.ok(mergeLines(undefined).error);
  assert.ok(mergeLines([{ itemId: 1, qty: 0 }]).error);
  assert.ok(mergeLines([{ itemId: 1, qty: 1.5 }]).error);
  assert.ok(mergeLines([{ itemId: 'x', qty: 1 }]).error);
  assert.ok(mergeLines([null]).error);
});

const ordered = [{ itemId: 1, qty: 10 }, { itemId: 2, qty: 4 }];

test('deliveryQuantities defaults omitted lines to the full quantity', () => {
  assert.deepEqual(deliveryQuantities(ordered, undefined).lines, ordered);
  assert.deepEqual(deliveryQuantities(ordered, [{ itemId: 1, qty: 6 }]).lines,
    [{ itemId: 1, qty: 6 }, { itemId: 2, qty: 4 }]);
});

test('deliveryQuantities rejects bad quantities and unknown items', () => {
  assert.ok(deliveryQuantities(ordered, [{ itemId: 1, qty: 11 }]).error);
  assert.ok(deliveryQuantities(ordered, [{ itemId: 1, qty: -1 }]).error);
  assert.ok(deliveryQuantities(ordered, [{ itemId: 1, qty: 1.5 }]).error);
  assert.ok(deliveryQuantities(ordered, [{ itemId: 9, qty: 1 }]).error);
  assert.ok(deliveryQuantities(ordered, 'x').error);
});

test('deliveryQuantities requires at least one delivered unit', () => {
  const r = deliveryQuantities(ordered, [{ itemId: 1, qty: 0 }, { itemId: 2, qty: 0 }]);
  assert.equal(r.error, 'Deliver at least one item, or cancel the order.');
  assert.equal(deliveryQuantities(ordered, [{ itemId: 1, qty: 0 }]).lines[1].qty, 4);
});

test('parseDate falls back on invalid input', () => {
  const fb = new Date(0);
  assert.equal(parseDate('nope', fb), fb);
  assert.equal(parseDate(undefined, fb), fb);
  assert.equal(parseDate('2026-10-01T00:00:00Z', fb).toISOString(), '2026-10-01T00:00:00.000Z');
});

test('mergeLines caps absurd quantities', () => {
  assert.ok(mergeLines([{ itemId: 1, qty: 1000000 }]).error);
  assert.ok(mergeLines([{ itemId: 1, qty: 60000 }, { itemId: 1, qty: 60000 }]).error);
});
