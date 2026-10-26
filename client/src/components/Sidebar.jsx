import { useEffect, useState } from 'react';
import { Link, NavLink } from 'react-router-dom';
import api from '../lib/api';
import { useAuth } from '../context/AuthContext';
import { can } from '../lib/roles';
import { ROLE_LABELS, initials, pesoCompact } from '../lib/format';
import '../styles/shell.css';

const D = "'Bricolage Grotesque', system-ui, sans-serif";
const M = "'IBM Plex Mono', ui-monospace, monospace";

// Logo mark: the configured shop mark, else initials of the first two words / first two letters.
export function shopInitials(name, mark) {
  if (mark) return mark;
  const w = String(name || '').trim().split(/\s+/).filter(Boolean);
  if (!w.length) return 'MB';
  return (w.length > 1 ? w[0][0] + w[1][0] : w[0].slice(0, 2)).toUpperCase();
}

const Icon = ({ children }) => (
  <svg aria-hidden="true" focusable="false" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">{children}</svg>
);

const NAV = [
  { to: '/', label: 'Sales report', cap: 'reports', end: true, icon: <><path d="M4 20V10" /><path d="M10 20V4" /><path d="M16 20v-7" /><path d="M22 20H2" /></> },
  { to: '/items', label: 'Items', cap: 'items', end: true, icon: <><path d="M8 6h12" /><path d="M8 12h12" /><path d="M8 18h12" /><path d="M4 6h.01" /><path d="M4 12h.01" /><path d="M4 18h.01" /></> },
  { to: '/items/new', label: 'Add item', cap: 'items', icon: <><path d="M21 8 12 3 3 8v8l9 5 9-5z" /><path d="M3 8l9 5 9-5" /><path d="M12 13v8" /></> },
  { to: '/pos', label: 'Record sale', cap: 'pos', icon: <><path d="M6 3h12v18l-3-2-3 2-3-2-3 2z" /><path d="M9 8h6" /><path d="M9 12h6" /></> },
  { to: '/orders', label: 'Orders', agentLabel: 'My orders', cap: 'orders', badge: true, icon: <><path d="M9 4h6a1 1 0 0 1 1 1v1H8V5a1 1 0 0 1 1-1z" /><path d="M8 6H6a1 1 0 0 0-1 1v13a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V7a1 1 0 0 0-1-1h-2" /><path d="M9 12h6" /><path d="M9 16h4" /></> },
  { to: '/stores', label: 'Stores', cap: 'stores', icon: <><path d="M4 9l1.5-5h13L20 9" /><path d="M4 9a2.7 2.7 0 0 0 5.3 0 2.7 2.7 0 0 0 5.4 0A2.7 2.7 0 0 0 20 9" /><path d="M5 12v8h14v-8" /><path d="M10 20v-5h4v5" /></> },
  { to: '/map', label: 'Ecosystem Map', cap: 'map', icon: <><path d="M9 4 3 6.5v14L9 18l6 2.5 6-2.5v-14L15 6.5z" /><path d="M9 4v14" /><path d="M15 6.5v14" /></> },
  { to: '/profile', label: 'My profile', icon: <><circle cx="12" cy="8" r="4" /><path d="M4 21c1.5-4 4.5-6 8-6s6.5 2 8 6" /></> },
  { to: '/admin', label: 'Admin', cap: 'admin', icon: <><circle cx="12" cy="12" r="3" /><path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9 7 7M17 17l2.1 2.1M4.9 19.1 7 17M17 7l2.1-2.1" /></> }
];

const MODES = [{ id: 'turnover', label: 'Turnover' }, { id: 'sales', label: 'Sales' }];

// Revenue mode is only offered to roles that can see the Sales report (server enforces this too).
function TopItems({ showRevenue }) {
  const [mode, setMode] = useState('turnover');
  const [rows, setRows] = useState(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let live = true;
    setFailed(false);
    api.get('/reports/top-items', { params: { by: mode === 'turnover' ? 'turnover' : 'revenue', limit: 10 } })
      .then((r) => { if (live) setRows(Array.isArray(r.data) ? r.data : []); })
      .catch(() => { if (live) { setFailed(true); setRows((x) => x || []); } });
    return () => { live = false; };
  }, [mode]);

  const max = rows && rows.length ? Math.max(...rows.map((r) => r.value), 0) || 1 : 1;
  const fmt = (v) => (mode === 'turnover' ? `${Number(v).toFixed(1)}×` : pesoCompact(v));

  return (
    <section aria-labelledby="top-h" style={{ display: 'flex', flexDirection: 'column', gap: 14, padding: '18px 14px', borderRadius: 14, background: '#2A2925' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 8 }}>
        <h2 id="top-h" style={{ margin: 0, fontFamily: D, fontWeight: 600, fontSize: 17 }}>Top 10 items</h2>
        <span style={{ fontSize: 12, color: '#A9A497' }}>This month</span>
      </div>
      {showRevenue && <div role="group" aria-label="Rank by" style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 4, padding: 4, borderRadius: 10, background: '#1C1B18' }}>
        {MODES.map((m) => {
          const on = m.id === mode;
          return (
            <button key={m.id} type="button" aria-pressed={on} onClick={() => setMode(m.id)}
              style={{ minHeight: 36, border: 0, borderRadius: 7, background: on ? '#7BC3A0' : 'transparent', color: on ? '#1C1B18' : '#D9D5CB', font: "600 13px 'IBM Plex Sans', system-ui, sans-serif", cursor: 'pointer' }}>
              {m.label}
            </button>
          );
        })}
      </div>}
      <div aria-live="polite">
        {rows === null && <p className="sb-muted">Loading…</p>}
        {rows && rows.length === 0 && <p className="sb-muted">{failed ? 'Could not load rankings.' : 'No sales yet this month.'}</p>}
      </div>
      {rows && rows.length > 0 && (
        <ol style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 12, opacity: 1 }}>
          {rows.map((t, i) => (
            <li key={t.id} style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              <div style={{ display: 'flex', alignItems: 'baseline', gap: 10 }}>
                <span style={{ width: 18, flex: '0 0 18px', font: `500 12px ${M}`, color: '#A9A497' }}>{i + 1}</span>
                <span style={{ flex: '1 1 auto', minWidth: 0, fontSize: 13, lineHeight: 1.3 }}>{t.name}</span>
                <span style={{ flex: '0 0 auto', font: `500 13px ${M}`, color: '#F4F2EC' }}>{fmt(t.value)}</span>
              </div>
              <div aria-hidden="true" style={{ marginLeft: 28, height: 4, borderRadius: 2, background: '#3A3833', overflow: 'hidden' }}>
                <div style={{ width: `${(t.value / max * 100).toFixed(1)}%`, height: '100%', background: '#7BC3A0', borderRadius: 2 }} />
              </div>
            </li>
          ))}
        </ol>
      )}
      <p style={{ margin: 0, fontSize: 12, lineHeight: 1.45, color: '#A9A497' }}>
        {mode === 'turnover'
          ? 'Turnover = units sold ÷ average stock on hand. Higher means the item sells through faster.'
          : 'Ranked by revenue this month.'}
      </p>
    </section>
  );
}

// Pending field orders, for the nav badge (staff only). Polls every 60s.
function usePendingCount(enabled) {
  const [n, setN] = useState(0);
  useEffect(() => {
    if (!enabled) return undefined;
    let live = true;
    const load = () => api.get('/orders/pending-count').then((r) => { if (live) setN(Number(r.data?.pending) || 0); }).catch(() => {});
    load();
    const t = setInterval(load, 60000);
    return () => { live = false; clearInterval(t); };
  }, [enabled]);
  return n;
}

export default function Sidebar() {
  const { user, settings, logout } = useAuth();
  const pending = usePendingCount(can(user, 'orderActions'));
  const shop = settings?.shopName || 'Marbingo';
  const ini = initials(user?.firstName, user?.lastName);

  return (
    <aside className="sb" style={{ width: '100%', background: '#1C1B18', color: '#F4F2EC', fontFamily: "'IBM Plex Sans', system-ui, sans-serif", padding: '28px 18px 24px', display: 'flex', flexDirection: 'column', gap: 28, flex: 1 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '0 8px' }}>
        <div aria-hidden="true" style={{ width: 40, height: 40, flex: '0 0 40px', borderRadius: 10, background: '#E3EEE7', color: '#1E5B43', display: 'flex', alignItems: 'center', justifyContent: 'center', fontFamily: D, fontWeight: 700, fontSize: 17 }}>{shopInitials(shop, settings.shopMark)}</div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0 }}>
          <span style={{ fontFamily: D, fontWeight: 700, fontSize: 18, lineHeight: 1.1, overflowWrap: 'anywhere' }}>{shop}</span>
          <span style={{ fontSize: 12, color: '#A9A497' }}>Dry goods · Back office</span>
        </div>
      </div>

      <nav aria-label="Main" style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        {NAV.filter((n) => !n.cap || can(user, n.cap)).map((n) => (
          <NavLink key={n.to} to={n.to} end={n.end} className="sb-link"
            style={({ isActive }) => ({ minHeight: 44, display: 'flex', alignItems: 'center', gap: 12, padding: '0 12px', borderRadius: 10, textDecoration: 'none', fontSize: 15, fontWeight: 500, background: isActive ? '#F4F2EC' : 'transparent', color: isActive ? '#1C1B18' : '#D9D5CB' })}>
            <Icon>{n.icon}</Icon>
            <span style={{ flex: '1 1 auto' }}>{user?.role === 'AGENT' && n.agentLabel ? n.agentLabel : n.label}</span>
            {n.badge && pending > 0 && (
              <span role="img" aria-label={`${pending} pending`} style={{ minWidth: 22, height: 22, padding: '0 7px', borderRadius: 11, background: '#7BC3A0', color: '#1C1B18', font: "600 12px 'IBM Plex Mono', ui-monospace, monospace", display: 'inline-flex', alignItems: 'center', justifyContent: 'center' }}>{pending > 99 ? '99+' : pending}</span>
            )}
          </NavLink>
        ))}
      </nav>

      <TopItems showRevenue={can(user, 'reports')} />

      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        <Link to="/profile" style={{ display: 'flex', alignItems: 'center', gap: 12, padding: 10, borderRadius: 12, border: '1px solid #3A3833', textDecoration: 'none', color: '#F4F2EC' }}>
          {user?.photoUrl ? (
            <img src={user.photoUrl} alt="" style={{ width: 40, height: 40, flex: '0 0 40px', borderRadius: '50%', objectFit: 'cover' }} />
          ) : (
            <span aria-hidden="true" style={{ width: 40, height: 40, flex: '0 0 40px', borderRadius: '50%', background: '#7BC3A0', color: '#1C1B18', display: 'flex', alignItems: 'center', justifyContent: 'center', fontWeight: 600, fontSize: 14 }}>{ini}</span>
          )}
          <span style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0 }}>
            <span style={{ fontSize: 14, fontWeight: 600 }}>{[user?.firstName, user?.lastName].filter(Boolean).join(' ')}</span>
            <span style={{ fontSize: 12, color: '#A9A497' }}>{ROLE_LABELS[user?.role] || ''}</span>
          </span>
        </Link>
        <button type="button" className="sb-signout" onClick={logout}>Sign out</button>
      </div>
    </aside>
  );
}
