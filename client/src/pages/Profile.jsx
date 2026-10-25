import { useEffect, useState } from 'react';
import api, { apiError } from '../lib/api';
import { useAuth } from '../context/AuthContext';
import { ROLE_LABELS, initials, longDate } from '../lib/format';
import '../styles/forms.css';

const toForm = (u) => ({ first: u?.firstName || '', last: u?.lastName || '', email: u?.email || '', mobile: u?.mobile || '', addr: u?.address || '' });

export default function Profile() {
  const { user, setUser } = useAuth();
  const [info, setInfo] = useState(() => toForm(user));
  const [infoMsg, setInfoMsg] = useState({ text: '', ok: false });
  const [infoErr, setInfoErr] = useState({ message: '', fields: {} });
  const [savingInfo, setSavingInfo] = useState(false);
  const [photoMsg, setPhotoMsg] = useState({ text: '', ok: false });
  const [pw, setPw] = useState({ cur: '', next: '', conf: '' });
  const [pwMsg, setPwMsg] = useState({ text: '', ok: false });
  const [savingPw, setSavingPw] = useState(false);

  useEffect(() => {
    let alive = true;
    api.get('/profile').then((r) => {
      const u = r.data.user || r.data;
      if (!alive || !u) return;
      setUser(u);
      setInfo(toForm(u));
    }).catch(() => {});
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (!user) return null;
  const full = (info.first + ' ' + info.last).trim() || 'Your name';
  const fe = infoErr.fields || {};

  const onInfo = (k) => (e) => {
    setInfo((p) => ({ ...p, [k]: e.target.value }));
    setInfoMsg({ text: '', ok: false });
  };
  const onPw = (k) => (e) => {
    setPw((p) => ({ ...p, [k]: e.target.value }));
    setPwMsg({ text: '', ok: false });
  };

  async function onPhoto(e) {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    const fd = new FormData();
    fd.append('photo', file);
    setPhotoMsg({ text: 'Uploading…', ok: true });
    try {
      const { data } = await api.post('/profile/photo', fd);
      setUser(data.user);
      setPhotoMsg({ text: 'Photo updated.', ok: true });
    } catch (err) {
      setPhotoMsg({ text: apiError(err).message, ok: false });
    }
  }

  async function saveInfo(e) {
    e.preventDefault();
    setSavingInfo(true);
    setInfoErr({ message: '', fields: {} });
    setInfoMsg({ text: '', ok: false });
    try {
      const { data } = await api.put('/profile', { firstName: info.first, lastName: info.last, email: info.email, mobile: info.mobile, address: info.addr });
      setUser(data.user);
      setInfoMsg({ text: 'Changes saved.', ok: true });
    } catch (err) {
      const er = apiError(err);
      setInfoErr(er);
      if (!Object.keys(er.fields).length) setInfoMsg({ text: er.message, ok: false });
    } finally {
      setSavingInfo(false);
    }
  }

  async function savePw(e) {
    e.preventDefault();
    const bad = (text) => setPwMsg({ text, ok: false });
    if (!pw.cur) return bad('Enter your current password.');
    if (pw.next.length < 8) return bad('New password needs at least 8 characters.');
    if (pw.next !== pw.conf) return bad('The new passwords don’t match.');
    setSavingPw(true);
    try {
      await api.put('/profile/password', { current: pw.cur, next: pw.next });
      setPw({ cur: '', next: '', conf: '' });
      setPwMsg({ text: 'Password updated.', ok: true });
    } catch (err) {
      const er = apiError(err);
      bad(er.fields.current || er.message);
    } finally {
      setSavingPw(false);
    }
  }

  const fieldErr = (k) => fe[k] && <span className="err">{fe[k]}</span>;

  return (
    <div className="page-col">
      <header className="page-head">
        <p className="eyebrow">Account</p>
        <h1 className="page-title">My profile</h1>
      </header>

      <div className="split">
        <section aria-label="Profile summary" className="card summary" style={{ flex: '1 1 280px', minWidth: 0 }}>
          {user.photoUrl ? (
            <img className="avatar" src={user.photoUrl} alt="Profile photo" />
          ) : (
            <div aria-hidden="true" className="avatar avatar-ph">{initials(info.first, info.last)}</div>
          )}
          <label htmlFor="avatar" className="upload-btn">Change photo</label>
          <input id="avatar" type="file" className="file-hidden" accept="image/png,image/jpeg,image/webp" onChange={onPhoto} />
          {photoMsg.text && <span role="status" className={photoMsg.ok ? 'status-ok' : 'status-err'}>{photoMsg.text}</span>}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <span style={{ fontFamily: 'var(--font-display)', fontWeight: 600, fontSize: 22 }}>{full}</span>
            <span style={{ fontSize: 14, color: 'var(--muted)' }}>{ROLE_LABELS[user.role] || user.role}</span>
          </div>
          <dl>
            <dt>Employee ID</dt><dd className="mono">{user.employeeId}</dd>
            <dt>Role</dt><dd>{ROLE_LABELS[user.role] || user.role}</dd>
            <dt>Member since</dt><dd className="mono">{user.createdAt ? longDate(user.createdAt) : '—'}</dd>
          </dl>
          <p style={{ margin: 0, fontSize: 12, lineHeight: 1.45, color: 'var(--muted)' }}>Role and employee ID are managed by the shop admin.</p>
        </section>

        <div className="main-col">
          <form className="card" onSubmit={saveInfo}>
            <h2 className="h2">Personal information</h2>
            <div className="grid-fit" style={{ '--min': '220px' }}>
              <div className="field">
                <label htmlFor="first">First name</label>
                <input id="first" type="text" autoComplete="given-name" className={'input' + (fe.firstName ? ' invalid' : '')} value={info.first} onChange={onInfo('first')} />
                {fieldErr('firstName')}
              </div>
              <div className="field">
                <label htmlFor="last">Last name</label>
                <input id="last" type="text" autoComplete="family-name" className={'input' + (fe.lastName ? ' invalid' : '')} value={info.last} onChange={onInfo('last')} />
                {fieldErr('lastName')}
              </div>
              <div className="field">
                <label htmlFor="email">Email</label>
                <input id="email" type="email" autoComplete="email" className={'input' + (fe.email ? ' invalid' : '')} value={info.email} onChange={onInfo('email')} />
                {fieldErr('email')}
              </div>
              <div className="field">
                <label htmlFor="mobile">Mobile number</label>
                <input id="mobile" type="tel" autoComplete="tel" className={'input mono' + (fe.mobile ? ' invalid' : '')} value={info.mobile} onChange={onInfo('mobile')} />
                {fieldErr('mobile')}
              </div>
            </div>
            <div className="field">
              <label htmlFor="addr">Address</label>
              <input id="addr" type="text" autoComplete="street-address" className={'input' + (fe.address ? ' invalid' : '')} value={info.addr} onChange={onInfo('addr')} />
              {fieldErr('address')}
            </div>
            <div className="actions">
              {infoMsg.text && <span role="status" className={infoMsg.ok ? 'status-ok' : 'status-err'}>{infoMsg.text}</span>}
              <button type="submit" className="btn btn-primary" disabled={savingInfo}>Save changes</button>
            </div>
          </form>

          <form className="card" onSubmit={savePw} noValidate>
            <h2 className="h2">Change password</h2>
            <div className="grid-fit" style={{ '--min': '200px' }}>
              <div className="field">
                <label htmlFor="cur">Current password</label>
                <input id="cur" type="password" autoComplete="current-password" className="input" value={pw.cur} onChange={onPw('cur')} />
              </div>
              <div className="field">
                <label htmlFor="new">New password</label>
                <input id="new" type="password" autoComplete="new-password" className="input" value={pw.next} onChange={onPw('next')} aria-describedby="pw-hint" />
                <span id="pw-hint" className="hint">At least 8 characters.</span>
              </div>
              <div className="field">
                <label htmlFor="confirm">Confirm new password</label>
                <input id="confirm" type="password" autoComplete="new-password" className="input" value={pw.conf} onChange={onPw('conf')} />
              </div>
            </div>
            <div className="actions">
              {pwMsg.text && <span role="status" className={pwMsg.ok ? 'status-ok' : 'status-err'}>{pwMsg.text}</span>}
              <button type="submit" className="btn btn-secondary" disabled={savingPw}>Update password</button>
            </div>
          </form>
        </div>
      </div>
    </div>
  );
}
