import { Suspense, lazy } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { Toaster } from './components/ui/sonner.tsx';
import { RequireAuth } from './auth/RequireAuth.tsx';
import { AppShell } from './components/AppShell.tsx';
import { LoginPage } from './pages/login/LoginPage.tsx';
import { OptionsChainPage } from './pages/options/OptionsChainPage.tsx';
import { OptionsSpurtPage } from './pages/options/OptionsSpurtPage.tsx';
import { OiAnalysisPage } from './pages/options/OiAnalysisPage.tsx';
import { ConnectingDotsPage } from './pages/options/ConnectingDotsPage.tsx';
import { TrendingOiPage } from './pages/options/TrendingOiPage.tsx';
import { TrendingOiPaPage } from './pages/options/TrendingOiPaPage.tsx';
import { BigOiMovementPage } from './pages/options/BigOiMovementPage.tsx';
import { FuturesOiSpurtPage } from './pages/futures/FuturesOiSpurtPage.tsx';
import { FuturesMoversPage } from './pages/futures/FuturesMoversPage.tsx';
import { FuturesEodPage } from './pages/futures/FuturesEodPage.tsx';
import { FuturesOiAnalysisPage } from './pages/futures/FuturesOiAnalysisPage.tsx';
import { FuturesPreOpenMarketPage } from './pages/futures/FuturesPreOpenMarketPage.tsx';
import { BanksAnalysisPage } from './pages/futures/BanksAnalysisPage.tsx';
import { MarketHolidaysPage } from './pages/features/MarketHolidaysPage.tsx';
import { RiskCalculatorPage } from './pages/features/RiskCalculatorPage.tsx';
import { MultipleWindowPage } from './pages/features/MultipleWindowPage.tsx';
import { ParticipantWiseOiPage } from './pages/fiidii/ParticipantWiseOiPage.tsx';
import { SignalsPage } from './pages/signals/SignalsPage.tsx';
import { RejectionsPage } from './pages/signals/RejectionsPage.tsx';
import { DashboardPage } from './pages/dashboard/DashboardPage.tsx';
import { ScalperCockpitPage } from './pages/scalper/ScalperCockpitPage.tsx';
import { StrategiesListPage } from './pages/strategies/StrategiesListPage.tsx';
import { StrategyVersionsPage } from './pages/strategies/StrategyVersionsPage.tsx';
import { StrategyEditorPage } from './pages/strategies/StrategyEditorPage.tsx';
import { GraduationPage } from './pages/strategies/GraduationPage.tsx';
import { SwingSellDecisionsPage } from './pages/strategies/SwingSellDecisionsPage.tsx';
import { BacktestRunnerPage } from './pages/backtests/BacktestRunnerPage.tsx';
import { JobsPage } from './pages/backtests/JobsPage.tsx';
import { JournalPage } from './pages/journal/JournalPage.tsx';
import { OrdersPage } from './pages/orders/OrdersPage.tsx';
import { WatchlistsPage } from './pages/watchlists/WatchlistsPage.tsx';
import { MinerviniScreenerPage } from './pages/equity/MinerviniScreenerPage.tsx';
import { ManasAroraScreenerPage } from './pages/equity/ManasAroraScreenerPage.tsx';
import { SettingsPage } from './pages/settings/SettingsPage.tsx';
import { StatusPage } from './pages/dataops/StatusPage.tsx';
import { CoveragePage } from './pages/dataops/CoveragePage.tsx';
import { CollectionWizardPage } from './pages/dataops/CollectionWizardPage.tsx';
import { QueryConsolePage } from './pages/dataops/QueryConsolePage.tsx';
import { ExportWizardPage } from './pages/dataops/ExportWizardPage.tsx';
import { IngestHealthPage } from './pages/dataops/IngestHealthPage.tsx';

// The ECharts-bearing pages are lazy-loaded so the ~1 MB ECharts bundle is a separate chunk fetched
// only when the route is visited, keeping the main payload lean (§20.1).
const OptionsStraddlePage = lazy(() =>
  import('./pages/options/OptionsStraddlePage.tsx').then((m) => ({ default: m.OptionsStraddlePage })),
);
const CalendarSpreadPage = lazy(() =>
  import('./pages/options/CalendarSpreadPage.tsx').then((m) => ({ default: m.CalendarSpreadPage })),
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
const FiiDerivativeStatsPage = lazy(() =>
  import('./pages/fiidii/FiiDerivativeStatsPage.tsx').then((m) => ({ default: m.FiiDerivativeStatsPage })),
);
const OiStatisticsPage = lazy(() =>
  import('./pages/options/OiStatisticsPage.tsx').then((m) => ({ default: m.OiStatisticsPage })),
);
const IntervalWiseOiPage = lazy(() =>
  import('./pages/options/IntervalWiseOiPage.tsx').then((m) => ({ default: m.IntervalWiseOiPage })),
);
const StrategyBuilderPage = lazy(() =>
  import('./pages/strategies/StrategyBuilderPage.tsx').then((m) => ({ default: m.StrategyBuilderPage })),
);
const ActiveStrikesPage = lazy(() =>
  import('./pages/options/ActiveStrikesPage.tsx').then((m) => ({ default: m.ActiveStrikesPage })),
);
const ActiveStrikesIvPage = lazy(() =>
  import('./pages/options/ActiveStrikesIvPage.tsx').then((m) => ({ default: m.ActiveStrikesIvPage })),
);
const FuturesOiChartPage = lazy(() =>
  import('./pages/futures/FuturesOiChartPage.tsx').then((m) => ({ default: m.FuturesOiChartPage })),
);
const OptionsChartPage = lazy(() =>
  import('./pages/options/OptionsChartPage.tsx').then((m) => ({ default: m.OptionsChartPage })),
);
const MultipleOiChartPage = lazy(() =>
  import('./pages/options/MultipleOiChartPage.tsx').then((m) => ({ default: m.MultipleOiChartPage })),
);
const OptionsOiChartPage = lazy(() =>
  import('./pages/options/OptionsOiChartPage.tsx').then((m) => ({ default: m.OptionsOiChartPage })),
);
const OiHeatmapPage = lazy(() =>
  import('./pages/options/OiHeatmapPage.tsx').then((m) => ({ default: m.OiHeatmapPage })),
);
const OiExpiryStrategyPage = lazy(() =>
  import('./pages/options/OiExpiryStrategyPage.tsx').then((m) => ({
    default: m.OiExpiryStrategyPage,
  })),
);
const OpenHighStrategyPage = lazy(() =>
  import('./pages/options/OpenHighStrategyPage.tsx').then((m) => ({
    default: m.OpenHighStrategyPage,
  })),
);
const VixIndexPage = lazy(() =>
  import('./pages/features/VixIndexPage.tsx').then((m) => ({ default: m.VixIndexPage })),
);
const WorldIndicesPage = lazy(() =>
  import('./pages/features/WorldIndicesPage.tsx').then((m) => ({ default: m.WorldIndicesPage })),
);
const PreOpenMarketPage = lazy(() =>
  import('./pages/equity/PreOpenMarketPage.tsx').then((m) => ({ default: m.PreOpenMarketPage })),
);
const AnnouncementPage = lazy(() =>
  import('./pages/equity/AnnouncementPage.tsx').then((m) => ({ default: m.AnnouncementPage })),
);
const BreadthPage = lazy(() =>
  import('./pages/equity/BreadthPage.tsx').then((m) => ({ default: m.BreadthPage })),
);
const OiBuzzPage = lazy(() =>
  import('./pages/futures/OiBuzzPage.tsx').then((m) => ({ default: m.OiBuzzPage })),
);
const DeliveryDataPage = lazy(() =>
  import('./pages/equity/DeliveryDataPage.tsx').then((m) => ({ default: m.DeliveryDataPage })),
);
const MinerviniCandidatePage = lazy(() =>
  import('./pages/equity/MinerviniCandidatePage.tsx').then((m) => ({
    default: m.MinerviniCandidatePage,
  })),
);
const ManasAroraCandidatePage = lazy(() =>
  import('./pages/equity/ManasAroraCandidatePage.tsx').then((m) => ({
    default: m.ManasAroraCandidatePage,
  })),
);
const ManasAroraBacktestPage = lazy(() =>
  import('./pages/equity/ManasAroraBacktestPage.tsx').then((m) => ({
    default: m.ManasAroraBacktestPage,
  })),
);
const EquityReturnsPage = lazy(() =>
  import('./pages/equity/EquityReturnsPage.tsx').then((m) => ({ default: m.EquityReturnsPage })),
);
const SectorHeatmapPage = lazy(() =>
  import('./pages/equity/SectorHeatmapPage.tsx').then((m) => ({ default: m.SectorHeatmapPage })),
);
const SectorStatsPage = lazy(() =>
  import('./pages/equity/SectorStatsPage.tsx').then((m) => ({ default: m.SectorStatsPage })),
);
const IndexContributionPage = lazy(() =>
  import('./pages/equity/IndexContributionPage.tsx').then((m) => ({ default: m.IndexContributionPage })),
);
const OpenHighLowPage = lazy(() =>
  import('./pages/equity/OpenHighLowPage.tsx').then((m) => ({ default: m.OpenHighLowPage })),
);
const NewsPage = lazy(() =>
  import('./pages/equity/NewsPage.tsx').then((m) => ({ default: m.NewsPage })),
);
const PaperPage = lazy(() =>
  import('./pages/paper/PaperPage.tsx').then((m) => ({ default: m.PaperPage })),
);
const BacktestResultsPage = lazy(() =>
  import('./pages/backtests/BacktestResultsPage.tsx').then((m) => ({ default: m.BacktestResultsPage })),
);
const BacktestComparePage = lazy(() =>
  import('./pages/backtests/BacktestComparePage.tsx').then((m) => ({ default: m.BacktestComparePage })),
);
const SweepDetailPage = lazy(() =>
  import('./pages/optimizations/SweepDetailPage.tsx').then((m) => ({ default: m.SweepDetailPage })),
);
const ChartsPage = lazy(() =>
  import('./pages/charts/ChartsPage.tsx').then((m) => ({ default: m.ChartsPage })),
);
const AdvanceChartPage = lazy(() =>
  import('./pages/charts/AdvanceChartPage.tsx').then((m) => ({ default: m.AdvanceChartPage })),
);
const MultiframeChartPage = lazy(() =>
  import('./pages/charts/MultiframeChartPage.tsx').then((m) => ({ default: m.MultiframeChartPage })),
);
// The unified Scalping Cockpit composes the ECharts-bearing panels (straddle + heatmap) → lazy chunk.
const CockpitPage = lazy(() =>
  import('./pages/scalper/CockpitPage.tsx').then((m) => ({ default: m.CockpitPage })),
);
// The insights feed (INT-I1) — a table + explain drawer (shadcn Sheet); route-lazy to keep it off the
// main chunk until visited.
const InsightsPage = lazy(() =>
  import('./pages/insights/InsightsPage.tsx').then((m) => ({ default: m.InsightsPage })),
);

function Lazy({ children }: { children: React.ReactNode }) {
  return <Suspense fallback={<p className="text-sm text-ay-muted">Loading chart…</p>}>{children}</Suspense>;
}

// Route tree (master plan §20). /login public; everything else behind RequireAuth → the hybrid
// AppShell layout. Section-based routes mirror the mega-menu. Wave 2 adds the depth pages (Options
// Trending/Premium/Big-OI, Futures Spurt/Movers/EOD, FII/DII Capital-Market/Participant/Long-Short).
export function App() {
  return (
    <>
      <Toaster position="top-right" closeButton />
      <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireAuth />}>
        <Route element={<AppShell />}>
          <Route index element={<Navigate to="/dashboard" replace />} />
          {/* Trading (cockpit) */}
          <Route path="/cockpit" element={<Lazy><CockpitPage /></Lazy>} />
          <Route path="/scalper" element={<ScalperCockpitPage />} />
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/signals" element={<SignalsPage />} />
          <Route path="/signals/:book" element={<SignalsPage />} />
          <Route path="/signal-rejections" element={<RejectionsPage />} />
          <Route path="/insights" element={<Lazy><InsightsPage /></Lazy>} />
          <Route path="/paper" element={<Lazy><PaperPage /></Lazy>} />
          <Route path="/paper/:book" element={<Lazy><PaperPage /></Lazy>} />
          <Route path="/orders" element={<OrdersPage />} />
          <Route path="/journal" element={<JournalPage />} />
          <Route path="/watchlists" element={<WatchlistsPage />} />
          <Route path="/settings" element={<SettingsPage />} />
          <Route path="/charts" element={<Lazy><ChartsPage /></Lazy>} />
          <Route path="/advance-chart" element={<Lazy><AdvanceChartPage /></Lazy>} />
          <Route path="/multiframe-chart" element={<Lazy><MultiframeChartPage /></Lazy>} />
          {/* Options */}
          <Route path="/options/options-chain" element={<OptionsChainPage />} />
          <Route path="/options/oi-spurt" element={<OptionsSpurtPage />} />
          <Route path="/options/oi-analysis" element={<OiAnalysisPage />} />
          <Route path="/options/trending-oi" element={<TrendingOiPage />} />
          <Route path="/options/trending-oi-pa" element={<TrendingOiPaPage />} />
          <Route path="/options/big-oi-movement" element={<BigOiMovementPage />} />
          <Route path="/options/options-premium" element={<Lazy><OptionsPremiumPage /></Lazy>} />
          <Route path="/options/straddle-chart" element={<Lazy><OptionsStraddlePage /></Lazy>} />
          <Route path="/options/calendar-spread" element={<Lazy><CalendarSpreadPage /></Lazy>} />
          <Route path="/options/oi-statistics" element={<Lazy><OiStatisticsPage /></Lazy>} />
          <Route path="/options/interval-wise-oi" element={<Lazy><IntervalWiseOiPage /></Lazy>} />
          <Route path="/options/active-strikes" element={<Lazy><ActiveStrikesPage /></Lazy>} />
          <Route path="/options/active-strikes-iv" element={<Lazy><ActiveStrikesIvPage /></Lazy>} />
          <Route path="/options/options-chart" element={<Lazy><OptionsChartPage /></Lazy>} />
          <Route path="/options/multiple-oi-chart" element={<Lazy><MultipleOiChartPage /></Lazy>} />
          <Route path="/options/oi-chart" element={<Lazy><OptionsOiChartPage /></Lazy>} />
          <Route path="/options/oi-heatmap" element={<Lazy><OiHeatmapPage /></Lazy>} />
          <Route path="/options/oi-expiry-strategy" element={<Lazy><OiExpiryStrategyPage /></Lazy>} />
          <Route path="/options/open-high-strategy" element={<Lazy><OpenHighStrategyPage /></Lazy>} />
          {/* Futures */}
          <Route path="/futures/oi-spurt" element={<FuturesOiSpurtPage />} />
          <Route path="/futures/market-movers" element={<FuturesMoversPage />} />
          <Route path="/futures/eod-oi-analyzer" element={<FuturesEodPage />} />
          <Route path="/futures/oi-analysis" element={<FuturesOiAnalysisPage />} />
          <Route path="/futures/pre-open-market" element={<FuturesPreOpenMarketPage />} />
          <Route path="/futures/oi-chart" element={<Lazy><FuturesOiChartPage /></Lazy>} />
          <Route path="/futures/oi-buzz" element={<Lazy><OiBuzzPage /></Lazy>} />
          <Route path="/futures/banks" element={<BanksAnalysisPage />} />
          {/* FII / DII */}
          <Route path="/fii-dii/capital-market" element={<Lazy><FiiDiiCapitalMarketPage /></Lazy>} />
          <Route path="/fii-dii/fii-derivative-stats" element={<Lazy><FiiDerivativeStatsPage /></Lazy>} />
          <Route path="/fii-dii/participant-wise-oi" element={<ParticipantWiseOiPage />} />
          <Route path="/fii-dii/long-short-ratio" element={<Lazy><FiiLongShortPage /></Lazy>} />
          {/* Equity */}
          <Route path="/equity/pre-open-market" element={<Lazy><PreOpenMarketPage /></Lazy>} />
          <Route path="/equity/announcement" element={<Lazy><AnnouncementPage /></Lazy>} />
          <Route path="/equity/breadth" element={<Lazy><BreadthPage /></Lazy>} />
          <Route path="/equity/delivery-data" element={<Lazy><DeliveryDataPage /></Lazy>} />
          <Route path="/equity/equity-returns" element={<Lazy><EquityReturnsPage /></Lazy>} />
          <Route path="/equity/minervini" element={<MinerviniScreenerPage />} />
          <Route path="/equity/minervini/:symbol" element={<Lazy><MinerviniCandidatePage /></Lazy>} />
          <Route path="/equity/manas-arora" element={<ManasAroraScreenerPage />} />
          <Route path="/equity/manas-arora/backtest" element={<Lazy><ManasAroraBacktestPage /></Lazy>} />
          <Route path="/equity/manas-arora/:symbol" element={<Lazy><ManasAroraCandidatePage /></Lazy>} />
          <Route path="/equity/sector-heatmap" element={<Lazy><SectorHeatmapPage /></Lazy>} />
          <Route path="/equity/sector-stats" element={<Lazy><SectorStatsPage /></Lazy>} />
          <Route path="/equity/index-contribution" element={<Lazy><IndexContributionPage /></Lazy>} />
          <Route path="/equity/open-high-low" element={<Lazy><OpenHighLowPage /></Lazy>} />
          <Route path="/equity/news" element={<Lazy><NewsPage /></Lazy>} />
          {/* Strategies */}
          <Route path="/strategies" element={<StrategiesListPage />} />
          <Route path="/strategies/graduation" element={<GraduationPage />} />
          <Route path="/strategies/swing-sell-decisions" element={<SwingSellDecisionsPage />} />
          <Route path="/strategies/:id/edit" element={<StrategyEditorPage />} />
          <Route path="/strategies/:id/versions" element={<StrategyVersionsPage />} />
          <Route path="/strategies/strategy-builder" element={<Lazy><StrategyBuilderPage /></Lazy>} />
          {/* Backtests */}
          <Route path="/backtests/run" element={<BacktestRunnerPage />} />
          <Route path="/backtests/jobs" element={<JobsPage />} />
          <Route path="/backtests/compare" element={<Lazy><BacktestComparePage /></Lazy>} />
          <Route path="/backtests/:id" element={<Lazy><BacktestResultsPage /></Lazy>} />
          <Route path="/optimizations/:sweepId" element={<Lazy><SweepDetailPage /></Lazy>} />
          {/* Features */}
          <Route path="/features/connecting-dots" element={<ConnectingDotsPage />} />
          <Route path="/features/vix-index" element={<Lazy><VixIndexPage /></Lazy>} />
          <Route path="/features/market-holidays" element={<MarketHolidaysPage />} />
          <Route path="/features/risk-calculator" element={<RiskCalculatorPage />} />
          <Route path="/features/multiple-window" element={<MultipleWindowPage />} />
          <Route path="/features/world-indices" element={<Lazy><WorldIndicesPage /></Lazy>} />
          {/* Data Ops — operator console for the expired/OI backfill (wave PR-DO) */}
          <Route path="/data-ops" element={<Navigate to="/data-ops/status" replace />} />
          <Route path="/data-ops/status" element={<StatusPage />} />
          <Route path="/data-ops/coverage" element={<CoveragePage />} />
          <Route path="/data-ops/collection" element={<CollectionWizardPage />} />
          <Route path="/data-ops/query" element={<QueryConsolePage />} />
          <Route path="/data-ops/export" element={<ExportWizardPage />} />
          <Route path="/data-ops/ingest-health" element={<IngestHealthPage />} />
        </Route>
      </Route>
      </Routes>
    </>
  );
}
