import { useCallback, useEffect, useState } from 'react';
import api, { apiError } from '../lib/api';
import { useAuth } from '../context/AuthContext';
import { ROLE_LABELS } from '../lib/format';
import '../styles/forms.css';

const ROLES = ['MANAGER', 'INVENTORY', 'CASHIER', 'AGENT'];
const TABS = [['users', 'Users'], ['categories', 'Categories'], ['packs', 'Pack types'], ['shop', 'Shop'], ['tracking', 'Tracking']];

function Users() {
  const { user: me } = useAuth();
  const [users, setUsers] = useState([]);
  const [err, setErr] = useState('');
  const [fe, setFe] = useState({});
  const [ok, setOk] = useState('');
  const blank = { firstName: '', lastName: '', email: '', role: 'CASHIER', password: '' };
  const [f, setF] = useState(blank);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    api.get('/users').then((r) => setUsers(Array.isArray(r.data) ? r.data : r.data.users || [])).catch((e) => setErr(apiError(e).message));
  }, []);
  useEffect(load, [load]);

  const set = (k) => (e) => setF((p) => ({ ...p, [k]: e.target.value }));

  async function add(e) {
    e.preventDefault();
    setErr(''); setFe({}); setOk('');
    if (f.password.length < 8) { setFe({ password: 'Temporary password needs at least 8 characters.' }); return; }
    setBusy(true);
    try {
      await api.post('/users', f);
      setF(blank); setOk('User added.'); load();
    } catch (er) {
      const x = apiError(er); setErr(x.message); setFe(x.fields);
    } finally { setBusy(false); }
  }

  async function update(u, patch) {
    setErr(''); setOk('');
    try {
      await api.put('/users/' + u.id, patch);
      load();
    } catch (er) { setErr(apiError(er).message); }
  }

  return (
    <>
      {err && <div role="alert" className="banner-err">{err}</div>}
      {ok && <div role="status" className="banner-ok"><span>{ok}</span></div>}
      <form className="card" onSubmit={add} noValidate style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <h2 className="h2">Add user</h2>
        <div className="grid-fit" style={{ '--min': '180px' }}>
          <div className="field"><label htmlFor="u-first">First name</label><input id="u-first" className={'input' + (fe.firstName ? ' invalid' : '')} value={f.firstName} onChange={set('firstName')} required />{fe.firstName && <span className="err">{fe.firstName}</span>}</div>
          <div className="field"><label htmlFor="u-last">Last name</label><input id="u-last" className={'input' + (fe.lastName ? ' invalid' : '')} value={f.lastName} onChange={set('lastName')} required />{fe.lastName && <span className="err">{fe.lastName}</span>}</div>
          <div className="field"><label htmlFor="u-email">Email</label><input id="u-email" type="email" className={'input' + (fe.email ? ' invalid' : '')} value={f.email} onChange={set('email')} required />{fe.email && <span className="err">{fe.email}</span>}</div>
          <div className="field"><label htmlFor="u-role">Role</label>
            <select id="u-role" className="select" value={f.role} onChange={set('role')}>{ROLES.map((r) => <option key={r} value={r}>{ROLE_LABELS[r]}</option>)}</select></div>
          <div className="field"><label htmlFor="u-pw">Temporary password</label><input id="u-pw" type="password" autoComplete="new-password" className={'input' + (fe.password ? ' invalid' : '')} value={f.password} onChange={set('password')} aria-describedby="u-pw-h" /><span id="u-pw-h" className="hint">At least 8 characters.</span>{fe.password && <span className="err">{fe.password}</span>}</div>
        </div>
        <div className="actions"><button type="submit" className="btn btn-primary" disabled={busy}>Add user</button></div>
      </form>

      <div className="card">
        <h2 className="h2" style={{ marginBottom: 12 }}>Users</h2>
        <div className="table-wrap">
          <table className="tbl">
            <thead><tr><th>Name</th><th>Email</th><th>Role</th><th>Employee ID</th><th>Active</th></tr></thead>
            <tbody>
              {users.map((u) => (
                <tr key={u.id}>
                  <td>{u.firstName} {u.lastName}</td>
                  <td>{u.email}</td>
                  <td>
                    <select className="select" style={{ minWidth: 150 }} aria-label={`Role for ${u.firstName} ${u.lastName}`} value={u.role} disabled={u.id === me?.id} onChange={(e) => update(u, { role: e.target.value })}>
                      {ROLES.map((r) => <option key={r} value={r}>{ROLE_LABELS[r]}</option>)}
                    </select>
                  </td>
                  <td className="mono">{u.employeeId}</td>
                  <td>
                    <button type="button" className={'btn btn-sm ' + (u.active ? 'btn-secondary' : 'btn-ghost')} style={u.active ? undefined : { border: '1px solid var(--input-border)' }} aria-pressed={!!u.active} disabled={u.id === me?.id} onClick={() => update(u, { active: !u.active })}>
                      {u.active ? 'Active' : 'Inactive'}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}

function Categories() {
  const [cats, setCats] = useState([]);
  const [name, setName] = useState('');
  const [editing, setEditing] = useState(null);
  const [editName, setEditName] = useState('');
  const [err, setErr] = useState('');
  const load = useCallback(() => { api.get('/categories').then((r) => setCats(r.data)).catch((e) => setErr(apiError(e).message)); }, []);
  useEffect(load, [load]);

  const run = async (fn) => { setErr(''); try { await fn(); load(); } catch (e) { setErr(apiError(e).message); } };

  const add = (e) => { e.preventDefault(); if (!name.trim()) return; run(async () => { await api.post('/categories', { name: name.trim() }); setName(''); }); };
  const rename = (e) => { e.preventDefault(); run(async () => { await api.put('/categories/' + editing, { name: editName.trim() }); setEditing(null); }); };
  const del = (c) => {
    if (window.confirm(`Delete “${c.name}”? Its items will stay in inventory without a category.`)) run(() => api.delete('/categories/' + c.id));
  };

  return (
    <>
      {err && <div role="alert" className="banner-err">{err}</div>}
      <form className="card inline-form" onSubmit={add}>
        <div className="field" style={{ flex: '1 1 240px' }}><label htmlFor="c-new">New category</label><input id="c-new" className="input" value={name} onChange={(e) => setName(e.target.value)} /></div>
        <button type="submit" className="btn btn-primary">Add category</button>
      </form>
      <div className="card">
        <h2 className="h2" style={{ marginBottom: 4 }}>Categories</h2>
        <p className="page-sub" style={{ margin: '0 0 12px' }}>Deleting a category keeps its items; they just have no category.</p>
        <div className="table-wrap">
          <table className="tbl">
            <thead><tr><th>Name</th><th className="num">Items</th><th><span className="sr-only">Actions</span></th></tr></thead>
            <tbody>
              {cats.map((c) => (
                <tr key={c.id}>
                  <td>
                    {editing === c.id ? (
                      <form className="row-actions" onSubmit={rename}>
                        <input className="input" aria-label={`New name for ${c.name}`} value={editName} onChange={(e) => setEditName(e.target.value)} autoFocus style={{ minWidth: 180 }} />
                        <button type="submit" className="btn btn-primary btn-sm">Save</button>
                        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setEditing(null)}>Cancel</button>
                      </form>
                    ) : c.name}
                  </td>
                  <td className="num">{c.itemCount ?? 0}</td>
                  <td>
                    {editing !== c.id && (
                      <div className="row-actions" style={{ justifyContent: 'flex-end' }}>
                        <button type="button" className="btn btn-secondary btn-sm" onClick={() => { setEditing(c.id); setEditName(c.name); }}>Rename</button>
                        <button type="button" className="btn btn-ghost btn-sm" style={{ color: 'var(--danger)' }} onClick={() => del(c)}>Delete</button>
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}

function PackTypes() {
  const [types, setTypes] = useState([]);
  const [name, setName] = useState('');
  const [size, setSize] = useState('');
  const [fe, setFe] = useState({});
  const [editing, setEditing] = useState(null);
  const [ed, setEd] = useState({ name: '', size: '' });
  const [err, setErr] = useState('');
  const load = useCallback(() => { api.get('/pack-types', { params: { all: 1 } }).then((r) => setTypes(Array.isArray(r.data) ? r.data : r.data.packTypes || [])).catch((e) => setErr(apiError(e).message)); }, []);
  useEffect(load, [load]);

  const run = async (fn) => {
    setErr(''); setFe({});
    try { await fn(); load(); return true; } catch (e) { const x = apiError(e); setErr(x.message); setFe(x.fields); return false; }
  };
  const sizeVal = (v) => (String(v).trim() === '' ? null : Number(v));

  const add = (e) => {
    e.preventDefault();
    if (!name.trim()) { setFe({ name: 'Enter a name.' }); return; }
    run(async () => { await api.post('/pack-types', { name: name.trim(), defaultSize: sizeVal(size) }); setName(''); setSize(''); });
  };
  const save = (e) => {
    e.preventDefault();
    run(async () => { await api.put('/pack-types/' + editing, { name: ed.name.trim(), defaultSize: sizeVal(ed.size) }); setEditing(null); });
  };
  const del = (t) => {
    if (window.confirm(`Delete the pack type “${t.name}”?`)) run(() => api.delete('/pack-types/' + t.id));
  };

  return (
    <>
      {err && <div role="alert" className="banner-err">{err}</div>}
      <form className="card inline-form" onSubmit={add} noValidate>
        <div className="field" style={{ flex: '1 1 200px' }}>
          <label htmlFor="pt-new">New pack type</label>
          <input id="pt-new" className={'input' + (fe.name ? ' invalid' : '')} value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Sack" aria-invalid={!!fe.name} />
          {fe.name && <span className="err">{fe.name}</span>}
        </div>
        <div className="field" style={{ flex: '0 1 180px' }}>
          <label htmlFor="pt-size">Default size (optional)</label>
          <input id="pt-size" type="number" min="2" step="1" inputMode="numeric" className={'input mono' + (fe.defaultSize ? ' invalid' : '')} value={size} onChange={(e) => setSize(e.target.value)} placeholder="e.g. 12" />
          {fe.defaultSize && <span className="err">{fe.defaultSize}</span>}
        </div>
        <button type="submit" className="btn btn-primary">Add pack type</button>
      </form>
      <div className="card">
        <h2 className="h2" style={{ marginBottom: 4 }}>Pack types</h2>
        <p className="page-sub" style={{ margin: '0 0 12px' }}>Bundles an item can be sold in at a lower price. A type in use can be deactivated but not deleted.</p>
        <div className="table-wrap">
          <table className="tbl" style={{ minWidth: 560 }}>
            <thead><tr><th>Name</th><th className="num">Default size</th><th className="num">Items</th><th>Active</th><th><span className="sr-only">Actions</span></th></tr></thead>
            <tbody>
              {types.map((t) => (
                <tr key={t.id}>
                  {editing === t.id ? (
                    <td colSpan={3}>
                      <form className="row-actions" onSubmit={save}>
                        <input className="input" aria-label={`Name for ${t.name}`} value={ed.name} onChange={(e) => setEd((p) => ({ ...p, name: e.target.value }))} autoFocus style={{ width: 170 }} />
                        <input className="input mono" type="number" min="2" step="1" aria-label={`Default size for ${t.name}`} placeholder="Size" value={ed.size} onChange={(e) => setEd((p) => ({ ...p, size: e.target.value }))} style={{ width: 100 }} />
                        <button type="submit" className="btn btn-primary btn-sm">Save</button>
                        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setEditing(null)}>Cancel</button>
                      </form>
                    </td>
                  ) : (
                    <>
                      <td>{t.name}</td>
                      <td className="num">{t.defaultSize ?? '—'}</td>
                      <td className="num">{t.itemCount ?? 0}</td>
                    </>
                  )}
                  <td>
                    <button type="button" className={'btn btn-sm ' + (t.active ? 'btn-secondary' : 'btn-ghost')} style={t.active ? undefined : { border: '1px solid var(--input-border)' }} aria-pressed={!!t.active} aria-label={`${t.name} is ${t.active ? 'active' : 'inactive'}`} onClick={() => run(() => api.put('/pack-types/' + t.id, { active: !t.active }))}>
                      {t.active ? 'Active' : 'Inactive'}
                    </button>
                  </td>
                  <td>
                    {editing !== t.id && (
                      <div className="row-actions" style={{ justifyContent: 'flex-end' }}>
                        <button type="button" className="btn btn-secondary btn-sm" onClick={() => { setEditing(t.id); setEd({ name: t.name, size: t.defaultSize ?? '' }); }}>Edit</button>
                        <button type="button" className="btn btn-ghost btn-sm" style={{ color: 'var(--danger)' }} onClick={() => del(t)}>Delete</button>
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}

function Shop() {
  const { settings, refreshSettings } = useAuth();
  const [name, setName] = useState(settings?.shopName || '');
  const [mark, setMark] = useState(settings?.shopMark || '');
  const [msg, setMsg] = useState({ text: '', ok: true });
  useEffect(() => { if (settings?.shopName) setName(settings.shopName); }, [settings?.shopName]);
  useEffect(() => { if (settings?.shopMark) setMark(settings.shopMark); }, [settings?.shopMark]);

  async function save(e) {
    e.preventDefault();
    setMsg({ text: '', ok: true });
    try {
      await api.put('/settings', { shopName: name.trim(), shopMark: mark.trim() });
      await refreshSettings();
      setMsg({ text: 'Saved.', ok: true });
    } catch (er) {
      const x = apiError(er);
      setMsg({ text: x.fields.shopName || x.fields.shopMark || x.message, ok: false });
    }
  }
  return (
    <form className="card" onSubmit={save} style={{ display: 'flex', flexDirection: 'column', gap: 16, maxWidth: 520 }}>
      <h2 className="h2">Shop</h2>
      <div className="field"><label htmlFor="shop">Shop name</label><input id="shop" className="input" value={name} onChange={(e) => { setName(e.target.value); setMsg({ text: '', ok: true }); }} required /></div>
      <div className="field">
        <label htmlFor="mark">Logo letters</label>
        <input id="mark" className="input mono" value={mark} maxLength={3} aria-describedby="mark-hint" style={{ maxWidth: 120, textTransform: 'uppercase' }} onChange={(e) => { setMark(e.target.value); setMsg({ text: '', ok: true }); }} />
        <span id="mark-hint" className="hint">Up to 3 letters for the logo square. Leave blank to use the shop name’s initials.</span>
      </div>
      <div className="actions">
        {msg.text && <span role="status" className={msg.ok ? 'status-ok' : 'status-err'}>{msg.text}</span>}
        <button type="submit" className="btn btn-primary">Save</button>
      </div>
    </form>
  );
}

const DAYS = [[1, 'Mon'], [2, 'Tue'], [3, 'Wed'], [4, 'Thu'], [5, 'Fri'], [6, 'Sat'], [7, 'Sun']];

function Tracking() {
  const [sch, setSch] = useState(null);
  const [msg, setMsg] = useState({ text: '', ok: true });
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    api.get('/settings/tracking-schedule').then((r) => setSch(r.data)).catch((e) => setMsg({ text: apiError(e).message, ok: false }));
  }, []);
  const edit = (p) => { setSch((s) => ({ ...s, ...p })); setMsg({ text: '', ok: true }); };
  const toggle = (d) => edit({ days: sch.days.includes(d) ? sch.days.filter((x) => x !== d) : [...sch.days, d].sort() });

  async function save(e) {
    e.preventDefault();
    setBusy(true);
    try {
      const { data } = await api.put('/settings/tracking-schedule', { days: sch.days, start: sch.start, end: sch.end });
      setSch(data);
      setMsg({ text: 'Saved.', ok: true });
    } catch (er) {
      const x = apiError(er);
      setMsg({ text: Object.values(x.fields)[0] || x.message, ok: false });
    } finally { setBusy(false); }
  }

  if (!sch) return <div className="card">{msg.text ? <div role="alert" className="banner-err">{msg.text}</div> : <p className="empty">Loading schedule…</p>}</div>;
  return (
    <form className="card" onSubmit={save} style={{ display: 'flex', flexDirection: 'column', gap: 16, maxWidth: 560 }}>
      <h2 className="h2">Work schedule</h2>
      <p className="page-sub" style={{ margin: 0 }}>Field agents' tablets share their location only during these hours. Location history is kept for 12 months.</p>
      <div role="group" aria-label="Working days" style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
        {DAYS.map(([d, l]) => <button key={d} type="button" className="pill" aria-pressed={sch.days.includes(d)} onClick={() => toggle(d)}>{l}</button>)}
      </div>
      <div className="grid-fit" style={{ '--min': '140px', maxWidth: 360 }}>
        <div className="field"><label htmlFor="ts-start">Start</label><input id="ts-start" type="time" className="input mono" value={sch.start} onChange={(e) => edit({ start: e.target.value })} required /></div>
        <div className="field"><label htmlFor="ts-end">End</label><input id="ts-end" type="time" className="input mono" value={sch.end} onChange={(e) => edit({ end: e.target.value })} required /></div>
      </div>
      <p className="hint" style={{ margin: 0 }}>Times are Asia/Manila.</p>
      <div className="actions">
        {msg.text && <span role="status" className={msg.ok ? 'status-ok' : 'status-err'}>{msg.text}</span>}
        <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? 'Saving…' : 'Save schedule'}</button>
      </div>
    </form>
  );
}

export default function Admin() {
  const [tab, setTab] = useState('users');
  return (
    <div className="page-col">
      <header className="page-head">
        <p className="eyebrow">Settings</p>
        <h1 className="page-title">Admin</h1>
      </header>
      <div className="tabs" role="group" aria-label="Admin sections">
        {TABS.map(([id, label]) => <button key={id} type="button" className="pill" aria-pressed={tab === id} onClick={() => setTab(id)}>{label}</button>)}
      </div>
      {tab === 'users' && <Users />}
      {tab === 'categories' && <Categories />}
      {tab === 'packs' && <PackTypes />}
      {tab === 'shop' && <Shop />}
      {tab === 'tracking' && <Tracking />}
    </div>
  );
}
