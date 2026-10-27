import { apiError } from '../lib/api';
import { num } from '../lib/format';

const dtFmt = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit', timeZone: 'Asia/Manila' });
export const fmtDT = (iso) => (iso ? dtFmt.format(new Date(iso)) : '—');
export const fullName = (u) => (u ? [u.firstName, u.lastName].filter(Boolean).join(' ') : '—');
export const monthLabel = (m) => (m ? new Intl.DateTimeFormat('en-US', { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(new Date(m + '-01T00:00:00Z')) : '—');

export const PERIOD_BADGE = { draft: 'badge-none', open: 'badge-in', counting: 'badge-low', closed: 'badge-none' };
export const PERIOD_LABEL = { draft: 'Draft', open: 'Open', counting: 'Counting', closed: 'Closed' };
export const units = (n) => `${num(n)} ${Math.abs(n) === 1 ? 'unit' : 'units'}`;

export const PeriodBadge = ({ status }) => <span className={'badge ' + (PERIOD_BADGE[status] || 'badge-none')}>{PERIOD_LABEL[status] || status}</span>;

// Axios error -> { message, fields, short?, uncounted? } (409s carry machine-readable lists).
export function truckError(e) {
  const d = e?.response?.data || {};
  return { ...apiError(e), short: d.short, uncounted: d.uncounted };
}

// Error banner that also lists short stock / uncounted items from a 409.
export function ErrBanner({ err }) {
  if (!err) return null;
  const e = typeof err === 'string' ? { message: err } : err;
  return (
    <div role="alert" className="banner-err">
      {/* The server message already names each short item; when the list follows, keep only its lead-in. */}
      {e.short?.length > 0 || e.uncounted?.length > 0 ? e.message.replace(/:.*$/s, ':') : e.message}
      {e.short?.length > 0 && (
        <ul className="trk-short">
          {e.short.map((s) => <li key={s.itemId}>{s.name}: {num(s.need)} needed, {num(s.have)} left</li>)}
        </ul>
      )}
      {e.uncounted?.length > 0 && (
        <ul className="trk-short">
          {e.uncounted.map((s) => <li key={s.itemId}>{s.name}: expected {num(s.expected)}, not counted</li>)}
        </ul>
      )}
    </div>
  );
}
