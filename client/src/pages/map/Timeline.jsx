import { useEffect, useMemo, useRef, useState } from 'react';
import { agentName, fmtDur, fmtKm, fmtTime, travelDistance } from './util';

export function Summary({ track }) {
  const s = track?.summary;
  if (!s) return null;
  const items = [['Distance', fmtKm(s.distanceM)], ['Moving', fmtDur(s.movingS)], ['Stopped', fmtDur(s.stoppedS)], ['Stops', s.stops], ['Stores visited', s.storesVisited]];
  return (
    <section className="card mp-summary" aria-label="Day summary">
      <div className="mp-stats">
        {items.map(([k, v]) => <div key={k} className="margin-box"><span className="t">{k}</span><span className="v">{v}</span></div>)}
      </div>
      <p className="hint" style={{ margin: 0 }}>First seen {fmtTime(s.firstAt)} · last seen {fmtTime(s.lastAt)}</p>
    </section>
  );
}

export function Playback({ track, onPos }) {
  const pts = track?.points || [];
  const [i, setI] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState(60);
  const ref = useRef(0);
  ref.current = i;
  const times = useMemo(() => pts.map((p) => new Date(p.t).getTime()), [pts]);

  useEffect(() => { setI(0); setPlaying(false); }, [track]);
  useEffect(() => { onPos(pts[i] ? [pts[i].lat, pts[i].lng] : null); }, [i, pts, onPos]);
  useEffect(() => () => onPos(null), [onPos]);
  useEffect(() => {
    if (!playing) return undefined;
    const id = setInterval(() => {
      const target = times[ref.current] + 100 * speed;
      let j = ref.current;
      while (j < times.length - 1 && times[j] < target) j++;
      if (j <= ref.current) j = Math.min(ref.current + 1, times.length - 1);
      if (j >= times.length - 1) setPlaying(false);
      setI(j);
    }, 100);
    return () => clearInterval(id);
  }, [playing, speed, times]);

  if (pts.length < 2) return null;
  return (
    <section className="card mp-play" aria-label="Route playback">
      <div className="mp-row" style={{ justifyContent: 'space-between' }}>
        <h3 className="h2" style={{ fontSize: 16 }}>Play route</h3>
        <span className="mono" aria-live="off" style={{ fontSize: 14 }}>{fmtTime(pts[i].t)}</span>
      </div>
      <div className="mp-row">
        <button type="button" className="btn btn-secondary btn-sm" aria-pressed={playing} onClick={() => { if (!playing && i >= pts.length - 1) setI(0); setPlaying((p) => !p); }}>{playing ? 'Pause' : 'Play'}</button>
        <input type="range" min={0} max={pts.length - 1} value={i} aria-label="Route position" style={{ flex: 1, accentColor: 'var(--accent)' }} onChange={(e) => { setI(Number(e.target.value)); }} />
        <label className="sr-only" htmlFor="mp-speed">Playback speed</label>
        <select id="mp-speed" className="select" style={{ width: 'auto', minHeight: 36 }} value={speed} onChange={(e) => setSpeed(Number(e.target.value))}>
          {[[30, '×30'], [60, '×60'], [120, '×120'], [300, '×300']].map(([v, l]) => <option key={v} value={v}>{l}</option>)}
        </select>
      </div>
    </section>
  );
}

export function Timeline({ stops, track, onFocus }) {
  const rows = useMemo(() => {
    const out = [];
    stops.forEach((s, idx) => {
      if (idx > 0) {
        const p = stops[idx - 1];
        const mins = Math.max(0, Math.round((new Date(s.startedAt) - new Date(p.endedAt)) / 60000));
        out.push({ type: 'travel', key: 't' + s.id, from: [p.lat, p.lng], to: [s.lat, s.lng], m: travelDistance(track?.route, [p.lat, p.lng], [s.lat, s.lng]), mins });
      }
      out.push({ type: 'stop', key: 's' + s.id, s, n: idx + 1 });
    });
    return out;
  }, [stops, track]);

  if (!stops.length) return null;
  return (
    <section className="card mp-timeline" aria-label="Timeline">
      <h3 className="h2" style={{ fontSize: 16, marginBottom: 8 }}>Timeline</h3>
      <ol className="mp-list">
        {rows.map((r) => r.type === 'stop' ? (
          <li key={r.key}>
            <button type="button" className="mp-rowbtn" onClick={() => onFocus({ key: 'stop-' + r.s.id, pos: [r.s.lat, r.s.lng] })}>
              <span className={'mp-num' + (r.s.customer ? '' : ' amber')} aria-hidden="true">{r.n}</span>
              <span className="mp-rowmain">
                <strong>{r.s.customer ? r.s.customer.name : 'Unlisted place'}</strong>
                <span className="hint">{fmtTime(r.s.startedAt)} – {fmtTime(r.s.endedAt)} · {fmtDur(r.s.durationS)}</span>
              </span>
              <span className={'badge ' + (r.s.customer ? 'badge-in' : 'badge-low')}>{r.s.customer ? 'Store' : 'Unlisted'}</span>
            </button>
          </li>
        ) : (
          <li key={r.key}>
            <button type="button" className="mp-rowbtn travel" onClick={() => onFocus({ key: null, bounds: [r.from, r.to] })}>
              <span className="mp-line" aria-hidden="true" />
              <span className="hint">Travel · {r.m >= 1000 ? fmtKm(r.m) : Math.round(r.m) + ' m'} · {r.mins} min</span>
            </button>
          </li>
        ))}
      </ol>
    </section>
  );
}

export function VisitsHere({ stops }) {
  if (!stops.length) return null;
  const list = [...stops].reverse();
  return (
    <section className="card mp-timeline" aria-label="Visits here">
      <h3 className="h2" style={{ fontSize: 16, marginBottom: 8 }}>Visits here</h3>
      <ul className="mp-list">
        {list.map((s) => (
          <li key={s.id} className="mp-visit">
            <strong>{agentName(s.agent)}</strong>
            <span className="hint">{s.day} · {fmtTime(s.startedAt)} – {fmtTime(s.endedAt)} · {fmtDur(s.durationS)}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}
