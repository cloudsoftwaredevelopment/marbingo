// Pure helpers for field orders (no DB), plus the shared order shape.
const fieldPrice = (item) => (item.wholesalePrice != null ? item.wholesalePrice : item.sellingPrice);

const priceLine = (item, qty) => {
  const unitPrice = fieldPrice(item);
  return { itemId: item.id, qty, unitPrice, lineTotal: unitPrice * qty };
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
  })),
});

const shapeCustomer = (c) => ({
  id: c.id, clientUuid: c.clientUuid, name: c.name, ownerName: c.ownerName, phone: c.phone,
  address: c.address, notes: c.notes, active: c.active, createdAt: c.createdAt, updatedAt: c.updatedAt,
});

module.exports = { fieldPrice, priceLine, mergeLines, deliveryQuantities, parseDate, orderInclude, shapeOrder, shapeCustomer };
