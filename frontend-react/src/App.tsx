import { Suspense, lazy } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { RequireAuth } from './auth/RequireAuth.tsx';
import { AppShell } from './components/AppShell.tsx';
import { LoginPage } from './pages/login/LoginPage.tsx';
import { OptionsChainPage } from './pages/options/OptionsChainPage.tsx';
import { OptionsSpurtPage } from './pages/options/OptionsSpurtPage.tsx';
import { OiAnalysisPage } from './pages/options/OiAnalysisPage.tsx';
import { ConnectingDotsPage } from './pages/options/ConnectingDotsPage.tsx';
import { TrendingOiPage } from './pages/options/TrendingOiPage.tsx';
import { BigOiMovementPage } from './pages/options/BigOiMovementPage.tsx';
import { FuturesOiSpurtPage } from './pages/futures/FuturesOiSpurtPage.tsx';
import { FuturesMoversPage } from './pages/futures/FuturesMoversPage.tsx';
import { FuturesEodPage } from './pages/futures/FuturesEodPage.tsx';
import { ParticipantWiseOiPage } from './pages/fiidii/ParticipantWiseOiPage.tsx';

// The ECharts-bearing pages are lazy-loaded so the ~1 MB ECharts bundle is a separate chunk fetched
// only when the route is visited, keeping the main payload lean (§20.1).
const OptionsStraddlePage = lazy(() =>
  import('./pages/options/OptionsStraddlePage.tsx').then((m) => ({ default: m.OptionsStraddlePage })),
);
const OptionsPremiumPage = lazy(() =>
  import('./pages/options/OptionsPremiumPage.tsx').then((m) => ({ default: m.OptionsPremiumPage })),
);
const FiiDiiCapitalMarketPage = lazy(() =>
  import('./pages/fiidii/FiiDiiCapitalMarketPage.tsx').then((m) => ({ default: m.FiiDiiCapitalMarketPage })),
);
const FiiLongShortPage = lazy(() =>
  import('./pages/fiidii/FiiLongShortPage.tsx').then((m) => ({ default: m.FiiLongShortPage })),
);
const OiStatisticsPage = lazy(() =>
  import('./pages/options/OiStatisticsPage.tsx').then((m) => ({ default: m.OiStatisticsPage })),
);
const ActiveStrikesPage = lazy(() =>
  import('./pages/options/ActiveStrikesPage.tsx').then((m) => ({ default: m.ActiveStrikesPage })),
);

function Lazy({ children }: { children: React.ReactNode }) {
  return <Suspense fallback={<p className="text-sm text-ay-muted">Loading chart…</p>}>{children}</Suspense>;
}

// Route tree (master plan §20). /login public; everything else behind RequireAuth → the hybrid
// AppShell layout. Section-based routes mirror the mega-menu. Wave 2 adds the depth pages (Options
// Trending/Premium/Big-OI, Futures Spurt/Movers/EOD, FII/DII Capital-Market/Participant/Long-Short).
export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireAuth />}>
        <Route element={<AppShell />}>
          <Route index element={<Navigate to="/options/options-chain" replace />} />
          {/* Options */}
          <Route path="/options/options-chain" element={<OptionsChainPage />} />
          <Route path="/options/oi-spurt" element={<OptionsSpurtPage />} />
          <Route path="/options/oi-analysis" element={<OiAnalysisPage />} />
          <Route path="/options/trending-oi" element={<TrendingOiPage />} />
          <Route path="/options/big-oi-movement" element={<BigOiMovementPage />} />
          <Route path="/options/options-premium" element={<Lazy><OptionsPremiumPage /></Lazy>} />
          <Route path="/options/straddle-chart" element={<Lazy><OptionsStraddlePage /></Lazy>} />
          <Route path="/options/oi-statistics" element={<Lazy><OiStatisticsPage /></Lazy>} />
          <Route path="/options/active-strikes" element={<Lazy><ActiveStrikesPage /></Lazy>} />
          {/* Futures */}
          <Route path="/futures/oi-spurt" element={<FuturesOiSpurtPage />} />
          <Route path="/futures/market-movers" element={<FuturesMoversPage />} />
          <Route path="/futures/eod-oi-analyzer" element={<FuturesEodPage />} />
          {/* FII / DII */}
          <Route path="/fii-dii/capital-market" element={<Lazy><FiiDiiCapitalMarketPage /></Lazy>} />
          <Route path="/fii-dii/participant-wise-oi" element={<ParticipantWiseOiPage />} />
          <Route path="/fii-dii/long-short-ratio" element={<Lazy><FiiLongShortPage /></Lazy>} />
          {/* Features */}
          <Route path="/features/connecting-dots" element={<ConnectingDotsPage />} />
        </Route>
      </Route>
    </Routes>
  );
}
