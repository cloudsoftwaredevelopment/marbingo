// Shop-timezone (Asia/Manila, UTC+8, no DST) period resolution. Pure functions, no DB.
const OFFSET_MS = 8 * 3600 * 1000;
const DAY = 86400000;
const MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const DOW = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const RANGE_ERROR = 'Choose a start date before the end date';

// A "local day number" = days since epoch of the Manila calendar date.
const dayOf = (instant) => Math.floor((instant.getTime() + OFFSET_MS) / DAY);
const startOfDay = (dn) => new Date(dn * DAY - OFFSET_MS);
// Calendar fields for a local day number (computed in UTC on a shifted date).
const fields = (dn) => {
  const d = new Date(dn * DAY);
  return { y: d.getUTCFullYear(), m: d.getUTCMonth(), d: d.getUTCDate(), dow: d.getUTCDay() };
};
const toDayNum = (y, m, d) => Math.floor(Date.UTC(y, m, d) / DAY);
const md = (dn) => { const f = fields(dn); return MON[f.m] + ' ' + f.d; };
const iso = (dn) => new Date(dn * DAY).toISOString().slice(0, 10);

function parseIso(s) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(s || ''));
  if (!m) return null;
  const y = +m[1], mo = +m[2] - 1, d = +m[3];
  const dn = toDayNum(y, mo, d);
  const f = fields(dn);
  return f.y === y && f.m === mo && f.d === d ? dn : null;
}

// Compact range: "Oct 1 – 31, 2026", "Oct 26 – Nov 1, 2026", "Dec 29, 2025 – Jan 4, 2026".
function rangeLabel(a, b) {
  const fa = fields(a), fb = fields(b);
  if (fa.y !== fb.y) return `${md(a)}, ${fa.y} – ${md(b)}, ${fb.y}`;
  if (fa.m !== fb.m) return `${md(a)} – ${md(b)}, ${fb.y}`;
  return `${md(a)} – ${fb.d}, ${fb.y}`;
}

function chunkLabel(a, b) {
  if (a === b) return md(a);
  const fa = fields(a), fb = fields(b);
  return md(a) + '–' + (fa.m === fb.m ? fb.d : md(b));
}

function weekChunks(a, b) {
  const out = [];
  for (let s = a; s <= b; s += 7) {
    const e = Math.min(s + 6, b);
    out.push({ label: chunkLabel(s, e), start: startOfDay(s), end: startOfDay(e + 1) });
  }
  return out;
}

function resolvePeriod(preset, { from, to } = {}, now = new Date()) {
  const today = dayOf(now);
  let a, b, label, unit, bucket, buckets;

  if (preset === 'today') {
    a = b = today;
    const f = fields(today);
    label = `${DOW[f.dow]}, ${MON[f.m]} ${f.d}, ${f.y}`;
    unit = 'By hour';
    bucket = 'hour';
    buckets = [];
    for (let h = 7; h < 19; h++) {
      const start = new Date(startOfDay(today).getTime() + h * 3600000);
      const hr = h % 12 === 0 ? 12 : h % 12;
      buckets.push({ label: hr + (h < 12 ? 'a' : 'p'), start, end: new Date(start.getTime() + 3600000) });
    }
  } else if (preset === 'week') {
    a = today - ((fields(today).dow + 6) % 7);
    b = a + 6;
    label = rangeLabel(a, b);
    unit = 'By day';
    bucket = 'day';
    buckets = [];
    for (let i = 0; i < 7; i++) {
      buckets.push({ label: DOW[(i + 1) % 7], start: startOfDay(a + i), end: startOfDay(a + i + 1) });
    }
  } else if (preset === 'custom') {
    const pa = parseIso(from), pb = parseIso(to);
    if (pa === null || pb === null || pb < pa) return { error: RANGE_ERROR };
    a = pa;
    b = Math.min(pb, pa + 365);
    const fa = fields(a), fb = fields(b);
    label = `${md(a)}, ${fa.y} – ${md(b)}, ${fb.y}`;
    unit = 'By week';
    bucket = 'week';
    buckets = weekChunks(a, b);
  } else {
    const f = fields(today);
    const y = preset === 'last' && f.m === 0 ? f.y - 1 : f.y;
    const m = preset === 'last' ? (f.m + 11) % 12 : f.m;
    a = toDayNum(y, m, 1);
    b = toDayNum(y, m + 1, 1) - 1;
    label = rangeLabel(a, b);
    unit = 'By week';
    bucket = 'week';
    buckets = weekChunks(a, b);
  }

  buckets.forEach((x) => { x.upcoming = x.start > now; });
  if (buckets.some((x) => x.upcoming)) unit += ' · dashed = days still ahead';
  return { from: startOfDay(a), to: startOfDay(b + 1), fromDate: iso(a), toDate: iso(b), label, unit, bucket, buckets };
}

// Index of the bucket containing `instant`, or -1.
function bucketIndex(buckets, instant) {
  const t = instant.getTime();
  return buckets.findIndex((x) => t >= x.start.getTime() && t < x.end.getTime());
}

module.exports = { resolvePeriod, bucketIndex, parseIso, dayOf, startOfDay, RANGE_ERROR };
