import { useCallback, useEffect, useRef, useState } from 'react';
import api, { apiError } from '../lib/api';
import { useAuth } from '../context/AuthContext';
import { peso, unitPlural } from '../lib/format';
import '../styles/forms.css';

// Tolerate a few response shapes for items.
const norm = (i) => ({
  id: i.id,
  name: i.name,
  sku: i.sku,
  unit: i.unit,
  price: i.price ?? i.sellingPrice ?? 0,
  stock: i.qtyOnHand ?? i.qty ?? 0,
  category: typeof i.category === 'string' ? i.category : i.category?.name || i.categoryName || ''
});
const listOf = (d) => (Array.isArray(d) ? d : d.items || []);
const timeFmt = (iso) => new Date(iso).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true });

export default function Pos() {
  const { user } = useAuth();
  const [q, setQ] = useState('');
  const [results, setResults] = useState([]);
  const [cart, setCart] = useState([]);
  const [busy, setBusy] = useState(false);
  const [ok, setOk] = useState('');
  const [err, setErr] = useState('');
  const [mine, setMine] = useState({ sales: [], summary: { count: 0, total: 0 } });
  const scanRef = useRef(null);
  const seq = useRef(0);

  const loadMine = useCallback(() => {
    api.get('/sales/mine').then((r) => setMine({ sales: r.data.sales || [], summary: r.data.summary || { count: 0, total: 0 } })).catch(() => {});
  }, []);
  useEffect(() => { loadMine(); scanRef.current?.focus(); }, [loadMine]);

  useEffect(() => {
    const term = q.trim();
    if (!term) { setResults([]); return undefined; }
    const my = ++seq.current;
    const t = setTimeout(() => {
      api.get('/items', { params: { q: term } }).then((r) => {
        if (my === seq.current) setResults(listOf(r.data).map(norm));
      }).catch(() => {});
    }, 200);
    return () => clearTimeout(t);
  }, [q]);

  function add(item) {
    setErr(''); setOk('');
    if (item.stock <= 0) return;
    setCart((c) => {
      const ex = c.find((l) => l.id === item.id);
      if (ex) return c.map((l) => (l.id === item.id ? { ...l, stock: item.stock, qty: Math.min(l.qty + 1, item.stock) } : l));
      return [...c, { ...item, qty: 1 }];
    });
    setQ(''); setResults([]);
    scanRef.current?.focus();
  }

  async function onEnter(e) {
    if (e.key !== 'Enter') return;
    e.preventDefault();
    const term = q.trim();
    if (!term) return;
    if (/^\d+$/.test(term)) {
      try {
        const { data } = await api.get('/items/by-sku/' + encodeURIComponent(term));
        add(norm(data.item || data));
        return;
      } catch (er) {
        setErr(er.response?.status === 404 ? `No item with SKU ${term}.` : apiError(er).message);
        return;
      }
    }
    const first = results.find((r) => r.stock > 0);
    if (first) add(first);
  }

  const setQty = (id, v) => setCart((c) => c.map((l) => {
    if (l.id !== id) return l;
    const n = Math.max(1, Math.min(l.stock, Math.floor(Number(v)) || 1));
    return { ...l, qty: n };
  }));
  const remove = (id) => setCart((c) => c.filter((l) => l.id !== id));
  const clear = () => { setCart([]); setErr(''); scanRef.current?.focus(); };

  const count = cart.reduce((s, l) => s + l.qty, 0);
  const total = cart.reduce((s, l) => s + l.qty * l.price, 0);

  async function complete() {
    setBusy(true); setErr(''); setOk('');
    try {
      const { data } = await api.post('/sales', { lines: cart.map((l) => ({ itemId: l.id, qty: l.qty })) });
      const sale = data.sale || data;
      setOk(`Sale #${sale.id} recorded · ${peso(sale.total ?? total)}`);
      setCart([]);
      loadMine();
      scanRef.current?.focus();
    } catch (er) {
      setErr(apiError(er).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="page-col">
      <header className="page-head">
        <p className="eyebrow">Sales</p>
        <h1 className="page-title">Record sale</h1>
        <p className="page-sub">{user?.role === 'AGENT' ? 'Field sale' : 'Counter sale'}</p>
      </header>

      {ok && <div role="status" className="banner-ok"><span>{ok}</span><button type="button" className="btn btn-ghost" onClick={() => setOk('')}>Dismiss</button></div>}
      {err && <div role="alert" className="banner-err">{err}</div>}

      <div className="split">
        <div style={{ flex: '2 1 480px', minWidth: 0, display: 'flex', flexDirection: 'column', gap: 20 }}>
          <div className="card" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            <div className="field">
              <label htmlFor="scan">Scan barcode or search item</label>
              <input id="scan" ref={scanRef} type="text" className="input mono" value={q} onChange={(e) => setQ(e.target.value)} onKeyDown={onEnter} autoFocus autoComplete="off" placeholder="Scan or type" />
            </div>
            {results.length > 0 && (
              <ul className="results" aria-label="Search results">
                {results.map((i) => (
                  <li key={i.id}>
                    <button type="button" className="result-btn" disabled={i.stock <= 0} onClick={() => add(i)}>
                      <span><strong>{i.name}</strong><br /><span className="meta">{i.category || 'No category'}</span></span>
                      <span style={{ textAlign: 'right' }}>
                        <span className="mono">{peso(i.price)}</span><br />
                        <span className="meta">{i.stock > 0 ? `${i.stock} ${unitPlural(i.unit)} left` : 'Out of stock'}</span>
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>

          <div className="card">
            <h2 className="h2" style={{ marginBottom: 12 }}>Cart</h2>
            {cart.length === 0 ? <p className="page-sub" style={{ margin: 0 }}>No items yet. Scan a barcode or search above.</p> : (
              <div className="table-wrap">
                <table className="tbl">
                  <thead><tr><th>Item</th><th className="num">Unit price</th><th>Qty</th><th className="num">Total</th><th><span className="sr-only">Remove</span></th></tr></thead>
                  <tbody>
                    {cart.map((l) => (
                      <tr key={l.id}>
                        <td>{l.name}</td>
                        <td className="num">{peso(l.price)}</td>
                        <td>
                          <div className="stepper">
                            <button type="button" className="btn btn-secondary btn-icon" aria-label={`Decrease ${l.name}`} disabled={l.qty <= 1} onClick={() => setQty(l.id, l.qty - 1)}>−</button>
                            <input type="number" className="input mono" aria-label={`Quantity of ${l.name}`} min="1" max={l.stock} value={l.qty} onChange={(e) => setQty(l.id, e.target.value)} />
                            <button type="button" className="btn btn-secondary btn-icon" aria-label={`Increase ${l.name}`} disabled={l.qty >= l.stock} onClick={() => setQty(l.id, l.qty + 1)}>+</button>
                          </div>
                        </td>
                        <td className="num">{peso(l.qty * l.price)}</td>
                        <td><button type="button" className="btn btn-ghost btn-sm" onClick={() => remove(l.id)} aria-label={`Remove ${l.name}`}>Remove</button></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>

        <aside className="card" style={{ flex: '1 1 280px', minWidth: 0, display: 'flex', flexDirection: 'column', gap: 16 }} aria-label="Sale summary">
          <span className="eyebrow">Summary</span>
          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 15 }}><span>Items</span><span className="mono">{count}</span></div>
          <div><span className="label">Total</span><div className="big-num">{peso(total)}</div></div>
          <button type="button" className="btn btn-primary" disabled={cart.length === 0 || busy} onClick={complete}>{busy ? 'Saving…' : 'Complete sale'}</button>
          <button type="button" className="btn btn-ghost" onClick={clear} disabled={busy}>Clear</button>
        </aside>
      </div>

      <section className="card" aria-label="My sales today">
        <div style={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'space-between', gap: 8, marginBottom: 12 }}>
          <h2 className="h2">My sales today</h2>
          <span className="mono" style={{ color: 'var(--muted)', fontSize: 14 }}>{mine.summary.count} sales · {peso(mine.summary.total)}</span>
        </div>
        {mine.sales.length === 0 ? <p className="page-sub" style={{ margin: 0 }}>No sales recorded yet today.</p> : (
          <div className="table-wrap">
            <table className="tbl">
              <thead><tr><th>Time</th><th>Items</th><th className="num">Total</th></tr></thead>
              <tbody>
                {mine.sales.map((s) => (
                  <tr key={s.id}>
                    <td className="mono" style={{ whiteSpace: 'nowrap' }}>{timeFmt(s.soldAt)}</td>
                    <td>{(s.lines || []).map((l) => `${l.name} × ${l.qty}`).join(', ')}</td>
                    <td className="num">{peso(s.total)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
