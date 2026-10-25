import { Outlet } from 'react-router-dom';
import Sidebar from './Sidebar';

export default function Layout() {
  return (
    <div className="shell">
      <div className="shell-side"><Sidebar /></div>
      <main className="shell-main"><Outlet /></main>
    </div>
  );
}
