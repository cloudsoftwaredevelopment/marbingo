import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { priceQty, suggestUpsell } from '../src/lib/pricing.js';

const vectors = JSON.parse(readFileSync(new URL('../../docs/pricing-vectors.json', import.meta.url), 'utf8'));

for (const c of vectors.cases) {
  test(c.name, () => {
    assert.deepStrictEqual(priceQty(c.qty, c.retail, c.packs), c.expect);
    assert.deepStrictEqual(suggestUpsell(c.qty, c.retail, c.packs), c.upsell);
  });
}
