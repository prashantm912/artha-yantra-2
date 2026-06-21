import { Suspense, lazy } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { RequireAuth } from './auth/RequireAuth.tsx';
import { AppShell } from './components/AppShell.tsx';
import { LoginPage } from './pages/login/LoginPage.tsx';
import { OptionsChainPage } from './pages/options/OptionsChainPage.tsx';
import { OptionsSpurtPage } from './pages/options/OptionsSpurtPage.tsx';
import { OiAnalysisPage } from './pages/options/OiAnalysisPage.tsx';
import { ConnectingDotsPage } from './pages/options/ConnectingDotsPage.tsx';

// The straddle chart pulls in ECharts (~1 MB) — lazy-load it so that bundle is a separate chunk
// fetched only when the route is visited, keeping the main payload lean (§20.1).
const OptionsStraddlePage = lazy(() =>
  import('./pages/options/OptionsStraddlePage.tsx').then((m) => ({ default: m.OptionsStraddlePage })),
);

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
          <Route path="/features/connecting-dots" element={<ConnectingDotsPage />} />
          <Route
            path="/options/straddle-chart"
            element={
              <Suspense fallback={<p className="text-sm text-ay-muted">Loading chart…</p>}>
                <OptionsStraddlePage />
              </Suspense>
            }
          />
        </Route>
      </Route>
    </Routes>
  );
}
