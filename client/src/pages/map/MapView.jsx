import { useEffect, useMemo, useRef, useState } from 'react';
import { Circle, MapContainer, Marker, Polyline, Popup, TileLayer, Tooltip, useMap, useMapEvents } from 'react-leaflet';
import 'leaflet/dist/leaflet.css';
import api from '../../lib/api';
import { agentIcon, dotIcon, letterIcon, stopIcon, storeIcon } from './icons';
import { RING, agentName, fmtDay, fmtDur, fmtTime, relTime, storeInitials } from './util';

export const DEFAULT_CENTER = [10.7246, 122.557];

function Controller({ armed, onMapClick, focus, fit, registry }) {
  const map = useMap();
  useMapEvents({ click: (e) => { if (armed) onMapClick(e.latlng.lat, e.latlng.lng); } });
  useEffect(() => { map.getContainer().style.cursor = armed ? 'crosshair' : ''; }, [armed, map]);
  useEffect(() => {
    if (!fit) return;
    if (fit.bounds && fit.bounds.length > 1) map.fitBounds(fit.bounds, { padding: [40, 40], maxZoom: 17 });
    else if (fit.bounds && fit.bounds.length === 1) map.setView(fit.bounds[0], 16);
  }, [fit, map]);
  useEffect(() => {
    if (!focus) return undefined;
    if (focus.bounds) map.flyToBounds(focus.bounds, { padding: [60, 60], maxZoom: 17, duration: 0.6 });
    else map.flyTo(focus.pos, Math.max(map.getZoom(), 16), { duration: 0.6 });
    const t = setTimeout(() => { const m = registry.current[focus.key]; if (m) m.openPopup(); }, 700);
    return () => clearTimeout(t);
  }, [focus, map, registry]);
  return null;
}

function StorePopup({ c }) {
  const [v, setV] = useState(null);
  const [err, setErr] = useState(false);
  useEffect(() => {
    let live = true;
    api.get('/tracking/visits', { params: { customerId: c.id } }).then((r) => { if (live) setV(r.data); }).catch(() => { if (live) setErr(true); });
    return () => { live = false; };
  }, [c.id]);
  const list = Array.isArray(v) ? v : v?.visits || v?.items || [];
  const sum = v?.summary;
  return (
    <div className="mp-pop">
      <strong>{c.name}</strong>
      {c.ownerName && <span>Owner: {c.ownerName}</span>}
      {c.address && <span>{c.address}</span>}
      <span className="muted">Location: {c.locationSource === 'gps' ? 'from agent GPS' : c.locationSource === 'manual' ? 'placed manually' : 'unknown'}{c.locationAccuracy != null ? ` (±${Math.round(c.locationAccuracy)} m)` : ''}</span>
      <div className="mp-pop-sec">
        <b>Visits</b>
        {err ? <span className="muted">Could not load visits.</span> : !v ? <span className="muted">Loading…</span> : (
          <>
            <span>{sum?.visits ?? list.length} total · last {sum?.lastVisitAt ? fmtDay(sum.lastVisitAt) : 'never'} · {fmtDur(sum?.totalS || 0)} on site</span>
            {list.slice(0, 5).map((s) => (
              <span key={s.id} className="mp-pop-row">{fmtDay(s.startedAt)} {fmtTime(s.startedAt)} · {agentName(s.agent)} · {fmtDur(s.durationS)}</span>
            ))}
          </>
        )}
      </div>
    </div>
  );
}

function StoreMarker({ c, moving, onMoveRequest, onMoved, registry }) {
  const [open, setOpen] = useState(false);
  const pos = useMemo(() => [c.lat, c.lng], [c.lat, c.lng]);
  const icon = useMemo(() => storeIcon(storeInitials(c.name), moving), [c.name, moving]);
  const handlers = useMemo(() => ({
    popupopen: () => setOpen(true),
    popupclose: () => setOpen(false),
    dragend: (e) => onMoved(c, e.target)
  }), [c, onMoved]);
  return (
    <Marker position={pos} icon={icon} draggable={moving} eventHandlers={handlers} ref={(m) => { registry.current['store-' + c.id] = m; }} keyboard>
      <Tooltip direction="right">{c.name}</Tooltip>
      <Popup minWidth={230}>
        {open && <StorePopup c={c} />}
        <button type="button" className="btn btn-secondary btn-sm mp-pop-btn" onClick={onMoveRequest}>{moving ? 'Drag the pin, then done' : 'Move pin'}</button>
      </Popup>
    </Marker>
  );
}

function AgentMarker({ a, selected, registry }) {
  const pos = useMemo(() => [a.last.lat, a.last.lng], [a.last.lat, a.last.lng]);
  const icon = useMemo(() => agentIcon(((a.agent.firstName || '')[0] || '') + ((a.agent.lastName || '')[0] || ''), a.status), [a.agent, a.status]);
  const label = { tracking: 'Tracking', signal_lost: 'Signal lost', off_duty: 'Off duty' }[a.status] || a.status;
  return (
    <Marker position={pos} icon={icon} zIndexOffset={selected ? 1000 : 500} ref={(m) => { registry.current['agent-' + a.agent.id] = m; }}>
      <Tooltip direction="right">{agentName(a.agent)}</Tooltip>
      <Popup>
        <div className="mp-pop">
          <strong>{agentName(a.agent)}</strong>
          <span>{label}</span>
          <span>Last seen {relTime(a.last.recordedAt)} ({fmtTime(a.last.recordedAt)})</span>
          {a.last.speed != null && <span>Speed {(a.last.speed * 3.6).toFixed(0)} km/h</span>}
          {a.last.battery != null && <span>Battery {Math.round(a.last.battery)}%</span>}
          <span>Today: {a.todayStops ?? 0} stops · {((a.todayDistanceM || 0) / 1000).toFixed(1)} km</span>
        </div>
      </Popup>
    </Marker>
  );
}

function StopMarker({ s, n, ringColor, registry }) {
  const pos = useMemo(() => [s.lat, s.lng], [s.lat, s.lng]);
  const size = Math.round(Math.min(40, Math.max(24, 18 + Math.sqrt(s.durationS / 60) * 3.2)));
  const icon = useMemo(() => stopIcon(n, size, ringColor, !s.customer), [n, size, ringColor, s.customer]);
  return (
    <Marker position={pos} icon={icon} zIndexOffset={200} ref={(m) => { registry.current['stop-' + s.id] = m; }}>
      <Popup>
        <div className="mp-pop">
          <strong>{s.customer ? s.customer.name : 'Unlisted place'}</strong>
          <span>{agentName(s.agent)}</span>
          <span>{fmtTime(s.startedAt)} – {fmtTime(s.endedAt)}</span>
          <span>{fmtDur(s.durationS)}</span>
        </div>
      </Popup>
    </Marker>
  );
}

function RouteLine({ route }) {
  const segs = useMemo(() => {
    const N = Math.min(5, Math.max(1, Math.floor(route.length / 2)));
    const size = Math.ceil((route.length - 1) / N);
    const out = [];
    for (let i = 0; i < N; i++) {
      const part = route.slice(i * size, Math.min(route.length, (i + 1) * size + 1));
      if (part.length > 1) out.push({ part, o: 0.35 + (0.65 * (i + 1)) / N });
    }
    return out;
  }, [route]);
  return segs.map((s, i) => <Polyline key={i} positions={s.part} pathOptions={{ color: '#1E5B43', weight: 5, opacity: s.o, lineJoin: 'round' }} />);
}

export default function MapView(props) {
  const { stores, agents, selectedAgent, track, stops, layers, armed, onMapClick, point, radius, movingId, onMoveRequest, onMoved, focus, fit, play, agentIndex } = props;
  const registry = useRef({});
  const route = track?.route || [];
  const numbered = useMemo(() => {
    const cnt = {};
    return stops.map((s) => { const k = s.agent.id + '|' + s.day; cnt[k] = (cnt[k] || 0) + 1; return { s, n: cnt[k] }; });
  }, [stops]);
  const startIcon = useMemo(() => letterIcon('S', 'start'), []);
  const endIcon = useMemo(() => letterIcon('E', 'end'), []);
  const playIcon = useMemo(() => dotIcon(), []);
  const showAgents = layers.agents ? agents.filter((a) => a.last && (a.status !== 'off_duty' || String(a.agent.id) === String(selectedAgent))) : [];

  return (
    <MapContainer center={DEFAULT_CENTER} zoom={14} className="mp-map" aria-label="Map of store locations and field agent positions" scrollWheelZoom>
      <TileLayer url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png" attribution="&copy; OpenStreetMap contributors" maxZoom={19} />
      <Controller armed={armed} onMapClick={onMapClick} focus={focus} fit={fit} registry={registry} />
      {point && <Circle center={[point.lat, point.lng]} radius={radius} pathOptions={{ color: '#1E5B43', weight: 2, fillColor: '#1E5B43', fillOpacity: 0.12 }} />}
      {point && <Marker position={[point.lat, point.lng]} icon={letterIcon('+', 'pt')} interactive={false} />}
      {layers.route && route.length > 1 && <RouteLine route={route} />}
      {layers.route && route.length > 1 && <Marker position={route[0]} icon={startIcon} interactive={false} />}
      {layers.route && route.length > 1 && !(layers.agents && selectedAgent) && <Marker position={route[route.length - 1]} icon={endIcon} interactive={false} />}
      {layers.route && play && <Marker position={play} icon={playIcon} interactive={false} zIndexOffset={900} />}
      {layers.stores && stores.map((c) => (
        <StoreMarker key={c.id} c={c} moving={movingId === c.id} onMoveRequest={() => onMoveRequest(c.id)} onMoved={onMoved} registry={registry} />
      ))}
      {layers.stops && numbered.map(({ s, n }) => (
        <StopMarker key={s.id} s={s} n={n} ringColor={selectedAgent ? null : RING[(agentIndex[s.agent.id] || 0) % RING.length]} registry={registry} />
      ))}
      {showAgents.map((a) => <AgentMarker key={a.agent.id} a={a} selected={String(a.agent.id) === String(selectedAgent)} registry={registry} />)}
    </MapContainer>
  );
}
