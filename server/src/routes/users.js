const router = require('express').Router();
const bcrypt = require('bcryptjs');
const prisma = require('../db');
const { authenticate, requireRole } = require('../middleware/auth');
const { fail, wrap, publicUser, EMAIL_RE, idParam } = require('../lib/http');

const ROLES = ['MANAGER', 'INVENTORY', 'CASHIER', 'AGENT'];

router.use(authenticate, requireRole('MANAGER'));
router.param('id', idParam('User not found.'));

router.get('/', wrap(async (req, res) => {
  const users = await prisma.user.findMany({ orderBy: { employeeId: 'asc' } });
  res.json(users.map(publicUser));
}));

async function nextEmployeeId() {
  const all = await prisma.user.findMany({ select: { employeeId: true } });
  const max = all.reduce((m, u) => Math.max(m, parseInt(u.employeeId.replace(/\D/g, ''), 10) || 0), 0);
  return 'EMP-' + String(max + 1).padStart(4, '0');
}

router.post('/', wrap(async (req, res) => {
  const b = req.body;
  const firstName = String(b.firstName || '').trim();
  const lastName = String(b.lastName || '').trim();
  const email = String(b.email || '').trim().toLowerCase();
  const password = String(b.password || '');
  const fields = {};
  if (!firstName) fields.firstName = 'Enter the first name.';
  if (!lastName) fields.lastName = 'Enter the last name.';
  if (!EMAIL_RE.test(email)) fields.email = 'Enter a valid email address.';
  if (!ROLES.includes(b.role)) fields.role = 'Choose a role.';
  if (password.length < 8) fields.password = 'Use at least 8 characters.';
  if (Object.keys(fields).length) return fail(res, 400, 'Check the highlighted fields.', fields);

  if (await prisma.user.findUnique({ where: { email } })) {
    return fail(res, 409, 'That email is already in use.', { email: 'That email is already in use.' });
  }
  const user = await prisma.user.create({
    data: {
      firstName, lastName, email, role: b.role,
      mobile: String(b.mobile || '').trim() || null,
      employeeId: await nextEmployeeId(),
      passwordHash: await bcrypt.hash(password, 10)
    }
  });
  res.status(201).json({ user: publicUser(user) });
}));

router.put('/:id', wrap(async (req, res) => {
  const id = req.id;
  const target = await prisma.user.findUnique({ where: { id } });
  if (!target) return fail(res, 404, 'User not found.');
  const b = req.body;
  const data = {};
  const fields = {};

  if (b.role !== undefined) {
    if (!ROLES.includes(b.role)) fields.role = 'Choose a role.';
    else data.role = b.role;
  }
  if (b.active !== undefined) data.active = !!b.active;
  if (b.firstName !== undefined) {
    if (!String(b.firstName).trim()) fields.firstName = 'Enter the first name.';
    else data.firstName = String(b.firstName).trim();
  }
  if (b.lastName !== undefined) {
    if (!String(b.lastName).trim()) fields.lastName = 'Enter the last name.';
    else data.lastName = String(b.lastName).trim();
  }
  if (b.password) {
    if (String(b.password).length < 8) fields.password = 'Use at least 8 characters.';
    else data.passwordHash = await bcrypt.hash(String(b.password), 10);
  }
  if (Object.keys(fields).length) return fail(res, 400, 'Check the highlighted fields.', fields);

  if (id === req.user.id) {
    if (data.active === false) return fail(res, 400, 'You cannot deactivate your own account.');
    if (data.role && data.role !== 'MANAGER') return fail(res, 400, 'You cannot change your own role.');
  }
  // A password reset or deactivation revokes the user's existing sessions.
  if (data.passwordHash || data.active === false) data.tokenVersion = { increment: 1 };
  const user = await prisma.user.update({ where: { id }, data });
  res.json({ user: publicUser(user) });
}));

module.exports = router;
