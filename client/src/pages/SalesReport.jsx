import { useEffect, useState } from 'react';
import api, { apiError } from '../lib/api';
import { num, pesoCompact, pesoWhole, plural, unitPlural } from '../lib/format';

const D = "'Bricolage Grotesque', system-ui, sans-serif";
const M = "'IBM Plex Mono', ui-monospace, monospace";
const TABS = [
  { id: 'today', label: 'Today' },
  { id: 'week', label: 'This week' },
  { id: 'month', label: 'This month' },
  { id: 'last', label: 'Last month' },
  { id: 'custom', label: 'Custom' }
];
// YYYY-MM-DD in the shop's timezone (Asia/Manila, UTC+8), regardless of the browser's zone.
const ymd = (d) => new Date(d.getTime() + 8 * 3600e3).toISOString().slice(0, 10);
const cardStyle = { background: '#FFFEFB', border: '1px solid #DDD8CC', borderRadius: 14 };
const h2Style = { margin: 0, fontFamily: D, fontWeight: 600, fontSize: 20 };
const GRID = '40px minmax(0, 2.4fr) minmax(0, 1fr) minmax(0, 1.8fr) minmax(0, 1.2fr) minmax(0, 1.6fr)';
const ITEM_GRID = GRID; // Sales by item has the extra "Sold as" column
const STORE_GRID = '40px minmax(0, 2.4fr) minmax(0, 1fr) minmax(0, 1.2fr) minmax(0, 1.6fr)';
// "12 Sacks · 340 kg loose"; "—" when the item has no breakdown data.
const soldAs = (r) => {
  const bits = (r.soldAs || []).map((x) => `${x.count} ${plural(x.name, x.count)}`);
  if (r.loose > 0) bits.push(`${num(r.loose)}${r.unit ? ' ' + unitPlural(r.unit) : ''} loose`);
  return bits.length ? bits.join(' · ') : '—';
};

function Skeleton({ h }) {
  return <div aria-hidden="true" style={{ height: h, borderRadius: 10, background: '#ECE8DF' }} />;
}

export default function SalesReport() {
  const [preset, setPreset] = useState('month');
  const [from, setFrom] = useState(() => { const d = new Date(); d.setDate(d.getDate() - 30); return ymd(d); });
  const [to, setTo] = useState(() => ymd(new Date()));
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState('');

  useEffect(() => {
    let live = true;
    const params = { preset };
    if (preset === 'custom') { params.from = from; params.to = to; }
    setLoading(true);
    setFailure('');
    api.get('/reports/sales', { params })
      .then((r) => { if (live) setData(r.data); })
      .catch((e) => { if (live) setFailure(apiError(e).message); })
      .finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, [preset, from, to]);

  const bad = data?.error;
  const t = data?.totals || { gross: 0, transactions: 0, units: 0, average: 0 };
  const buckets = bad ? [] : data?.buckets || [];
  const items = bad ? [] : data?.items || [];
  const vmax = Math.max(1, ...buckets.filter((b) => !b.upcoming).map((b) => b.value));
  const stores = bad ? null : Array.isArray(data?.stores) ? data.stores : null;
  const maxStore = stores && stores.length ? Math.max(...stores.map((s) => s.revenue), 1) : 1;
  const storeTotal = stores ? stores.reduce((a, s) => a + s.revenue, 0) : 0;
  const maxRev = items.length ? items[0].revenue : 0;
  const first = data === null && loading;
  const dim = loading && data ? 0.55 : 1;

  const kpis = bad || !data
    ? [['Gross sales', pesoWhole(0)], ['Transactions', num(0)], ['Units sold', num(0)], ['Average sale', pesoWhole(0)]]
    : [['Gross sales', pesoWhole(t.gross)], ['Transactions', num(t.transactions)], ['Units sold', num(t.units)], ['Average sale', pesoWhole(t.average)]];

  return (
    <>
      <header style={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'space-between', alignItems: 'flex-end', gap: 16 }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <p className="eyebrow">Reports</p>
          <h1 className="page-title">Sales report</h1>
        </div>
        <p style={{ margin: 0, fontFamily: M, fontSize: 14, color: '#5E5A52' }}>{bad ? data.error : data?.range?.label || ''}</p>
      </header>

      <section aria-label="Date filter" style={{ ...cardStyle, display: 'flex', flexWrap: 'wrap', alignItems: 'flex-end', gap: 16, padding: 12 }}>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          {TABS.map((tab) => (
            <button key={tab.id} type="button" className="pill" aria-pressed={tab.id === preset} onClick={() => setPreset(tab.id)}>{tab.label}</button>
          ))}
        </div>
        {preset === 'custom' && (
          <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'flex-end', gap: 12, marginLeft: 'auto' }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <label htmlFor="from" style={{ fontSize: 12, fontWeight: 600, color: '#5E5A52' }}>From</label>
              <input id="from" type="date" className="input" style={{ font: `500 14px ${M}`, width: 'auto' }} value={from} onChange={(e) => setFrom(e.target.value)} />
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <label htmlFor="to" style={{ fontSize: 12, fontWeight: 600, color: '#5E5A52' }}>To</label>
              <input id="to" type="date" className="input" style={{ font: `500 14px ${M}`, width: 'auto' }} value={to} onChange={(e) => setTo(e.target.value)} />
            </div>
          </div>
        )}
      </section>

      {failure && <div className="banner-err" role="alert">{failure}</div>}

      {first ? (
        <>
          <Skeleton h={92} />
          <Skeleton h={340} />
          <Skeleton h={300} />
        </>
      ) : (
        <div style={{ display: 'contents' }}>
          <section aria-label="Totals" aria-live="polite" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(200px, 100%), 1fr))', gap: 16, opacity: dim }}>
            {kpis.map(([label, value]) => (
              <div key={label} style={{ ...cardStyle, padding: 20, display: 'flex', flexDirection: 'column', gap: 10 }}>
                <span style={{ fontSize: 13, fontWeight: 600, color: '#5E5A52' }}>{label}</span>
                <span style={{ fontFamily: D, fontWeight: 700, fontSize: 32, lineHeight: 1, letterSpacing: '-0.01em' }}>{value}</span>
              </div>
            ))}
          </section>

          <section aria-labelledby="chart-h" style={{ ...cardStyle, padding: 24, display: 'flex', flexDirection: 'column', gap: 20, opacity: dim }}>
            <div style={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'space-between', alignItems: 'baseline', gap: 8 }}>
              <h2 id="chart-h" style={h2Style}>Sales over time</h2>
              <span style={{ fontSize: 13, color: '#5E5A52' }}>{data?.unit || ''}</span>
            </div>
            <div style={{ overflowX: 'auto' }}>
              <div style={{ height: 270, minWidth: 420, display: 'flex', alignItems: 'flex-end', gap: 12, borderBottom: '1px solid #DDD8CC' }}>
                {buckets.map((b, i) => {
                  const up = b.upcoming;
                  const h = up ? 28 : Math.max(4, Math.round((b.value / vmax) * 200));
                  return (
                    <div key={i} title={up ? `${b.label}: not yet` : `${b.label}: ${pesoWhole(b.value)}`} style={{ flex: '1 1 0', minWidth: 36, height: '100%', display: 'flex', flexDirection: 'column', justifyContent: 'flex-end', alignItems: 'center', gap: 8 }}>
                      <span style={{ font: `500 12px ${M}`, color: '#5E5A52', whiteSpace: 'nowrap' }}>{up ? '—' : pesoCompact(b.value)}</span>
                      <div style={{ width: '100%', maxWidth: 64, height: h, background: up ? 'transparent' : '#1E5B43', border: up ? '1.5px dashed #A39C8C' : 0, borderBottom: 0, borderRadius: '6px 6px 0 0', boxSizing: 'border-box' }} />
                    </div>
                  );
                })}
              </div>
              <div style={{ minWidth: 420, display: 'flex', gap: 12, paddingTop: 10 }}>
                {buckets.map((b, i) => (
                  <span key={i} style={{ flex: '1 1 0', minWidth: 36, textAlign: 'center', fontSize: 12, color: '#5E5A52', whiteSpace: 'nowrap' }}>{b.label}</span>
                ))}
              </div>
            </div>
          </section>

          <section aria-labelledby="items-h" style={{ ...cardStyle, padding: 24, display: 'flex', flexDirection: 'column', gap: 16, opacity: dim }}>
            <div style={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'space-between', alignItems: 'baseline', gap: 8 }}>
              <h2 id="items-h" style={h2Style}>Sales by item</h2>
              <span style={{ fontSize: 13, color: '#5E5A52' }}>Sorted by revenue</span>
            </div>
            <div style={{ overflowX: 'auto' }}>
              <div style={{ minWidth: 820, display: 'flex', flexDirection: 'column' }}>
                <div style={{ display: 'grid', gridTemplateColumns: ITEM_GRID, gap: 16, padding: '0 0 10px', borderBottom: '1px solid #DDD8CC', fontSize: 12, fontWeight: 600, letterSpacing: '0.06em', textTransform: 'uppercase', color: '#5E5A52' }}>
                  <span>#</span><span>Item</span><span style={{ textAlign: 'right' }}>Units sold</span><span>Sold as</span><span style={{ textAlign: 'right' }}>Revenue</span><span>Share of sales</span>
                </div>
                {items.map((r, i) => {
                  const share = t.gross ? (r.revenue / t.gross) * 100 : 0;
                  return (
                    <div key={r.id} style={{ display: 'grid', gridTemplateColumns: ITEM_GRID, gap: 16, alignItems: 'center', padding: '12px 0', borderBottom: '1px solid #ECE8DF' }}>
                      <span style={{ font: `500 13px ${M}`, color: '#5E5A52' }}>{i + 1}</span>
                      <div style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0 }}>
                        <span style={{ fontSize: 15, fontWeight: 500 }}>{r.name}</span>
                        <span style={{ fontSize: 12, color: '#5E5A52' }}>{r.category}</span>
                      </div>
                      <span style={{ textAlign: 'right', font: `500 14px ${M}` }}>{num(r.units)}</span>
                      <span style={{ fontSize: 13, color: '#5E5A52', overflowWrap: 'anywhere' }}>{soldAs(r)}</span>
                      <span style={{ textAlign: 'right', font: `500 14px ${M}` }}>{pesoWhole(r.revenue)}</span>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                        <div aria-hidden="true" style={{ flex: '1 1 auto', height: 8, background: '#ECE8DF', borderRadius: 4, overflow: 'hidden' }}>
                          <div style={{ width: `${(maxRev ? (r.revenue / maxRev) * 100 : 0).toFixed(1)}%`, height: '100%', background: '#1E5B43', borderRadius: 4 }} />
                        </div>
                        <span style={{ width: 48, textAlign: 'right', font: `500 13px ${M}`, color: '#5E5A52' }}>{share.toFixed(1)}%</span>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          </section>
          {stores && (
            <section aria-labelledby="stores-h" style={{ ...cardStyle, padding: 24, display: 'flex', flexDirection: 'column', gap: 16, opacity: dim }}>
              <div style={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'space-between', alignItems: 'baseline', gap: 8 }}>
                <h2 id="stores-h" style={h2Style}>Sales by store</h2>
                <span style={{ fontSize: 13, color: '#5E5A52' }}>Field sales, sorted by revenue</span>
              </div>
              {stores.length === 0 ? (
                <p style={{ margin: 0, fontSize: 15, color: '#5E5A52' }}>No field sales in this period.</p>
              ) : (
                <div style={{ overflowX: 'auto' }}>
                  <div style={{ minWidth: 640, display: 'flex', flexDirection: 'column' }}>
                    <div style={{ display: 'grid', gridTemplateColumns: STORE_GRID, gap: 16, padding: '0 0 10px', borderBottom: '1px solid #DDD8CC', fontSize: 12, fontWeight: 600, letterSpacing: '0.06em', textTransform: 'uppercase', color: '#5E5A52' }}>
                      <span>#</span><span>Store</span><span style={{ textAlign: 'right' }}>Orders</span><span style={{ textAlign: 'right' }}>Revenue</span><span>Share of field sales</span>
                    </div>
                    {stores.map((r, i) => (
                      <div key={r.id} style={{ display: 'grid', gridTemplateColumns: STORE_GRID, gap: 16, alignItems: 'center', padding: '12px 0', borderBottom: '1px solid #ECE8DF' }}>
                        <span style={{ font: `500 13px ${M}`, color: '#5E5A52' }}>{i + 1}</span>
                        <span style={{ fontSize: 15, fontWeight: 500, minWidth: 0, overflowWrap: 'anywhere' }}>{r.name}</span>
                        <span style={{ textAlign: 'right', font: `500 14px ${M}` }}>{num(r.orders)}</span>
                        <span style={{ textAlign: 'right', font: `500 14px ${M}` }}>{pesoWhole(r.revenue)}</span>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                          <div aria-hidden="true" style={{ flex: '1 1 auto', height: 8, background: '#ECE8DF', borderRadius: 4, overflow: 'hidden' }}>
                            <div style={{ width: `${((r.revenue / maxStore) * 100).toFixed(1)}%`, height: '100%', background: '#1E5B43', borderRadius: 4 }} />
                          </div>
                          <span style={{ width: 48, textAlign: 'right', font: `500 13px ${M}`, color: '#5E5A52' }}>{(storeTotal ? (r.revenue / storeTotal) * 100 : 0).toFixed(1)}%</span>
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </section>
          )}
        </div>
      )}
    </>
  );
}
