// Transactional truck operations (docs/truck-api.md), shared by the routes and the seed.
// Warehouse = items.qty_on_hand + stock_moves; truck = truck_stock + truck_moves (qty == SUM(change)).
const prisma = require('../db');
const { packInclude } = require('./packs');
const { priceLine, parseDate } = require('./orders');
const { EXPECTED_REASONS, expected, reconcile, uncounted, nextMonth, toMap, toRows } = require('./truck');

class TruckError extends Error {
  constructor(status, message, extra) { super(message); this.status = status; this.expose = true; this.extra = extra; }
}

const MANILA_MS = 8 * 3600 * 1000;
const MONTH_RE = /^\d{4}-(0[1-9]|1[0-2])$/;
const MAX_QTY = 100000;
const DAY_MS = 86400000;
const LATE_WINDOW_DAYS = 35;

const manilaMonth = (d = new Date()) => new Date(d.getTime() + MANILA_MS).toISOString().slice(0, 7);

// Adds delta to truck_stock atomically (insert or add) and returns the new qty. The truck ledger row is the caller's job.
async function addStock(tx, truckId, itemId, delta) {
  const r = await tx.$queryRaw`
    INSERT INTO truck_stock (truck_id, item_id, qty) VALUES (${truckId}, ${itemId}, ${delta})
    ON CONFLICT (truck_id, item_id) DO UPDATE SET qty = truck_stock.qty + EXCLUDED.qty
    RETURNING qty`;
  return r[0].qty;
}

// Serialises period transitions against transfers and sales on one truck: open/close take the exclusive lock,
// everything that posts a move into a period takes the shared one.
const lockTruck = (tx, truckId, exclusive) => (exclusive
  ? tx.$queryRaw`SELECT id FROM trucks WHERE id = ${truckId} FOR UPDATE`
  : tx.$queryRaw`SELECT id FROM trucks WHERE id = ${truckId} FOR SHARE`);

// [{itemId, qty}] with qty a whole number >= 1; duplicates merged.
function mergeQty(raw) {
  if (!Array.isArray(raw) || !raw.length) throw new TruckError(400, 'Add at least one item.');
  const merged = new Map();
  for (const l of raw) {
    const itemId = Number(l && l.itemId);
    const qty = Number(l && l.qty);
    if (!Number.isInteger(itemId) || !Number.isInteger(qty) || qty < 1) {
      throw new TruckError(400, 'Each line needs an item and a whole quantity of at least 1.');
    }
    merged.set(itemId, (merged.get(itemId) || 0) + qty);
    if (merged.get(itemId) > MAX_QTY) throw new TruckError(400, 'That quantity is too large.');
  }
  return [...merged].map(([itemId, qty]) => ({ itemId, qty })).sort((a, b) => a.itemId - b.itemId);
}

const shortMessage = (what, short) =>
  `Not enough ${what} for: ${short.map((s) => `${s.name} (${s.have} left, ${s.need} needed)`).join(', ')}.`;

const transferInclude = {
  lines: { include: { item: { select: { name: true, unit: true } } }, orderBy: { itemId: 'asc' } },
  requestedBy: { select: { id: true, firstName: true, lastName: true } },
  decidedBy: { select: { id: true, firstName: true, lastName: true } },
};

const userRef = (u) => (u ? { id: u.id, firstName: u.firstName, lastName: u.lastName } : null);

const shapeTransfer = (t) => ({
  id: t.id, truckId: t.truckId, periodId: t.periodId, kind: t.kind, status: t.status, note: t.note,
  requestedBy: userRef(t.requestedBy), requestedAt: t.requestedAt,
  decidedBy: userRef(t.decidedBy), decidedAt: t.decidedAt,
  lines: t.lines.map((l) => ({ itemId: l.itemId, name: l.item.name, unit: l.item.unit, qty: l.qty })),
});

// ---- periods -------------------------------------------------------------------------------------------------

async function createPeriod(truckId, month) {
  const truck = await prisma.truck.findUnique({ where: { id: truckId } });
  if (!truck) throw new TruckError(404, 'Truck not found.');
  if (!truck.active) throw new TruckError(409, 'This truck is not active.');
  const m = month === undefined || month === null || month === '' ? manilaMonth() : String(month);
  if (!MONTH_RE.test(m)) throw new TruckError(400, 'Month must look like 2026-10.', { fields: { month: 'Month must look like 2026-10.' } });
  try {
    return await prisma.truckPeriod.create({ data: { truckId, month: m } });
  } catch (e) {
    if (e.code !== 'P2002') throw e;
    const open = await prisma.truckPeriod.findFirst({ where: { truckId, status: { not: 'closed' } } });
    throw new TruckError(409, open ? `This truck already has an unfinished period (${open.month}).` : `${m} already exists for this truck.`);
  }
}

// Applies an approved transfer's stock effects inside tx. Throws 409 {short} (rolling the caller back) when
// the source (warehouse for load/topup, truck for return) cannot cover every line.
async function applyTransfer(tx, t, periodId, userId, now) {
  const lines = [...t.lines].sort((a, b) => a.itemId - b.itemId);
  const short = [];
  if (t.kind === 'return') {
    for (const l of lines) {
      const r = await tx.truckStock.updateMany({
        where: { truckId: t.truckId, itemId: l.itemId, qty: { gte: l.qty } }, data: { qty: { decrement: l.qty } },
      });
      if (r.count !== 1) {
        const cur = await tx.truckStock.findUnique({ where: { truckId_itemId: { truckId: t.truckId, itemId: l.itemId } } });
        short.push({ itemId: l.itemId, name: l.item.name, need: l.qty, have: cur ? cur.qty : 0 });
      }
    }
    if (short.length) throw new TruckError(409, shortMessage('stock on the truck', short), { short });
    for (const l of lines) await tx.item.update({ where: { id: l.itemId }, data: { qtyOnHand: { increment: l.qty } } });
    await tx.stockMove.createMany({ data: lines.map((l) => ({ itemId: l.itemId, change: l.qty, reason: 'truck_return', at: now, byId: userId })) });
  } else {
    for (const l of lines) {
      const r = await tx.item.updateMany({ where: { id: l.itemId, qtyOnHand: { gte: l.qty } }, data: { qtyOnHand: { decrement: l.qty } } });
      if (r.count !== 1) {
        const cur = await tx.item.findUnique({ where: { id: l.itemId } });
        short.push({ itemId: l.itemId, name: l.item.name, need: l.qty, have: cur.qtyOnHand });
      }
    }
    if (short.length) throw new TruckError(409, shortMessage('warehouse stock', short), { short });
    await tx.stockMove.createMany({ data: lines.map((l) => ({ itemId: l.itemId, change: -l.qty, reason: 'truck_load', at: now, byId: userId })) });
    for (const l of lines) await addStock(tx, t.truckId, l.itemId, l.qty);
  }
  const sign = t.kind === 'return' ? -1 : 1;
  await tx.truckMove.createMany({
    data: lines.map((l) => ({
      truckId: t.truckId, itemId: l.itemId, periodId, change: sign * l.qty, reason: t.kind, at: now,
      refType: 'transfer', refId: t.id, byId: userId,
    })),
  });
}

// Requests a transfer. load -> needs a draft period; topup/return -> needs the open period.
async function requestTransfer(truckId, userId, { kind, note, lines }) {
  if (!['load', 'topup', 'return'].includes(kind)) throw new TruckError(400, 'Choose load, top-up or return.', { fields: { kind: 'Choose load, top-up or return.' } });
  const merged = mergeQty(lines);
  const truck = await prisma.truck.findUnique({ where: { id: truckId } });
  if (!truck) throw new TruckError(404, 'Truck not found.');
  if (!truck.active) throw new TruckError(409, 'This truck is not active.');
  const period = await prisma.truckPeriod.findFirst({ where: { truckId, status: { not: 'closed' } } });
  if (kind === 'load' && (!period || period.status !== 'draft')) {
    throw new TruckError(409, 'A load can only be requested while the period is a draft. Use a top-up once it is open.');
  }
  if (kind !== 'load' && (!period || period.status !== 'open')) {
    throw new TruckError(409, 'Top-ups and returns need an open period.');
  }
  const items = await prisma.item.findMany({ where: { id: { in: merged.map((l) => l.itemId) } }, select: { id: true, name: true } });
  if (items.length !== merged.length) throw new TruckError(400, 'One of the items does not exist.');
  if (kind === 'return') {
    // Refuse up front what approval would refuse: truck stock minus returns already waiting for approval.
    const ids = merged.map((l) => l.itemId);
    const stock = await prisma.truckStock.findMany({ where: { truckId, itemId: { in: ids } } });
    const pending = await prisma.truckTransferLine.groupBy({
      by: ['itemId'], _sum: { qty: true },
      where: { itemId: { in: ids }, transfer: { truckId, kind: 'return', status: 'pending' } },
    });
    const short = [];
    for (const l of merged) {
      const have = (stock.find((s) => s.itemId === l.itemId)?.qty || 0) - (pending.find((p) => p.itemId === l.itemId)?._sum.qty || 0);
      if (l.qty > have) short.push({ itemId: l.itemId, name: items.find((i) => i.id === l.itemId).name, need: l.qty, have: Math.max(have, 0) });
    }
    if (short.length) throw new TruckError(409, shortMessage('stock on the truck', short), { short });
  }
  const t = await prisma.truckTransfer.create({
    data: {
      truckId, periodId: period.id, kind, note: note ? String(note).trim() || null : null, requestedById: userId,
      lines: { create: merged },
    },
    include: transferInclude,
  });
  return t;
}

async function approveTransfer(transferId, userId) {
  return prisma.$transaction(async (tx) => {
    const t = await tx.truckTransfer.findUnique({ where: { id: transferId }, include: transferInclude });
    if (!t) throw new TruckError(404, 'Transfer not found.');
    if (t.status !== 'pending') throw new TruckError(409, `This transfer was already ${t.status}.`);
    if (t.kind === 'load') throw new TruckError(409, 'A load is approved by opening the period (Approve beginning inventory).');
    await lockTruck(tx, t.truckId, false);
    const period = await tx.truckPeriod.findUnique({ where: { id: t.periodId } });
    if (period.status !== 'open') throw new TruckError(409, 'Top-ups and returns can only be approved while the period is open.');
    const now = new Date();
    // Claim first so a concurrent approve/reject can't also apply it.
    const claim = await tx.truckTransfer.updateMany({
      where: { id: t.id, status: 'pending' }, data: { status: 'approved', decidedById: userId, decidedAt: now },
    });
    if (claim.count !== 1) throw new TruckError(409, 'This transfer was already decided.');
    await applyTransfer(tx, t, period.id, userId, now);
    return tx.truckTransfer.findUnique({ where: { id: t.id }, include: transferInclude });
  });
}

async function rejectTransfer(transferId, userId) {
  const t = await prisma.truckTransfer.findUnique({ where: { id: transferId } });
  if (!t) throw new TruckError(404, 'Transfer not found.');
  const r = await prisma.truckTransfer.updateMany({
    where: { id: transferId, status: 'pending' }, data: { status: 'rejected', decidedById: userId, decidedAt: new Date() },
  });
  if (r.count !== 1) throw new TruckError(409, `This transfer was already ${t.status}.`);
  return prisma.truckTransfer.findUnique({ where: { id: transferId }, include: transferInclude });
}

// Manager approves the beginning inventory: pending load transfers are applied (warehouse -> truck) and
// openingSnapshot is fixed. The snapshot is the truck stock right now, minus anything already posted to this period
// (late sales that synced while it was still a draft), so that expected = snapshot + period moves counts each unit once.
async function openPeriod(periodId, userId) {
  return prisma.$transaction(async (tx) => {
    const p = await tx.truckPeriod.findUnique({ where: { id: periodId } });
    if (!p) throw new TruckError(404, 'Period not found.');
    await lockTruck(tx, p.truckId, true);
    const now = new Date();
    const claim = await tx.truckPeriod.updateMany({
      where: { id: periodId, status: 'draft' }, data: { status: 'open', openedAt: now, openedById: userId },
    });
    if (claim.count !== 1) throw new TruckError(409, 'This period is already open.');

    const loads = await tx.truckTransfer.findMany({
      where: { truckId: p.truckId, kind: 'load', status: 'pending' }, include: transferInclude, orderBy: { id: 'asc' },
    });
    for (const t of loads) {
      await tx.truckTransfer.update({ where: { id: t.id }, data: { status: 'approved', decidedById: userId, decidedAt: now, periodId } });
      await applyTransfer(tx, t, periodId, userId, now);
    }

    const stock = await tx.truckStock.findMany({ where: { truckId: p.truckId } });
    const pre = await tx.truckMove.groupBy({
      by: ['itemId'], where: { periodId, reason: { in: EXPECTED_REASONS } }, _sum: { change: true },
    });
    const opening = Object.fromEntries(stock.map((s) => [s.itemId, s.qty]));
    for (const g of pre) opening[g.itemId] = (opening[g.itemId] || 0) - (g._sum.change || 0);
    for (const k of Object.keys(opening)) if (opening[k] === 0) delete opening[k];
    return tx.truckPeriod.update({ where: { id: periodId }, data: { openingSnapshot: toRows(opening) } });
  }, { timeout: 20000 });
}

async function startCount(periodId, userId) {
  const p = await prisma.truckPeriod.findUnique({ where: { id: periodId } });
  if (!p) throw new TruckError(404, 'Period not found.');
  const r = await prisma.truckPeriod.updateMany({ where: { id: periodId, status: 'open' }, data: { status: 'counting', countStartedAt: new Date() } });
  if (r.count !== 1) throw new TruckError(409, p.status === 'counting' ? 'The count has already started.' : `A ${p.status} period cannot be counted.`);
  return prisma.truckPeriod.findUnique({ where: { id: periodId } });
}

// Replaces the whole count. lines: [{ itemId, counted >= 0 }].
async function saveCount(periodId, userId, raw) {
  if (!Array.isArray(raw)) throw new TruckError(400, 'Counts must be a list.');
  const seen = new Map();
  for (const l of raw) {
    const itemId = Number(l && l.itemId);
    const counted = Number(l && l.counted);
    if (!Number.isInteger(itemId) || !Number.isInteger(counted) || counted < 0 || counted > MAX_QTY) {
      throw new TruckError(400, 'Each count needs an item and a whole number of 0 or more.');
    }
    seen.set(itemId, counted);
  }
  const p = await prisma.truckPeriod.findUnique({ where: { id: periodId } });
  if (!p) throw new TruckError(404, 'Period not found.');
  if (p.status !== 'counting') throw new TruckError(409, 'Start the count before entering quantities.');
  const items = await prisma.item.findMany({ where: { id: { in: [...seen.keys()] } }, select: { id: true } });
  if (items.length !== seen.size) throw new TruckError(400, 'One of the items does not exist.');
  await prisma.$transaction(async (tx) => {
    const cur = await tx.truckPeriod.findUnique({ where: { id: periodId } });
    if (cur.status !== 'counting') throw new TruckError(409, 'This period is no longer being counted.');
    const count = await tx.truckCount.upsert({
      where: { periodId }, create: { periodId, countedById: userId }, update: { countedById: userId, countedAt: new Date() },
    });
    await tx.truckCountLine.deleteMany({ where: { countId: count.id } });
    await tx.truckCountLine.createMany({ data: [...seen].map(([itemId, counted]) => ({ countId: count.id, itemId, counted })) });
  });
}

// Reconcile report for a period, computed live from the ledger (a closed period's frozen copy is in period.report).
async function buildReport(db, periodId) {
  const p = await db.truckPeriod.findUnique({
    where: { id: periodId },
    include: { truck: true, count: { include: { lines: true, countedBy: { select: { id: true, firstName: true, lastName: true } } } } },
  });
  if (!p) throw new TruckError(404, 'Period not found.');
  const moves = await db.truckMove.findMany({ where: { periodId, reason: { in: EXPECTED_REASONS } }, select: { itemId: true, change: true, reason: true } });
  const exp = expected({ opening: toMap(p.openingSnapshot), moves });
  const counted = p.count ? Object.fromEntries(p.count.lines.map((l) => [l.itemId, l.counted])) : {};
  const countedIds = new Set(p.count ? p.count.lines.map((l) => l.itemId) : []);

  const ids = [...new Set([...Object.keys(exp), ...Object.keys(counted)].map(Number))];
  const items = await db.item.findMany({ where: { id: { in: ids } }, select: { id: true, name: true, unit: true, sellingPrice: true } });
  const info = new Map(items.map((i) => [i.id, i]));
  const prices = Object.fromEntries(items.map((i) => [i.id, i.sellingPrice]));
  const rec = reconcile(exp, counted, prices);

  const opening = toMap(p.openingSnapshot);
  const sum = (itemId, reason) => moves.filter((m) => m.itemId === itemId && m.reason === reason).reduce((n, m) => n + m.change, 0);
  const missingCounts = uncounted(exp, countedIds).map((id) => ({ itemId: id, name: info.get(id).name, expected: exp[id] }));
  const [salesN, oversoldN, lateN, agg] = await Promise.all([
    db.truckSale.count({ where: { periodId } }),
    db.truckSale.count({ where: { periodId, oversold: true } }),
    db.truckSale.count({ where: { periodId, late: true } }),
    db.sale.aggregate({ where: { truckSale: { periodId } }, _sum: { total: true } }),
  ]);

  return {
    periodId, month: p.month, status: p.status,
    truck: { id: p.truck.id, name: p.truck.name, plate: p.truck.plate, driverName: p.truck.driverName },
    generatedAt: new Date(),
    counted: !!p.count,
    countedAt: p.count ? p.count.countedAt : null,
    countedBy: p.count ? userRef(p.count.countedBy) : null,
    complete: missingCounts.length === 0, // every item with stock expected has a count line (required to close)
    uncountedItems: missingCounts,
    matched: rec.matched,
    totals: { missingQty: rec.missingQty, missingValue: rec.missingValue, surplusQty: rec.surplusQty, surplusValue: rec.surplusValue },
    lines: rec.lines.map((l) => ({
      ...l,
      name: info.get(l.itemId).name, unit: info.get(l.itemId).unit, retailPrice: info.get(l.itemId).sellingPrice,
      opening: opening[l.itemId] || 0,
      toppedUp: sum(l.itemId, 'topup'), returned: -sum(l.itemId, 'return'), sold: -sum(l.itemId, 'sale'),
      hasCount: countedIds.has(l.itemId),
    })),
    sales: { count: salesN, total: agg._sum.total || 0, oversold: oversoldN, late: lateN },
  };
}

// Manager closes a counted period: every item with expected != 0 needs a count line. Posts count_variance moves
// (counted - expected) so truck_stock == counted, freezes the report and creates the next draft (carry-over).
async function closePeriod(periodId, userId) {
  return prisma.$transaction(async (tx) => {
    const p0 = await tx.truckPeriod.findUnique({ where: { id: periodId } });
    if (!p0) throw new TruckError(404, 'Period not found.');
    await lockTruck(tx, p0.truckId, true);
    const p = await tx.truckPeriod.findUnique({ where: { id: periodId } });
    if (p.status !== 'counting') {
      throw new TruckError(409, p.status === 'closed' ? 'This period is already closed.' : 'Start and save the count before closing.');
    }
    const report = await buildReport(tx, periodId);
    if (!report.counted) throw new TruckError(409, 'Save the count before closing.');
    if (!report.complete) {
      throw new TruckError(409, `Count every item on the truck: ${report.uncountedItems.map((u) => u.name).join(', ')}.`, { uncounted: report.uncountedItems });
    }
    const now = new Date();
    for (const l of report.lines) {
      if (l.variance === 0) continue;
      await addStock(tx, p.truckId, l.itemId, l.variance);
      await tx.truckMove.create({
        data: { truckId: p.truckId, itemId: l.itemId, periodId, change: l.variance, reason: 'count_variance', at: now, refType: 'period', refId: periodId, byId: userId },
      });
    }
    const frozen = { ...report, status: 'closed', closedAt: now };
    const closed = await tx.truckPeriod.update({
      where: { id: periodId }, data: { status: 'closed', closedAt: now, closedById: userId, report: frozen },
    });
    const month = nextMonth(p.month, manilaMonth(now));
    const exists = await tx.truckPeriod.findUnique({ where: { truckId_month: { truckId: p.truckId, month } } });
    const next = exists || await tx.truckPeriod.create({ data: { truckId: p.truckId, month } });
    return { period: closed, report: frozen, next };
  }, { timeout: 20000 });
}

// ---- truck sales ---------------------------------------------------------------------------------------------

const saleInclude = { sale: { include: { lines: { orderBy: { id: 'asc' } } } } };

const shapeTruckSale = (ts) => ({
  id: ts.sale.id, clientUuid: ts.clientUuid, truckId: ts.truckId, periodId: ts.periodId,
  soldAt: ts.sale.soldAt, customerId: ts.sale.customerId, total: ts.sale.total, oversold: ts.oversold, late: ts.late,
  lines: ts.sale.lines.map((l) => ({ itemId: l.itemId, qty: l.qty, unitPrice: l.unitPrice, lineTotal: l.lineTotal, breakdown: l.breakdown })),
});

// One offline truck sale -> { clientUuid, ok, sale } | { clientUuid, ok:false, error }.
// Stock is decremented UNCONDITIONALLY (it may go below zero and the sale is flagged `oversold`): the goods are already
// in the customer's hands, so unlike warehouse sales this deliberately does not use the conditional `qty >= n` decrement.
async function recordTruckSale(truck, user, s, customerIds) {
  const clientUuid = typeof (s && s.clientUuid) === 'string' ? s.clientUuid.trim() : '';
  if (!clientUuid) return { clientUuid: '', ok: false, error: 'Missing sale id.' };
  const bad = (error) => ({ clientUuid, ok: false, error });

  const replay = (ts) => (ts.truckId !== truck.id ? bad('That sale id belongs to another truck.') : { clientUuid, ok: true, sale: shapeTruckSale(ts) });
  const existing = await prisma.truckSale.findUnique({ where: { clientUuid }, include: saleInclude });
  if (existing) return replay(existing);

  let customerId = null;
  if (s.customerClientUuid) {
    customerId = customerIds.get(s.customerClientUuid);
    if (!customerId) {
      const c = await prisma.customer.findUnique({ where: { clientUuid: String(s.customerClientUuid) } });
      customerId = c && c.id;
    }
    if (!customerId) return bad('Unknown store.');
  } else if (s.customerId !== undefined && s.customerId !== null) {
    const id = Number(s.customerId);
    const c = Number.isInteger(id) ? await prisma.customer.findUnique({ where: { id } }) : null;
    if (!c) return bad('Unknown store.');
    customerId = c.id;
  }

  let merged;
  try { merged = mergeQty(s.lines); } catch (e) { return bad(e.message); }
  const items = await prisma.item.findMany({ where: { id: { in: merged.map((l) => l.itemId) } }, include: packInclude });
  const byId = new Map(items.map((i) => [i.id, i]));
  const unknown = merged.find((l) => !byId.has(l.itemId));
  if (unknown) return bad(`Unknown item ${unknown.itemId}.`);
  const lines = merged.map((l) => priceLine(byId.get(l.itemId), l.qty));

  const now = new Date();
  const soldAt = parseDate(s.soldAt, now);
  const clamped = new Date(Math.min(Math.max(soldAt.getTime(), now.getTime() - LATE_WINDOW_DAYS * DAY_MS), now.getTime()));

  try {
    const ts = await prisma.$transaction(async (tx) => {
      await lockTruck(tx, truck.id, false);
      // The period that was open at soldAt (latest opened at/before it). If it is already closed, or none was open yet,
      // the sale posts to the current unfinished period and is marked late; soldAt keeps the real time.
      const at = await tx.truckPeriod.findFirst({
        where: { truckId: truck.id, status: { not: 'draft' }, openedAt: { lte: clamped } }, orderBy: { openedAt: 'desc' },
      });
      let period = at && at.status !== 'closed' ? at : null;
      let late = false;
      if (!period) {
        period = await tx.truckPeriod.findFirst({ where: { truckId: truck.id, status: { not: 'closed' } } });
        late = true;
      }
      if (!period) throw new TruckError(409, 'This truck has no period to post the sale to. Ask a manager to create one.');

      const sale = await tx.sale.create({
        data: {
          soldAt: clamped, cashierId: user.id, channel: 'truck', customerId,
          total: lines.reduce((n, l) => n + l.lineTotal, 0),
          lines: { create: lines.map(({ pricing, ...l }) => l) },
        },
      });
      let oversold = false;
      for (const l of lines) {
        const qty = await addStock(tx, truck.id, l.itemId, -l.qty);
        if (qty < 0) oversold = true;
      }
      await tx.truckMove.createMany({
        data: lines.map((l) => ({
          truckId: truck.id, itemId: l.itemId, periodId: period.id, change: -l.qty, reason: 'sale', at: clamped,
          refType: 'sale', refId: sale.id, byId: user.id,
        })),
      });
      return tx.truckSale.create({
        data: { clientUuid, saleId: sale.id, truckId: truck.id, periodId: period.id, oversold, late },
        include: saleInclude,
      });
    });
    return { clientUuid, ok: true, sale: shapeTruckSale(ts) };
  } catch (e) {
    if (e instanceof TruckError) return bad(e.message);
    if (e.code === 'P2002') {
      const dup = await prisma.truckSale.findUnique({ where: { clientUuid }, include: saleInclude });
      if (dup) return replay(dup);
    }
    console.error(e);
    return bad('Could not save this sale.');
  }
}

module.exports = {
  TruckError, manilaMonth, MONTH_RE, addStock, mergeQty, transferInclude, shapeTransfer, userRef,
  createPeriod, requestTransfer, approveTransfer, rejectTransfer, openPeriod, startCount, saveCount, buildReport, closePeriod,
  recordTruckSale,
};
