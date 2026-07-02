import { create } from 'zustand';
import { ApiError, apiFetch } from '../api/client.ts';
import { applyTheme, loadTheme, type ThemeId } from '../lib/theme.ts';

// SessionStore (C-2.22), ported to Zustand: gateway auth status + theme + mock-mode indicator.
// Navigation stays in components (React Router) — methods return results, not redirects.

export type AuthStatus = 'unknown' | 'checking' | 'authenticated' | 'anonymous';

interface SessionState {
  auth: AuthStatus;
  loggingIn: boolean;
  loginError: string | null;
  theme: ThemeId;
  /** Mock-mode banner: kite.session === 'MOCK' from /api/v1/system/status. */
  mockMode: boolean;

  /** One-shot probe (the RequireAuth data source). 200 alone ≠ signed in — the body decides. */
  probe(): Promise<boolean>;
  /** POSTs the form-urlencoded password; resolves true on success (component navigates). */
  login(password: string): Promise<boolean>;
  logout(): Promise<void>;
  setTheme(id: ThemeId): void;
  refreshSystemStatus(): Promise<void>;
}

export const useSession = create<SessionState>((set) => ({
  auth: 'unknown',
  loggingIn: false,
  loginError: null,
  theme: loadTheme(),
  mockMode: false,

  async probe() {
    set({ auth: 'checking' });
    try {
      const session = await apiFetch<{ authenticated?: boolean }>('/auth/session');
      const ok = session?.authenticated === true;
      set({ auth: ok ? 'authenticated' : 'anonymous' });
      return ok;
    } catch {
      set({ auth: 'anonymous' });
      return false;
    }
  },

  async login(password: string) {
    set({ loggingIn: true, loginError: null });
    try {
      await apiFetch('/auth/login', {
        method: 'POST',
        body: new URLSearchParams({ password }).toString(),
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        silenceToast: true, // login errors surface inline, not as a toast
      });
      set({ auth: 'authenticated', loggingIn: false });
      return true;
    } catch (err) {
      const message = err instanceof ApiError ? err.message || 'Login failed' : 'Login failed';
      set({ loggingIn: false, loginError: message });
      return false;
    }
  },

  async logout() {
    try {
      await apiFetch('/auth/logout', { method: 'POST' });
    } catch {
      /* clear locally regardless */
    }
    set({ auth: 'anonymous' });
  },

  setTheme(id: ThemeId) {
    applyTheme(id);
    set({ theme: id });
  },

  async refreshSystemStatus() {
    try {
      // kite.raw is the un-rolled feed state (P1-11): kite.session maps MOCK → VALID for the
      // health view, so checking session here meant the MOCK tag could never render.
      const status = await apiFetch<{ kite?: { raw?: string } }>('/system/status');
      set({ mockMode: status?.kite?.raw === 'MOCK' });
    } catch {
      /* status is best-effort */
    }
  },
}));
