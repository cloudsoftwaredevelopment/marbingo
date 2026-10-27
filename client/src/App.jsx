import { Navigate, Route, Routes } from 'react-router-dom';
import RequireAuth from './components/RequireAuth';
import Layout from './components/Layout';
import Login from './pages/Login';
import SalesReport from './pages/SalesReport';
import Inventory from './pages/Inventory';
import AddItem from './pages/AddItem';
import Profile from './pages/Profile';
import Pos from './pages/Pos';
import Admin from './pages/Admin';
import Orders from './pages/Orders';
import Stores from './pages/Stores';
import Trucks from './pages/Trucks';
import TruckDetail from './pages/TruckDetail';
import EcosystemMap from './pages/EcosystemMap';

// Layout renders <Outlet/> inside the shell with the sidebar.
export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route element={<RequireAuth><Layout /></RequireAuth>}>
        <Route index element={<RequireAuth cap="reports"><SalesReport /></RequireAuth>} />
        <Route path="items" element={<RequireAuth cap="items"><Inventory /></RequireAuth>} />
        <Route path="items/:id/edit" element={<RequireAuth cap="items"><AddItem key="edit" /></RequireAuth>} />
        <Route path="items/new" element={<RequireAuth cap="items"><AddItem key="new" /></RequireAuth>} />
        <Route path="pos" element={<RequireAuth cap="pos"><Pos /></RequireAuth>} />
        <Route path="orders" element={<RequireAuth cap="orders"><Orders /></RequireAuth>} />
        <Route path="stores" element={<RequireAuth cap="stores"><Stores /></RequireAuth>} />
        <Route path="trucks" element={<RequireAuth cap="trucks"><Trucks /></RequireAuth>} />
        <Route path="trucks/:id" element={<RequireAuth cap="trucks"><TruckDetail /></RequireAuth>} />
        <Route path="map" element={<RequireAuth cap="map"><EcosystemMap /></RequireAuth>} />
        <Route path="admin" element={<RequireAuth cap="admin"><Admin /></RequireAuth>} />
        <Route path="profile" element={<Profile />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
