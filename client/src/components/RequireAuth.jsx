import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { can, homePath } from '../lib/roles';

// Redirects to /login when signed out, or to the role's home when the capability is missing.
export default function RequireAuth({ cap, children }) {
  const { user } = useAuth();
  const loc = useLocation();
  if (!user) return <Navigate to="/login" replace state={{ from: loc.pathname }} />;
  if (cap && !can(user, cap)) return <Navigate to={homePath(user)} replace />;
  return children;
}
