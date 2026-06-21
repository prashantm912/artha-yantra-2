import { Navigate, Route, Routes } from 'react-router-dom';
import { RequireAuth } from './auth/RequireAuth.tsx';
import { AppShell } from './components/AppShell.tsx';
import { LoginPage } from './pages/login/LoginPage.tsx';
import { OptionsChainPage } from './pages/options/OptionsChainPage.tsx';
import { OptionsSpurtPage } from './pages/options/OptionsSpurtPage.tsx';
import { OiAnalysisPage } from './pages/options/OiAnalysisPage.tsx';

// PR-F route tree. /login public; everything else behind RequireAuth → the hybrid AppShell layout.
// Section-based routes (master plan §20) mirror the mega-menu. The anchor is the Options Chain
// (all-strikes mirrored grid); the true per-strike "Options OI Analysis" lands in Wave 1 (§20.6).
export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireAuth />}>
        <Route element={<AppShell />}>
          <Route index element={<Navigate to="/options/options-chain" replace />} />
          <Route path="/options/options-chain" element={<OptionsChainPage />} />
          <Route path="/options/oi-spurt" element={<OptionsSpurtPage />} />
          <Route path="/options/oi-analysis" element={<OiAnalysisPage />} />
        </Route>
      </Route>
    </Routes>
  );
}
