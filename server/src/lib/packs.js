// Pure pack helpers (docs/pricing.md): validation, snapshots, re-pricing, report aggregation, item shaping.
const { priceQty } = require('./pricing');

const packInclude = { packs: { include: { packType: { select: { name: true } } }, orderBy: [{ size: 'desc' }, { id: 'asc' }] } };

// Prisma item_packs rows (with packType) or already-flat packs → [{ packTypeId, name, size, price }].
const toPricingPacks = (packs) => (packs || []).map((p) => ({
  packTypeId: p.packTypeId ?? null, name: p.name ?? (p.packType && p.packType.name) ?? null, size: p.size, price: p.price,
}));

const snapshotOf = (retail, packs) => ({ retail, packs: toPricingPacks(packs) });

// Item as sent to clients: packs flattened.
function shapeItem(item) {
  const { packs, ...rest } = item;
  return { ...rest, packs: (packs || []).map((p) => ({ id: p.id, packTypeId: p.packTypeId, name: p.packType ? p.packType.name : p.name, size: p.size, price: p.price })) };
}

// A line priced for `qty` at the current retail + packs: the order/sale line columns.
function priceItem(item, qty) {
  const packs = toPricingPacks(item.packs);
  const r = priceQty(qty, item.sellingPrice, packs);
  return { total: r.total, parts: r.parts, retail: item.sellingPrice, packs };
}

// Re-prices `qty` from a stored line ({ unitPrice, pricing }). Legacy lines (no snapshot) are one loose part.
function repriceLine(line, qty) {
  const snap = line.pricing;
  if (snap && Number.isInteger(snap.retail)) return priceQty(qty, snap.retail, snap.packs || []);
  const amount = line.unitPrice * qty;
  return { total: amount, parts: qty > 0 ? [{ packTypeId: null, name: null, size: 1, count: qty, price: line.unitPrice, amount }] : [] };
}

const toPesoCentavos = (v) => {
  if (typeof v === 'number') return Number.isFinite(v) ? Math.round(v * 100) : NaN;
  if (typeof v === 'string' && v.trim() !== '' && Number.isFinite(Number(v))) return Math.round(Number(v) * 100);
  return NaN;
};

// raw: parsed [{ packTypeId, size, price }]; types: Map id → { name, active }.
// → { packs: [{packTypeId,size,price}], errors: [{index,message}], warnings: [string] }
// keepTypeIds: types the item already uses — those stay valid even if since deactivated, so the
// item can still be saved; inactive types can't be newly added.
function validatePacks(raw, types, retail, keepTypeIds = new Set()) {
  const errors = [];
  const warnings = [];
  const packs = [];
  if (!Array.isArray(raw)) return { packs, errors: [{ index: -1, message: 'Packs must be a list.' }], warnings };
  const seenType = new Set();
  const seenSize = new Set();
  raw.forEach((p, index) => {
    const err = (message) => errors.push({ index, message });
    const typeId = Number(p && p.packTypeId);
    const type = Number.isInteger(typeId) ? types.get(typeId) : null;
    const size = typeof (p && p.size) === 'number' || (typeof (p && p.size) === 'string' && p.size.trim() !== '') ? Number(p.size) : NaN;
    const price = toPesoCentavos(p && p.price);
    let ok = true;
    if (!type || (!type.active && !keepTypeIds.has(typeId))) { err('Choose a pack type.'); ok = false; }
    if (!Number.isInteger(size) || size < 2) { err('Size must be a whole number of at least 2.'); ok = false; }
    if (!Number.isInteger(price) || price <= 0) { err('Price must be more than 0.'); ok = false; }
    if (!ok) return;
    if (seenType.has(typeId)) { err(`${type.name} is already on this item.`); return; }
    if (seenSize.has(size)) { err(`Another pack already holds ${size}.`); return; }
    seenType.add(typeId);
    seenSize.add(size);
    packs.push({ packTypeId: typeId, size, price });
    if (retail != null && price >= size * retail) warnings.push(`${type.name} is not cheaper than buying ${size} loose.`);
  });
  return { packs, errors, warnings };
}

// rows: [{ itemId, breakdown, qty }] (breakdown null → legacy, all loose). → Map itemId → { soldAs: [{name,size,count}], loose }.
function aggregateSoldAs(rows) {
  const out = new Map();
  for (const r of rows) {
    const e = out.get(r.itemId) || { packs: new Map(), loose: 0 };
    out.set(r.itemId, e);
    if (!Array.isArray(r.breakdown)) { e.loose += r.qty; continue; }
    for (const part of r.breakdown) {
      if (part.name) {
        const key = `${part.name}|${part.size}`;
        const cur = e.packs.get(key) || { name: part.name, size: part.size, count: 0 };
        cur.count += part.count;
        e.packs.set(key, cur);
      } else e.loose += part.count;
    }
  }
  const res = new Map();
  for (const [id, e] of out) {
    res.set(id, { soldAs: [...e.packs.values()].sort((a, b) => b.size - a.size || a.name.localeCompare(b.name)), loose: e.loose });
  }
  return res;
}

module.exports = { packInclude, toPricingPacks, snapshotOf, shapeItem, priceItem, repriceLine, validatePacks, aggregateSoldAs };
