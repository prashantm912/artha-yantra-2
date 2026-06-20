import { Navigate, Route, Routes } from 'react-router-dom';
import { RequireAuth } from './auth/RequireAuth.tsx';
import { AppShell } from './components/AppShell.tsx';
import { LoginPage } from './pages/login/LoginPage.tsx';
import { OiOptionsPage } from './pages/oi/OiOptionsPage.tsx';

// PR-F route tree. /login public; everything else behind RequireAuth → the hybrid AppShell layout.
// Section-based routes (master plan §20) mirror the mega-menu; the OI Analysis anchor proves the
// FilterBar + cascade + DataTable + OiBadge4 + decimal stack end-to-end. More routes land per wave.
export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireAuth />}>
        <Route element={<AppShell />}>
          <Route index element={<Navigate to="/options/oi-analysis" replace />} />
          <Route path="/options/oi-analysis" element={<OiOptionsPage />} />
        </Route>
      </Route>
    </Routes>
  );
}
