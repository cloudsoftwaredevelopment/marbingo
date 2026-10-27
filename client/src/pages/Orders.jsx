import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import api, { apiError } from '../lib/api';
import { useAuth } from '../context/AuthContext';
import { can } from '../lib/roles';
import { num, peso, deliveredTotal, deliveredLineTotal, orderTotal, partsWithMoney } from '../lib/format';
import { priceQty } from '../lib/pricing';
import '../styles/forms.css';
import '../styles/orders.css';

const STATUS = [['pending', 'Pending'], ['delivered', 'Delivered'], ['cancelled', 'Cancelled'], ['all', 'All']];
const BADGE = { pending: 'badge-low', delivered: 'badge-in', cancelled: 'badge-out' };
const LABEL = { pending: 'Pending', delivered: 'Delivered', cancelled: 'Cancelled' };
const dtFmt = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit', timeZone: 'Asia/Manila' });
const fmtDT = (iso) => (iso ? dtFmt.format(new Date(iso)) : '—');
const fullName = (u) => (u ? [u.firstName, u.lastName].filter(Boolean).join(' ') : '—');
const itemCount = (o) => o.lines.length;

export function useModal(onClose) {
  const ref = useRef(null);
  useEffect(() => {
    const prev = document.activeElement;
    const box = ref.current;
    const focusables = () => [...box.querySelectorAll('button:not([disabled]), input:not([disabled]), select, textarea, [href], [tabindex]:not([tabindex="-1"])')];
    (box.querySelector('[data-autofocus]') || focusables()[0])?.focus();
    function onKey(e) {
      if (e.key === 'Escape') { e.stopPropagation(); onClose(); return; }
      if (e.key !== 'Tab') return;
      const f = focusables();
      if (!f.length) return;
      const first = f[0], last = f[f.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
      else if (!box.contains(document.activeElement)) { e.preventDefault(); first.focus(); }
    }
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('keydown', onKey); prev?.focus?.(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return ref;
}

// Re-prices `qty` of an order line the way the server will on delivery: from the line's pricing
// snapshot { retail, packs }. The order JSON may not include the snapshot, so when line.pricing is
// absent we rebuild it from the line's breakdown (its pack parts give size/price) with unitPrice as retail.
// Limitation of the fallback: a pack that wasn't used at the ordered qty isn't known, so the dialog
// total is an estimate; the server's figure (shown after delivery) is authoritative.
function repriceLine(l, qty) {
  const snap = l.pricing;
  if (snap && Number.isInteger(snap.retail)) return priceQty(qty, snap.retail, snap.packs || []);
  const seen = new Map();
  (l.breakdown || []).filter((x) => x.name).forEach((x) => seen.set(`${x.size}:${x.price}`, { packTypeId: x.packTypeId, name: x.name, size: x.size, price: x.price }));
  return priceQty(qty, l.unitPrice, [...seen.values()]);
}
const hasPacks = (parts) => Array.isArray(parts) && parts.some((x) => x.name);

function DeliverDialog({ order, onClose, onDone }) {
  const ref = useModal(onClose);
  const [qty, setQty] = useState(() => Object.fromEntries(order.lines.map((l) => [l.itemId, String(l.qty)])));
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const val = (l) => {
    const n = Math.floor(Number(qty[l.itemId]));
    return Number.isFinite(n) ? Math.min(l.qty, Math.max(0, n)) : l.qty;
  };
  const priced = order.lines.map((l) => repriceLine(l, val(l)));
  const total = priced.reduce((a, r) => a + r.total, 0);
  const none = order.lines.every((l) => val(l) === 0);

  async function confirm() {
    setBusy(true); setErr('');
    try {
      const { data } = await api.post(`/orders/${order.id}/deliver`, { lines: order.lines.map((l) => ({ itemId: l.itemId, qty: val(l) })) });
      onDone(data.order || order);
    } catch (e) {
      setErr(apiError(e).message);
      setBusy(false);
    }
  }

  return (
    <div className="modal-back">
      <div ref={ref} className="modal" role="dialog" aria-modal="true" aria-labelledby="dlv-h">
        <h2 id="dlv-h">Deliver order #{order.id}</h2>
        <p className="page-sub" style={{ margin: 0 }}>{order.customer?.name} · set the quantity actually delivered for each item.</p>
        <div className="lines">
          {order.lines.map((l, i) => (
            <div className="line" key={l.itemId}>
              <span className="nm" id={`dl-n-${l.itemId}`}>{l.name}</span>
              <span className="meta">Ordered {num(l.qty)} · {peso(l.unitPrice)} each loose</span>
              {(hasPacks(priced[i].parts) || hasPacks(l.breakdown)) && <span className="meta" aria-live="polite">Delivering: {partsWithMoney(priced[i].parts, l.unit) || 'nothing'} · {peso(priced[i].total)}</span>}
              <span className="stepper">
                <input type="number" min="0" max={l.qty} step="1" inputMode="numeric" className="input mono" aria-labelledby={`dl-n-${l.itemId}`}
                  data-autofocus={i === 0 ? '' : undefined} value={qty[l.itemId]}
                  onChange={(e) => setQty((p) => ({ ...p, [l.itemId]: e.target.value }))}
                  onBlur={() => setQty((p) => ({ ...p, [l.itemId]: String(val(l)) }))} />
              </span>
            </div>
          ))}
        </div>
        <div className="total"><span>New total</span><span className="mono" aria-live="polite">{peso(total)}</span></div>
        {err && <div role="alert" className="banner-err">{err}</div>}
        <div className="actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Close</button>
          <button type="button" className="btn btn-primary" disabled={busy || none} onClick={confirm}>{busy ? 'Delivering…' : 'Confirm delivery'}</button>
        </div>
      </div>
    </div>
  );
}

function CancelDialog({ order, onClose, onDone }) {
  const ref = useModal(onClose);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');

  async function submit(e) {
    e.preventDefault();
    if (!reason.trim()) { setErr('Enter a reason for cancelling.'); return; }
    setBusy(true); setErr('');
    try {
      const { data } = await api.post(`/orders/${order.id}/cancel`, { reason: reason.trim() });
      onDone(data.order || order);
    } catch (e2) {
      setErr(apiError(e2).message);
      setBusy(false);
    }
  }

  return (
    <div className="modal-back">
      <form ref={ref} className="modal" role="dialog" aria-modal="true" aria-labelledby="cx-h" onSubmit={submit} noValidate>
        <h2 id="cx-h">Cancel order #{order.id}</h2>
        <div className="field">
          <label htmlFor="cx-reason">Reason *</label>
          <textarea id="cx-reason" data-autofocus className={'textarea' + (err ? ' invalid' : '')} rows="3" value={reason} onChange={(e) => { setReason(e.target.value); setErr(''); }} placeholder="e.g. Store asked to cancel" aria-invalid={!!err} />
          {err && <span className="err" role="alert">{err}</span>}
        </div>
        <div className="actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Keep order</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? 'Cancelling…' : 'Cancel order'}</button>
        </div>
      </form>
    </div>
  );
}

function Slip({ order, shop }) {
  const c = order.customer || {};
  return createPortal(
    <div className="slip-print" aria-hidden="true">
      <h1>{shop}</h1>
      <div className="c">Delivery slip</div>
      <hr />
      <div>Order #{order.id}</div>
      <div>{fmtDT(order.orderedAt)}</div>
      <div>Agent: {fullName(order.agent)}</div>
      <hr />
      <div><strong>{c.name}</strong></div>
      {c.address && <div>{c.address}</div>}
      {c.phone && <div>{c.phone}</div>}
      <hr />
      {order.lines.map((l) => {
        const delivered = order.status === 'delivered' && l.deliveredQty != null;
        const q = delivered ? l.deliveredQty : l.qty;
        const parts = delivered ? l.deliveredBreakdown : l.breakdown;
        return (
          <div className="ln" key={l.itemId}>
            <div>{l.name}</div>
            {Array.isArray(parts) && parts.length > 0 ? parts.map((x, k) => (
              <div className="r" key={k}>
                <span>{x.name ? `${x.count} × ${x.name} (${x.size}${l.unit ? ' ' + l.unit : ''})` : `${x.count}${l.unit ? ' ' + l.unit : ''} × ${peso(x.price)}`}</span>
                <span>{peso(x.amount)}</span>
              </div>
            )) : <div className="r"><span>{num(q)} × {peso(l.unitPrice)}</span><span>{peso(q * l.unitPrice)}</span></div>}
          </div>
        );
      })}
      <hr />
      <div className="tot"><span>TOTAL</span><span>{peso(orderTotal(order))}</span></div>
      <div className="sig">Received by: ____________</div>
    </div>,
    document.body
  );
}

const shownTotal = orderTotal;

export default function Orders() {
  const { user, settings } = useAuth();
  const actions = can(user, 'orderActions');
  const isAgent = user?.role === 'AGENT';
  const [status, setStatus] = useState('pending');
  const [agentId, setAgentId] = useState('');
  const [store, setStore] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [data, setData] = useState(null);
  const [agents, setAgents] = useState([]);
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState('');
  const [ok, setOk] = useState('');
  const [open, setOpen] = useState(null);
  const [dialog, setDialog] = useState(null); // { kind, order }
  const [slip, setSlip] = useState(null);

  const load = useCallback(() => {
    const params = {};
    if (status !== 'all') params.status = status;
    if (agentId) params.agentId = agentId;
    if (from) params.from = from;
    if (to) params.to = to;
    return api.get('/orders', { params })
      .then((r) => { setData(r.data); setFailure(''); })
      .catch((e) => setFailure(apiError(e).message))
      .finally(() => setLoading(false));
  }, [status, agentId, from, to]);

  useEffect(() => { setLoading(true); load(); }, [load]);

  // Build the agent dropdown from every agent seen so far, so it doesn't shrink once filtered.
  useEffect(() => {
    if (!data?.orders) return;
    setAgents((prev) => {
      const m = new Map(prev.map((a) => [a.id, a]));
      data.orders.forEach((o) => o.agent && m.set(o.agent.id, o.agent));
      return [...m.values()].sort((a, b) => fullName(a).localeCompare(fullName(b)));
    });
  }, [data]);

  useEffect(() => {
    if (!slip) return undefined;
    const t = setTimeout(() => window.print(), 50);
    const done = () => setSlip(null);
    window.addEventListener('afterprint', done);
    return () => { clearTimeout(t); window.removeEventListener('afterprint', done); };
  }, [slip]);

  const orders = useMemo(() => {
    const q = store.trim().toLowerCase();
    const list = data?.orders || [];
    return q ? list.filter((o) => (o.customer?.name || '').toLowerCase().includes(q)) : list;
  }, [data, store]);
  const counts = data?.counts || { pending: 0, delivered: 0, cancelled: 0 };
  const countFor = (id) => (id === 'all' ? counts.pending + counts.delivered + counts.cancelled : counts[id]);

  function done(msg) {
    setDialog(null);
    setOk(msg);
    load();
  }
  const toggle = (id) => setOpen((p) => (p === id ? null : id));
  const rowKey = (e, id) => {
    if (e.target !== e.currentTarget) return;
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(id); }
  };

  return (
    <>
      <header className="page-head">
        <p className="eyebrow">Field sales</p>
        <h1 className="page-title">{isAgent ? 'My orders' : 'Orders'}</h1>
      </header>

      {ok && (
        <div role="status" className="banner-ok">
          <span>{ok}</span>
          <button type="button" className="btn btn-ghost" onClick={() => setOk('')}>Dismiss</button>
        </div>
      )}
      {failure && <div role="alert" className="banner-err">{failure}</div>}

      <section className="card filter-bar" aria-label="Filters">
        <div className="group" role="group" aria-label="Status">
          {STATUS.map(([id, label]) => (
            <button key={id} type="button" className="pill" aria-pressed={status === id} onClick={() => setStatus(id)}>
              {label}{data && <span className="cnt">{num(countFor(id))}</span>}
            </button>
          ))}
        </div>
        <div className="group" style={{ marginLeft: 'auto', alignItems: 'flex-end' }}>
          {!isAgent && (
            <div className="fld">
              <label htmlFor="f-agent">Agent</label>
              <select id="f-agent" className="select" value={agentId} onChange={(e) => setAgentId(e.target.value)}>
                <option value="">All agents</option>
                {agents.map((a) => <option key={a.id} value={a.id}>{fullName(a)}</option>)}
              </select>
            </div>
          )}
          <div className="fld">
            <label htmlFor="f-store">Store</label>
            <input id="f-store" type="search" className="input" placeholder="Search stores" value={store} onChange={(e) => setStore(e.target.value)} />
          </div>
          <div className="fld">
            <label htmlFor="f-from">From</label>
            <input id="f-from" type="date" className="input mono" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} />
          </div>
          <div className="fld">
            <label htmlFor="f-to">To</label>
            <input id="f-to" type="date" className="input mono" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} />
          </div>
        </div>
      </section>

      <section className="card" aria-label="Orders" style={{ padding: 16, opacity: loading && data ? 0.55 : 1 }}>
        {!data && loading ? (
          <p className="empty">Loading orders…</p>
        ) : orders.length === 0 ? (
          <p className="empty">{status === 'pending' && !store && !agentId && !from && !to ? 'No pending orders.' : 'No orders match these filters.'}</p>
        ) : (
          <div className="table-wrap">
            <table className="tbl orders" style={{ minWidth: 760 }}>
              <thead>
                <tr>
                  <th scope="col"><span className="sr-only">Expand</span></th>
                  <th scope="col">Order</th><th scope="col">Store</th><th scope="col">Agent</th><th scope="col">Ordered</th>
                  <th scope="col" className="num">Items</th><th scope="col" className="num">Total</th><th scope="col">Status</th>
                </tr>
              </thead>
              <tbody>
                {orders.map((o) => {
                  const isOpen = open === o.id;
                  return (
                    <Fragment key={o.id}>
                      <tr className="row-main" tabIndex={0} aria-expanded={isOpen} aria-controls={`ord-${o.id}`} onClick={() => toggle(o.id)} onKeyDown={(e) => rowKey(e, o.id)}>
                        <td><span className={'chev' + (isOpen ? ' open' : '')} aria-hidden="true">›</span></td>
                        <td className="ref">#{o.id}</td>
                        <td>{o.customer?.name}</td>
                        <td>{fullName(o.agent)}</td>
                        <td style={{ whiteSpace: 'nowrap' }}>{fmtDT(o.orderedAt)}</td>
                        <td className="num">{itemCount(o)}</td>
                        <td className="num">{peso(shownTotal(o))}</td>
                        <td><span className={'badge ' + (BADGE[o.status] || 'badge-none')}>{LABEL[o.status] || o.status}</span></td>
                      </tr>
                      {isOpen && (
                        <tr className="row-detail" id={`ord-${o.id}`}>
                          <td colSpan={8}>
                            <div className="detail-box">
                              <div className="detail-meta">
                                {o.notes && <span>Notes: {o.notes}</span>}
                                {o.status === 'delivered' && <span>Delivered {fmtDT(o.deliveredAt)}{o.deliveredBy ? ` by ${fullName(o.deliveredBy)}` : ''}</span>}
                                {o.status === 'cancelled' && <span>Cancelled{o.cancelledReason ? `: ${o.cancelledReason}` : ''}</span>}
                              </div>
                              <div className="table-wrap">
                                <table className="tbl" style={{ minWidth: 420 }}>
                                  <thead><tr>
                                    <th scope="col">Item</th><th scope="col" className="num">Qty</th><th scope="col" className="num">Retail price</th>
                                    {o.status === 'delivered' && <th scope="col" className="num">Delivered</th>}
                                    <th scope="col" className="num">Line total</th>
                                  </tr></thead>
                                  <tbody>
                                    {o.lines.map((l) => (
                                      <tr key={l.itemId}>
                                        <td>
                                          {l.name}
                                          {hasPacks(l.breakdown) && <span className="sub-note">Ordered: {partsWithMoney(l.breakdown, l.unit)}</span>}
                                          {o.status === 'delivered' && hasPacks(l.deliveredBreakdown) && <span className="sub-note">Delivered: {partsWithMoney(l.deliveredBreakdown, l.unit)}</span>}
                                        </td>
                                        <td className="num">{num(l.qty)}</td>
                                        <td className="num">{peso(l.unitPrice)}</td>
                                        {o.status === 'delivered' && <td className="num">{num(l.deliveredQty ?? l.qty)}</td>}
                                        <td className="num">{peso(o.status === 'delivered' ? deliveredLineTotal(l) : l.lineTotal)}</td>
                                      </tr>
                                    ))}
                                  </tbody>
                                </table>
                              </div>
                              <div className="row-actions">
                                <button type="button" className="btn btn-secondary btn-sm" onClick={() => setSlip(o)}>Print delivery slip</button>
                                {actions && o.status === 'pending' && (
                                  <>
                                    <button type="button" className="btn btn-primary btn-sm" onClick={() => setDialog({ kind: 'deliver', order: o })}>Deliver</button>
                                    <button type="button" className="btn btn-secondary btn-sm" onClick={() => setDialog({ kind: 'cancel', order: o })}>Cancel order</button>
                                  </>
                                )}
                              </div>
                            </div>
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {dialog?.kind === 'deliver' && (
        <DeliverDialog order={dialog.order} onClose={() => setDialog(null)}
          onDone={(o) => done(`Order #${o.id} delivered · ${peso(deliveredTotal(o))}`)} />
      )}
      {dialog?.kind === 'cancel' && (
        <CancelDialog order={dialog.order} onClose={() => setDialog(null)} onDone={(o) => done(`Order #${o.id} cancelled.`)} />
      )}
      {slip && <Slip order={slip} shop={settings?.shopName || 'Marbingo'} />}
    </>
  );
}
