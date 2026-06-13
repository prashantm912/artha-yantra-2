import { computed, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { patchState, signalStore, withComputed, withHooks, withMethods, withState } from '@ngrx/signals';

/** Gateway-session auth states. */
export type AuthStatus = 'unknown' | 'checking' | 'authenticated' | 'anonymous';

interface SessionState {
  auth: AuthStatus;
  loggingIn: boolean;
  loginError: string | null;
  theme: 'dark' | 'light';
  /** Mock-mode banner source: kite.session === MOCK from /api/v1/system/status. */
  mockMode: boolean;
}

const THEME_KEY = 'ay.theme';

function initialTheme(): 'dark' | 'light' {
  const stored = localStorage.getItem(THEME_KEY);
  if (stored === 'light' || stored === 'dark') {
    return stored;
  }
  // first run respects the OS preference; dark is the product default (C-2.23).
  // matchMedia is absent under jsdom — tests get the default.
  if (typeof window.matchMedia === 'function') {
    return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
  }
  return 'dark';
}

function applyTheme(theme: 'dark' | 'light'): void {
  document.documentElement.classList.toggle('ay-light', theme === 'light');
}

/**
 * SessionStore (C-2.22): gateway auth status + theme + mock-mode indicator. Server-cache state
 * is populated ONLY by store methods through the typed client; templates read signals.
 */
export const SessionStore = signalStore(
  { providedIn: 'root' },
  withState<SessionState>({
    auth: 'unknown',
    loggingIn: false,
    loginError: null,
    theme: 'dark',
    mockMode: false,
  }),
  withComputed((store) => ({
    authenticated: computed(() => store.auth() === 'authenticated'),
  })),
  withMethods((store, http = inject(HttpClient), router = inject(Router)) => ({
    /**
     * One-shot session probe (the auth guard's data source). The gateway exposes
     * /api/v1/auth/session as a PUBLIC endpoint that always answers 200 with an
     * `authenticated` boolean — so a 200 alone does NOT mean signed in; the body decides.
     */
    probe(): Promise<boolean> {
      patchState(store, { auth: 'checking' });
      return new Promise((resolve) => {
        http.get<{ authenticated?: boolean }>('/api/v1/auth/session').subscribe({
          next: (session) => {
            const ok = session?.authenticated === true;
            patchState(store, { auth: ok ? 'authenticated' : 'anonymous' });
            resolve(ok);
          },
          error: () => {
            patchState(store, { auth: 'anonymous' });
            resolve(false);
          },
        });
      });
    },

    login(password: string, returnUrl: string): void {
      patchState(store, { loggingIn: true, loginError: null });
      const body = new URLSearchParams({ password }).toString();
      http
        .post('/api/v1/auth/login', body, {
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        })
        .subscribe({
          next: () => {
            patchState(store, { auth: 'authenticated', loggingIn: false });
            void router.navigateByUrl(returnUrl || '/');
          },
          error: (error: { error?: { message?: string } }) => {
            patchState(store, {
              loggingIn: false,
              loginError: error?.error?.message ?? 'Login failed',
            });
          },
        });
    },

    logout(): void {
      http.post('/api/v1/auth/logout', null).subscribe({
        complete: () => {
          patchState(store, { auth: 'anonymous' });
          void router.navigateByUrl('/login');
        },
        error: () => {
          patchState(store, { auth: 'anonymous' });
          void router.navigateByUrl('/login');
        },
      });
    },

    toggleTheme(): void {
      const next = store.theme() === 'dark' ? 'light' : 'dark';
      localStorage.setItem(THEME_KEY, next);
      applyTheme(next);
      patchState(store, { theme: next });
    },

    /** Seeds the mock-mode banner from system status (Caffeine-cached server-side). */
    refreshSystemStatus(): void {
      http.get<{ kite?: { session?: string } }>('/api/v1/system/status').subscribe({
        next: (status) =>
          patchState(store, { mockMode: status?.kite?.session === 'MOCK' }),
        error: () => undefined,
      });
    },
  })),
  withHooks({
    onInit(store) {
      const theme = initialTheme();
      applyTheme(theme);
      patchState(store, { theme });
    },
  }),
);
