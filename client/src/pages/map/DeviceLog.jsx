import { agentName, fmtDur, fmtTime } from './util';

// Gap kinds from the tablet's device log (docs/tracking-api.md "Device log"). tone: bad = no location recorded.
const KINDS = {
  power: { label: 'Tablet switched off', tone: 'bad' },
  app: { label: 'App stopped (force-stopped or closed by the phone)', tone: 'bad' },
  mock: { label: 'Fake GPS app in use', tone: 'bad' },
  gps: { label: 'GPS / location turned off', tone: 'warn' },
  permission: { label: 'Location permission removed', tone: 'warn' },
  airplane: { label: 'Airplane mode on', tone: 'off' },
  data: { label: 'Mobile data switched off', tone: 'off' },
  signal: { label: 'No signal', tone: 'muted' },
  clock: { label: 'Automatic time turned off', tone: 'muted' },
};

export function gapLabel(kind, detail) {
  if (kind === 'power' && detail?.battery != null && detail.battery <= 5) return 'Tablet off (battery died)';
  return KINDS[kind]?.label || kind;
}

const shiftText = (ms) => `${fmtDur(Math.abs(ms) / 1000)} ${ms < 0 ? 'back' : 'ahead'}`;
function instantLabel(e) {
  if (e.type === 'clock_changed') return `Clock changed by hand (${shiftText(e.detail?.shiftMs || 0)})`;
  if (e.type === 'clock_skew') return `Tablet clock is ${fmtDur(Math.abs(e.detail?.shiftMs || 0) / 1000)} ${e.detail?.shiftMs < 0 ? 'behind' : 'ahead'} — times may be off`;
  return e.type;
}

function gapNote(g) {
  const bits = [];
  if (g.kind === 'power' && g.detail?.estimated) bits.push('start time approximate');
  if (g.kind === 'power' && g.detail?.battery != null) bits.push(`battery ${g.detail.battery}%`);
  if (g.kind === 'data' || g.kind === 'airplane' || g.kind === 'signal') bits.push('GPS still recorded, uploaded later');
  return bits.join(' · ');
}

// Live list: agents whose tablet has a problem right now.
export function DeviceAlerts({ agents, onPick }) {
  const rows = agents.filter((a) => a.alert || a.status === 'signal_lost');
  if (!rows.length) return null;
  return (
    <section className="card mp-timeline" aria-label="Device alerts">
      <h3 className="h2" style={{ fontSize: 16 }}>Device alerts now</h3>
      <ul className="mp-list">
        {rows.map((a) => (
          <li key={a.agent.id}>
            <button type="button" className="mp-rowbtn" onClick={() => onPick(a.agent.id)}>
              <span className={'dl-dot ' + (a.alert ? KINDS[a.alert.kind]?.tone || 'warn' : 'muted')} aria-hidden="true" />
              <span className="mp-rowmain">
                <strong>{agentName(a.agent)}</strong>
                <span className="hint">
                  {a.alert ? `${gapLabel(a.alert.kind, a.alert.detail)} since ${fmtTime(a.alert.since)}`
                    : `No update since ${fmtTime(a.last?.recordedAt)}`}
                </span>
              </span>
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}

// One agent-day: a bar across work hours with coloured gaps, totals, and the list.
export default function DeviceLog({ log }) {
  if (!log || !log.workStart) return null;
  const { gaps, instants, summary } = log;
  const t0 = new Date(log.workStart).getTime();
  const span = new Date(log.workEnd).getTime() - t0;
  const pct = (iso) => Math.max(0, Math.min(100, ((new Date(iso).getTime() - t0) / span) * 100));
  const rows = [
    ...gaps.map((g) => ({ at: g.startedAt, key: 'g' + g.kind + g.startedAt, g })),
    ...instants.map((e) => ({ at: e.at, key: 'i' + e.type + e.at, e })),
  ].sort((a, b) => new Date(a.at) - new Date(b.at));

  return (
    <section className="card mp-timeline" aria-label="Device log">
      <h3 className="h2" style={{ fontSize: 16 }}>Device log</h3>
      <div className="mp-stats">
        <div className="margin-box"><span className="t">No location</span><span className="v">{fmtDur(summary.noLocationS)}</span></div>
        <div className="margin-box"><span className="t">Offline</span><span className="v">{fmtDur(summary.offlineS)}</span></div>
      </div>
      <div className="dl-bar" role="img" aria-label={`${gaps.length} gaps between ${fmtTime(log.workStart)} and ${fmtTime(log.workEnd)}`}>
        {gaps.map((g) => (
          <span key={g.kind + g.startedAt} className={'dl-seg ' + (KINDS[g.kind]?.tone || 'muted')}
            style={{ left: pct(g.startedAt) + '%', width: Math.max(0.6, pct(g.endedAt) - pct(g.startedAt)) + '%' }}
            title={`${gapLabel(g.kind, g.detail)} · ${fmtTime(g.startedAt)} – ${g.open ? 'now' : fmtTime(g.endedAt)}`} />
        ))}
      </div>
      <div className="mp-row hint" style={{ justifyContent: 'space-between', fontSize: 12 }}>
        <span>{fmtTime(log.workStart)}</span><span>{fmtTime(log.workEnd)}</span>
      </div>
      {!rows.length ? <p className="hint" style={{ margin: 0 }}>No gaps: the tablet stayed on and connected.</p> : (
        <ul className="mp-list">
          {rows.map((r) => (r.g ? (
            <li key={r.key} className="dl-row">
              <span className={'dl-dot ' + (KINDS[r.g.kind]?.tone || 'muted')} aria-hidden="true" />
              <span className="mp-rowmain">
                <strong>{gapLabel(r.g.kind, r.g.detail)}</strong>
                <span className="hint">
                  {fmtTime(r.g.startedAt)} – {r.g.open ? 'now' : fmtTime(r.g.endedAt)} · {fmtDur(r.g.durationS)}
                  {gapNote(r.g) && <> · {gapNote(r.g)}</>}
                </span>
              </span>
            </li>
          ) : (
            <li key={r.key} className="dl-row">
              <span className="dl-dot muted" aria-hidden="true" />
              <span className="mp-rowmain">
                <strong>{instantLabel(r.e)}</strong>
                <span className="hint">{fmtTime(r.e.at)}</span>
              </span>
            </li>
          )))}
        </ul>
      )}
    </section>
  );
}
