const router = require('express').Router();
const prisma = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');
const { fail, wrap } = require('../lib/http');
const { validateSchedule, readSchedule } = require('../lib/tracking');

const NAME = 'shop_name';
const MARK = 'shop_mark';

// Fallback logo mark: initials of the first two words, or the first two letters.
const deriveMark = (name) => {
  const w = name.trim().split(/\s+/);
  return (w.length > 1 ? w[0][0] + w[1][0] : w[0].slice(0, 2)).toUpperCase();
};

async function read() {
  const rows = await prisma.setting.findMany({ where: { key: { in: [NAME, MARK] } } });
  const get = (k) => rows.find((r) => r.key === k)?.value;
  const shopName = get(NAME) || 'Marbingo';
  return { shopName, shopMark: get(MARK) || deriveMark(shopName) };
}

const put = (key, value) => prisma.setting.upsert({ where: { key }, update: { value }, create: { key, value } });

router.get('/', authenticate, wrap(async (req, res) => res.json(await read())));

router.put('/', authenticate, requireRole('MANAGER'), wrap(async (req, res) => {
  const name = String(req.body.shopName || '').trim();
  const mark = String(req.body.shopMark ?? '').trim().toUpperCase();
  const fields = {};
  if (!name) fields.shopName = 'Enter the shop name.';
  if (mark.length > 3) fields.shopMark = 'Use up to 3 letters.';
  if (Object.keys(fields).length) return fail(res, 400, 'Check the highlighted fields.', fields);
  await put(NAME, name);
  if (mark) await put(MARK, mark);
  else await prisma.setting.deleteMany({ where: { key: MARK } });
  res.json(await read());
}));

router.get('/tracking-schedule', authenticate, wrap(async (req, res) => res.json(await readSchedule(prisma))));

router.put('/tracking-schedule', authenticate, requireRole('MANAGER'), wrap(async (req, res) => {
  const r = validateSchedule(req.body || {});
  if (r.fields) return fail(res, 400, 'Check the highlighted fields.', r.fields);
  await put('tracking_schedule', JSON.stringify(r.schedule));
  res.json(r.schedule);
}));

module.exports = router;
