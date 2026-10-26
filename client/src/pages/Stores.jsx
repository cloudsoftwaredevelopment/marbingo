import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import api, { apiError } from '../lib/api';
import { num, peso, pesoWhole, orderTotal } from '../lib/format';
import '../styles/forms.css';
import '../styles/orders.css';

const BADGE = { pending: 'badge-low', delivered: 'badge-in', cancelled: 'badge-out' };
const dFmt = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'Asia/Manila' });
const BLANK = { name: '', ownerName: '', phone: '', address: '', notes: '' };

function StoreFields({ f, set, fe, prefix }) {
  return (
    <div className="grid-fit" style={{ '--min': '200px' }}>
      <div className="field">
        <label htmlFor={prefix + 'name'}>Store name *</label>
        <input id={prefix + 'name'} className={'input' + (fe.name ? ' invalid' : '')} value={f.name} onChange={set('name')} aria-invalid={!!fe.name} />
        {fe.name && <span className="err">{fe.name}</span>}
      </div>
      <div className="field">
        <label htmlFor={prefix + 'owner'}>Owner</label>
        <input id={prefix + 'owner'} className="input" value={f.ownerName} onChange={set('ownerName')} />
      </div>
      <div className="field">
        <label htmlFor={prefix + 'phone'}>Phone</label>
        <input id={prefix + 'phone'} type="tel" className="input mono" value={f.phone} onChange={set('phone')} />
      </div>
      <div className="field">
        <label htmlFor={prefix + 'address'}>Address</label>
        <input id={prefix + 'address'} className="input" value={f.address} onChange={set('address')} />
      </div>
      <div className="field" style={{ gridColumn: '1 / -1' }}>
        <label htmlFor={prefix + 'notes'}>Notes</label>
        <textarea id={prefix + 'notes'} rows="2" className="textarea" value={f.notes} onChange={set('notes')} />
      </div>
    </div>
  );
}

const LocChip = ({ c }) => (c.lat != null && c.lng != null
  ? <span className="badge badge-in">On map</span>
  : <span className="badge badge-none">No location</span>);

const toForm = (c) => ({ name: c.name || '', ownerName: c.ownerName || '', phone: c.phone || '', address: c.address || '', notes: c.notes || '' });

function Detail({ id, onChanged }) {
  const [d, setD] = useState(null);
  const [f, setF] = useState(BLANK);
  const [active, setActive] = useState(true);
  const [fe, setFe] = useState({});
  const [err, setErr] = useState('');
  const [ok, setOk] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let live = true;
    setD(null); setErr(''); setOk(''); setFe({});
    api.get('/customers/' + id)
      .then((r) => { if (live) { setD(r.data); setF(toForm(r.data.customer)); setActive(r.data.customer.active); } })
      .catch((e) => { if (live) setErr(apiError(e).message); });
    return () => { live = false; };
  }, [id]);

  const set = (k) => (e) => setF((p) => ({ ...p, [k]: e.target.value }));

  async function save(e) {
    e.preventDefault();
    setErr(''); setOk(''); setFe({});
    if (!f.name.trim()) { setFe({ name: 'Enter the store name.' }); return; }
    setBusy(true);
    try {
      const { data } = await api.put('/customers/' + id, { ...f, name: f.name.trim(), active });
      setD((p) => ({ ...p, customer: data.customer }));
      setActive(data.customer.active);
      setOk('Store saved.');
      onChanged();
    } catch (e2) {
      const x = apiError(e2); setErr(x.message); setFe(x.fields);
    } finally { setBusy(false); }
  }

  if (!d) return <div className="card">{err ? <div role="alert" className="banner-err">{err}</div> : <p className="empty">Loading store…</p>}</div>;
  const t = d.totals || {};
  return (
    <div className="card" style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
      <h2 className="h2">{d.customer.name}</h2>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
        <LocChip c={d.customer} />
        {d.customer.lat != null && <Link to={`/map?customerId=${d.customer.id}`}>View on map</Link>}
      </div>
      <div className="grid-fit" style={{ '--min': '140px' }}>
        {[['Orders', num(t.orderCount)], ['Pending', num(t.pendingCount)], ['Total delivered', pesoWhole(t.totalDelivered)]].map(([k, v]) => (
          <div key={k} className="margin-box"><span className="t">{k}</span><span className="v">{v}</span></div>
        ))}
      </div>
      <form onSubmit={save} noValidate style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <StoreFields f={f} set={set} fe={fe} prefix="e-" />
        <label style={{ display: 'flex', alignItems: 'center', gap: 10, minHeight: 44, fontSize: 15, fontWeight: 500 }}>
          <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} style={{ width: 20, height: 20 }} />
          Active (inactive stores are hidden from agents' lists)
        </label>
        {err && <div role="alert" className="banner-err">{err}</div>}
        {ok && <div role="status" className="banner-ok"><span>{ok}</span></div>}
        <div className="actions"><button type="submit" className="btn btn-primary" disabled={busy}>{busy ? 'Saving…' : 'Save changes'}</button></div>
      </form>
      <h3 className="h2" style={{ fontSize: 17 }}>Orders</h3>
      {d.orders.length === 0 ? <p className="empty" style={{ padding: 8 }}>No orders from this store yet.</p> : (
        <div className="table-wrap">
          <table className="tbl stores" style={{ minWidth: 420 }}>
            <thead><tr><th scope="col">Order</th><th scope="col">Date</th><th scope="col">Status</th><th scope="col" className="num">Total</th></tr></thead>
            <tbody>
              {d.orders.map((o) => (
                <tr key={o.id}>
                  <td className="ref">#{o.id}</td>
                  <td>{dFmt.format(new Date(o.orderedAt))}</td>
                  <td><span className={'badge ' + (BADGE[o.status] || 'badge-none')}>{o.status[0].toUpperCase() + o.status.slice(1)}</span></td>
                  <td className="num">{peso(orderTotal(o))}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

export default function Stores() {
  const [q, setQ] = useState('');
  const [list, setList] = useState(null);
  const [failure, setFailure] = useState('');
  const [sel, setSel] = useState(null);
  const [adding, setAdding] = useState(false);
  const [f, setF] = useState(BLANK);
  const [fe, setFe] = useState({});
  const [err, setErr] = useState('');
  const [ok, setOk] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    return api.get('/customers', { params: { q: q.trim() || undefined } })
      .then((r) => { setList(Array.isArray(r.data) ? r.data : r.data.customers || []); setFailure(''); })
      .catch((e) => setFailure(apiError(e).message));
  }, [q]);

  useEffect(() => { const t = setTimeout(load, 250); return () => clearTimeout(t); }, [load]);

  const set = (k) => (e) => setF((p) => ({ ...p, [k]: e.target.value }));

  async function add(e) {
    e.preventDefault();
    setErr(''); setFe({}); setOk('');
    if (!f.name.trim()) { setFe({ name: 'Enter the store name.' }); return; }
    setBusy(true);
    try {
      const { data } = await api.post('/customers', { ...f, name: f.name.trim(), clientUuid: crypto.randomUUID() });
      setOk(`“${data.customer.name}” was added.`);
      setF(BLANK); setAdding(false); setSel(data.customer.id);
      load();
    } catch (e2) {
      const x = apiError(e2); setErr(x.message); setFe(x.fields);
    } finally { setBusy(false); }
  }

  return (
    <>
      <header style={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'space-between', alignItems: 'flex-end', gap: 16 }}>
        <div className="page-head">
          <p className="eyebrow">Field sales</p>
          <h1 className="page-title">Stores</h1>
        </div>
        <button type="button" className="btn btn-primary" aria-expanded={adding} onClick={() => setAdding((v) => !v)}>{adding ? 'Close form' : 'Add store'}</button>
      </header>

      {ok && <div role="status" className="banner-ok"><span>{ok}</span><button type="button" className="btn btn-ghost" onClick={() => setOk('')}>Dismiss</button></div>}
      {failure && <div role="alert" className="banner-err">{failure}</div>}

      {adding && (
        <form className="card" onSubmit={add} noValidate>
          <h2 className="h2">Add store</h2>
          <StoreFields f={f} set={set} fe={fe} prefix="n-" />
          {err && <div role="alert" className="banner-err">{err}</div>}
          <div className="actions">
            <button type="button" className="btn btn-ghost" onClick={() => { setAdding(false); setErr(''); setFe({}); }}>Cancel</button>
            <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? 'Saving…' : 'Save store'}</button>
          </div>
        </form>
      )}

      <div className="split">
        <div className="main-col">
          <div className="field">
            <label htmlFor="st-q" className="sr-only">Search stores</label>
            <input id="st-q" type="search" className="input" placeholder="Search by name, owner, phone or address" value={q} onChange={(e) => setQ(e.target.value)} />
          </div>
          <div className="card" style={{ padding: 16 }}>
            {list === null ? <p className="empty">Loading stores…</p> : list.length === 0 ? (
              <p className="empty">{q ? 'No stores match your search.' : 'No stores yet. Add the first one.'}</p>
            ) : (
              <div className="table-wrap">
                <table className="tbl stores" style={{ minWidth: 520 }}>
                  <thead><tr>
                    <th scope="col">Store</th><th scope="col">Phone</th>
                    <th scope="col" className="num">Orders</th><th scope="col" className="num">Delivered</th>
                  </tr></thead>
                  <tbody>
                    {list.map((c) => (
                      <tr key={c.id} style={{ background: sel === c.id ? 'var(--accent-tint)' : undefined }}>
                        <td>
                          <button type="button" className="btn btn-ghost btn-sm" aria-current={sel === c.id} onClick={() => setSel(c.id)} style={{ padding: '0 8px', fontWeight: 600, textAlign: 'left', color: 'var(--accent)' }}>{c.name}</button>
                          <span style={{ marginLeft: 6 }}><LocChip c={c} /></span>
                          {!c.active && <span className="badge badge-none" style={{ marginLeft: 6 }}>Inactive</span>}
                          {(c.ownerName || c.address) && (
                            <span style={{ display: 'block', padding: '0 8px', fontSize: 13, color: 'var(--muted)' }}>
                              {[c.ownerName, c.address].filter(Boolean).join(' · ')}
                            </span>
                          )}
                        </td>
                        <td className="mono" style={{ whiteSpace: 'nowrap' }}>{c.phone || '—'}</td>
                        <td className="num">{num(c.orderCount)}</td>
                        <td className="num" style={{ whiteSpace: 'nowrap' }}>{pesoWhole(c.totalDelivered)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
        {sel && <div className="side-col" style={{ flex: '1 1 380px' }}><Detail id={sel} onChanged={load} /></div>}
      </div>
    </>
  );
}
