import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guard';

// All lazy, all auth-guarded except /login (C-2.24). The Stage-C page set is
// deliberately minimal: /signals lands in Phase 26; everything else is E/F.
export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./pages/login/login-page').then((m) => m.LoginPage),
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./shell/app-shell').then((m) => m.AppShell),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
      {
        path: 'dashboard',
        title: 'Dashboard · ArthaYantra',
        loadComponent: () =>
          import('./pages/dashboard/dashboard-page').then((m) => m.DashboardPage),
      },
      {
        path: 'signals',
        title: 'Signals · ArthaYantra',
        loadComponent: () => import('./pages/signals/signals-page').then((m) => m.SignalsPage),
      },
      {
        path: 'charts',
        title: 'Charts · ArthaYantra',
        loadComponent: () => import('./pages/charts/charts-page').then((m) => m.ChartsPage),
      },
      {
        path: 'options',
        title: 'Options · ArthaYantra',
        loadComponent: () => import('./pages/options/options-page').then((m) => m.OptionsPage),
      },
      {
        path: 'futures',
        title: 'Futures · ArthaYantra',
        loadComponent: () => import('./pages/futures/futures-page').then((m) => m.FuturesPage),
      },
      {
        path: 'paper',
        title: 'Paper trading · ArthaYantra',
        loadComponent: () => import('./pages/paper/paper-page').then((m) => m.PaperPage),
      },
      {
        path: 'strategies',
        title: 'Strategies · ArthaYantra',
        loadComponent: () =>
          import('./pages/strategies/strategy-list-page').then((m) => m.StrategyListPage),
      },
      {
        path: 'strategies/compare',
        title: 'Compare strategies · ArthaYantra',
        loadComponent: () =>
          import('./pages/strategies/strategy-compare-page').then((m) => m.StrategyComparePage),
      },
      {
        path: 'strategies/:id/edit',
        title: 'Strategy editor · ArthaYantra',
        loadComponent: () =>
          import('./pages/strategies/strategy-editor-page').then((m) => m.StrategyEditorPage),
        canDeactivate: [
          (c: import('./pages/strategies/strategy-editor-page').StrategyEditorPage) =>
            c.canDeactivate(),
        ],
      },
      {
        path: 'strategies/:id/versions',
        title: 'Strategy versions · ArthaYantra',
        loadComponent: () => import('./pages/strategies/versions-page').then((m) => m.VersionsPage),
      },
      {
        path: 'backtests/run',
        title: 'Backtest runner · ArthaYantra',
        loadComponent: () => import('./pages/backtests/runner-page').then((m) => m.RunnerPage),
      },
      {
        path: 'backtests/compare',
        title: 'Compare runs · ArthaYantra',
        loadComponent: () => import('./pages/backtests/compare-page').then((m) => m.ComparePage),
      },
      {
        path: 'backtests/jobs',
        title: 'Jobs · ArthaYantra',
        loadComponent: () => import('./pages/jobs/jobs-page').then((m) => m.JobsPage),
      },
      {
        path: 'backtests/:id',
        title: 'Backtest result · ArthaYantra',
        loadComponent: () => import('./pages/backtests/results-page').then((m) => m.ResultsPage),
      },
      {
        path: 'optimizations/:sweepId',
        title: 'Sweep explorer · ArthaYantra',
        loadComponent: () =>
          import('./pages/optimizations/sweep-detail-page').then((m) => m.SweepDetailPage),
      },
      {
        path: 'watchlists',
        title: 'Watchlists · ArthaYantra',
        loadComponent: () =>
          import('./pages/watchlists/watchlists-page').then((m) => m.WatchlistsPage),
      },
      {
        path: 'settings',
        title: 'Settings · ArthaYantra',
        loadComponent: () => import('./pages/settings/settings-page').then((m) => m.SettingsPage),
      },
      {
        path: 'home',
        loadComponent: () => import('./pages/home/home-page').then((m) => m.HomePage),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
