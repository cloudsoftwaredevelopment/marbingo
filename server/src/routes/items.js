const router = require('express').Router();
const prisma = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');
const { fail, wrap, toCentavos, idParam } = require('../lib/http');
const { imageUpload, saveImage } = require('../lib/upload');

const UNITS = ['pc', 'pack', 'kg', 'sack', 'box', 'bottle', 'can'];
router.param('id', idParam('Item not found.'));

const include = { category: { select: { id: true, name: true } } };
const isInt = (v) => /^\d+$/.test(String(v).trim());
const has = (b, k) => b[k] !== undefined;

// Validates the form fields. With partial=true only supplied fields are checked.
function parse(b, partial) {
  const fields = {};
  const data = {};
  const need = (k) => !partial || has(b, k);

  if (need('name')) {
    const name = String(b.name || '').trim();
    if (!name) fields.name = 'Enter the item name.';
    else data.name = name;
  }
  if (need('qty')) {
    if (!isInt(b.qty === undefined ? '' : b.qty)) fields.qty = 'Enter how many are in stock.';
    else data.qty = Number(b.qty);
  }
  if (need('price')) {
    const p = toCentavos(b.price);
    if (p === null || Number.isNaN(p)) fields.price = 'Enter the selling price.';
    else data.sellingPrice = p;
  }
  if (has(b, 'reorder')) {
    const r = String(b.reorder).trim();
    if (r === '') data.reorderLevel = 0;
    else if (!isInt(r)) fields.reorder = 'Enter a whole number, 0 or more.';
    else data.reorderLevel = Number(r);
  }
  if (has(b, 'cost')) {
    const c = toCentavos(b.cost);
    if (Number.isNaN(c)) fields.cost = 'Enter a valid cost price.';
    else data.costPrice = c;
  }
  if (has(b, 'unit') && String(b.unit).trim() !== '') {
    if (!UNITS.includes(b.unit)) fields.unit = 'Choose a unit.';
    else data.unit = b.unit;
  }
  if (has(b, 'description')) data.description = String(b.description).trim() || null;
  if (has(b, 'sku')) data.sku = String(b.sku).trim() || null;
  if (has(b, 'categoryId')) {
    const c = String(b.categoryId).trim();
    data.categoryId = c === '' || c === 'null' ? null : Number(c);
    if (data.categoryId !== null && !Number.isInteger(data.categoryId)) fields.categoryId = 'Choose a category.';
  }
  return { fields, data };
}

const savePhoto = (file) => saveImage(file.buffer, 'items', `${Date.now()}-${Math.round(Math.random() * 1e6)}.webp`,
  { width: 800, height: 800, fit: 'inside', withoutEnlargement: true });

async function skuConflict(res, sku, exceptId) {
  const other = await prisma.item.findUnique({ where: { sku } });
  if (!other || other.id === exceptId) return false;
  const msg = `This SKU is already used by "${other.name}".`;
  fail(res, 409, msg, { sku: msg });
  return true;
}

router.get('/', authenticate, wrap(async (req, res) => {
  const q = String(req.query.q || '').trim();
  const where = q
    ? { OR: [{ name: { contains: q, mode: 'insensitive' } }, { sku: { contains: q, mode: 'insensitive' } }] }
    : {};
  res.json(await prisma.item.findMany({ where, include, orderBy: { name: 'asc' }, take: 50 }));
}));

router.get('/by-sku/:sku', authenticate, wrap(async (req, res) => {
  const item = await prisma.item.findUnique({ where: { sku: req.params.sku.trim() }, include });
  if (!item) return fail(res, 404, 'No item has that SKU.');
  res.json(item);
}));

const editors = [authenticate, requireRole('MANAGER', 'INVENTORY')];

router.post('/', ...editors, imageUpload.single('photo'), wrap(async (req, res) => {
  const { fields, data } = parse(req.body, false);
  if (Object.keys(fields).length) return fail(res, 400, 'Check the highlighted fields.', fields);
  if (data.sku && (await skuConflict(res, data.sku))) return;

  const { qty, ...rest } = data;
  const imageUrl = req.file ? await savePhoto(req.file) : null;
  const item = await prisma.$transaction(async (tx) => {
    const created = await tx.item.create({
      data: { ...rest, qtyOnHand: qty, imageUrl, createdById: req.user.id }
    });
    if (qty > 0) await tx.stockMove.create({ data: { itemId: created.id, change: qty, reason: 'initial', byId: req.user.id } });
    return tx.item.findUnique({ where: { id: created.id }, include });
  });
  res.status(201).json({ item });
}));

router.put('/:id', ...editors, imageUpload.single('photo'), wrap(async (req, res) => {
  const id = req.id;
  if (!(await prisma.item.findUnique({ where: { id } }))) return fail(res, 404, 'Item not found.');
  const { fields, data } = parse(req.body, true);
  if (Object.keys(fields).length) return fail(res, 400, 'Check the highlighted fields.', fields);
  if (data.sku && (await skuConflict(res, data.sku, id))) return;

  delete data.qty;
  if (req.file) data.imageUrl = await savePhoto(req.file);
  const item = await prisma.item.update({ where: { id }, data, include });
  res.json({ item });
}));

router.post('/:id/restock', ...editors, wrap(async (req, res) => {
  const id = req.id;
  const qty = Number(req.body.qty);
  const reason = req.body.reason || 'restock';
  if (!Number.isInteger(qty) || qty === 0) return fail(res, 400, 'Enter a quantity other than 0.', { qty: 'Enter a quantity other than 0.' });
  if (!['restock', 'adjustment'].includes(reason)) return fail(res, 400, 'Choose restock or adjustment.', { reason: 'Choose restock or adjustment.' });

  const item = await prisma.$transaction(async (tx) => {
    // Conditional update so concurrent negative adjustments can't take stock below 0.
    const where = qty < 0 ? { id, qtyOnHand: { gte: -qty } } : { id };
    const r = await tx.item.updateMany({ where, data: { qtyOnHand: { increment: qty } } });
    if (r.count !== 1) return { status: (await tx.item.findUnique({ where: { id } })) ? 400 : 404 };
    await tx.stockMove.create({ data: { itemId: id, change: qty, reason, byId: req.user.id } });
    return { item: await tx.item.findUnique({ where: { id }, include }) };
  });
  if (item.status === 404) return fail(res, 404, 'Item not found.');
  if (item.status === 400) return fail(res, 400, 'Stock cannot go below 0.', { qty: 'Stock cannot go below 0.' });
  res.json({ item: item.item });
}));

module.exports = router;
