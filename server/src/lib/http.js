// Shared response helpers. Error shape: { error, fields? }.
function fail(res, status, error, fields) {
  return res.status(status).json(fields ? { error, fields } : { error });
}

// Wraps async route handlers so thrown errors reach the error middleware.
const wrap = (fn) => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);

// Strips secrets before sending a user to the client.
function publicUser(u) {
  if (!u) return null;
  const { passwordHash, ...rest } = u;
  return rest;
}

// "62.50" | 62.5 → 6250 centavos. Returns null for blank, NaN for invalid.
function toCentavos(v) {
  if (v === undefined || v === null || v === '') return null;
  const n = Number(v);
  if (!Number.isFinite(n) || n < 0) return NaN;
  return Math.round(n * 100);
}

module.exports = { fail, wrap, publicUser, toCentavos };
