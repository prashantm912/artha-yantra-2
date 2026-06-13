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
      { path: '', pathMatch: 'full', redirectTo: 'home' },
      {
        path: 'home',
        loadComponent: () => import('./pages/home/home-page').then((m) => m.HomePage),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
