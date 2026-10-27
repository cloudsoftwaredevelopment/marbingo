import { useCallback, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import api, { apiError } from '../lib/api';
import FilterPanel from './map/FilterPanel';
import MapView from './map/MapView';
import { Playback, Summary, Timeline, VisitsHere } from './map/Timeline';
import DeviceLog, { DeviceAlerts } from './map/DeviceLog';
import { agentName, fmtYmd, hasLoc, todayYmd } from './map/util';
import '../styles/forms.css';
import '../styles/map.css';

const list = (d, key) => (Array.isArray(d) ? d : d?.[key] || []);

export default function EcosystemMap() {
  const [sp] = useSearchParams();
  const [f, setF] = useState(() => ({
    agentId: sp.get('agentId') || '', date: todayYmd(), timeFrom: '', timeTo: '',
    customerId: sp.get('customerId') || '', point: null, radius: 300, minMinutes: 3
  }));
  const set = useCallback((p) => setF((x) => ({ ...x, ...p })), []);
  const [layers, setLayers] = useState({ stores: true, agents: true, route: true, stops: true });
  const [agents, setAgents] = useState([]);
  const [customers, setCustomers] = useState([]);
  const [unplaced, setUnplaced] = useState([]);
  const [track, setTrack] = useState(null);
  const [stops, setStops] = useState([]);
  const [deviceLog, setDeviceLog] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [toast, setToast] = useState('');
  const [armed, setArmed] = useState(null); // {kind:'point'} | {kind:'place', id, name}
  const [movingId, setMovingId] = useState(null);
  const [focus, setFocus] = useState(null);
  const [fit, setFit] = useState(null);
  const [playPos, setPlayPos] = useState(null);
  const [fitted, setFitted] = useState(false);

  // Live agents, every 30 s (paused while the tab is hidden).
  useEffect(() => {
    let live = true;
    const load = () => {
      if (document.hidden) return;
      api.get('/tracking/live').then((r) => { if (live) setAgents(list(r.data, 'agents')); }).catch((e) => { if (live) setError(apiError(e).message); });
    };
    load();
    const t = setInterval(load, 30000);
    document.addEventListener('visibilitychange', load);
    return () => { live = false; clearInterval(t); document.removeEventListener('visibilitychange', load); };
  }, []);

  const loadStores = useCallback(() => {
    api.get('/customers').then((r) => setCustomers(list(r.data, 'customers'))).catch((e) => setError(apiError(e).message));
    api.get('/customers', { params: { located: 0 } }).then((r) => setUnplaced(list(r.data, 'customers'))).catch(() => {});
  }, []);
  useEffect(loadStores, [loadStores]);

  const located = useMemo(() => customers.filter(hasLoc), [customers]);
  const agentIndex = useMemo(() => Object.fromEntries(agents.map((a, i) => [a.agent.id, i])), [agents]);
  const agent = agents.find((a) => String(a.agent.id) === String(f.agentId))?.agent;

  // Initial fit: the selected store from the URL, else every store.
  useEffect(() => {
    if (fitted || !customers.length) return;
    setFitted(true);
    const c = customers.find((x) => String(x.id) === String(f.customerId));
    if (c && hasLoc(c)) setFocus({ key: 'store-' + c.id, pos: [c.lat, c.lng] });
    else if (located.length) setFit({ bounds: located.map((x) => [x.lat, x.lng]) });
  }, [customers, located, fitted, f.customerId]);

  // Route + stops for the current filters.
  useEffect(() => {
    let live = true;
    setLoading(true);
    setError('');
    const time = { from: f.timeFrom || undefined, to: f.timeTo || undefined };
    const stopParams = { agentId: f.agentId || undefined, from: f.date, to: f.date, timeFrom: f.timeFrom || undefined, timeTo: f.timeTo || undefined, minMinutes: f.minMinutes };
    if (f.customerId) stopParams.customerId = f.customerId;
    else if (f.point) { stopParams.near = `${f.point.lat},${f.point.lng}`; stopParams.radius = f.radius; }
    const trackReq = f.agentId ? api.get('/tracking/track', { params: { agentId: f.agentId, date: f.date, ...time } }) : Promise.resolve({ data: null });
    Promise.all([trackReq, api.get('/tracking/stops', { params: stopParams })])
      .then(([t, s]) => {
        if (!live) return;
        setTrack(t.data);
        setStops(list(s.data, 'stops'));
        const r = t.data?.route;
        if (r && r.length) setFit({ bounds: r });
      })
      .catch((e) => { if (live) { setError(apiError(e).message); setTrack(null); setStops([]); } })
      .finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, [f.agentId, f.date, f.timeFrom, f.timeTo, f.customerId, f.point, f.radius, f.minMinutes]);

  // Device log for the selected agent-day; refreshed with the live list so open gaps keep growing.
  useEffect(() => {
    setDeviceLog(null);
    if (!f.agentId) return undefined;
    let live = true;
    const load = () => {
      if (document.hidden) return;
      api.get('/tracking/device-log', { params: { agentId: f.agentId, date: f.date } })
        .then((r) => { if (live) setDeviceLog(r.data); })
        .catch(() => { if (live) setDeviceLog(null); });
    };
    load();
    const t = f.date === todayYmd() ? setInterval(load, 60000) : null;
    return () => { live = false; if (t) clearInterval(t); };
  }, [f.agentId, f.date]);

  const flash = (m) => { setToast(m); setTimeout(() => setToast(''), 4000); };

  async function onMapClick(lat, lng) {
    const a = armed;
    setArmed(null);
    if (!a) return;
    if (a.kind === 'point') { set({ point: { lat, lng }, customerId: '' }); return; }
    try {
      await api.put(`/customers/${a.id}/location`, { lat, lng });
      flash(`${a.name} placed on the map.`);
      loadStores();
      setFocus({ key: 'store-' + a.id, pos: [lat, lng] });
    } catch (e) { setError(apiError(e).message); }
  }

  const onMoved = useCallback(async (c, marker) => {
    const { lat, lng } = marker.getLatLng();
    try {
      await api.put(`/customers/${c.id}/location`, { lat, lng });
      flash(`${c.name} pin moved.`);
      setMovingId(null);
      loadStores();
    } catch (e) {
      marker.setLatLng([c.lat, c.lng]);
      setError(apiError(e).message);
    }
  }, [loadStores]);

  const toggleMove = useCallback((id) => setMovingId((m) => (m === id ? null : id)), []);
  const doFocus = (x) => setFocus({ ...x, n: Date.now() });
  const clearPoint = () => set({ point: null });
  const toggleLayer = (k) => setLayers((l) => ({ ...l, [k]: !l[k] }));

  const single = !!f.agentId;
  const hasFilter = !!(f.customerId || f.point);
  const empty = !loading && !error && single && !stops.length && !(track?.points?.length);
  const mapStores = f.customerId ? located : located;

  return (
    <div className="page-col mp-page">
      <header className="page-head">
        <p className="eyebrow">Field sales</p>
        <h1 className="page-title">Ecosystem Map</h1>
      </header>
      {toast && <div role="status" className="banner-ok"><span>{toast}</span></div>}
      {error && <div role="alert" className="banner-err">{error}</div>}
      {armed && (
        <div role="status" className="banner-ok">
          <span>{armed.kind === 'point' ? 'Click the map to set the point.' : `Click the map to place ${armed.name}.`}</span>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => setArmed(null)}>Cancel</button>
        </div>
      )}

      <div className="mp-layout">
        <div className="mp-panel">
          <FilterPanel f={f} set={set} agents={agents} stores={located} layers={layers} toggleLayer={toggleLayer} armed={armed} setArmed={setArmed} onClearPoint={clearPoint} />
          <div aria-live="polite" className="mp-status">
            {loading && <p className="hint">Loading…</p>}
            {empty && <p className="empty card">No GPS data for {agentName(agent) || 'this agent'} on {fmtYmd(f.date)}.</p>}
            {!loading && !single && !stops.length && !error && <p className="hint">No stops found for {fmtYmd(f.date)} with these filters.</p>}
            {single && track && track.snapped === false && <p className="hint">Showing raw GPS trail (road snapping unavailable)</p>}
          </div>
          {!single && <DeviceAlerts agents={agents} onPick={(id) => set({ agentId: String(id) })} />}
          {single && track && <Summary track={track} />}
          {single && <DeviceLog log={deviceLog} />}
          {single && track && <Playback track={track} onPos={setPlayPos} />}
          {single && <Timeline stops={stops} track={track} onFocus={doFocus} />}
          {!single && hasFilter && <VisitsHere stops={stops} />}
          {unplaced.length > 0 && (
            <section className="card mp-unplaced" aria-label="Unplaced stores">
              <h3 className="h2" style={{ fontSize: 16, marginBottom: 4 }}>Unplaced stores</h3>
              <p className="hint" style={{ margin: '0 0 8px' }}>{unplaced.length} without a location.</p>
              <ul className="mp-list">
                {unplaced.map((c) => (
                  <li key={c.id} className="mp-row" style={{ justifyContent: 'space-between' }}>
                    <span>{c.name}</span>
                    <button type="button" className="btn btn-secondary btn-sm" aria-pressed={armed?.id === c.id} onClick={() => setArmed({ kind: 'place', id: c.id, name: c.name })}>Place on map</button>
                  </li>
                ))}
              </ul>
            </section>
          )}
        </div>
        <div className="mp-mapbox">
          <MapView stores={mapStores} agents={agents} selectedAgent={f.agentId} track={single ? track : null} stops={stops}
            layers={layers} armed={!!armed} onMapClick={onMapClick} point={f.point} radius={f.radius} movingId={movingId}
            onMoveRequest={toggleMove} onMoved={onMoved} focus={focus} fit={fit} play={single ? playPos : null} agentIndex={agentIndex} />
        </div>
      </div>
    </div>
  );
}
