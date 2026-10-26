const router = require('express').Router();
const prisma = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');
const { fail, wrap, idParam } = require('../lib/http');

router.param('id', idParam('Pack type not found.'));
router.use(authenticate);
const manager = requireRole('MANAGER');

const shape = (t) => {
  const { _count, ...rest } = t;
  return _count ? { ...rest, itemCount: _count.itemPacks } : rest;
};

// Validates { name?, defaultSize? }. Returns { fields } or { data }.
function parse(b, partial) {
  const fields = {};
  const data = {};
  if (!partial || b.name !== undefined) {
    const name = String(b.name || '').trim();
    if (!name) fields.name = 'Enter the pack name.';
    else data.name = name;
  }
  if (b.defaultSize !== undefined) {
    if (b.defaultSize === null || b.defaultSize === '') data.defaultSize = null;
    else {
      const n = Number(b.defaultSize);
      if (!Number.isInteger(n) || n < 2) fields.defaultSize = 'Enter a whole number of at least 2.';
      else data.defaultSize = n;
    }
  }
  if (b.active !== undefined) data.active = b.active === true || b.active === 'true';
  return { fields, data };
}

async function nameTaken(res, name, exceptId) {
  const other = await prisma.packType.findFirst({ where: { name: { equals: name, mode: 'insensitive' } } });
  if (!other || other.id === exceptId) return false;
  const msg = `"${other.name}" already exists.`;
  fail(res, 409, msg, { name: msg });
  return true;
}

router.get('/', wrap(async (req, res) => {
  const all = req.query.all === '1';
  const rows = await prisma.packType.findMany({
    where: all ? {} : { active: true },
    orderBy: [{ sort: 'asc' }, { id: 'asc' }],
    ...(all ? { include: { _count: { select: { itemPacks: true } } } } : {}),
  });
  res.json(rows.map(shape));
}));

router.post('/', manager, wrap(async (req, res) => {
  const { fields, data } = parse(req.body || {}, false);
  if (Object.keys(fields).length) return fail(res, 400, 'Check the highlighted fields.', fields);
  if (await nameTaken(res, data.name)) return;
  const last = await prisma.packType.aggregate({ _max: { sort: true } });
  const created = await prisma.packType.create({ data: { ...data, active: true, sort: (last._max.sort || 0) + 1 } });
  res.status(201).json(shape(created));
}));

router.put('/:id', manager, wrap(async (req, res) => {
  const id = req.id;
  if (!(await prisma.packType.findUnique({ where: { id } }))) return fail(res, 404, 'Pack type not found.');
  const { fields, data } = parse(req.body || {}, true);
  if (Object.keys(fields).length) return fail(res, 400, 'Check the highlighted fields.', fields);
  if (data.name && (await nameTaken(res, data.name, id))) return;
  const type = await prisma.packType.update({ where: { id }, data });
  // The pack name is embedded in synced item packs, so a rename/deactivation must re-sync those items.
  await prisma.item.updateMany({ where: { packs: { some: { packTypeId: id } } }, data: { updatedAt: new Date() } });
  res.json(shape(type));
}));

router.delete('/:id', manager, wrap(async (req, res) => {
  const id = req.id;
  if (!(await prisma.packType.findUnique({ where: { id } }))) return fail(res, 404, 'Pack type not found.');
  const n = await prisma.itemPack.count({ where: { packTypeId: id } });
  if (n) return fail(res, 409, `In use by ${n} item${n === 1 ? '' : 's'} — deactivate it instead.`);
  await prisma.packType.delete({ where: { id } });
  res.json({ ok: true });
}));

module.exports = router;
