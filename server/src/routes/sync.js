const router = require('express').Router();
const prisma = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');
const { fail, wrap } = require('../lib/http');
const { readSchedule } = require('../lib/tracking');
const { fieldPrice, orderInclude, shapeOrder, shapeCustomer } = require('../lib/orders');

router.use(authenticate);

// Rows are stamped with their write time, not commit time, so a transaction still open during the
// previous pull can commit with updatedAt < since. Re-send a window before `since`; device upserts are idempotent.
const OVERLAP_MS = 5 * 60 * 1000;

router.get('/pull', requireRole('MANAGER', 'AGENT'), wrap(async (req, res) => {
  // Captured before the queries so rows changed mid-pull are picked up next time.
  const serverTime = new Date();
  let since = null;
  if (req.query.since) {
    since = new Date(String(req.query.since));
    if (Number.isNaN(since.getTime())) return fail(res, 400, 'since must be an ISO date.');
  }
  const changed = since ? { updatedAt: { gt: new Date(since.getTime() - OVERLAP_MS) } } : {};
  const orderWhere = { ...changed, ...(req.user.role === 'AGENT' ? { agentId: req.user.id } : {}) };

  const [items, categories, customers, orders] = await Promise.all([
    prisma.item.findMany({ where: changed, include: { category: { select: { name: true } } }, orderBy: { id: 'asc' } }),
    prisma.category.findMany({ orderBy: { name: 'asc' } }),
    prisma.customer.findMany({ where: changed, orderBy: { id: 'asc' } }),
    prisma.order.findMany({ where: orderWhere, include: orderInclude, orderBy: { id: 'asc' } }),
  ]);

  const trackingSchedule = await readSchedule(prisma);

  res.json({
    serverTime,
    trackingSchedule,
    trackingConsentAt: req.user.trackingConsentAt,
    items: items.map((i) => ({
      id: i.id, name: i.name, sku: i.sku, unit: i.unit,
      categoryId: i.categoryId, category: i.category ? i.category.name : null,
      sellingPrice: i.sellingPrice, wholesalePrice: i.wholesalePrice, fieldPrice: fieldPrice(i),
      qtyOnHand: i.qtyOnHand, imageUrl: i.imageUrl, updatedAt: i.updatedAt,
    })),
    categories: categories.map((c) => ({ id: c.id, name: c.name })),
    customers: customers.map(shapeCustomer),
    orders: orders.map(shapeOrder),
  });
}));

module.exports = router;
