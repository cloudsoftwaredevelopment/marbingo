const jwt = require('jsonwebtoken');
const prisma = require('../db');

// Verifies the Bearer access token and loads the user onto req.user.
async function authenticate(req, res, next) {
  const header = req.headers.authorization || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : null;
  if (!token) return res.status(401).json({ error: 'Not signed in.' });
  try {
    const payload = jwt.verify(token, process.env.JWT_SECRET);
    const user = await prisma.user.findUnique({ where: { id: payload.sub } });
    if (!user || !user.active) return res.status(401).json({ error: 'Account not available.' });
    req.user = user;
    next();
  } catch {
    res.status(401).json({ error: 'Session expired.' });
  }
}

// Usage: requireRole('MANAGER', 'INVENTORY')
function requireRole(...roles) {
  return (req, res, next) => {
    if (!req.user || !roles.includes(req.user.role)) {
      return res.status(403).json({ error: 'You do not have access to this.' });
    }
    next();
  };
}

module.exports = { authenticate, requireRole };
