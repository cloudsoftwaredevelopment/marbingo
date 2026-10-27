// Pure truck-period maths (no DB). Quantities are keyed by itemId in plain objects; money is integer centavos.

// Move reasons that count towards a period's expected stock. `load` is excluded because the loads applied when the
// period opens are already inside the opening snapshot, and `count_variance` only ever settles a closed count.
const EXPECTED_REASONS = ['topup', 'return', 'sale'];

// Snapshot rows [{itemId, qty}] <-> { itemId: qty }.
const toMap = (rows) => Object.fromEntries((rows || []).map((r) => [r.itemId, r.qty]));
const toRows = (map) => Object.entries(map).map(([itemId, qty]) => ({ itemId: Number(itemId), qty })).sort((a, b) => a.itemId - b.itemId);

// Expected stock at count time: opening + the period's top-ups, returns and sales (signed changes).
// opening: { itemId: qty }; moves: [{ itemId, change, reason }]. May be negative after an oversell.
function expected({ opening = {}, moves = [] }) {
  const out = { ...opening };
  for (const m of moves) {
    if (!EXPECTED_REASONS.includes(m.reason)) continue;
    out[m.itemId] = (out[m.itemId] || 0) + m.change;
  }
  return out;
}

// Compares expected with counted (both { itemId: qty }; prices { itemId: retail centavos }).
// An item missing from `counted` counts as 0. Items with nothing expected and nothing counted are left out.
// value = |variance| x retail. Missing = counted < expected, surplus = counted > expected.
function reconcile(exp, counted, prices = {}) {
  const ids = new Set([...Object.keys(exp), ...Object.keys(counted)].map(Number));
  const lines = [];
  let missingQty = 0, missingValue = 0, surplusQty = 0, surplusValue = 0;
  for (const itemId of [...ids].sort((a, b) => a - b)) {
    const e = exp[itemId] || 0;
    const c = counted[itemId] || 0;
    if (e === 0 && c === 0) continue;
    const variance = c - e;
    const value = Math.abs(variance) * (prices[itemId] || 0);
    const status = variance < 0 ? 'missing' : variance > 0 ? 'surplus' : 'ok';
    if (status === 'missing') { missingQty += -variance; missingValue += value; }
    if (status === 'surplus') { surplusQty += variance; surplusValue += value; }
    lines.push({ itemId, expected: e, counted: c, variance, status, value });
  }
  return { matched: lines.every((l) => l.status === 'ok'), lines, missingQty, missingValue, surplusQty, surplusValue };
}

// Items whose expected qty is non-zero but have no count line: they block closing.
const uncounted = (exp, countedIds) => Object.keys(exp).map(Number).filter((id) => exp[id] !== 0 && !countedIds.has(id));

// Next period month (YYYY-MM): the month after `month`, but never earlier than `currentMonth`.
function nextMonth(month, currentMonth) {
  const [y, m] = month.split('-').map(Number);
  const next = m === 12 ? `${y + 1}-01` : `${y}-${String(m + 1).padStart(2, '0')}`;
  return next < currentMonth ? currentMonth : next;
}

module.exports = { EXPECTED_REASONS, toMap, toRows, expected, reconcile, uncounted, nextMonth };
