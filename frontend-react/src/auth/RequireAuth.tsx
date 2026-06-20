import { useEffect, useState } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useSession } from '../stores/session.store.ts';

// Route guard (replaces Angular's authGuard): render the protected tree if authenticated, else
// probe once; on a negative probe redirect to /login carrying the return URL (master plan §20).
export function RequireAuth() {
  const auth = useSession((s) => s.auth);
  const probe = useSession((s) => s.probe);
  const location = useLocation();
  const [probed, setProbed] = useState(auth === 'authenticated' || auth === 'anonymous');

  useEffect(() => {
    if (auth === 'unknown') {
      void probe().finally(() => setProbed(true));
    } else {
      setProbed(true);
    }
  }, [auth, probe]);

  if (auth === 'authenticated') return <Outlet />;
  if (!probed || auth === 'unknown' || auth === 'checking') {
    return (
      <div className="p-6 text-ay-muted" aria-live="polite">
        Checking session…
      </div>
    );
  }
  return (
    <Navigate to="/login" replace state={{ returnUrl: location.pathname + location.search }} />
  );
}
