import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import api, { apiError } from '../lib/api';
import { UNITS, unitPlural } from '../lib/format';
import '../styles/forms.css';

const BLANK = { name: '', desc: '', cat: '', sku: '', unit: 'pc', qty: '', reorder: '', cost: '', price: '' };
let rowSeq = 0;
const blankPack = () => ({ key: ++rowSeq, packTypeId: '', size: '', price: '' });
const pesoStr = (c) => (c == null ? '' : (c / 100).toFixed(2));
const peso2 = (n) => '₱' + Number(n).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export default function AddItem() {
  const { id } = useParams();
  const editing = id !== undefined;
  const [f, setF] = useState(BLANK);
  const [packs, setPacks] = useState([]);
  const [types, setTypes] = useState([]);
  const [loadErr, setLoadErr] = useState('');
  const [loaded, setLoaded] = useState(!editing);
  const [existingUrl, setExistingUrl] = useState('');
  const [warnings, setWarnings] = useState([]);
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
    api.get('/pack-types').then((r) => setTypes(Array.isArray(r.data) ? r.data : r.data.packTypes || [])).catch(() => {});
    return () => { if (urlRef.current) URL.revokeObjectURL(urlRef.current); };
  }, []);

  const fill = (it) => {
    setF({
      name: it.name || '', desc: it.description || '', cat: it.categoryId ? String(it.categoryId) : '', sku: it.sku || '',
      unit: it.unit || 'pc', qty: String(it.qtyOnHand ?? ''), reorder: it.reorderLevel ? String(it.reorderLevel) : '',
      cost: pesoStr(it.costPrice), price: pesoStr(it.sellingPrice)
    });
    setPacks((it.packs || []).map((p) => ({ key: ++rowSeq, packTypeId: String(p.packTypeId), size: String(p.size), price: pesoStr(p.price), name: p.name })));
    setExistingUrl(it.imageUrl || '');
  };
  useEffect(() => {
    if (!editing) return undefined;
    let live = true;
    setLoaded(false); setLoadErr('');
    api.get('/items/' + id)
      .then((r) => { if (live) { fill(r.data.item || r.data); setLoaded(true); } })
      .catch((e) => { if (live) setLoadErr(e.response?.status === 404 ? 'Item not found.' : apiError(e).message); });
    return () => { live = false; };
  }, [id, editing]);

  const setPack = (key, patch) => {
    setPacks((p) => p.map((r) => (r.key === key ? { ...r, ...patch } : r)));
    setSrvErr((p) => (p.fields.packErrors || p.fields.packs ? { ...p, fields: { ...p.fields, packErrors: undefined, packs: undefined } } : p));
  };
  const pickType = (key, typeId) => {
    const t = types.find((x) => String(x.id) === typeId);
    setPacks((p) => p.map((r) => {
      if (r.key !== key) return r;
      const old = types.find((x) => String(x.id) === r.packTypeId);
      const sizeIsDefault = r.size === '' || (old && String(old.defaultSize) === r.size);
      return { ...r, packTypeId: typeId, size: sizeIsDefault && t?.defaultSize ? String(t.defaultSize) : r.size };
    }));
  };

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
  const qtyErr = (!editing && tried && f.qty === '') ? 'Enter how many are in stock.' : fe.qty;
  const priceErr = (tried && f.price === '') ? 'Enter the selling price.' : fe.price;

  async function save(again) {
    setSaved(''); setWarnings([]);
    setSrvErr({ message: '', fields: {} });
    setTried(true);
    if (f.name.trim() === '' || (!editing && f.qty === '') || f.price === '') return;
    setSaving(true);
    const fd = new FormData();
    fd.append('name', f.name.trim());
    fd.append('description', f.desc);
    if (f.cat) fd.append('categoryId', f.cat);
    if (f.sku.trim()) fd.append('sku', f.sku.trim());
    fd.append('unit', f.unit);
    if (!editing) fd.append('qty', f.qty);
    if (f.reorder !== '') fd.append('reorder', f.reorder);
    if (f.cost !== '') fd.append('cost', f.cost);
    fd.append('price', f.price);
    const sent = []; // row indexes in the order they are sent, to map packErrors back to rows
    const payload = [];
    packs.forEach((r, i) => {
      if (r.packTypeId === '' && r.size === '' && r.price === '') return;
      sent.push(i);
      payload.push({ packTypeId: Number(r.packTypeId) || null, size: r.size === '' ? null : Number(r.size), price: r.price });
    });
    fd.append('packs', JSON.stringify(payload));
    if (file) fd.append('photo', file);
    try {
      const { data } = editing ? await api.put('/items/' + id, fd) : await api.post('/items', fd);
      setWarnings(data?.warnings || []);
      setSaved(editing ? 'Changes saved.' : `“${f.name.trim()}” was added to inventory with ${f.qty} ${unitPlural(f.unit)} in stock.`);
      setTried(false);
      if (editing && data?.item) { fill(data.item); clearPhoto(); }
      if (again) {
        setF(BLANK);
        setPacks([]);
        clearPhoto();
        setTimeout(() => nameRef.current?.focus(), 0);
      }
    } catch (err) {
      const x = apiError(err);
      // packErrors indexes refer to the rows we sent; translate them to row positions.
      if (Array.isArray(x.fields.packErrors)) x.fields = { ...x.fields, packErrors: x.fields.packErrors.map((e) => ({ ...e, index: e.index >= 0 ? sent[e.index] ?? e.index : e.index })) };
      setSrvErr(x);
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
  const packErr = (i) => (fe.packErrors || []).find((e) => e.index === i)?.message;
  const generalPackErr = (fe.packErrors || []).find((e) => e.index === -1)?.message || (!(fe.packErrors || []).length && fe.packs) || '';
  const retailC = f.price !== '' ? Number(f.price) : null;
  const unitLbl = unitPlural(f.unit);
  const typeName = (r) => types.find((t) => String(t.id) === r.packTypeId)?.name || r.name || '';
  const previewPacks = packs.filter((r) => r.packTypeId !== '' && Number(r.size) >= 2 && Number(r.price) > 0);
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
        <h1 className="page-title">{editing ? 'Edit item' : 'Add new item'}</h1>
        <p className="page-sub">Fields marked * are required.</p>
      </header>

      {loadErr && <div role="alert" className="banner-err">{loadErr} <Link to="/items">Back to items</Link></div>}
      {saved && (
        <div role="status" className="banner-ok">
          <span>{saved}{warnings.map((w) => <span key={w} className="sub-note" style={{ color: 'var(--warn)', fontWeight: 600 }}>{w}</span>)}</span>
          <button type="button" className="btn btn-ghost" onClick={() => setSaved('')}>Dismiss</button>
        </div>
      )}
      {srvErr.message && !Object.keys(srvErr.fields || {}).length && <div role="alert" className="banner-err">{srvErr.message}</div>}

      {editing && !loaded ? (loadErr ? null : <p className="empty">Loading item…</p>) : (
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
                <label htmlFor="qty">Quantity in stock{editing ? '' : ' *'}</label>
                <input id="qty" ref={qtyRef} type="number" min="0" step="1" inputMode="numeric" className={'input mono' + (qtyErr ? ' invalid' : '')} value={f.qty} onChange={set('qty')} placeholder="0" aria-invalid={!!qtyErr} readOnly={editing} aria-describedby={editing ? 'qty-hint' : undefined} />
                {editing && <span id="qty-hint" className="hint">Change stock with a restock or adjustment</span>}
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

          <fieldset className="card">
            <legend>Pack prices</legend>
            <p className="page-sub" style={{ margin: 0 }}>Optional. Sell bundles at a lower price; the shop picks the cheapest mix of packs and loose {unitLbl} automatically.</p>
            {generalPackErr && <span className="err" role="alert">{generalPackErr}</span>}
            <div className="pack-rows">
              {packs.map((r, i) => {
                const size = Number(r.size), price = Number(r.price);
                const ok = size >= 2 && price > 0;
                const per = ok ? price / size : null;
                const cheaper = per !== null && retailC !== null && retailC > 0 && per < retailC;
                const e = packErr(i);
                const nm = typeName(r) || 'pack';
                return (
                  <div className="pack-row" key={r.key} role="group" aria-label={`Pack ${i + 1}`}>
                    <div className="field">
                      <label htmlFor={`pk-t-${r.key}`}>Pack type</label>
                      <select id={`pk-t-${r.key}`} className={'select' + (e ? ' invalid' : '')} value={r.packTypeId} onChange={(ev) => pickType(r.key, ev.target.value)}>
                        <option value="">Choose a type</option>
                        {r.packTypeId !== '' && !types.some((t) => String(t.id) === r.packTypeId) && <option value={r.packTypeId}>{r.name}</option>}
                        {types.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
                      </select>
                    </div>
                    <div className="field">
                      <label htmlFor={`pk-s-${r.key}`}>Holds</label>
                      <span className="unit-suffix">
                        <input id={`pk-s-${r.key}`} type="number" min="2" step="1" inputMode="numeric" className={'input mono' + (e ? ' invalid' : '')} value={r.size} onChange={(ev) => setPack(r.key, { size: ev.target.value })} placeholder="50" />
                        <span aria-hidden="true">{unitLbl}</span>
                      </span>
                    </div>
                    <div className="field">
                      <label htmlFor={`pk-p-${r.key}`}>Price for the whole pack (₱)</label>
                      <input id={`pk-p-${r.key}`} type="number" min="0" step="0.01" inputMode="decimal" className={'input mono' + (e ? ' invalid' : '')} value={r.price} onChange={(ev) => setPack(r.key, { price: ev.target.value })} placeholder="0.00" />
                    </div>
                    <button type="button" className="btn btn-ghost btn-sm rm" style={{ color: 'var(--danger)' }} onClick={() => setPacks((p) => p.filter((x) => x.key !== r.key))} aria-label={`Remove pack ${i + 1}${r.packTypeId ? ' (' + nm + ')' : ''}`}>Remove</button>
                    <div className="pack-note" aria-live="polite">
                      {ok && retailC !== null && retailC > 0 && (cheaper
                        ? <span className="pack-hint">{peso2(per)} per {f.unit} · saves {Math.round((1 - per / retailC) * 100)}% vs retail</span>
                        : <span className="pack-hint warn">Not cheaper than buying loose</span>)}
                      {ok && (retailC === null || retailC <= 0) && <span className="pack-hint">{peso2(per)} per {f.unit}</span>}
                      {e && <span className="err" role="alert" style={{ display: 'block' }}>{e}</span>}
                    </div>
                  </div>
                );
              })}
            </div>
            <div><button type="button" className="btn btn-secondary" onClick={() => setPacks((p) => [...p, blankPack()])}>Add pack</button></div>
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
            <Link to={editing ? '/items' : '/'} className="btn btn-ghost">{editing ? 'Back to items' : 'Cancel'}</Link>
            {!editing && <button type="button" className="btn btn-secondary" disabled={saving} onClick={() => save(true)}>Save &amp; add another</button>}
            <button type="submit" className="btn btn-primary" disabled={saving}>{saving ? 'Saving…' : editing ? 'Save changes' : 'Save item'}</button>
          </div>
        </form>

        <aside className="side-col" aria-label="Preview">
          <span className="eyebrow">Preview</span>
          <div className="preview">
            {photoUrl || existingUrl ? (
              <img src={photoUrl || existingUrl} alt={f.name ? 'Photo of ' + f.name : 'Item photo'} />
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
              {previewPacks.length > 0 && (
                <div className="packs" aria-label="Pack prices">
                  {previewPacks.map((r) => <span key={r.key}>{typeName(r) || 'Pack'} ({Number(r.size)} {unitLbl}) {peso2(r.price)}</span>)}
                </div>
              )}
              <span className="stock">{(q === null ? '—' : q) + ' ' + unitPlural(f.unit) + ' on hand' + (f.sku ? ' · ' + f.sku : '')}</span>
            </div>
          </div>
        </aside>
      </div>
      )}
    </div>
  );
}
