const test = require('node:test');
const assert = require('node:assert/strict');
const { validatePacks, aggregateSoldAs, repriceLine, shapeItem } = require('../src/lib/packs');

const types = new Map([[1, { name: 'Dozen', active: true }], [5, { name: 'Sack', active: true }], [9, { name: 'Old', active: false }]]);

test('validatePacks accepts good packs and parses peso prices', () => {
  const r = validatePacks([{ packTypeId: 5, size: '50', price: '2900.50' }, { packTypeId: 1, size: 12, price: 600 }], types, 6200);
  assert.deepEqual(r.errors, []);
  assert.deepEqual(r.packs, [{ packTypeId: 5, size: 50, price: 290050 }, { packTypeId: 1, size: 12, price: 60000 }]);
  assert.deepEqual(r.warnings, []);
});

test('validatePacks reports per-pack errors', () => {
  const r = validatePacks([
    { packTypeId: 99, size: 10, price: 5 }, { packTypeId: 9, size: 10, price: 5 }, { packTypeId: 5, size: 1, price: 5 },
    { packTypeId: 5, size: 20, price: 0 }, { packTypeId: 5, size: 20, price: 5 }, { packTypeId: 5, size: 30, price: 5 },
    { packTypeId: 1, size: 20, price: 5 },
  ], types, 100);
  assert.deepEqual(r.errors.map((e) => e.index), [0, 1, 2, 3, 5, 6]);
});

test('validatePacks warns when a pack is not cheaper than loose', () => {
  const r = validatePacks([{ packTypeId: 5, size: 50, price: 3100 }], types, 6200);
  assert.deepEqual(r.errors, []);
  assert.deepEqual(r.warnings, ['Sack is not cheaper than buying 50 loose.']);
  assert.equal(validatePacks('x', types, 1).errors.length, 1);
});

test('aggregateSoldAs sums packs and loose, legacy rows count as loose', () => {
  const sack = { packTypeId: 5, name: 'Sack', size: 50, count: 2, price: 1, amount: 2 };
  const loose = { packTypeId: null, name: null, size: 1, count: 3, price: 1, amount: 3 };
  const m = aggregateSoldAs([
    { itemId: 1, qty: 103, breakdown: [sack, loose] },
    { itemId: 1, qty: 50, breakdown: [{ ...sack, count: 1 }] },
    { itemId: 1, qty: 7, breakdown: null },
    { itemId: 2, qty: 4, breakdown: null },
  ]);
  assert.deepEqual(m.get(1), { soldAs: [{ name: 'Sack', size: 50, count: 3 }], loose: 10 });
  assert.deepEqual(m.get(2), { soldAs: [], loose: 4 });
});

test('repriceLine re-prices from the snapshot, never current prices', () => {
  const line = { unitPrice: 6200, pricing: { retail: 6200, packs: [{ packTypeId: 5, name: 'Sack', size: 50, price: 290000 }] } };
  assert.equal(repriceLine(line, 60).total, 290000 + 10 * 6200);
  const part = repriceLine(line, 30);
  assert.equal(part.total, 30 * 6200);
  assert.deepEqual(part.parts.map((p) => p.count), [30]);
  assert.deepEqual(repriceLine(line, 0).parts, []);
});

test('repriceLine legacy line is one loose part at unit price', () => {
  const r = repriceLine({ unitPrice: 5600, pricing: null }, 20);
  assert.equal(r.total, 112000);
  assert.deepEqual(r.parts, [{ packTypeId: null, name: null, size: 1, count: 20, price: 5600, amount: 112000 }]);
});

test('shapeItem flattens packs with their type name', () => {
  const s = shapeItem({ id: 1, packs: [{ id: 3, packTypeId: 5, size: 50, price: 9, packType: { name: 'Sack' } }] });
  assert.equal(s.id, 1);
  assert.deepEqual(s.packs, [{ id: 3, packTypeId: 5, name: 'Sack', size: 50, price: 9 }]);
});

test('validatePacks keeps a since-deactivated type already on the item, but rejects it as new', () => {
  const t = new Map([[7, { id: 7, name: 'Bundle', active: false }]]);
  assert.strictEqual(validatePacks([{ packTypeId: 7, size: 5, price: 100 }], t, 100).errors.length, 1);
  assert.strictEqual(validatePacks([{ packTypeId: 7, size: 5, price: 100 }], t, 100, new Set([7])).errors.length, 0);
});
