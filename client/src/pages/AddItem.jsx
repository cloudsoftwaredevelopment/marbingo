import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import api, { apiError } from '../lib/api';
import { UNITS, unitPlural } from '../lib/format';
import '../styles/forms.css';

const BLANK = { name: '', desc: '', cat: '', sku: '', unit: 'pc', qty: '', reorder: '', cost: '', price: '' };
const peso2 = (n) => '₱' + Number(n).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export default function AddItem() {
  const [f, setF] = useState(BLANK);
  const [file, setFile] = useState(null);
  const [photoUrl, setPhotoUrl] = useState('');
  const [cats, setCats] = useState([]);
  const [tried, setTried] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState('');
  const [srvErr, setSrvErr] = useState({ message: '', fields: {} });
  const [over, setOver] = useState(false);
  const nameRef = useRef(null);
  const qtyRef = useRef(null);
  const fileRef = useRef(null);
  const urlRef = useRef('');

  useEffect(() => {
    api.get('/categories').then((r) => setCats(r.data)).catch(() => {});
    return () => { if (urlRef.current) URL.revokeObjectURL(urlRef.current); };
  }, []);

  const set = (k) => (e) => {
    setF((p) => ({ ...p, [k]: e.target.value }));
    setSrvErr((p) => (p.fields[k] ? { ...p, fields: { ...p.fields, [k]: undefined } } : p));
  };

  function setPhoto(fl) {
    if (!fl) return;
    if (urlRef.current) URL.revokeObjectURL(urlRef.current);
    urlRef.current = URL.createObjectURL(fl);
    setFile(fl);
    setPhotoUrl(urlRef.current);
  }
  function clearPhoto() {
    if (urlRef.current) URL.revokeObjectURL(urlRef.current);
    urlRef.current = '';
    setFile(null);
    setPhotoUrl('');
    if (fileRef.current) fileRef.current.value = '';
  }

  const fe = srvErr.fields || {};
  const nameErr = (tried && f.name.trim() === '') ? 'Enter the item name.' : fe.name;
  const qtyErr = (tried && f.qty === '') ? 'Enter how many are in stock.' : fe.qty;
  const priceErr = (tried && f.price === '') ? 'Enter the selling price.' : fe.price;

  async function save(again) {
    setSaved('');
    setSrvErr({ message: '', fields: {} });
    setTried(true);
    if (f.name.trim() === '' || f.qty === '' || f.price === '') return;
    setSaving(true);
    const fd = new FormData();
    fd.append('name', f.name.trim());
    fd.append('description', f.desc);
    if (f.cat) fd.append('categoryId', f.cat);
    if (f.sku.trim()) fd.append('sku', f.sku.trim());
    fd.append('unit', f.unit);
    fd.append('qty', f.qty);
    if (f.reorder !== '') fd.append('reorder', f.reorder);
    if (f.cost !== '') fd.append('cost', f.cost);
    fd.append('price', f.price);
    if (file) fd.append('photo', file);
    try {
      await api.post('/items', fd);
      setSaved(`“${f.name.trim()}” was added to inventory with ${f.qty} ${unitPlural(f.unit)} in stock.`);
      setTried(false);
      if (again) {
        setF(BLANK);
        clearPhoto();
        setTimeout(() => nameRef.current?.focus(), 0);
      }
    } catch (err) {
      setSrvErr(apiError(err));
    } finally {
      setSaving(false);
    }
  }

  const q = f.qty === '' ? null : Number(f.qty);
  const r = f.reorder === '' ? 0 : Number(f.reorder);
  const [badgeCls, badgeTxt] = q === null ? ['badge-none', 'Stock not set'] : q <= 0 ? ['badge-out', 'Out of stock'] : q <= r ? ['badge-low', 'Low stock'] : ['badge-in', 'In stock'];
  let margin = '—';
  if (f.price !== '' && f.cost !== '') {
    const p = Number(f.price), m = p - Number(f.cost);
    margin = peso2(m) + ' · ' + (p > 0 ? Math.round((m / p) * 100) : 0) + '%';
  }
  const catName = cats.find((c) => String(c.id) === String(f.cat))?.name;

  const onSkuKey = (e) => {
    if (e.key === 'Enter') { e.preventDefault(); qtyRef.current?.focus(); }
  };
  const onDzKey = (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); fileRef.current?.click(); }
  };
  const onDrop = (e) => {
    e.preventDefault();
    setOver(false);
    const fl = e.dataTransfer?.files?.[0];
    if (fl && /^image\/(png|jpe?g|webp)$/.test(fl.type)) setPhoto(fl);
  };

  return (
    <div className="page-col">
      <header className="page-head">
        <p className="eyebrow">Inventory</p>
        <h1 className="page-title">Add new item</h1>
        <p className="page-sub">Fields marked * are required.</p>
      </header>

      {saved && (
        <div role="status" className="banner-ok">
          <span>{saved}</span>
          <button type="button" className="btn btn-ghost" onClick={() => setSaved('')}>Dismiss</button>
        </div>
      )}
      {srvErr.message && !Object.keys(srvErr.fields || {}).length && <div role="alert" className="banner-err">{srvErr.message}</div>}

      <div className="split">
        <form className="main-col" onSubmit={(e) => { e.preventDefault(); save(false); }} noValidate>
          <fieldset className="card">
            <legend>Item details</legend>
            <div className="field">
              <label htmlFor="name">Item name *</label>
              <input id="name" ref={nameRef} type="text" className={'input' + (nameErr ? ' invalid' : '')} value={f.name} onChange={set('name')} placeholder="e.g. Jasmine rice, 1 kg" aria-invalid={!!nameErr} />
              {nameErr && <span className="err">{nameErr}</span>}
            </div>
            <div className="field">
              <label htmlFor="desc">Description</label>
              <textarea id="desc" rows="3" className="textarea" value={f.desc} onChange={set('desc')} placeholder="Brand, pack size, variant, storage notes" />
            </div>
            <div className="grid-fit" style={{ '--min': '220px' }}>
              <div className="field">
                <label htmlFor="cat">Category</label>
                <select id="cat" className="select" value={f.cat} onChange={set('cat')}>
                  <option value="">Choose a category</option>
                  {cats.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
                </select>
              </div>
              <div className="field">
                <label htmlFor="sku">SKU / barcode</label>
                <input id="sku" type="text" className={'input mono' + (fe.sku ? ' invalid' : '')} value={f.sku} onChange={set('sku')} onKeyDown={onSkuKey} placeholder="Scan or type" autoComplete="off" aria-invalid={!!fe.sku} />
                {fe.sku && <span className="err">{fe.sku}</span>}
              </div>
            </div>
          </fieldset>

          <fieldset className="card">
            <legend>Stock &amp; pricing</legend>
            <div className="grid-fit" style={{ '--min': '160px' }}>
              <div className="field">
                <label htmlFor="qty">Quantity in stock *</label>
                <input id="qty" ref={qtyRef} type="number" min="0" step="1" inputMode="numeric" className={'input mono' + (qtyErr ? ' invalid' : '')} value={f.qty} onChange={set('qty')} placeholder="0" aria-invalid={!!qtyErr} />
                {qtyErr && <span className="err">{qtyErr}</span>}
              </div>
              <div className="field">
                <label htmlFor="unit">Unit</label>
                <select id="unit" className="select" value={f.unit} onChange={set('unit')}>
                  {UNITS.map((u) => <option key={u.id} value={u.id}>{u.label}</option>)}
                </select>
              </div>
              <div className="field">
                <label htmlFor="reorder">Reorder level</label>
                <input id="reorder" type="number" min="0" step="1" inputMode="numeric" className={'input mono' + (fe.reorder ? ' invalid' : '')} value={f.reorder} onChange={set('reorder')} placeholder="0" aria-describedby="reorder-hint" />
                <span id="reorder-hint" className="hint">Flag as low stock at or below this.</span>
                {fe.reorder && <span className="err">{fe.reorder}</span>}
              </div>
            </div>
            <div className="grid-fit" style={{ '--min': '160px', alignItems: 'start' }}>
              <div className="field">
                <label htmlFor="cost">Cost price (₱)</label>
                <input id="cost" type="number" min="0" step="0.01" inputMode="decimal" className={'input mono' + (fe.cost ? ' invalid' : '')} value={f.cost} onChange={set('cost')} placeholder="0.00" />
                {fe.cost && <span className="err">{fe.cost}</span>}
              </div>
              <div className="field">
                <label htmlFor="price">Selling price (₱) *</label>
                <input id="price" type="number" min="0" step="0.01" inputMode="decimal" className={'input mono' + (priceErr ? ' invalid' : '')} value={f.price} onChange={set('price')} placeholder="0.00" aria-invalid={!!priceErr} />
                {priceErr && <span className="err">{priceErr}</span>}
              </div>
              <div className="margin-box">
                <span className="t">Margin per unit</span>
                <span className="v">{margin}</span>
              </div>
            </div>
          </fieldset>

          <fieldset className="card" style={{ gap: 14 }}>
            <legend>Photo</legend>
            <label
              htmlFor="photo" tabIndex={0} onKeyDown={onDzKey}
              className={'dropzone' + (over ? ' over' : '')}
              onDragOver={(e) => { e.preventDefault(); setOver(true); }}
              onDragEnter={(e) => { e.preventDefault(); setOver(true); }}
              onDragLeave={() => setOver(false)}
              onDrop={onDrop}
            >
              <svg aria-hidden="true" width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#1E5B43" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M12 16V4" /><path d="m7 9 5-5 5 5" /><path d="M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3" /></svg>
              <span className="dz-title">{file ? 'Replace photo · ' + file.name : 'Upload a photo'}</span>
              <span className="dz-sub">JPG, PNG or WEBP · square photos look best</span>
            </label>
            <input id="photo" ref={fileRef} type="file" className="file-hidden" tabIndex={-1} accept="image/png,image/jpeg,image/webp" onChange={(e) => setPhoto(e.target.files?.[0])} />
            {fe.photo && <span className="err">{fe.photo}</span>}
          </fieldset>

          <div className="actions">
            <Link to="/" className="btn btn-ghost">Cancel</Link>
            <button type="button" className="btn btn-secondary" disabled={saving} onClick={() => save(true)}>Save &amp; add another</button>
            <button type="submit" className="btn btn-primary" disabled={saving}>{saving ? 'Saving…' : 'Save item'}</button>
          </div>
        </form>

        <aside className="side-col" aria-label="Preview">
          <span className="eyebrow">Preview</span>
          <div className="preview">
            {photoUrl ? (
              <img src={photoUrl} alt={f.name ? 'Photo of ' + f.name : 'Item photo'} />
            ) : (
              <div className="ph">
                <svg aria-hidden="true" width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="4" width="18" height="16" rx="2" /><circle cx="9" cy="10" r="2" /><path d="m21 16-5-5-9 9" /></svg>
              </div>
            )}
            <div className="body">
              <span className="cat">{catName || 'No category'}</span>
              <span className="nm">{f.name || 'Item name'}</span>
              <span className="ds">{f.desc || 'Description shows here.'}</span>
              <div className="row">
                <span className="price">{f.price !== '' ? peso2(f.price) : '₱ —'}</span>
                <span className={'badge ' + badgeCls}>{badgeTxt}</span>
              </div>
              <span className="stock">{(q === null ? '—' : q) + ' ' + unitPlural(f.unit) + ' on hand' + (f.sku ? ' · ' + f.sku : '')}</span>
            </div>
          </div>
        </aside>
      </div>
    </div>
  );
}
