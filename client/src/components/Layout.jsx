import { useEffect, useRef, useState } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import Sidebar, { navTitle } from './Sidebar';
import BrandMark from './BrandMark';
import { useAuth } from '../context/AuthContext';

// Desktop (>= 1024px): sidebar pinned on the left. Smaller screens: a sticky top bar whose menu button
// slides the same sidebar in as a drawer (closes on navigation, Escape, scrim tap or the close button).
export default function Layout() {
  const { user, settings } = useAuth();
  const { pathname } = useLocation();
  const [open, setOpen] = useState(false);
  const menuBtn = useRef(null);
  const drawer = useRef(null);

  useEffect(() => { setOpen(false); }, [pathname]);

  useEffect(() => {
    if (!open) return undefined;
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    drawer.current?.querySelector('.sb-close')?.focus();
    const onKey = (e) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('keydown', onKey);
    const btn = menuBtn.current;
    return () => { document.body.style.overflow = prev; document.removeEventListener('keydown', onKey); btn?.focus(); };
  }, [open]);

  const shop = settings?.shopName || 'Marbingo';
  return (
    <div className="shell">
      <header className="shell-top">
        <button ref={menuBtn} type="button" className="shell-menu" aria-label="Open menu" aria-expanded={open} aria-controls="sb-drawer" onClick={() => setOpen(true)}>
          <svg aria-hidden="true" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M4 7h16M4 12h16M4 17h16" /></svg>
        </button>
        <BrandMark size={32} radius={8} />
        <span className="shell-title">{navTitle(pathname, user) || shop}</span>
      </header>
      <div ref={drawer} id="sb-drawer" className={'shell-side' + (open ? ' open' : '')}>
        <Sidebar onClose={() => setOpen(false)} />
      </div>
      {open && <div className="shell-scrim" aria-hidden="true" onClick={() => setOpen(false)} />}
      <main className="shell-main"><Outlet /></main>
    </div>
  );
}
