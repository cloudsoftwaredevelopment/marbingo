const router = require('express').Router();
const prisma = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');
const { fail, wrap, idParam } = require('../lib/http');

const shape = (c) => ({ id: c.id, name: c.name, itemCount: c._count.items });
const include = { _count: { select: { items: true } } };

function cleanName(req, res) {
  const name = String(req.body.name || '').trim();
  if (!name) {
    fail(res, 400, 'Enter the category name.', { name: 'Enter the category name.' });
    return null;
  }
  return name;
}

async function duplicate(name, exceptId) {
  const found = await prisma.category.findFirst({ where: { name: { equals: name, mode: 'insensitive' } } });
  return found && found.id !== exceptId;
}

router.param('id', idParam('Category not found.'));

const dupe = (res) => fail(res, 409, 'That category already exists.', { name: 'That category already exists.' });

router.get('/', authenticate, wrap(async (req, res) => {
  const cats = await prisma.category.findMany({ orderBy: { name: 'asc' }, include });
  res.json(cats.map(shape));
}));

router.post('/', authenticate, requireRole('MANAGER'), wrap(async (req, res) => {
  const name = cleanName(req, res);
  if (!name) return;
  if (await duplicate(name)) return dupe(res);
  const cat = await prisma.category.create({ data: { name }, include });
  res.status(201).json(shape(cat));
}));

router.put('/:id', authenticate, requireRole('MANAGER'), wrap(async (req, res) => {
  const id = req.id;
  const name = cleanName(req, res);
  if (!name) return;
  if (!(await prisma.category.findUnique({ where: { id } }))) return fail(res, 404, 'Category not found.');
  if (await duplicate(name, id)) return dupe(res);
  const cat = await prisma.category.update({ where: { id }, data: { name }, include });
  res.json(shape(cat));
}));

router.delete('/:id', authenticate, requireRole('MANAGER'), wrap(async (req, res) => {
  const id = req.id;
  if (!(await prisma.category.findUnique({ where: { id } }))) return fail(res, 404, 'Category not found.');
  await prisma.category.delete({ where: { id } });
  res.json({ ok: true });
}));

module.exports = router;
