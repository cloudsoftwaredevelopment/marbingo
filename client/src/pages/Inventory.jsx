import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import api, { apiError } from '../lib/api';
import { num, packLabel, peso, unitPlural } from '../lib/format';
import '../styles/forms.css';
import '../styles/orders.css';

const listOf = (d) => (Array.isArray(d) ? d : d.items || []);

export default function Inventory() {
  const [q, setQ] = useState('');
  const [items, setItems] = useState(null);
  const [failure, setFailure] = useState('');
  const seq = useRef(0);

  useEffect(() => {
    const my = ++seq.current;
    const t = setTimeout(() => {
      api.get('/items', { params: q.trim() ? { q: q.trim() } : {} })
        .then((r) => { if (my === seq.current) { setItems(listOf(r.data)); setFailure(''); } })
        .catch((e) => { if (my === seq.current) { setFailure(apiError(e).message); setItems((x) => x || []); } });
    }, items === null ? 0 : 250);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q]);

  return (
    <div className="page-col">
      <header className="page-head" style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-end', justifyContent: 'space-between', gap: 16 }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <p className="eyebrow">Inventory</p>
          <h1 className="page-title">Items</h1>
        </div>
        <Link to="/items/new" className="btn btn-primary">Add item</Link>
      </header>

      {failure && <div role="alert" className="banner-err">{failure}</div>}

      <section className="card" aria-label="Items" style={{ padding: 16, display: 'flex', flexDirection: 'column', gap: 14 }}>
        <div className="field search-bar">
          <label htmlFor="inv-q">Search items</label>
          <input id="inv-q" type="search" className="input" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Name or SKU" autoComplete="off" />
        </div>
        {items === null ? <p className="empty">Loading items…</p> : items.length === 0 ? (
          <p className="empty">{q.trim() ? 'No items match your search.' : 'No items yet.'}</p>
        ) : (
          <div className="table-wrap">
            <table className="tbl" style={{ minWidth: 840 }}>
              <thead>
                <tr>
                  <th scope="col">Item</th><th scope="col" className="num">Warehouse</th><th scope="col" className="num">On trucks</th><th scope="col" className="num">Retail price</th>
                  <th scope="col">Packs</th><th scope="col"><span className="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                {items.map((i) => {
                  const low = i.qtyOnHand <= (i.reorderLevel || 0);
                  const cat = typeof i.category === 'string' ? i.category : i.category?.name;
                  return (
                    <tr key={i.id}>
                      <td>
                        <strong style={{ fontWeight: 600 }}>{i.name}</strong>
                        <span className="sub">{cat || 'No category'}{i.sku && <> · <span className="mono">{i.sku}</span></>}</span>
                      </td>
                      <td className="num" style={{ whiteSpace: 'nowrap' }}>
                        {num(i.qtyOnHand)} {unitPlural(i.unit)}{' '}
                        {low && <span className={'badge ' + (i.qtyOnHand <= 0 ? 'badge-out' : 'badge-low')} style={{ fontFamily: 'var(--font-sans)', marginLeft: 6 }}>{i.qtyOnHand <= 0 ? 'Out' : 'Low'}</span>}
                      </td>
                      <td className="num" style={{ whiteSpace: 'nowrap' }}>{num(i.onTruck)}</td>
                      <td className="num" style={{ whiteSpace: 'nowrap' }}>{peso(i.sellingPrice)} / {i.unit}</td>
                      <td>
                        {i.packs?.length
                          ? i.packs.map((p) => <span key={p.id ?? p.packTypeId} className="mono" style={{ display: 'block', fontSize: 13, whiteSpace: 'nowrap' }}>{packLabel(p, i.unit)}</span>)
                          : <span className="muted">—</span>}
                      </td>
                      <td style={{ textAlign: 'right' }}>
                        <Link to={`/items/${i.id}/edit`} className="btn btn-secondary btn-sm" aria-label={`Edit ${i.name}`}>Edit</Link>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
