import { useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { apiError } from '../lib/api';
import { homePath } from '../lib/roles';
import { shopInitials } from '../components/Sidebar';
import '../styles/shell.css';

export default function Login() {
  const { user, login, settings } = useAuth();
  const nav = useNavigate();
  const loc = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  if (user) return <Navigate to={homePath(user)} replace />;

  const shop = settings?.shopName || 'Marbingo';

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      const u = await login(email.trim(), password);
      nav(loc.state?.from || homePath(u), { replace: true });
    } catch (err) {
      setError(apiError(err).message);
      setBusy(false);
    }
  }

  return (
    <div className="login-page">
      <main className="card login-card">
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <div aria-hidden="true" style={{ width: 40, height: 40, flex: '0 0 40px', borderRadius: 10, background: '#E3EEE7', color: '#1E5B43', display: 'flex', alignItems: 'center', justifyContent: 'center', fontFamily: 'var(--font-display)', fontWeight: 700, fontSize: 17 }}>{shopInitials(shop)}</div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <span style={{ fontFamily: 'var(--font-display)', fontWeight: 700, fontSize: 18, lineHeight: 1.1 }}>{shop}</span>
            <span style={{ fontSize: 12, color: 'var(--muted)' }}>Back office</span>
          </div>
        </div>
        <h1 className="page-title" style={{ fontSize: 32 }}>Sign in</h1>
        <form onSubmit={submit} noValidate style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          {error && <div className="banner-err" role="alert">{error}</div>}
          <div className="field">
            <label htmlFor="email">Email</label>
            <input id="email" className="input" type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="password">Password</label>
            <input id="password" className="input" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
          </div>
          <button type="submit" className="btn btn-primary" style={{ width: '100%' }} disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
        </form>
      </main>
      <p className="login-note">Demo accounts: maria@ / jose@ / ana@ / ramon@marbingo.test · password marbingo123</p>
    </div>
  );
}
