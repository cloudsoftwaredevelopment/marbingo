// Road snapping via a self-hosted OSRM. Every call falls back to raw geometry when OSRM is unset or fails.
const { haversine, simplify, tMs } = require('./tracking');

const TIMEOUT_MS = 5000;
const CHUNK = 100;
const base = () => (process.env.OSRM_URL || '').replace(/\/+$/, '');

async function getJson(url) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), TIMEOUT_MS);
  try {
    const res = await fetch(url, { signal: ctl.signal });
    if (!res.ok) return null;
    return await res.json();
  } catch {
    return null;
  } finally {
    clearTimeout(timer);
  }
}

const pathLength = (route) => {
  let d = 0;
  for (let i = 1; i < route.length; i++) d += haversine(route[i - 1][0], route[i - 1][1], route[i][0], route[i][1]);
  return Math.round(d);
};

const rawRoute = (pts) => simplify(pts.filter((p) => p.accuracy == null || p.accuracy <= 100), 10).map((p) => [p.lat, p.lng]);

// Accuracy <= 50 m, strictly increasing seconds, and at least 5 m from the previous kept point.
function prepare(points) {
  const out = [];
  let lastS = -1;
  for (const p of points) {
    if (p.accuracy != null && p.accuracy > 50) continue;
    const s = Math.floor(tMs(p) / 1000);
    if (s <= lastS) continue;
    const prev = out[out.length - 1];
    if (prev && haversine(prev.lat, prev.lng, p.lat, p.lng) < 5) continue;
    out.push({ lat: p.lat, lng: p.lng, accuracy: p.accuracy, s });
    lastS = s;
  }
  return out;
}

async function matchChunk(c) {
  const coords = c.map((p) => `${p.lng.toFixed(6)},${p.lat.toFixed(6)}`).join(';');
  const radiuses = c.map((p) => Math.round(Math.min(50, Math.max(10, p.accuracy == null ? 20 : p.accuracy)))).join(';');
  const times = c.map((p) => p.s).join(';');
  const url = `${base()}/match/v1/driving/${coords}?geometries=geojson&overview=full&radiuses=${radiuses}&timestamps=${times}&gaps=split&tidy=true`;
  const j = await getJson(url);
  if (!j || j.code !== 'Ok' || !Array.isArray(j.matchings) || !j.matchings.length) return null;
  const route = [];
  let distanceM = 0;
  for (const m of j.matchings) {
    for (const [lng, lat] of m.geometry.coordinates) route.push([lat, lng]);
    distanceM += m.distance || 0;
  }
  return { route, distanceM };
}

// points: [{ lat, lng, accuracy, t | recordedAt }] sorted by time → { snapped, route: [[lat, lng]], distanceM }
async function matchTrack(points) {
  const raw = () => { const route = rawRoute(points); return { snapped: false, route, distanceM: pathLength(route) }; };
  if (!base() || points.length < 2) return raw();
  const pts = prepare(points);
  if (pts.length < 2) return raw();

  const route = [];
  let distanceM = 0;
  let matched = 0;
  for (let i = 0; i < pts.length - 1; i += CHUNK - 1) {
    const chunk = pts.slice(i, i + CHUNK);
    if (chunk.length < 2) break;
    const m = await matchChunk(chunk);
    let seg;
    if (m) { seg = m.route; distanceM += m.distanceM; matched++; }
    else {
      seg = simplify(chunk, 10).map((p) => [p.lat, p.lng]);
      distanceM += pathLength(seg);
    }
    const last = route[route.length - 1];
    route.push(...(last && seg.length && last[0] === seg[0][0] && last[1] === seg[0][1] ? seg.slice(1) : seg));
  }
  if (!matched) return raw();
  return { snapped: true, route, distanceM: Math.round(distanceM) };
}

// Driving geometry between two { lat, lng } points → { coords: [[lat, lng]], distanceM, snapped }.
async function routeBetween(a, b) {
  const straight = () => ({
    coords: [[a.lat, a.lng], [b.lat, b.lng]],
    distanceM: Math.round(haversine(a.lat, a.lng, b.lat, b.lng)),
    snapped: false,
  });
  if (!base()) return straight();
  const url = `${base()}/route/v1/driving/${a.lng},${a.lat};${b.lng},${b.lat}?geometries=geojson&overview=full`;
  const j = await getJson(url);
  const r = j && j.code === 'Ok' && j.routes && j.routes[0];
  if (!r) return straight();
  return { coords: r.geometry.coordinates.map(([lng, lat]) => [lat, lng]), distanceM: Math.round(r.distance), snapped: true };
}

module.exports = { matchTrack, routeBetween };
