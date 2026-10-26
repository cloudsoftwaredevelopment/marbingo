// Shared helpers for the Ecosystem Map. All times display in Asia/Manila.
const TZ = 'Asia/Manila';
const timeFmt = new Intl.DateTimeFormat('en-US', { hour: 'numeric', minute: '2-digit', timeZone: TZ });
const dayFmt = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', timeZone: TZ });
const dayLongFmt = new Intl.DateTimeFormat('en-US', { weekday: 'short', month: 'short', day: 'numeric', timeZone: TZ });
const ymdFmt = new Intl.DateTimeFormat('en-CA', { year: 'numeric', month: '2-digit', day: '2-digit', timeZone: TZ });

export const todayYmd = () => ymdFmt.format(new Date());
export const fmtTime = (iso) => (iso ? timeFmt.format(new Date(iso)) : '—');
export const fmtDay = (iso) => dayFmt.format(new Date(iso));
export const fmtDayLong = (iso) => dayLongFmt.format(new Date(iso));
// "Oct 26" from a YYYY-MM-DD string.
export const fmtYmd = (ymd) => dayFmt.format(new Date(ymd + 'T12:00:00+08:00'));

export function fmtDur(seconds) {
  const m = Math.round((seconds || 0) / 60);
  if (m < 60) return `${Math.max(m, 0)} min`;
  const h = Math.floor(m / 60);
  const r = m % 60;
  return `${h} h ${String(r).padStart(2, '0')} min`;
}
export const fmtKm = (m) => ((m || 0) / 1000).toFixed(1) + ' km';

export function relTime(iso, now = Date.now()) {
  const s = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000));
  if (s < 60) return 'just now';
  if (s < 3600) return Math.round(s / 60) + ' min ago';
  if (s < 86400) return Math.floor(s / 3600) + ' h ago';
  return Math.floor(s / 86400) + ' d ago';
}

export function haversine(a, b) {
  const R = 6371000, rad = Math.PI / 180;
  const dLat = (b[0] - a[0]) * rad, dLng = (b[1] - a[1]) * rad;
  const x = Math.sin(dLat / 2) ** 2 + Math.cos(a[0] * rad) * Math.cos(b[0] * rad) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(x));
}

export const agentName = (a) => [a?.firstName, a?.lastName].filter(Boolean).join(' ');
export const storeInitials = (name) => {
  const w = String(name || '').replace(/[^A-Za-z0-9\s]/g, '').trim().split(/\s+/).filter(Boolean);
  if (!w.length) return '?';
  return (w.length > 1 ? w[0][0] + w[1][0] : w[0].slice(0, 2)).toUpperCase();
};
export const hasLoc = (c) => c && c.lat != null && c.lng != null;

// Ring colours per agent index when several agents are shown.
export const RING = ['#1E5B43', '#2F6FA3', '#8A4209', '#7A3E8E', '#A12A1E', '#5E5A52'];

// Distance along a route between the vertices nearest to two points; straight-line when not usable.
export function travelDistance(route, from, to) {
  const straight = haversine(from, to);
  if (!route || route.length < 2) return straight;
  const near = (p) => { let bi = 0, bd = Infinity; route.forEach((r, i) => { const d = haversine(r, p); if (d < bd) { bd = d; bi = i; } }); return bi; };
  const i = near(from), j = near(to);
  if (j <= i) return straight;
  let sum = haversine(from, route[i]) + haversine(route[j], to);
  for (let k = i; k < j; k++) sum += haversine(route[k], route[k + 1]);
  return Math.max(sum, straight);
}
