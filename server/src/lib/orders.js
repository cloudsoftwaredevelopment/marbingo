// Pure helpers for field orders (no DB), plus the shared order shape.
const { priceItem, repriceLine } = require('./packs');

// Order line columns for `qty` of an item (with packs loaded): retail unit price, priced total, breakdown, snapshot.
const priceLine = (item, qty) => {
  const r = priceItem(item, qty);
  return {
    itemId: item.id, qty, unitPrice: r.retail, lineTotal: r.total, breakdown: r.parts,
    pricing: { retail: r.retail, packs: r.packs },
  };
};

const MAX_QTY = 100000;

// Merges duplicate itemIds and checks each qty is an integer >= 1.
function mergeLines(raw) {
  if (!Array.isArray(raw) || !raw.length) return { error: 'Add at least one item to the order.' };
  const merged = new Map();
  for (const l of raw) {
    const itemId = Number(l && l.itemId);
    const qty = Number(l && l.qty);
    if (!Number.isInteger(itemId) || !Number.isInteger(qty) || qty < 1) {
      return { error: 'Each line needs an item and a whole quantity of at least 1.' };
    }
    merged.set(itemId, (merged.get(itemId) || 0) + qty);
    if (merged.get(itemId) > MAX_QTY) return { error: 'That quantity is too large.' };
  }
  return { lines: [...merged].map(([itemId, qty]) => ({ itemId, qty })) };
}

// Delivered quantity per order line. Lines left out are delivered in full.
// orderLines: [{ itemId, qty }]; raw: [{ itemId, qty }] from the request.
function deliveryQuantities(orderLines, raw) {
  if (raw !== undefined && !Array.isArray(raw)) return { error: 'Lines must be a list.' };
  const asked = new Map();
  for (const l of raw || []) {
    const itemId = Number(l && l.itemId);
    const qty = Number(l && l.qty);
    const ordered = orderLines.find((o) => o.itemId === itemId);
    if (!ordered) return { error: `Item ${l && l.itemId} is not on this order.` };
    if (!Number.isInteger(qty) || qty < 0 || qty > ordered.qty) {
      return { error: 'Delivered quantity must be a whole number from 0 up to the ordered quantity.' };
    }
    asked.set(itemId, qty);
  }
  const out = orderLines.map((o) => ({ itemId: o.itemId, qty: asked.has(o.itemId) ? asked.get(o.itemId) : o.qty }));
  if (!out.some((l) => l.qty > 0)) return { error: 'Deliver at least one item, or cancel the order.' };
  return { lines: out };
}

const parseDate = (v, fallback) => {
  const d = new Date(v);
  return typeof v === 'string' && !Number.isNaN(d.getTime()) ? d : fallback;
};

const userRef = (u) => (u ? { id: u.id, firstName: u.firstName, lastName: u.lastName } : null);

const orderInclude = {
  customer: { select: { id: true, clientUuid: true, name: true } },
  agent: { select: { id: true, firstName: true, lastName: true } },
  deliveredBy: { select: { id: true, firstName: true, lastName: true } },
  lines: { include: { item: { select: { name: true, unit: true } } }, orderBy: { id: 'asc' } },
};

const shapeOrder = (o) => ({
  id: o.id,
  clientUuid: o.clientUuid,
  status: o.status,
  customer: { id: o.customer.id, clientUuid: o.customer.clientUuid, name: o.customer.name },
  agent: userRef(o.agent),
  orderedAt: o.orderedAt,
  syncedAt: o.syncedAt,
  updatedAt: o.updatedAt,
  deliveredAt: o.deliveredAt,
  deliveredBy: userRef(o.deliveredBy),
  cancelledReason: o.cancelledReason,
  notes: o.notes,
  total: o.total,
  saleId: o.saleId,
  lines: o.lines.map((l) => ({
    itemId: l.itemId, name: l.item.name, unit: l.item.unit,
    qty: l.qty, unitPrice: l.unitPrice, lineTotal: l.lineTotal, deliveredQty: l.deliveredQty,
    breakdown: l.breakdown || repriceLine(l, l.qty).parts,
    deliveredBreakdown: l.deliveredQty == null ? null : repriceLine(l, l.deliveredQty).parts,
    pricing: l.pricing || null, // snapshot {retail, packs} — lets the web deliver dialog re-price exactly
  })),
});

const shapeCustomer = (c) => ({
  id: c.id, clientUuid: c.clientUuid, name: c.name, ownerName: c.ownerName, phone: c.phone,
  address: c.address, notes: c.notes, active: c.active,
  lat: c.lat, lng: c.lng, locationSource: c.locationSource, locationAccuracy: c.locationAccuracy, locatedAt: c.locatedAt,
  createdAt: c.createdAt, updatedAt: c.updatedAt,
});

// Reads lat/lng/locationAccuracy from a request body. Returns { error, fields } | { data } | { data: null } when absent.
// null/null clears the location. Source is gps for AGENT callers, manual otherwise.
function parseLocation(b, user) {
  const given = (k) => b[k] !== undefined;
  if (!given('lat') && !given('lng')) return { data: null };
  if (b.lat === null && b.lng === null) {
    return { data: { lat: null, lng: null, locationSource: null, locationAccuracy: null, locatedAt: null, locatedById: null } };
  }
  const lat = b.lat === null || b.lat === '' ? NaN : Number(b.lat);
  const lng = b.lng === null || b.lng === '' ? NaN : Number(b.lng);
  const fields = {};
  if (!Number.isFinite(lat) || lat < -90 || lat > 90) fields.lat = 'Latitude must be between -90 and 90.';
  if (!Number.isFinite(lng) || lng < -180 || lng > 180) fields.lng = 'Longitude must be between -180 and 180.';
  let acc = null;
  if (b.locationAccuracy !== undefined && b.locationAccuracy !== null) {
    acc = Number(b.locationAccuracy);
    if (!Number.isFinite(acc) || acc < 0) fields.locationAccuracy = 'Accuracy must be 0 or more metres.';
  }
  if (Object.keys(fields).length) return { error: 'Check the highlighted fields.', fields };
  return {
    data: {
      lat, lng, locationAccuracy: acc, locationSource: user.role === 'AGENT' ? 'gps' : 'manual',
      locatedAt: new Date(), locatedById: user.id,
    },
  };
}

module.exports = { parseLocation, priceLine, mergeLines, deliveryQuantities, parseDate, orderInclude, shapeOrder, shapeCustomer };
