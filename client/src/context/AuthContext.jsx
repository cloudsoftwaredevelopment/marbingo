import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import api, { TOKEN_KEY, REFRESH_KEY, USER_KEY, setLogoutHandler } from '../lib/api';

const AuthContext = createContext(null);

function readUser() {
  try { return JSON.parse(localStorage.getItem(USER_KEY)) || null; } catch { return null; }
}

export function AuthProvider({ children }) {
  const [user, setUserState] = useState(readUser);
  const [settings, setSettings] = useState({ shopName: 'Marbingo', shopMark: 'MB' });

  const setUser = useCallback((u) => {
    setUserState(u);
    if (u) localStorage.setItem(USER_KEY, JSON.stringify(u));
    else localStorage.removeItem(USER_KEY);
  }, []);

  const logout = useCallback(() => {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(REFRESH_KEY);
    setUser(null);
  }, [setUser]);

  useEffect(() => { setLogoutHandler(logout); }, [logout]);

  const login = useCallback(async (email, password) => {
    const { data } = await api.post('/auth/login', { email, password });
    localStorage.setItem(TOKEN_KEY, data.accessToken);
    localStorage.setItem(REFRESH_KEY, data.refreshToken);
    setUser(data.user);
    return data.user;
  }, [setUser]);

  const refreshSettings = useCallback(async () => {
    try { const { data } = await api.get('/settings'); setSettings(data); } catch { /* keep default */ }
  }, []);

  // Re-validate the stored session and load shop settings once signed in.
  useEffect(() => {
    if (!user) return;
    api.get('/auth/me').then((r) => setUser(r.data.user)).catch(() => {});
    refreshSettings();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [!!user]);

  const value = useMemo(
    () => ({ user, setUser, login, logout, settings, refreshSettings }),
    [user, setUser, login, logout, settings, refreshSettings]
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export const useAuth = () => useContext(AuthContext);
