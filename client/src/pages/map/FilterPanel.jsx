import { agentName } from './util';

const RADII = [[100, '100 m'], [300, '300 m'], [500, '500 m'], [1000, '1 km']];
const MINS = [1, 3, 5, 10, 15];
const LAYERS = [['stores', 'Stores'], ['agents', 'Agents (live)'], ['route', 'Route'], ['stops', 'Stops']];

export default function FilterPanel({ f, set, agents, stores, layers, toggleLayer, armed, setArmed, onClearPoint }) {
  const picking = armed?.kind === 'point';
  return (
    <section className="card mp-filters" aria-label="Filters">
      <div className="grid-fit" style={{ '--min': '140px', gap: 12 }}>
        <div className="field" style={{ gridColumn: '1 / -1' }}>
          <label htmlFor="mp-agent">Agent</label>
          <select id="mp-agent" className="select" value={f.agentId} onChange={(e) => set({ agentId: e.target.value })}>
            <option value="">All agents</option>
            {agents.map((a) => <option key={a.agent.id} value={a.agent.id}>{agentName(a.agent)}</option>)}
          </select>
        </div>
        <div className="field" style={{ gridColumn: '1 / -1' }}>
          <label htmlFor="mp-date">Date</label>
          <input id="mp-date" type="date" className="input" value={f.date} onChange={(e) => e.target.value && set({ date: e.target.value })} />
        </div>
        <div className="field">
          <label htmlFor="mp-from">Time from</label>
          <input id="mp-from" type="time" className="input mono" value={f.timeFrom} onChange={(e) => set({ timeFrom: e.target.value })} />
        </div>
        <div className="field">
          <label htmlFor="mp-to">Time to</label>
          <input id="mp-to" type="time" className="input mono" value={f.timeTo} onChange={(e) => set({ timeTo: e.target.value })} />
        </div>
        <div className="field" style={{ gridColumn: '1 / -1' }}>
          <label htmlFor="mp-store">Store</label>
          <select id="mp-store" className="select" value={f.customerId} onChange={(e) => set({ customerId: e.target.value, point: null })}>
            <option value="">Any location</option>
            {stores.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </div>
        <div className="field" style={{ gridColumn: '1 / -1' }}>
          <span className="label">Or a point on the map</span>
          <div className="mp-row">
            <button type="button" className="pill mp-pill" aria-pressed={picking} onClick={() => setArmed(picking ? null : { kind: 'point' })}>{picking ? 'Click the map…' : 'Pick a point on the map'}</button>
            {f.point && <button type="button" className="btn btn-ghost btn-sm" onClick={onClearPoint}>Clear</button>}
          </div>
        </div>
        {f.point && (
          <div className="field">
            <label htmlFor="mp-radius">Radius</label>
            <select id="mp-radius" className="select" value={f.radius} onChange={(e) => set({ radius: Number(e.target.value) })}>
              {RADII.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
            </select>
          </div>
        )}
        <div className="field">
          <label htmlFor="mp-min">Min stop</label>
          <select id="mp-min" className="select" value={f.minMinutes} onChange={(e) => set({ minMinutes: Number(e.target.value) })}>
            {MINS.map((m) => <option key={m} value={m}>{m} min</option>)}
          </select>
        </div>
      </div>
      <div role="group" aria-label="Map layers" className="mp-row" style={{ marginTop: 14 }}>
        {LAYERS.map(([k, l]) => <button key={k} type="button" className="pill mp-pill" aria-pressed={!!layers[k]} onClick={() => toggleLayer(k)}>{l}</button>)}
      </div>
    </section>
  );
}
