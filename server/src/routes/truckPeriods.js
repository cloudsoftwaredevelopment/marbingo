const router = require('express').Router();
const prisma = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');
const { fail, wrap, idParam } = require('../lib/http');
const { openPeriod, startCount, saveCount, buildReport, closePeriod, userRef } = require('../lib/truckOps');

router.param('id', idParam('Period not found.'));
router.use(authenticate);

const staff = requireRole('MANAGER', 'INVENTORY');
const manager = requireRole('MANAGER');

const shapePeriod = (p, opening) => ({
  id: p.id, truckId: p.truckId, month: p.month, status: p.status,
  openedAt: p.openedAt, openedBy: userRef(p.openedBy), countStartedAt: p.countStartedAt, closedAt: p.closedAt, closedBy: userRef(p.closedBy),
  opening: opening || [],
});

const include = { openedBy: { select: { id: true, firstName: true, lastName: true } }, closedBy: { select: { id: true, firstName: true, lastName: true } } };

async function loadShaped(id) {
  const p = await prisma.truckPeriod.findUnique({ where: { id }, include });
  if (!p) return null;
  const snap = p.openingSnapshot || [];
  const items = await prisma.item.findMany({ where: { id: { in: snap.map((r) => r.itemId) } }, select: { id: true, name: true, unit: true } });
  const info = new Map(items.map((i) => [i.id, i]));
  return shapePeriod(p, snap.map((r) => ({ itemId: r.itemId, name: info.get(r.itemId).name, unit: info.get(r.itemId).unit, qty: r.qty })));
}

// Period + the quantities staff must count (expected != 0) + the saved count, if any.
router.get('/:id', staff, wrap(async (req, res) => {
  const period = await loadShaped(req.id);
  if (!period) return fail(res, 404, 'Period not found.');
  const report = await buildReport(prisma, req.id);
  const count = await prisma.truckCount.findUnique({ where: { periodId: req.id }, include: { lines: true } });
  // Expected for items with a non-zero balance, from the live report (lines with hasCount=false and expected=0 are not needed).
  const exp = report.lines.filter((l) => l.expected !== 0).map((l) => ({ itemId: l.itemId, name: l.name, unit: l.unit, expected: l.expected }));
  const unseen = report.uncountedItems.filter((u) => !exp.some((e) => e.itemId === u.itemId));
  res.json({
    period,
    expected: [...exp, ...unseen.map((u) => ({ itemId: u.itemId, name: u.name, unit: null, expected: u.expected }))],
    count: count ? { countedAt: count.countedAt, lines: count.lines.map((l) => ({ itemId: l.itemId, counted: l.counted })).sort((a, b) => a.itemId - b.itemId) } : null,
  });
}));

router.post('/:id/open', manager, wrap(async (req, res) => {
  await openPeriod(req.id, req.user.id);
  res.json({ period: await loadShaped(req.id) });
}));

router.post('/:id/start-count', staff, wrap(async (req, res) => {
  await startCount(req.id, req.user.id);
  res.json({ period: await loadShaped(req.id) });
}));

router.put('/:id/count', staff, wrap(async (req, res) => {
  await saveCount(req.id, req.user.id, (req.body || {}).lines);
  res.json({ report: await buildReport(prisma, req.id) });
}));

router.get('/:id/report', staff, wrap(async (req, res) => {
  const p = await prisma.truckPeriod.findUnique({ where: { id: req.id }, select: { status: true, report: true } });
  if (!p) return fail(res, 404, 'Period not found.');
  if (p.status === 'closed' && p.report) return res.json({ report: { ...p.report, preview: false } });
  res.json({ report: { ...(await buildReport(prisma, req.id)), preview: true } });
}));

router.post('/:id/close', manager, wrap(async (req, res) => {
  const r = await closePeriod(req.id, req.user.id);
  res.json({ period: await loadShaped(r.period.id), report: { ...r.report, preview: false }, nextPeriod: { id: r.next.id, month: r.next.month, status: r.next.status } });
}));

module.exports = router;
