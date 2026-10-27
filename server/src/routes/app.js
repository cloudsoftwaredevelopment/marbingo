const router = require('express').Router();
const fs = require('fs/promises');
const path = require('path');
const { wrap } = require('../lib/http');

// downloads/latest.json is written by scripts/release-apk.sh. Public (no auth) so a signed-out or
// expired tablet can still update itself.
const MANIFEST = path.join(__dirname, '..', '..', '..', 'downloads', 'latest.json');

router.get('/latest', wrap(async (req, res) => {
  let m;
  try { m = JSON.parse(await fs.readFile(MANIFEST, 'utf8')); } catch { return res.status(204).end(); }
  res.set('Cache-Control', 'no-store');
  res.json({
    versionCode: m.versionCode, versionName: m.versionName, url: m.url, sha256: m.sha256, size: m.size,
    minVersionCode: m.minVersionCode || 0, notes: m.notes || '', publishedAt: m.publishedAt,
  });
}));

module.exports = router;
