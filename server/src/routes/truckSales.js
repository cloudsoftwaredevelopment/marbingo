const router = require('express').Router();
const prisma = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');
const { fail, wrap } = require('../lib/http');
const { upsertCustomers } = require('../lib/customerSync');
const { recordTruckSale } = require('../lib/truckOps');

router.use(authenticate);

const MAX_BATCH = 100;

// Offline, idempotent (by clientUuid). AGENT: their assigned truck. MANAGER: pass truckId.
router.post('/batch', requireRole('MANAGER', 'AGENT'), wrap(async (req, res) => {
  const b = req.body || {};
  const sales = Array.isArray(b.sales) ? b.sales : [];
  const customers = Array.isArray(b.customers) ? b.customers : [];
  if (sales.length > MAX_BATCH) return fail(res, 400, `Send at most ${MAX_BATCH} sales at a time.`);

  let truck;
  if (req.user.role === 'AGENT') {
    truck = await prisma.truck.findUnique({ where: { agentId: req.user.id } });
    if (!truck || !truck.active) return fail(res, 403, 'You are not assigned to a truck.');
  } else {
    const id = Number(b.truckId);
    truck = Number.isInteger(id) ? await prisma.truck.findUnique({ where: { id } }) : null;
    if (!truck) return fail(res, 400, 'Choose a truck.', { truckId: 'Choose a truck.' });
  }

  const saved = await upsertCustomers(customers, req.user);
  const customerIds = new Map(saved.map((c) => [c.clientUuid, c.id]));
  const results = [];
  for (const s of sales) results.push(await recordTruckSale(truck, req.user, s, customerIds));
  res.json({ customers: saved, results });
}));

module.exports = router;
