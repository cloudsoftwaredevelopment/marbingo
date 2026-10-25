const router = require('express').Router();
const bcrypt = require('bcryptjs');
const prisma = require('../db');
const { authenticate } = require('../middleware/auth');
const { fail, wrap, publicUser, EMAIL_RE } = require('../lib/http');
const { imageUpload, saveImage, TYPE_MSG } = require('../lib/upload');
const { tokens } = require('./auth');

router.use(authenticate);

router.get('/', (req, res) => res.json({ user: publicUser(req.user) }));

router.put('/', wrap(async (req, res) => {
  const b = req.body;
  const firstName = String(b.firstName || '').trim();
  const lastName = String(b.lastName || '').trim();
  const email = String(b.email || '').trim().toLowerCase();
  const fields = {};
  if (!firstName) fields.firstName = 'Enter your first name.';
  if (!lastName) fields.lastName = 'Enter your last name.';
  if (!EMAIL_RE.test(email)) fields.email = 'Enter a valid email address.';
  if (Object.keys(fields).length) return fail(res, 400, 'Check the highlighted fields.', fields);

  const clash = await prisma.user.findUnique({ where: { email } });
  if (clash && clash.id !== req.user.id) {
    return fail(res, 409, 'That email is already in use.', { email: 'That email is already in use.' });
  }
  const user = await prisma.user.update({
    where: { id: req.user.id },
    data: {
      firstName, lastName, email,
      mobile: String(b.mobile || '').trim() || null,
      address: String(b.address || '').trim() || null
    }
  });
  res.json({ user: publicUser(user) });
}));

router.post('/photo', imageUpload.single('photo'), wrap(async (req, res) => {
  if (!req.file) return fail(res, 400, TYPE_MSG, { photo: TYPE_MSG });
  const photoUrl = await saveImage(req.file.buffer, 'avatars', `${req.user.id}-${Date.now()}.webp`, { width: 400, height: 400, fit: 'cover' });
  const user = await prisma.user.update({ where: { id: req.user.id }, data: { photoUrl } });
  res.json({ user: publicUser(user) });
}));

router.put('/password', wrap(async (req, res) => {
  const current = String(req.body.current || '');
  const next = String(req.body.next || '');
  if (!current || !(await bcrypt.compare(current, req.user.passwordHash))) {
    return fail(res, 400, 'Current password is incorrect.', { current: 'Current password is incorrect.' });
  }
  if (next.length < 8) return fail(res, 400, 'Use at least 8 characters.', { next: 'Use at least 8 characters.' });
  // Bumping tokenVersion signs out every other session; this one gets fresh tokens.
  const user = await prisma.user.update({
    where: { id: req.user.id },
    data: { passwordHash: await bcrypt.hash(next, 10), tokenVersion: { increment: 1 } }
  });
  res.json({ ok: true, ...tokens(user) });
}));

module.exports = router;
