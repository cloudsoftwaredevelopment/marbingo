import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import api, { apiError } from '../lib/api';
import { useAuth } from '../context/AuthContext';
import { can } from '../lib/roles';
import { num } from '../lib/format';
import { useModal } from './Orders';
import { PeriodBadge, fullName, monthLabel } from '../components/TruckBits';
import '../styles/forms.css';
import '../styles/orders.css';
import '../styles/trucks.css';

function TruckDialog({ truck, onClose, onDone }) {
  const ref = useModal(onClose);
  const [f, setF] = useState({ name: truck?.name || '', plate: truck?.plate || '', driverName: truck?.driverName || '', agentId: truck?.agent?.id ? String(truck.agent.id) : '' });
  const [active, setActive] = useState(truck ? truck.active : true);
  const [agents, setAgents] = useState([]);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [fe, setFe] = useState({});
  const set = (k) => (e) => setF((p) => ({ ...p, [k]: e.target.value }));

  useEffect(() => {
    api.get('/users', { params: { role: 'AGENT' } })
      .then((r) => setAgents((Array.isArray(r.data) ? r.data : r.data.users || []).filter((u) => u.active !== false || u.id === truck?.agent?.id)))
      .catch((e) => setErr(apiError(e).message));
  }, [truck]);

  async function submit(e) {
    e.preventDefault();
    setBusy(true); setErr(''); setFe({});
    const body = { name: f.name.trim(), plate: f.plate.trim(), driverName: f.driverName.trim(), agentId: f.agentId ? Number(f.agentId) : null };
    try {
      if (truck) await api.put(`/trucks/${truck.id}`, { ...body, active });
      else await api.post('/trucks', body);
      onDone(truck ? 'Truck updated.' : 'Truck added.');
    } catch (e2) {
      const a = apiError(e2);
      setErr(a.message); setFe(a.fields); setBusy(false);
    }
  }

  return (
    <div className="modal-back">
      <form ref={ref} className="modal" role="dialog" aria-modal="true" aria-labelledby="tk-h" onSubmit={submit} noValidate>
        <h2 id="tk-h">{truck ? 'Edit truck' : 'Add truck'}</h2>
        <div className="field">
          <label htmlFor="tk-name">Name</label>
          <input id="tk-name" className={'input' + (fe.name ? ' invalid' : '')} value={f.name} onChange={set('name')} data-autofocus placeholder="Truck 1" />
          {fe.name && <span className="err">{fe.name}</span>}
        </div>
        <div className="field"><label htmlFor="tk-plate">Plate number</label><input id="tk-plate" className="input" value={f.plate} onChange={set('plate')} /></div>
        <div className="field"><label htmlFor="tk-driver">Driver</label><input id="tk-driver" className="input" value={f.driverName} onChange={set('driverName')} /></div>
        <div className="field">
          <label htmlFor="tk-agent">Truck agent</label>
          <select id="tk-agent" className={'select' + (fe.agentId ? ' invalid' : '')} value={f.agentId} onChange={set('agentId')}>
            <option value="">No agent</option>
            {agents.map((a) => <option key={a.id} value={a.id}>{fullName(a)}</option>)}
          </select>
          {fe.agentId && <span className="err">{fe.agentId}</span>}
        </div>
        {truck && (
          <label style={{ display: 'flex', alignItems: 'center', gap: 10, minHeight: 44, fontSize: 15, fontWeight: 500 }}>
            <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} style={{ width: 20, height: 20 }} />
            Active
          </label>
        )}
        {err && <div role="alert" className="banner-err">{err}</div>}
        <div className="actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Close</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? 'Saving…' : 'Save'}</button>
        </div>
      </form>
    </div>
  );
}

export default function Trucks() {
  const { user } = useAuth();
  const manager = can(user, 'truckApprove');
  const [trucks, setTrucks] = useState(null);
  const [failure, setFailure] = useState('');
  const [ok, setOk] = useState('');
  const [dialog, setDialog] = useState(null); // { truck } (truck null = add)

  const load = useCallback(() => api.get('/trucks')
    .then((r) => { setTrucks(r.data.trucks || []); setFailure(''); })
    .catch((e) => { setFailure(apiError(e).message); setTrucks((x) => x || []); }), []);
  useEffect(() => { load(); }, [load]);

  return (
    <div className="page-col">
      <header className="page-head" style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-end', justifyContent: 'space-between', gap: 16 }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <p className="eyebrow">Mobile warehouses</p>
          <h1 className="page-title">Trucks</h1>
        </div>
        {manager && <button type="button" className="btn btn-primary" onClick={() => setDialog({ truck: null })}>Add truck</button>}
      </header>

      {ok && <div role="status" className="banner-ok"><span>{ok}</span><button type="button" className="btn btn-ghost" onClick={() => setOk('')}>Dismiss</button></div>}
      {failure && <div role="alert" className="banner-err">{failure}</div>}

      <section className="card" aria-label="Trucks" style={{ padding: 16 }}>
        {trucks === null ? <p className="empty">Loading trucks…</p> : trucks.length === 0 ? <p className="empty">No trucks yet.</p> : (
          <div className="table-wrap">
            <table className="tbl" style={{ minWidth: 820 }}>
              <thead>
                <tr>
                  <th scope="col">Truck</th><th scope="col">Driver</th><th scope="col">Agent</th><th scope="col">Period</th>
                  <th scope="col" className="num">Units on board</th><th scope="col"><span className="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                {trucks.map((t) => (
                  <tr key={t.id}>
                    <td>
                      <Link to={`/trucks/${t.id}`}><strong style={{ fontWeight: 600 }}>{t.name}</strong></Link>
                      <span className="sub">{t.plate || 'No plate'}{!t.active && ' · Inactive'}</span>
                    </td>
                    <td>{t.driverName || <span className="muted">—</span>}</td>
                    <td>{t.agent ? fullName(t.agent) : <span className="muted">—</span>}</td>
                    <td>
                      {t.currentPeriod ? <>{monthLabel(t.currentPeriod.month)} <PeriodBadge status={t.currentPeriod.status} /></> : <span className="muted">No period</span>}
                      {t.pendingTransfers > 0 && <span className="sub">{t.pendingTransfers} pending transfer{t.pendingTransfers === 1 ? '' : 's'}</span>}
                    </td>
                    <td className="num">{num(t.unitsOnBoard)}</td>
                    <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                      {manager && <button type="button" className="btn btn-ghost btn-sm" onClick={() => setDialog({ truck: t })} aria-label={`Edit ${t.name}`}>Edit</button>}{' '}
                      <Link to={`/trucks/${t.id}`} className="btn btn-secondary btn-sm">Open</Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {dialog && <TruckDialog truck={dialog.truck} onClose={() => setDialog(null)} onDone={(m) => { setDialog(null); setOk(m); load(); }} />}
    </div>
  );
}
