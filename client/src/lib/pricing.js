// Pack pricing — ES-module port of server/src/lib/pricing.js (docs/pricing.md). Keep behaviourally
// identical (same DP order, tie-breaking and greedy rule); checked by test/pricing.test.mjs against
// docs/pricing-vectors.json.

export const DP_LIMIT = 100000;

// Cheapest way to sell `qty` base units: full packs plus loose units at `retail` (centavos).
// packs: [{ packTypeId, name, size, price }] where price is for the whole pack.
// Returns { total, parts: [{ packTypeId, name, size, count, price, amount }] } — packs by size desc, loose last.
export function priceQty(qty, retail, packs = []) {
  const n = Math.max(0, Math.floor(Number(qty) || 0));
  const usable = packs
    .filter((p) => Number.isInteger(p.size) && p.size >= 2 && Number.isInteger(p.price) && p.price > 0)
    .sort((a, b) => b.size - a.size || a.price - b.price);
  const counts = n <= DP_LIMIT ? solveExact(n, retail, usable) : solveGreedy(n, retail, usable);
  return build(counts, retail, usable);
}

// Unbounded knapsack over quantity, minimising (cost, loose units, pack count) in that order:
// cheapest first; on equal cost, the most units in packs; then the fewest (i.e. largest) packs.
// Options are tried loose-first then packs by size descending, replacing only on a strictly
// better key, which settles any remaining full tie deterministically.
function solveExact(n, retail, packs) {
  const cost = new Float64Array(n + 1);
  const loose = new Int32Array(n + 1);
  const cnt = new Int32Array(n + 1);
  const pick = new Int32Array(n + 1); // -1 = loose unit, else index into packs
  const better = (c, l, k, q) => c < cost[q] || (c === cost[q] && (l < loose[q] || (l === loose[q] && k < cnt[q])));
  for (let q = 1; q <= n; q++) {
    cost[q] = cost[q - 1] + retail; loose[q] = loose[q - 1] + 1; cnt[q] = cnt[q - 1]; pick[q] = -1;
    for (let i = 0; i < packs.length; i++) {
      const s = packs[i].size;
      if (s > q) continue;
      const c = cost[q - s] + packs[i].price, l = loose[q - s], k = cnt[q - s] + 1;
      if (better(c, l, k, q)) { cost[q] = c; loose[q] = l; cnt[q] = k; pick[q] = i; }
    }
  }
  const counts = { loose: 0, packs: packs.map(() => 0) };
  for (let q = n; q > 0;) {
    const i = pick[q];
    if (i === -1) { counts.loose++; q--; } else { counts.packs[i]++; q -= packs[i].size; }
  }
  return counts;
}

// Very large quantities: largest pack first, using only packs cheaper per unit than retail.
function solveGreedy(n, retail, packs) {
  const counts = { loose: 0, packs: packs.map(() => 0) };
  let left = n;
  packs.forEach((p, i) => {
    if (p.price < p.size * retail) {
      counts.packs[i] = Math.floor(left / p.size);
      left -= counts.packs[i] * p.size;
    }
  });
  counts.loose = left;
  return counts;
}

function build(counts, retail, packs) {
  const parts = [];
  packs.forEach((p, i) => {
    if (counts.packs[i]) {
      parts.push({ packTypeId: p.packTypeId ?? null, name: p.name, size: p.size, count: counts.packs[i], price: p.price, amount: counts.packs[i] * p.price });
    }
  });
  if (counts.loose) parts.push({ packTypeId: null, name: null, size: 1, count: counts.loose, price: retail, amount: counts.loose * retail });
  return { total: parts.reduce((t, x) => t + x.amount, 0), parts };
}

// When buying a little more costs the same or less (e.g. 49 kg loose > one 50 kg sack), suggest it.
// Returns { addQty, total, saving } for the smallest such increase within one largest pack, else null.
export function suggestUpsell(qty, retail, packs = []) {
  const n = Math.max(0, Math.floor(Number(qty) || 0));
  const sizes = packs.filter((p) => Number.isInteger(p.size) && p.size >= 2 && p.price > 0).map((p) => p.size);
  if (!n || !sizes.length || n > DP_LIMIT) return null;
  const now = priceQty(n, retail, packs).total;
  for (let q = n + 1; q <= n + Math.max(...sizes); q++) {
    const t = priceQty(q, retail, packs).total;
    if (t <= now) return { addQty: q - n, total: t, saving: now - t };
  }
  return null;
}

// "1 × Sack (50 kg) + 3 kg" style summary for receipts and reports.
export function describe(parts, unit = 'pc') {
  return parts.map((x) => (x.name ? `${x.count} × ${x.name} (${x.size} ${unit})` : `${x.count} ${unit}`)).join(' + ');
}

