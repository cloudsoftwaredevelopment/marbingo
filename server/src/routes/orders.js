const router = require('express').Router();
const prisma = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');
const { fail, wrap, idParam } = require('../lib/http');
const { parseIso, startOfDay } = require('../lib/periods');
const { packInclude, repriceLine } = require('../lib/packs');
const {
  priceLine, mergeLines, parseLocation, deliveryQuantities, parseDate, orderInclude, shapeOrder,
} = require('../lib/orders');

router.param('id', idParam('Order not found.'));
router.use(authenticate);

const MAX_BATCH = 100;
const STATUSES = ['pending', 'delivered', 'cancelled'];
const staff = requireRole('MANAGER', 'INVENTORY');
const seeOrders = requireRole('MANAGER', 'INVENTORY', 'AGENT');

class OrderError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

const text = (v) => (v === undefined || v === null ? null : String(v).trim() || null);

async function upsertCustomers(list, user) {
  const out = [];
  for (const c of list) {
    const clientUuid = typeof (c && c.clientUuid) === 'string' ? c.clientUuid.trim() : '';
    const name = text(c && c.name);
    if (!clientUuid || !name) continue;
    // Create-only: a replayed batch must not overwrite edits made since (on the web or via PUT, which
    // the app uses to change existing stores). An invalid location is dropped and reported, not fatal,
    // so one bad fix can't block the offline queue forever.
    let row = await prisma.customer.findUnique({ where: { clientUuid } });
    let warning;
    if (!row) {
      const data = { name, ownerName: text(c.ownerName), phone: text(c.phone), address: text(c.address), notes: text(c.notes) };
      const loc = parseLocation(c, user);
      if (loc.error) warning = 'Location ignored: ' + Object.values(loc.fields).join(' ');
      else if (loc.data) Object.assign(data, loc.data);
      try {
        row = await prisma.customer.create({ data: { ...data, clientUuid, createdById: user.id } });
      } catch (e) {
        if (e.code !== 'P2002') throw e;
        row = await prisma.customer.findUnique({ where: { clientUuid } });
      }
    }
    out.push(warning ? { clientUuid, id: row.id, warning } : { clientUuid, id: row.id });
  }
  return out;
}

async function processOrder(o, user, customerIds) {
  const clientUuid = typeof (o && o.clientUuid) === 'string' ? o.clientUuid.trim() : '';
  if (!clientUuid) return { clientUuid: '', ok: false, error: 'Missing order id.' };
  const fail_ = (error) => ({ clientUuid, ok: false, error });

  const cancelReason = text(o.cancelReason);
  const existing = await prisma.order.findUnique({ where: { clientUuid }, include: orderInclude });
  if (existing) {
    // Never reveal another agent's order through a replayed (or guessed) clientUuid.
    if (existing.agentId !== user.id) return fail_('That order id belongs to another agent.');
    // Offline cancel of an order whose first upload did reach the server.
    if (cancelReason && existing.status === 'pending') {
      const cancelled = await prisma.order.update({
        where: { id: existing.id }, data: { status: 'cancelled', cancelledReason: cancelReason }, include: orderInclude,
      });
      return { clientUuid, ok: true, order: shapeOrder(cancelled) };
    }
    return { clientUuid, ok: true, order: shapeOrder(existing) };
  }

  let customerId = null;
  if (o.customerClientUuid) {
    customerId = customerIds.get(o.customerClientUuid);
    if (!customerId) {
      const c = await prisma.customer.findUnique({ where: { clientUuid: String(o.customerClientUuid) } });
      customerId = c && c.id;
    }
  } else if (o.customerId !== undefined) {
    const id = Number(o.customerId);
    const c = Number.isInteger(id) ? await prisma.customer.findUnique({ where: { id } }) : null;
    customerId = c && c.id;
  }
  if (!customerId) return fail_('Unknown store.');

  const merged = mergeLines(o.lines);
  if (merged.error) return fail_(merged.error);
  const items = await prisma.item.findMany({ where: { id: { in: merged.lines.map((l) => l.itemId) } }, include: packInclude });
  const byId = new Map(items.map((i) => [i.id, i]));
  const bad = merged.lines.find((l) => !byId.has(l.itemId));
  if (bad) return fail_(`Unknown item ${bad.itemId}.`);
  const lines = merged.lines.map((l) => priceLine(byId.get(l.itemId), l.qty));

  try {
    const created = await prisma.order.create({
      data: {
        clientUuid, customerId, agentId: user.id,
        orderedAt: parseDate(o.orderedAt, new Date()),
        notes: text(o.notes),
        // Cancelled on the device before it ever synced: record it so it can never be delivered.
        ...(cancelReason ? { status: 'cancelled', cancelledReason: cancelReason } : {}),
        total: lines.reduce((n, l) => n + l.lineTotal, 0),
        lines: { create: lines },
      },
      include: orderInclude,
    });
    return { clientUuid, ok: true, order: shapeOrder(created) };
  } catch (e) {
    if (e.code === 'P2002') {
      const dup = await prisma.order.findUnique({ where: { clientUuid }, include: orderInclude });
      if (dup) return { clientUuid, ok: true, order: shapeOrder(dup) };
    }
    console.error(e);
    return fail_('Could not save this order.');
  }
}

router.post('/batch', requireRole('MANAGER', 'AGENT'), wrap(async (req, res) => {
  const b = req.body || {};
  const orders = Array.isArray(b.orders) ? b.orders : [];
  const customers = Array.isArray(b.customers) ? b.customers : [];
  if (orders.length > MAX_BATCH) return fail(res, 400, `Send at most ${MAX_BATCH} orders at a time.`);

  const saved = await upsertCustomers(customers, req.user);
  const customerIds = new Map(saved.map((c) => [c.clientUuid, c.id]));
  const results = [];
  for (const o of orders) results.push(await processOrder(o, req.user, customerIds));
  res.json({ customers: saved, results });
}));

router.get('/pending-count', staff, wrap(async (req, res) => {
  res.json({ pending: await prisma.order.count({ where: { status: 'pending' } }) });
}));

router.get('/', seeOrders, wrap(async (req, res) => {
  const where = {};
  const q = req.query;
  if (req.user.role === 'AGENT') where.agentId = req.user.id;
  else if (q.agentId !== undefined) where.agentId = Number(q.agentId) || -1;
  if (q.customerId !== undefined) where.customerId = Number(q.customerId) || -1;
  if (q.from || q.to) {
    const a = q.from ? parseIso(q.from) : null;
    const z = q.to ? parseIso(q.to) : null;
    if ((q.from && a === null) || (q.to && z === null) || (a !== null && z !== null && z < a)) {
      return fail(res, 400, 'Choose a start date before the end date');
    }
    where.orderedAt = {};
    if (a !== null) where.orderedAt.gte = startOfDay(a);
    if (z !== null) where.orderedAt.lt = startOfDay(z + 1);
  }
  const [list, grouped] = await Promise.all([
    prisma.order.findMany({
      where: { ...where, ...(STATUSES.includes(q.status) ? { status: q.status } : {}) },
      include: orderInclude,
      orderBy: [{ orderedAt: 'desc' }, { id: 'desc' }],
      take: 200,
    }),
    prisma.order.groupBy({ by: ['status'], where, _count: true }),
  ]);
  const counts = { pending: 0, delivered: 0, cancelled: 0 };
  for (const g of grouped) counts[g.status] = g._count;
  res.json({ orders: list.map(shapeOrder), counts });
}));

// Loads an order the caller may see (agents: own only), else answers 404.
async function loadOrder(req, res) {
  const order = await prisma.order.findUnique({ where: { id: req.id }, include: orderInclude });
  if (!order || (req.user.role === 'AGENT' && order.agentId !== req.user.id)) {
    fail(res, 404, 'Order not found.');
    return null;
  }
  return order;
}



router.get('/:id', seeOrders, wrap(async (req, res) => {
  const order = await loadOrder(req, res);
  if (order) res.json({ order: shapeOrder(order) });
}));

router.post('/:id/deliver', staff, wrap(async (req, res) => {
  const order = await loadOrder(req, res);
  if (!order) return;
  if (order.status === 'delivered') return res.json({ order: shapeOrder(order) });
  if (order.status === 'cancelled') return fail(res, 409, 'This order was cancelled.');

  const q = deliveryQuantities(order.lines, (req.body || {}).lines);
  if (q.error) return fail(res, 400, q.error);
  const qtyBy = new Map(q.lines.map((l) => [l.itemId, l.qty]));

  const result = await prisma.$transaction(async (tx) => {
    // Claim the order first so a concurrent delivery or cancel can't also run.
    const now = new Date();
    const claim = await tx.order.updateMany({
      where: { id: order.id, status: 'pending' },
      data: { status: 'delivered', deliveredAt: now, deliveredById: req.user.id },
    });
    if (claim.count !== 1) {
      const fresh = await tx.order.findUnique({ where: { id: order.id }, include: orderInclude });
      return { order: fresh };
    }
    const saleLines = [];
    for (const l of order.lines) {
      const qty = qtyBy.get(l.itemId);
      if (qty > 0) {
        const r = await tx.item.updateMany({
          where: { id: l.itemId, qtyOnHand: { gte: qty } },
          data: { qtyOnHand: { decrement: qty } },
        });
        if (r.count !== 1) {
          const fresh = await tx.item.findUnique({ where: { id: l.itemId } });
          throw new OrderError(409, `Not enough stock for "${l.item.name}" (${fresh.qtyOnHand} left).`);
        }
        const priced = repriceLine(l, qty);
        saleLines.push({ itemId: l.itemId, qty, unitPrice: l.unitPrice, lineTotal: priced.total, breakdown: priced.parts });
      }
      await tx.orderLine.update({ where: { id: l.id }, data: { deliveredQty: qty } });
    }
    const sale = await tx.sale.create({
      data: {
        soldAt: now, cashierId: order.agentId, channel: 'field', customerId: order.customerId,
        total: saleLines.reduce((n, l) => n + l.lineTotal, 0),
        lines: { create: saleLines },
      },
    });
    await tx.stockMove.createMany({
      data: saleLines.map((l) => ({ itemId: l.itemId, change: -l.qty, reason: 'sale', byId: req.user.id, at: now })),
    });
    await tx.order.update({ where: { id: order.id }, data: { saleId: sale.id } });
    return { order: await tx.order.findUnique({ where: { id: order.id }, include: orderInclude }) };
  }).catch((e) => (e instanceof OrderError ? e : Promise.reject(e)));

  if (result instanceof OrderError) return fail(res, result.status, result.message);
  if (result.order.status === 'cancelled') return fail(res, 409, 'This order was cancelled.');
  res.json({ order: shapeOrder(result.order) });
}));

router.post('/:id/cancel', seeOrders, wrap(async (req, res) => {
  const order = await loadOrder(req, res);
  if (!order) return;
  if (order.status === 'delivered') return fail(res, 409, 'A delivered order cannot be cancelled.');
  if (order.status === 'cancelled') return res.json({ order: shapeOrder(order) });
  const reason = text((req.body || {}).reason);
  const r = await prisma.order.updateMany({
    where: { id: order.id, status: 'pending' },
    data: { status: 'cancelled', cancelledReason: reason },
  });
  if (r.count !== 1) return fail(res, 409, 'This order is no longer pending.');
  res.json({ order: shapeOrder(await prisma.order.findUnique({ where: { id: order.id }, include: orderInclude })) });
}));

module.exports = router;
