const router = require('express').Router();
const prisma = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');
const { fail, wrap } = require('../lib/http');

const KEY = 'shop_name';

async function shopName() {
  const row = await prisma.setting.findUnique({ where: { key: KEY } });
  return row ? row.value : 'Marbingo';
}

router.get('/', authenticate, wrap(async (req, res) => {
  res.json({ shopName: await shopName() });
}));

router.put('/', authenticate, requireRole('MANAGER'), wrap(async (req, res) => {
  const name = String(req.body.shopName || '').trim();
  if (!name) return fail(res, 400, 'Enter the shop name.', { shopName: 'Enter the shop name.' });
  await prisma.setting.upsert({ where: { key: KEY }, update: { value: name }, create: { key: KEY, value: name } });
  res.json({ shopName: name });
}));

module.exports = router;
