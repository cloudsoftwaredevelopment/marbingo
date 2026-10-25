import axios from 'axios';

// Tokens live in localStorage under these keys.
export const TOKEN_KEY = 'mb_token';
export const REFRESH_KEY = 'mb_refresh';
export const USER_KEY = 'mb_user';

const api = axios.create({ baseURL: '/api' });

api.interceptors.request.use((config) => {
  const t = localStorage.getItem(TOKEN_KEY);
  if (t) config.headers.Authorization = `Bearer ${t}`;
  return config;
});

let refreshing = null;
let onLogout = () => {};
export function setLogoutHandler(fn) { onLogout = fn; }

// On 401, try one refresh, then replay the request; otherwise log out.
api.interceptors.response.use(
  (r) => r,
  async (error) => {
    const original = error.config;
    const refresh = localStorage.getItem(REFRESH_KEY);
    if (error.response?.status === 401 && refresh && !original._retry && !original.url.startsWith('/auth/')) {
      original._retry = true;
      try {
        refreshing = refreshing || axios.post('/api/auth/refresh', { refreshToken: refresh });
        const { data } = await refreshing;
        localStorage.setItem(TOKEN_KEY, data.accessToken);
        if (data.refreshToken) localStorage.setItem(REFRESH_KEY, data.refreshToken);
        return api(original);
      } catch {
        onLogout();
      } finally {
        refreshing = null;
      }
    } else if (error.response?.status === 401 && !original.url.startsWith('/auth/login')) {
      onLogout();
    }
    return Promise.reject(error);
  }
);

// Pulls a readable message and per-field errors out of an axios error.
export function apiError(err) {
  const d = err?.response?.data || {};
  return { message: d.error || 'Something went wrong. Try again.', fields: d.fields || {} };
}

export default api;
