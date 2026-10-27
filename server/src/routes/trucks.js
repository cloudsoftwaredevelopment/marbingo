const router = require('express').Router();
const prisma = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');
const { fail, wrap, idParam } = require('../lib/http');
const { TruckError, createPeriod, requestTransfer, shapeTransfer, transferInclude, userRef } = require('../lib/truckOps');

router.param('id', idParam('Truck not found.'));
router.use(authenticate);

const staff = requireRole('MANAGER', 'INVENTORY');
const manager = requireRole('MANAGER');

const periodRef = (p) => (p ? { id: p.id, month: p.month, status: p.status, openedAt: p.openedAt, countStartedAt: p.countStartedAt, closedAt: p.closedAt } : null);

const truckInclude = { agent: { select: { id: true, firstName: true, lastName: true } } };

const shapeTruck = (t) => ({ id: t.id, name: t.name, plate: t.plate, driverName: t.driverName, active: t.active, agent: userRef(t.agent) });

// Validates body fields. With partial=true only supplied ones are checked. Returns { fields, data }.
async function parse(b, partial, truckId) {
  const fields = {};
  const data = {};
  const has = (k) => b[k] !== undefined;
  if (!partial || has('name')) {
    const name = String(b.name || '').trim();
    if (!name) fields.name = 'Enter the truck name.';
    else data.name = name;
  }
  if (has('plate')) data.plate = String(b.plate || '').trim() || null;
  if (has('driverName')) data.driverName = String(b.driverName || '').trim() || null;
  if (has('active')) data.active = !!b.active;
  if (has('agentId')) {
    if (b.agentId === null || b.agentId === '') data.agentId = null;
    else {
      const id = Number(b.agentId);
      const u = Number.isInteger(id) ? await prisma.user.findUnique({ where: { id }, include: { truck: true } }) : null;
      if (!u || u.role !== 'AGENT' || !u.active) fields.agentId = 'Choose an active agent.';
      else if (u.truck && u.truck.id !== truckId) fields.agentId = `${u.firstName} is already assigned to ${u.truck.name}.`;
      else data.agentId = id;
    }
  }
  return { fields, data };
}

router.get('/', staff, wrap(async (req, res) => {
  const [trucks, periods, stock, pending] = await Promise.all([
    prisma.truck.findMany({ include: truckInclude, orderBy: { id: 'asc' } }),
    prisma.truckPeriod.findMany({ where: { status: { not: 'closed' } } }),
    prisma.truckStock.groupBy({ by: ['truckId'], _sum: { qty: true } }),
    prisma.truckTransfer.groupBy({ by: ['truckId'], where: { status: 'pending' }, _count: true }),
  ]);
  res.json({
    trucks: trucks.map((t) => ({
      ...shapeTruck(t),
      currentPeriod: periodRef(periods.find((p) => p.truckId === t.id)),
      unitsOnBoard: (stock.find((s) => s.truckId === t.id) || { _sum: {} })._sum.qty || 0,
      pendingTransfers: (pending.find((s) => s.truckId === t.id) || {})._count || 0,
    })),
  });
}));

router.post('/', manager, wrap(async (req, res) => {
  const { fields, data } = await parse(req.body || {}, false, 0);
  if (Object.keys(fields).length) return fail(res, 400, 'Check the highlighted fields.', fields);
  const t = await prisma.truck.create({ data, include: truckInclude });
  res.status(201).json({ truck: shapeTruck(t) });
}));

router.get('/:id', staff, wrap(async (req, res) => {
  const t = await prisma.truck.findUnique({ where: { id: req.id }, include: truckInclude });
  if (!t) return fail(res, 404, 'Truck not found.');
  const [periods, stock, transfers] = await Promise.all([
    prisma.truckPeriod.findMany({ where: { truckId: t.id }, orderBy: { month: 'desc' }, take: 24 }),
    prisma.truckStock.findMany({ where: { truckId: t.id, qty: { not: 0 } }, include: { item: { select: { name: true, unit: true, sellingPrice: true } } }, orderBy: { itemId: 'asc' } }),
    prisma.truckTransfer.findMany({ where: { truckId: t.id }, include: transferInclude, orderBy: { id: 'desc' }, take: 50 }),
  ]);
  const stockRows = stock.map((s) => ({
    itemId: s.itemId, name: s.item.name, unit: s.item.unit, qty: s.qty, retailPrice: s.item.sellingPrice, value: s.qty * s.item.sellingPrice,
  }));
  res.json({
    truck: shapeTruck(t),
    currentPeriod: periodRef(periods.find((p) => p.status !== 'closed')),
    periods: periods.map((p) => ({ ...periodRef(p), matched: p.report ? p.report.matched : null, missingValue: p.report ? p.report.totals.missingValue : null })),
    stock: stockRows,
    unitsOnBoard: stockRows.reduce((n, r) => n + r.qty, 0),
    stockValue: stockRows.reduce((n, r) => n + r.value, 0),
    transfers: transfers.map(shapeTransfer),
    pendingTransfers: transfers.filter((x) => x.status === 'pending').length,
  });
}));

router.put('/:id', manager, wrap(async (req, res) => {
  const t = await prisma.truck.findUnique({ where: { id: req.id } });
  if (!t) return fail(res, 404, 'Truck not found.');
  const { fields, data } = await parse(req.body || {}, true, t.id);
  if (Object.keys(fields).length) return fail(res, 400, 'Check the highlighted fields.', fields);
  const live = await prisma.truckPeriod.findFirst({ where: { truckId: t.id, status: { in: ['open', 'counting'] } } });
  if (live && data.agentId !== undefined && data.agentId !== t.agentId) {
    return fail(res, 409, 'The agent cannot change while a period is open. Close the period first.');
  }
  if (live && data.active === false) return fail(res, 409, 'A truck with an open period cannot be deactivated. Close the period first.');
  const updated = await prisma.truck.update({ where: { id: t.id }, data, include: truckInclude });
  res.json({ truck: shapeTruck(updated) });
}));

router.get('/:id/periods', staff, wrap(async (req, res) => {
  if (!(await prisma.truck.findUnique({ where: { id: req.id } }))) return fail(res, 404, 'Truck not found.');
  const periods = await prisma.truckPeriod.findMany({ where: { truckId: req.id }, orderBy: { month: 'desc' } });
  res.json({ periods: periods.map((p) => ({ ...periodRef(p), matched: p.report ? p.report.matched : null, missingValue: p.report ? p.report.totals.missingValue : null })) });
}));

router.post('/:id/periods', manager, wrap(async (req, res) => {
  const p = await createPeriod(req.id, (req.body || {}).month);
  res.status(201).json({ period: periodRef(p) });
}));

router.post('/:id/transfers', staff, wrap(async (req, res) => {
  const b = req.body || {};
  const t = await requestTransfer(req.id, req.user.id, { kind: b.kind, note: b.note, lines: b.lines });
  res.status(201).json({ transfer: shapeTransfer(t) });
}));

module.exports = router;
