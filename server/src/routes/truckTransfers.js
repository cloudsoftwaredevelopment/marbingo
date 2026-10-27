const router = require('express').Router();
const { authenticate, requireRole } = require('../middleware/auth');
const { wrap, idParam } = require('../lib/http');
const { approveTransfer, rejectTransfer, shapeTransfer } = require('../lib/truckOps');

router.param('id', idParam('Transfer not found.'));
router.use(authenticate, requireRole('MANAGER'));

// Top-ups and returns apply immediately on approval. A pending load is approved by opening the period instead.
router.post('/:id/approve', wrap(async (req, res) => {
  res.json({ transfer: shapeTransfer(await approveTransfer(req.id, req.user.id)) });
}));

router.post('/:id/reject', wrap(async (req, res) => {
  res.json({ transfer: shapeTransfer(await rejectTransfer(req.id, req.user.id)) });
}));

module.exports = router;
