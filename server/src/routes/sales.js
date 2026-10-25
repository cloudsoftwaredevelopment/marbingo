const router = require('express').Router();
const prisma = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');
const { fail, wrap } = require('../lib/http');
const { parseIso, dayOf, startOfDay } = require('../lib/periods');

router.use(authenticate);

// Thrown inside the transaction so everything rolls back.
class SaleError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

const shapeSale = (s) => ({
  id: s.id,
  soldAt: s.soldAt,
  total: s.total,
  channel: s.channel,
  lines: s.lines.map((l) => ({
    itemId: l.itemId, name: l.item.name, qty: l.qty, unitPrice: l.unitPrice, lineTotal: l.lineTotal,
  })),
});

router.post('/', requireRole('MANAGER', 'CASHIER', 'AGENT'), wrap(async (req, res) => {
  const raw = Array.isArray(req.body && req.body.lines) ? req.body.lines : [];
  if (!raw.length) return fail(res, 400, 'Add at least one item to the sale.');
  const merged = new Map();
  for (const l of raw) {
    const itemId = Number(l && l.itemId);
    const qty = Number(l && l.qty);
    if (!Number.isInteger(itemId) || !Number.isInteger(qty) || qty < 1) {
      return fail(res, 400, 'Each line needs an item and a whole quantity of at least 1.');
    }
    merged.set(itemId, (merged.get(itemId) || 0) + qty);
  }
  const channel = req.user.role === 'AGENT' ? 'field' : 'counter';

  const result = await prisma.$transaction(async (tx) => {
    const items = await tx.item.findMany({ where: { id: { in: [...merged.keys()] } } });
    const byId = new Map(items.map((i) => [i.id, i]));
    const lines = [];
    for (const [itemId, qty] of merged) {
      const item = byId.get(itemId);
      if (!item) throw new SaleError(404, 'Item not found.');
      if (item.qtyOnHand < qty) {
        throw new SaleError(409, `Not enough stock for "${item.name}" (${item.qtyOnHand} left).`);
      }
      const r = await tx.item.updateMany({
        where: { id: itemId, qtyOnHand: { gte: qty } },
        data: { qtyOnHand: { decrement: qty } },
      });
      if (r.count !== 1) {
        const fresh = await tx.item.findUnique({ where: { id: itemId } });
        throw new SaleError(409, `Not enough stock for "${item.name}" (${fresh.qtyOnHand} left).`);
      }
      lines.push({ itemId, qty, unitPrice: item.sellingPrice, lineTotal: item.sellingPrice * qty });
    }
    const sale = await tx.sale.create({
      data: {
        cashierId: req.user.id,
        channel,
        total: lines.reduce((n, l) => n + l.lineTotal, 0),
        lines: { create: lines },
      },
      include: { lines: { include: { item: true }, orderBy: { id: 'asc' } } },
    });
    await tx.stockMove.createMany({
      data: lines.map((l) => ({ itemId: l.itemId, change: -l.qty, reason: 'sale', byId: req.user.id, at: sale.soldAt })),
    });
    return sale;
  }).catch((e) => (e instanceof SaleError ? e : Promise.reject(e)));

  if (result instanceof SaleError) return fail(res, result.status, result.message);
  res.status(201).json({ sale: shapeSale(result) });
}));

router.get('/mine', requireRole('MANAGER', 'CASHIER', 'AGENT'), wrap(async (req, res) => {
  const today = dayOf(new Date());
  const a = req.query.from ? parseIso(req.query.from) : today;
  const b = req.query.to ? parseIso(req.query.to) : a === null ? null : Math.max(a, today);
  if (a === null || b === null || b < a) return fail(res, 400, 'Choose a start date before the end date');
  const sales = await prisma.sale.findMany({
    where: { cashierId: req.user.id, soldAt: { gte: startOfDay(a), lt: startOfDay(b + 1) } },
    include: { lines: { include: { item: true }, orderBy: { id: 'asc' } } },
    orderBy: [{ soldAt: 'desc' }, { id: 'desc' }],
    take: 200,
  });
  res.json({
    sales: sales.map(shapeSale),
    summary: { count: sales.length, total: sales.reduce((n, s) => n + s.total, 0) },
  });
}));

module.exports = router;
