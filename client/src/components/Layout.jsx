import { Outlet } from 'react-router-dom';

export default function Layout() {
  return <div className="shell"><div className="shell-side" /><main className="shell-main"><Outlet /></main></div>;
}
