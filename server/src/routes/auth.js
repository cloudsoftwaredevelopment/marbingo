const router = require('express').Router();
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const prisma = require('../db');
const { authenticate } = require('../middleware/auth');
const { fail, wrap, publicUser } = require('../lib/http');

function tokens(user) {
  return {
    accessToken: jwt.sign({ sub: user.id, role: user.role, tv: user.tokenVersion }, process.env.JWT_SECRET, { expiresIn: '8h' }),
    refreshToken: jwt.sign({ sub: user.id, tv: user.tokenVersion }, process.env.JWT_REFRESH_SECRET, { expiresIn: '7d' })
  };
}

router.post('/login', wrap(async (req, res) => {
  const email = String(req.body.email || '').trim().toLowerCase();
  const password = String(req.body.password || '');
  const user = email ? await prisma.user.findUnique({ where: { email } }) : null;
  const ok = user && user.active && (await bcrypt.compare(password, user.passwordHash));
  if (!ok) return fail(res, 401, 'Email or password is incorrect.');
  res.json({ ...tokens(user), user: publicUser(user) });
}));

router.post('/refresh', wrap(async (req, res) => {
  try {
    const payload = jwt.verify(String(req.body.refreshToken || ''), process.env.JWT_REFRESH_SECRET);
    const user = await prisma.user.findUnique({ where: { id: payload.sub } });
    if (!user || !user.active) return fail(res, 401, 'Account not available.');
    if ((payload.tv || 0) !== user.tokenVersion) return fail(res, 401, 'Session expired.');
    res.json(tokens(user));
  } catch {
    fail(res, 401, 'Session expired.');
  }
}));

router.get('/me', authenticate, (req, res) => res.json({ user: publicUser(req.user) }));

module.exports = router;
module.exports.tokens = tokens;
