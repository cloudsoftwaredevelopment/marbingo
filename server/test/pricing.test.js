const test = require('node:test');
const assert = require('node:assert');
const path = require('path');
const { priceQty, suggestUpsell, describe } = require('../src/lib/pricing');

// Shared with client/src/lib/pricing.js and android Pricing.kt — all must match exactly.
const vectors = require(path.join(__dirname, '..', '..', 'docs', 'pricing-vectors.json'));

for (const c of vectors.cases) {
  test(`vector: ${c.name}`, () => {
    assert.deepStrictEqual(priceQty(c.qty, c.retail, c.packs), c.expect);
    assert.deepStrictEqual(suggestUpsell(c.qty, c.retail, c.packs), c.upsell);
  });
}

test('describe summarises parts', () => {
  const r = priceQty(53, 6200, [{ packTypeId: 5, name: 'Sack', size: 50, price: 290000 }]);
  assert.strictEqual(describe(r.parts, 'kg'), '1 × Sack (50 kg) + 3 kg');
});

test('input packs are not mutated', () => {
  const packs = [{ name: 'Box', size: 10, price: 900 }, { name: 'Crate', size: 20, price: 1800 }];
  const before = JSON.stringify(packs);
  priceQty(30, 100, packs);
  assert.strictEqual(JSON.stringify(packs), before);
});
