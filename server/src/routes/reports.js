const router = require('express').Router();
const prisma = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');
const { wrap } = require('../lib/http');
const { resolvePeriod, bucketIndex } = require('../lib/periods');
const { turnover } = require('../lib/turnover');

router.use(authenticate);

const PRESETS = ['today', 'week', 'month', 'last', 'custom'];

router.get('/sales', requireRole('MANAGER', 'INVENTORY'), wrap(async (req, res) => {
  const preset = PRESETS.includes(req.query.preset) ? req.query.preset : 'month';
  const p = resolvePeriod(preset, { from: req.query.from, to: req.query.to });
  if (p.error) {
    return res.json({
      range: { label: p.error }, error: p.error, unit: 'By week',
      totals: { gross: 0, transactions: 0, units: 0, average: 0 }, buckets: [], items: [],
    });
  }

  const [sales, rows] = await Promise.all([
    prisma.sale.findMany({
      where: { soldAt: { gte: p.from, lt: p.to } },
      select: { soldAt: true, total: true },
    }),
    prisma.$queryRaw`
      SELECT i.id, i.name, c.name AS category,
             SUM(l.qty)::int AS units, SUM(l.line_total)::int AS revenue
      FROM sale_lines l
      JOIN sales s ON s.id = l.sale_id
      JOIN items i ON i.id = l.item_id
      LEFT JOIN categories c ON c.id = i.category_id
      WHERE s.sold_at >= ${p.from} AND s.sold_at < ${p.to}
      GROUP BY i.id, i.name, c.name
      ORDER BY revenue DESC, i.name`,
  ]);

  const values = p.buckets.map(() => 0);
  let gross = 0;
  for (const s of sales) {
    gross += s.total;
    const idx = bucketIndex(p.buckets, s.soldAt);
    if (idx >= 0) values[idx] += s.total;
  }
  const units = rows.reduce((n, r) => n + r.units, 0);
  const transactions = sales.length;

  res.json({
    range: { label: p.label, from: p.fromDate, to: p.toDate },
    unit: p.unit,
    totals: { gross, transactions, units, average: transactions ? Math.round(gross / transactions) : 0 },
    buckets: p.buckets
      .map((b, i) => ({ label: b.label, value: values[i], upcoming: b.upcoming, offHours: b.offHours }))
      .filter((b) => !b.offHours || b.value > 0)
      .map(({ offHours, ...b }) => b),
    items: rows.map((r) => ({ id: r.id, name: r.name, category: r.category, units: r.units, revenue: r.revenue })),
  });
}));

// Revenue figures are limited to roles that can see the Sales report; others get turnover only.
router.get('/top-items', wrap(async (req, res) => {
  const by = req.query.by === 'revenue' ? 'revenue' : 'turnover';
  if (by === 'revenue' && !['MANAGER', 'INVENTORY'].includes(req.user.role)) {
    return res.status(403).json({ error: 'You do not have access to this.' });
  }
  const limit = Math.min(Math.max(parseInt(req.query.limit, 10) || 10, 1), 50);
  const now = new Date();
  const p = resolvePeriod('month', {}, now);
  const start = p.from;
  const end = p.to;
  const closeAt = now < end ? now : end;

  if (by === 'revenue') {
    const rows = await prisma.$queryRaw`
      SELECT i.id, i.name, SUM(l.line_total)::int AS value
      FROM sale_lines l
      JOIN sales s ON s.id = l.sale_id
      JOIN items i ON i.id = l.item_id
      WHERE s.sold_at >= ${start} AND s.sold_at < ${closeAt}
      GROUP BY i.id, i.name
      ORDER BY value DESC, i.name
      LIMIT ${limit}`;
    return res.json(rows);
  }

  const rows = await prisma.$queryRaw`
    SELECT i.id, i.name,
      COALESCE((SELECT SUM(l.qty) FROM sale_lines l JOIN sales s ON s.id = l.sale_id
                WHERE l.item_id = i.id AND s.sold_at >= ${start} AND s.sold_at < ${closeAt}), 0)::int AS units,
      COALESCE((SELECT SUM(m.change) FROM stock_moves m WHERE m.item_id = i.id AND m.at < ${start}), 0)::int AS opening,
      COALESCE((SELECT SUM(m.change) FROM stock_moves m WHERE m.item_id = i.id AND m.at < ${closeAt}), 0)::int AS closing
    FROM items i`;
  const out = rows
    .filter((r) => r.units > 0)
    .map((r) => ({ id: r.id, name: r.name, value: turnover(r.units, r.opening, r.closing) }))
    .sort((a, b) => b.value - a.value || a.name.localeCompare(b.name))
    .slice(0, limit);
  res.json(out);
}));

module.exports = router;
