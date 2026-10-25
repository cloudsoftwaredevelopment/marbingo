const router = require('express').Router();
const prisma = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');
const { fail, wrap, idParam } = require('../lib/http');
const { orderInclude, shapeOrder, shapeCustomer } = require('../lib/orders');

router.param('id', idParam('Store not found.'));
router.use(authenticate, requireRole('MANAGER', 'INVENTORY', 'AGENT'));

const TEXT = ['ownerName', 'phone', 'address', 'notes'];

// Validates store fields; with partial=true only supplied fields are checked.
function parse(b, partial) {
  const fields = {};
  const data = {};
  if (!partial || b.name !== undefined) {
    const name = String(b.name || '').trim();
    if (!name) fields.name = 'Enter the store name.';
    else data.name = name;
  }
  for (const k of TEXT) if (b[k] !== undefined) data[k] = String(b[k] === null ? '' : b[k]).trim() || null;
  if (partial && b.active !== undefined) data.active = Boolean(b.active);
  return { fields, data };
}

const totalsFor = async (ids) => {
  const [orders, delivered] = await Promise.all([
    prisma.order.groupBy({ by: ['customerId'], where: { customerId: { in: ids } }, _count: true }),
    prisma.sale.groupBy({ by: ['customerId'], where: { customerId: { in: ids }, order: { isNot: null } }, _sum: { total: true } }),
  ]);
  return {
    count: new Map(orders.map((r) => [r.customerId, r._count])),
    sum: new Map(delivered.map((r) => [r.customerId, r._sum.total || 0])),
  };
};

router.get('/', wrap(async (req, res) => {
  const q = String(req.query.q || '').trim();
  const where = {};
  if (req.query.all !== '1') where.active = true;
  if (q) {
    where.OR = ['name', 'ownerName', 'phone', 'address'].map((k) => ({ [k]: { contains: q, mode: 'insensitive' } }));
  }
  const customers = await prisma.customer.findMany({ where, orderBy: { name: 'asc' } });
  const t = await totalsFor(customers.map((c) => c.id));
  res.json(customers.map((c) => ({
    ...shapeCustomer(c), orderCount: t.count.get(c.id) || 0, totalDelivered: t.sum.get(c.id) || 0,
  })));
}));

router.get('/:id', wrap(async (req, res) => {
  const customer = await prisma.customer.findUnique({ where: { id: req.id } });
  if (!customer) return fail(res, 404, 'Store not found.');
  const where = { customerId: customer.id };
  if (req.user.role === 'AGENT') where.agentId = req.user.id;
  const orders = await prisma.order.findMany({
    where, include: orderInclude, orderBy: [{ orderedAt: 'desc' }, { id: 'desc' }], take: 200,
  });
  const t = await totalsFor([customer.id]);
  res.json({
    customer: shapeCustomer(customer),
    orders: orders.map(shapeOrder),
    totals: {
      orderCount: t.count.get(customer.id) || 0,
      pendingCount: await prisma.order.count({ where: { ...where, status: 'pending' } }),
      totalDelivered: t.sum.get(customer.id) || 0,
    },
  });
}));

router.post('/', wrap(async (req, res) => {
  const b = req.body || {};
  const clientUuid = typeof b.clientUuid === 'string' && b.clientUuid.trim() ? b.clientUuid.trim() : undefined;
  if (clientUuid) {
    const existing = await prisma.customer.findUnique({ where: { clientUuid } });
    if (existing) return res.json({ customer: shapeCustomer(existing) });
  }
  const { fields, data } = parse(b, false);
  if (Object.keys(fields).length) return fail(res, 400, 'Check the highlighted fields.', fields);
  try {
    const customer = await prisma.customer.create({ data: { ...data, clientUuid, createdById: req.user.id } });
    res.status(201).json({ customer: shapeCustomer(customer) });
  } catch (e) {
    // A concurrent request with the same clientUuid won the race.
    if (e.code === 'P2002' && clientUuid) {
      return res.json({ customer: shapeCustomer(await prisma.customer.findUnique({ where: { clientUuid } })) });
    }
    throw e;
  }
}));

router.put('/:id', wrap(async (req, res) => {
  const existing = await prisma.customer.findUnique({ where: { id: req.id } });
  if (!existing) return fail(res, 404, 'Store not found.');
  if (req.user.role === 'AGENT' && existing.createdById !== req.user.id) {
    return fail(res, 403, 'You do not have access to this.');
  }
  const { fields, data } = parse(req.body || {}, true);
  if (Object.keys(fields).length) return fail(res, 400, 'Check the highlighted fields.', fields);
  const customer = await prisma.customer.update({ where: { id: req.id }, data });
  res.json({ customer: shapeCustomer(customer) });
}));

module.exports = router;
