import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Link, useParams } from 'react-router-dom';
import api from '../lib/api';
import { useAuth } from '../context/AuthContext';
import { can } from '../lib/roles';
import { num, peso, unitPlural } from '../lib/format';
import { ErrBanner, PeriodBadge, fmtDT, fullName, monthLabel, truckError } from '../components/TruckBits';
import '../styles/forms.css';
import '../styles/orders.css';
import '../styles/trucks.css';

const TABS = [['overview', 'Overview'], ['stock', 'Stock on truck'], ['transfers', 'Transfers'], ['count', 'Count'], ['report', 'Report']];
const KIND_LABEL = { load: 'Load (beginning inventory)', topup: 'Top-up', return: 'Return to warehouse' };
const T_BADGE = { pending: 'badge-low', approved: 'badge-in', rejected: 'badge-out' };
const V_BADGE = { ok: 'badge-in', missing: 'badge-out', surplus: 'badge-low' };
const V_LABEL = { ok: 'OK', missing: 'Missing', surplus: 'Surplus' };
const signed = (n) => (n > 0 ? '+' : '') + num(n);

// Debounced item search; picking an item calls onPick({ id, name, unit }).
function ItemPicker({ id, onPick }) {
  const [q, setQ] = useState('');
  const [res, setRes] = useState([]);
  const seq = useRef(0);
  useEffect(() => {
    const term = q.trim();
    if (!term) { setRes([]); return undefined; }
    const my = ++seq.current;
    const t = setTimeout(() => {
      api.get('/items', { params: { q: term } }).then((r) => {
        if (my === seq.current) setRes((Array.isArray(r.data) ? r.data : r.data.items || []).slice(0, 8));
      }).catch(() => {});
    }, 200);
    return () => clearTimeout(t);
  }, [q]);
  return (
    <div className="field trk-pick">
      <label htmlFor={id}>Add item</label>
      <input id={id} type="search" className="input" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Name or SKU" autoComplete="off" />
      {res.length > 0 && (
        <ul>
          {res.map((i) => (
            <li key={i.id}><button type="button" onClick={() => { onPick({ id: i.id, name: i.name, unit: i.unit }); setQ(''); setRes([]); }}>
              {i.name} <span className="muted">· {num(i.qtyOnHand)} in warehouse</span>
            </button></li>
          ))}
        </ul>
      )}
    </div>
  );
}

function TransferForm({ truck, period, onDone }) {
  const kinds = period?.status === 'draft' ? ['load'] : period?.status === 'open' ? ['topup', 'return'] : [];
  const [kind, setKind] = useState(kinds[0] || '');
  const [note, setNote] = useState('');
  const [rows, setRows] = useState([]); // { id, name, unit, qty }
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState(null);
  useEffect(() => { if (!kinds.includes(kind)) setKind(kinds[0] || ''); }, [period?.status]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!kinds.length) return <p className="muted" style={{ margin: 0 }}>Transfers can be requested while the period is Draft (load) or Open (top-up / return).</p>;

  const add = (it) => setRows((r) => (r.some((x) => x.id === it.id) ? r : [...r, { ...it, qty: '1' }]));
  const setQty = (id, qty) => setRows((r) => r.map((x) => (x.id === id ? { ...x, qty } : x)));
  const valid = rows.length > 0 && rows.every((r) => Number.isInteger(Number(r.qty)) && Number(r.qty) >= 1);

  async function submit(e) {
    e.preventDefault();
    setBusy(true); setErr(null);
    try {
      await api.post(`/trucks/${truck.id}/transfers`, { kind, note: note.trim() || undefined, lines: rows.map((r) => ({ itemId: r.id, qty: Number(r.qty) })) });
      setRows([]); setNote('');
      onDone('Transfer requested.');
    } catch (e2) { setErr(truckError(e2)); }
    setBusy(false);
  }

  return (
    <form onSubmit={submit} noValidate style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <div className="field" style={{ maxWidth: 320 }}>
        <label htmlFor="tr-kind">Request</label>
        <select id="tr-kind" className="select" value={kind} onChange={(e) => setKind(e.target.value)}>
          {kinds.map((k) => <option key={k} value={k}>{KIND_LABEL[k]}</option>)}
        </select>
      </div>
      <ItemPicker id="tr-item" onPick={add} />
      {rows.length > 0 && (
        <div className="trk-rows">
          {rows.map((r) => (
            <div className="trk-row" key={r.id}>
              <span id={`tr-n-${r.id}`} style={{ fontWeight: 500, overflowWrap: 'anywhere' }}>{r.name}</span>
              <input type="number" min="1" step="1" inputMode="numeric" className="input mono" aria-labelledby={`tr-n-${r.id}`} aria-describedby={`tr-u-${r.id}`} value={r.qty} onChange={(e) => setQty(r.id, e.target.value)} />
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => setRows((x) => x.filter((y) => y.id !== r.id))} aria-label={`Remove ${r.name}`}>Remove</button>
              <span id={`tr-u-${r.id}`} className="sr-only">{unitPlural(r.unit)}</span>
            </div>
          ))}
        </div>
      )}
      <div className="field"><label htmlFor="tr-note">Note (optional)</label><input id="tr-note" className="input" value={note} onChange={(e) => setNote(e.target.value)} /></div>
      <ErrBanner err={err} />
      <div className="actions"><button type="submit" className="btn btn-primary" disabled={busy || !valid}>{busy ? 'Requesting…' : 'Request transfer'}</button></div>
    </form>
  );
}

function TransferList({ list, manager, onAct, busyId }) {
  if (!list.length) return <p className="empty" style={{ padding: 8 }}>Nothing here.</p>;
  return (
    <div className="table-wrap">
      <table className="tbl" style={{ minWidth: 640 }}>
        <thead><tr><th scope="col">Request</th><th scope="col">Items</th><th scope="col">Status</th><th scope="col"><span className="sr-only">Actions</span></th></tr></thead>
        <tbody>
          {list.map((t) => (
            <tr key={t.id}>
              <td>
                <strong style={{ fontWeight: 600 }}>{KIND_LABEL[t.kind] || t.kind}</strong>
                <span className="sub">{fullName(t.requestedBy)} · {fmtDT(t.requestedAt)}{t.note && <> · {t.note}</>}</span>
              </td>
              <td>{t.lines.map((l) => <span key={l.itemId} style={{ display: 'block', fontSize: 14 }}>{num(l.qty)} × {l.name}</span>)}</td>
              <td>
                <span className={'badge ' + (T_BADGE[t.status] || 'badge-none')}>{t.status[0].toUpperCase() + t.status.slice(1)}</span>
                {t.decidedBy && <span className="sub">{fullName(t.decidedBy)} · {fmtDT(t.decidedAt)}</span>}
              </td>
              <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                {t.status === 'pending' && manager && (
                  <>
                    {t.kind !== 'load' && <button type="button" className="btn btn-primary btn-sm" disabled={busyId === t.id} onClick={() => onAct(t, 'approve')}>Approve</button>}{' '}
                    <button type="button" className="btn btn-secondary btn-sm" disabled={busyId === t.id} onClick={() => onAct(t, 'reject')}>Reject</button>
                  </>
                )}
                {t.status === 'pending' && t.kind === 'load' && <span className="sub">Applied when the period is opened</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ReportView({ r }) {
  if (!r) return <p className="empty">No report yet.</p>;
  const verdict = !r.counted ? 'pending' : r.matched ? 'ok' : 'bad';
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div className={'trk-verdict ' + verdict} role="status">
        {!r.counted ? 'NOT COUNTED YET' : r.matched ? 'MATCHED' : 'NOT MATCHED'}
        <small>{monthLabel(r.month)} · {r.truck.name}{r.preview ? ' · live preview, not final' : ' · final'}</small>
      </div>
      {r.counted && !r.complete && r.uncountedItems?.length > 0 && (
        <div role="alert" className="banner-err">
          Not every item is counted yet (shown as zero): {r.uncountedItems.map((u) => u.name).join(', ')}.
        </div>
      )}
      <div className="grid-fit" style={{ '--min': '170px' }}>
        <div className="margin-box"><span className="t">Missing</span><span className="v">{num(r.totals.missingQty)} units</span><span className="trk-note">{peso(r.totals.missingValue)} at retail</span></div>
        <div className="margin-box"><span className="t">Surplus</span><span className="v">{num(r.totals.surplusQty)} units</span><span className="trk-note">{peso(r.totals.surplusValue)} at retail</span></div>
        {r.sales && <div className="margin-box"><span className="t">Truck sales</span><span className="v">{num(r.sales.count)} · {peso(r.sales.total)}</span>
          <span className="trk-note">{r.sales.oversold > 0 && <span className="trk-neg">{num(r.sales.oversold)} oversold</span>}{r.sales.oversold > 0 && r.sales.late > 0 && ' · '}{r.sales.late > 0 && `${num(r.sales.late)} late`}{!r.sales.oversold && !r.sales.late && 'none flagged'}</span></div>}
      </div>
      <div className="table-wrap">
        <table className="tbl" style={{ minWidth: 760 }}>
          <thead>
            <tr>
              <th scope="col">Item</th><th scope="col" className="num">Opening</th><th scope="col" className="num">Top-up</th><th scope="col" className="num">Returned</th>
              <th scope="col" className="num">Sold</th><th scope="col" className="num">Expected</th><th scope="col" className="num">Counted</th>
              <th scope="col" className="num">Variance</th><th scope="col" className="num">Value</th><th scope="col">Status</th>
            </tr>
          </thead>
          <tbody>
            {r.lines.map((l) => (
              <tr key={l.itemId}>
                <td>{l.name}<span className="sub">{l.unit}</span></td>
                <td className="num">{num(l.opening)}</td><td className="num">{num(l.toppedUp)}</td><td className="num">{num(l.returned)}</td>
                <td className="num">{num(l.sold)}</td>
                <td className={'num' + (l.expected < 0 ? ' trk-neg' : '')}>{num(l.expected)}</td>
                <td className="num">{l.hasCount ? num(l.counted) : '—'}</td>
                <td className={'num' + (l.variance < 0 ? ' trk-neg' : '')}>{signed(l.variance)}</td>
                <td className="num">{l.value ? peso(l.value) : '—'}</td>
                <td><span className={'badge ' + (V_BADGE[l.status] || 'badge-none')}>{V_LABEL[l.status] || l.status}</span></td>
              </tr>
            ))}
            {r.lines.length === 0 && <tr><td colSpan={10} className="muted">No stock moved on this truck.</td></tr>}
          </tbody>
        </table>
      </div>
      <p className="trk-note" style={{ margin: 0 }}>Generated {fmtDT(r.generatedAt)}{r.countedAt && <> · counted {fmtDT(r.countedAt)} by {fullName(r.countedBy)}</>}. Value is |variance| × retail price.</p>
    </div>
  );
}

// Printable A4 report, rendered through a portal (see print rules in trucks.css).
function PrintReport({ r, shop }) {
  return createPortal(
    <div className="truck-print" aria-hidden="true">
      <h1>{shop}</h1>
      <div>Truck stock report · {r.truck.name}{r.truck.plate ? ` (${r.truck.plate})` : ''} · {monthLabel(r.month)}</div>
      <div>Driver: {r.truck.driverName || '—'} · {r.preview ? 'Preview' : 'Final'} · generated {fmtDT(r.generatedAt)}</div>
      <div className="verdict">{!r.counted ? 'NOT COUNTED YET' : r.matched ? 'MATCHED' : 'NOT MATCHED'}</div>
      <div>Missing: {num(r.totals.missingQty)} units ({peso(r.totals.missingValue)}) · Surplus: {num(r.totals.surplusQty)} units ({peso(r.totals.surplusValue)})</div>
      {r.sales && <div>Truck sales: {num(r.sales.count)} ({peso(r.sales.total)}) · oversold {num(r.sales.oversold)} · late {num(r.sales.late)}</div>}
      <h2>Items</h2>
      <table>
        <thead><tr><th>Item</th><th className="n">Opening</th><th className="n">Top-up</th><th className="n">Returned</th><th className="n">Sold</th><th className="n">Expected</th><th className="n">Counted</th><th className="n">Variance</th><th className="n">Value</th><th>Status</th></tr></thead>
        <tbody>
          {r.lines.map((l) => (
            <tr key={l.itemId}>
              <td>{l.name}</td><td className="n">{num(l.opening)}</td><td className="n">{num(l.toppedUp)}</td><td className="n">{num(l.returned)}</td><td className="n">{num(l.sold)}</td>
              <td className="n">{num(l.expected)}</td><td className="n">{l.hasCount ? num(l.counted) : '—'}</td><td className="n">{signed(l.variance)}</td>
              <td className="n">{l.value ? peso(l.value) : '—'}</td><td>{V_LABEL[l.status]}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="sig"><span>Counted by</span><span>Driver / agent</span><span>Approved by (manager)</span></div>
    </div>,
    document.body
  );
}

export default function TruckDetail() {
  const { id } = useParams();
  const { user, settings } = useAuth();
  const manager = can(user, 'truckApprove');
  const [tab, setTab] = useState('overview');
  const [data, setData] = useState(null);
  const [pd, setPd] = useState(null); // GET /truck-periods/:id for the current period
  const [failure, setFailure] = useState('');
  const [err, setErr] = useState(null);
  const [ok, setOk] = useState('');
  const [busy, setBusy] = useState('');
  const [counts, setCounts] = useState({}); // itemId -> string
  const [rpid, setRpid] = useState(null);
  const [report, setReport] = useState(null);
  const [printing, setPrinting] = useState(false);

  const load = useCallback(() => api.get(`/trucks/${id}`)
    .then((r) => { setData(r.data); setFailure(''); return r.data; })
    .catch((e) => { setFailure(truckError(e).message); }), [id]);
  useEffect(() => { load(); }, [load]);

  const cur = data?.currentPeriod;
  const curKey = cur ? `${cur.id}:${cur.status}` : '';
  useEffect(() => {
    if (!cur) { setPd(null); return; }
    api.get(`/truck-periods/${cur.id}`).then((r) => {
      setPd(r.data);
      const saved = Object.fromEntries((r.data.count?.lines || []).map((l) => [l.itemId, String(l.counted)]));
      setCounts(saved);
    }).catch((e) => setErr(truckError(e)));
  }, [curKey]); // eslint-disable-line react-hooks/exhaustive-deps

  // Report period: the one being counted, else the latest closed.
  const reportable = useMemo(() => (data?.periods || []).filter((p) => p.status !== 'draft'), [data]);
  useEffect(() => {
    if (!data) return;
    if (rpid && reportable.some((p) => p.id === rpid)) return;
    const pick = reportable.find((p) => p.status === 'counting') || reportable.find((p) => p.status === 'closed') || reportable[0];
    setRpid(pick ? pick.id : null);
  }, [data, reportable, rpid]);
  useEffect(() => {
    if (tab !== 'report' || !rpid) { return; }
    let live = true;
    setReport(null);
    api.get(`/truck-periods/${rpid}/report`).then((r) => { if (live) setReport(r.data.report); }).catch((e) => { if (live) setErr(truckError(e)); });
    return () => { live = false; };
  }, [tab, rpid, curKey]);

  useEffect(() => {
    if (!printing) return undefined;
    const t = setTimeout(() => window.print(), 50);
    const done = () => setPrinting(false);
    window.addEventListener('afterprint', done);
    return () => { clearTimeout(t); window.removeEventListener('afterprint', done); };
  }, [printing]);

  // Runs a POST/PUT, shows the result, reloads. `after(data)` can steer the UI.
  async function run(key, fn, msg, after) {
    setBusy(key); setErr(null); setOk('');
    try {
      const res = await fn();
      if (msg) setOk(msg);
      await load();
      after?.(res.data);
    } catch (e) { setErr(truckError(e)); }
    setBusy('');
  }

  const actTransfer = (t, act) => run(`t${t.id}`, () => api.post(`/truck-transfers/${t.id}/${act}`), act === 'approve' ? 'Transfer approved and applied.' : 'Transfer rejected.');

  if (failure && !data) return <div className="page-col"><div role="alert" className="banner-err">{failure}</div><Link to="/trucks">Back to trucks</Link></div>;
  if (!data) return <div className="page-col"><p className="empty">Loading truck…</p></div>;

  const { truck, stock, transfers } = data;
  const pendingLoads = transfers.filter((t) => t.kind === 'load' && t.status === 'pending' && cur && t.periodId === cur.id);
  const pending = transfers.filter((t) => t.status === 'pending');
  const decided = transfers.filter((t) => t.status !== 'pending');
  const expected = pd?.expected || [];
  const counting = cur?.status === 'counting';
  const countValid = expected.every((x) => counts[x.itemId] !== undefined && counts[x.itemId] !== '' && Number.isInteger(Number(counts[x.itemId])) && Number(counts[x.itemId]) >= 0);

  const saveCount = () => run('count', () => {
    const lines = Object.entries(counts).filter(([, v]) => v !== '' && Number.isInteger(Number(v)) && Number(v) >= 0).map(([itemId, v]) => ({ itemId: Number(itemId), counted: Number(v) }));
    return api.put(`/truck-periods/${cur.id}/count`, { lines });
  }, 'Count saved.', (d) => { setReport(d.report); setRpid(cur.id); });

  return (
    <div className="page-col">
      <header className="page-head" style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-end', justifyContent: 'space-between', gap: 16 }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <p className="eyebrow"><Link to="/trucks">Trucks</Link></p>
          <h1 className="page-title">{truck.name}</h1>
        </div>
        {cur && <PeriodBadge status={cur.status} />}
      </header>

      {ok && <div role="status" className="banner-ok"><span>{ok}</span><button type="button" className="btn btn-ghost" onClick={() => setOk('')}>Dismiss</button></div>}
      <ErrBanner err={err} />

      <div className="trk-tabs" role="group" aria-label="Truck sections">
        {TABS.map(([k, label]) => <button key={k} type="button" className="pill" aria-pressed={tab === k} onClick={() => setTab(k)}>{label}{k === 'transfers' && pending.length > 0 && <span className="cnt">{pending.length}</span>}</button>)}
      </div>

      {tab === 'overview' && (
        <section className="card" style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
          <div className="trk-meta">
            {[['Plate', truck.plate || '—'], ['Driver', truck.driverName || '—'], ['Agent', truck.agent ? fullName(truck.agent) : '—'], ['Units on board', num(data.unitsOnBoard)], ['Stock at retail', peso(data.stockValue)], ['Status', truck.active ? 'Active' : 'Inactive']].map(([k, v]) => (
              <div key={k}><span className="k">{k}</span><span className="v">{v}</span></div>
            ))}
          </div>
          <h2 className="h2" style={{ margin: 0 }}>{cur ? <>Period {monthLabel(cur.month)} <PeriodBadge status={cur.status} /></> : 'No active period'}</h2>
          {cur && <p className="trk-note" style={{ margin: 0 }}>
            {cur.openedAt && <>Opened {fmtDT(cur.openedAt)}. </>}{cur.countStartedAt && <>Count started {fmtDT(cur.countStartedAt)}.</>}
          </p>}

          {!cur && <p className="muted" style={{ margin: 0 }}>This truck has no period. {manager ? 'Create one to start loading stock.' : 'Ask a manager to create one.'}</p>}
          {cur?.status === 'draft' && (
            <div>
              <h3 className="h2" style={{ fontSize: 16 }}>Beginning inventory to load</h3>
              {pendingLoads.length === 0 ? <p className="muted" style={{ margin: 0 }}>No load requests yet. Request one in Transfers; stock already on the truck carries over.</p> : (
                <ul style={{ margin: 0, paddingLeft: 18 }}>
                  {pendingLoads.flatMap((t) => t.lines).map((l, i) => <li key={i}>{num(l.qty)} × {l.name}</li>)}
                </ul>
              )}
              {!manager && <p className="trk-note">A manager approves the beginning inventory.</p>}
            </div>
          )}
          <div className="trk-actions">
            {!cur && manager && <button type="button" className="btn btn-primary" disabled={!!busy} onClick={() => run('create', () => api.post(`/trucks/${truck.id}/periods`, {}), 'Period created.')}>{busy === 'create' ? 'Creating…' : 'Create period'}</button>}
            {cur?.status === 'draft' && manager && <button type="button" className="btn btn-primary" disabled={!!busy} onClick={() => run('open', () => api.post(`/truck-periods/${cur.id}/open`), 'Beginning inventory approved. The period is open.')}>{busy === 'open' ? 'Opening…' : 'Approve beginning inventory'}</button>}
            {cur?.status === 'open' && <button type="button" className="btn btn-primary" disabled={!!busy} onClick={() => run('start', () => api.post(`/truck-periods/${cur.id}/start-count`), 'Count started.', () => setTab('count'))}>{busy === 'start' ? 'Starting…' : 'Start count'}</button>}
            {counting && <button type="button" className="btn btn-secondary" onClick={() => setTab('count')}>Go to count</button>}
            {counting && manager && <button type="button" className="btn btn-primary" disabled={!!busy} onClick={() => run('close', () => api.post(`/truck-periods/${cur.id}/close`), 'Period closed. The report is final.', (d) => { setReport(d.report); setRpid(cur.id); setTab('report'); })}>{busy === 'close' ? 'Closing…' : 'Close & generate report'}</button>}
          </div>

          <h3 className="h2" style={{ fontSize: 16, marginBottom: -6 }}>Periods</h3>
          <div className="table-wrap">
            <table className="tbl" style={{ minWidth: 480 }}>
              <thead><tr><th scope="col">Month</th><th scope="col">Status</th><th scope="col">Result</th><th scope="col" className="num">Missing</th><th scope="col"><span className="sr-only">Actions</span></th></tr></thead>
              <tbody>
                {data.periods.map((p) => (
                  <tr key={p.id}>
                    <td>{monthLabel(p.month)}</td>
                    <td><PeriodBadge status={p.status} /></td>
                    <td>{p.matched == null ? <span className="muted">—</span> : <span className={'badge ' + (p.matched ? 'badge-in' : 'badge-out')}>{p.matched ? 'Matched' : 'Not matched'}</span>}</td>
                    <td className="num">{p.missingValue == null ? '—' : peso(p.missingValue)}</td>
                    <td style={{ textAlign: 'right' }}>{p.status !== 'draft' && <button type="button" className="btn btn-ghost btn-sm" onClick={() => { setRpid(p.id); setTab('report'); }}>Report</button>}</td>
                  </tr>
                ))}
                {data.periods.length === 0 && <tr><td colSpan={5} className="muted">No periods yet.</td></tr>}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {tab === 'stock' && (
        <section className="card" style={{ padding: 16 }}>
          {stock.length === 0 ? <p className="empty">Nothing on this truck.</p> : (
            <div className="table-wrap">
              <table className="tbl" style={{ minWidth: 480 }}>
                <thead><tr><th scope="col">Item</th><th scope="col" className="num">Qty</th><th scope="col" className="num">Retail price</th><th scope="col" className="num">Retail value</th></tr></thead>
                <tbody>
                  {stock.map((s) => (
                    <tr key={s.itemId}>
                      <td>{s.name}</td>
                      <td className={'num' + (s.qty < 0 ? ' trk-neg' : '')}>{num(s.qty)} {unitPlural(s.unit)}{s.qty < 0 && <span className="sub" style={{ fontFamily: 'var(--font-sans)' }}>Oversold</span>}</td>
                      <td className="num">{peso(s.retailPrice)}</td>
                      <td className={'num' + (s.value < 0 ? ' trk-neg' : '')}>{peso(s.value)}</td>
                    </tr>
                  ))}
                </tbody>
                <tfoot><tr><th scope="row">Total</th><td className="num">{num(data.unitsOnBoard)}</td><td /><td className="num">{peso(data.stockValue)}</td></tr></tfoot>
              </table>
            </div>
          )}
        </section>
      )}

      {tab === 'transfers' && (
        <section className="card" style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
          <TransferForm truck={truck} period={cur} onDone={(m) => { setOk(m); load(); }} />
          <h3 className="h2" style={{ fontSize: 16, marginBottom: -8 }}>Pending</h3>
          <TransferList list={pending} manager={manager} onAct={actTransfer} busyId={busy.startsWith('t') ? Number(busy.slice(1)) : null} />
          <h3 className="h2" style={{ fontSize: 16, marginBottom: -8 }}>History</h3>
          <TransferList list={decided} manager={false} />
        </section>
      )}

      {tab === 'count' && (
        <section className="card" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          {!counting ? (
            <p className="muted" style={{ margin: 0 }}>{cur?.status === 'open' ? 'Start the count from the Overview tab to enter quantities.' : cur ? 'The count is entered while the period is in Counting.' : 'No active period.'}</p>
          ) : (
            <>
              <p className="muted" style={{ margin: 0 }}>Count the goods physically on the truck and enter every quantity. Saving replaces the whole count.</p>
              <div className="table-wrap">
                <table className="tbl" style={{ minWidth: 420 }}>
                  <thead><tr><th scope="col">Item</th><th scope="col" className="num">Expected</th><th scope="col" className="num">Counted</th></tr></thead>
                  <tbody>
                    {expected.map((x) => (
                      <tr key={x.itemId}>
                        <td id={`ct-n-${x.itemId}`}>{x.name}<span className="sub">{x.unit}</span></td>
                        <td className={'num' + (x.expected < 0 ? ' trk-neg' : '')}>{num(x.expected)}</td>
                        <td className="num">
                          <input type="number" min="0" step="1" inputMode="numeric" className="input mono trk-count-in" aria-labelledby={`ct-n-${x.itemId}`}
                            value={counts[x.itemId] ?? ''} onChange={(e) => setCounts((p) => ({ ...p, [x.itemId]: e.target.value }))} />
                        </td>
                      </tr>
                    ))}
                    {expected.length === 0 && <tr><td colSpan={3} className="muted">No items expected on the truck.</td></tr>}
                  </tbody>
                </table>
              </div>
              <div className="actions">
                <button type="button" className="btn btn-primary" disabled={!!busy} onClick={saveCount}>{busy === 'count' ? 'Saving…' : 'Save count'}</button>
                {!countValid && expected.length > 0 && <span className="trk-note">Every item needs a count before the period can be closed.</span>}
              </div>
              {report && report.periodId === cur.id && report.counted && (
                <p style={{ margin: 0 }}>Live result: <strong>{report.matched ? 'Matched' : 'Not matched'}</strong> · missing {num(report.totals.missingQty)} ({peso(report.totals.missingValue)}) · surplus {num(report.totals.surplusQty)}. <button type="button" className="btn btn-ghost btn-sm" onClick={() => setTab('report')}>View report</button></p>
              )}
            </>
          )}
        </section>
      )}

      {tab === 'report' && (
        <section className="card" style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          {reportable.length === 0 ? <p className="empty">Reports appear once a period is open.</p> : (
            <>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'flex-end', justifyContent: 'space-between' }}>
                <div className="field">
                  <label htmlFor="rp-sel">Period</label>
                  <select id="rp-sel" className="select" value={rpid || ''} onChange={(e) => setRpid(Number(e.target.value))}>
                    {reportable.map((p) => <option key={p.id} value={p.id}>{monthLabel(p.month)} · {p.status}</option>)}
                  </select>
                </div>
                <button type="button" className="btn btn-secondary" disabled={!report} onClick={() => setPrinting(true)}>Print</button>
              </div>
              {report ? <ReportView r={report} /> : <p className="empty">Loading report…</p>}
            </>
          )}
        </section>
      )}

      {printing && report && <PrintReport r={report} shop={settings?.shopName || 'Marbingo'} />}
    </div>
  );
}
