import { useEffect, useState } from 'react';
import { Outlet, useNavigate } from 'react-router-dom';
import { useSession } from '../stores/session.store.ts';
import { useWsState } from '../stores/useWsState.ts';
import { wsClient } from '../lib/wsClient.ts';
import { THEMES, type ThemeId } from '../lib/theme.ts';
import { MegaMenu } from './MegaMenu.tsx';
import { Button } from './atoms/Button.tsx';
import { cn } from '../lib/cn.ts';

// Hybrid shell (master plan §20): AY topbar (IST clock · mock tag · WS pill · theme picker · logout)
// + oipulse mega-menu. Responsive — topbar wraps and the mega-menu is a dropdown on mobile.

const IST = new Intl.DateTimeFormat('en-IN', {
  timeZone: 'Asia/Kolkata',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
});

function IstClock() {
  const [now, setNow] = useState(() => IST.format(new Date()));
  useEffect(() => {
    const id = setInterval(() => setNow(IST.format(new Date())), 1000);
    return () => clearInterval(id);
  }, []);
  return (
    <span className="text-xs tabular-nums text-ay-muted" aria-label="Indian Standard Time">
      {now} IST
    </span>
  );
}

const PILL: Record<string, string> = {
  connected: 'text-bull',
  connecting: 'text-warn',
  reconnecting: 'text-warn',
  idle: 'text-ay-muted',
};

function WsPill() {
  const state = useWsState();
  return (
    <span className={cn('text-xs', PILL[state])} aria-live="polite">
      ● WS {state}
    </span>
  );
}

export function AppShell() {
  const theme = useSession((s) => s.theme);
  const setTheme = useSession((s) => s.setTheme);
  const mockMode = useSession((s) => s.mockMode);
  const logout = useSession((s) => s.logout);
  const refreshSystemStatus = useSession((s) => s.refreshSystemStatus);
  const navigate = useNavigate();

  useEffect(() => {
    wsClient.activate();
    void refreshSystemStatus();
  }, [refreshSystemStatus]);

  async function onLogout() {
    await logout();
    navigate('/login', { replace: true });
  }

  return (
    <div className="min-h-screen bg-surface-0 text-ay-text">
      {/* Skip-to-content — first focusable; visually-hidden until keyboard-focused, then jumps to <main>. */}
      <a
        href="#main"
        className="sr-only rounded-md border border-ay-border bg-surface-1 px-3 py-2 text-sm text-ay-text focus:not-sr-only focus:absolute focus:left-3 focus:top-3 focus:z-[60]"
      >
        Skip to main content
      </a>
      <header className="flex flex-wrap items-center gap-3 border-b border-ay-border bg-surface-1 px-4 py-2">
        <span className="font-semibold text-accent">ArthaYantra</span>
        <MegaMenu />
        <div className="ml-auto flex flex-wrap items-center gap-3">
          {/* Phone: clock + theme picker hide below md (theme lives in Settings too) — the wrapped
              topbar stacked ~3 rows and ate ~300px of a 915px viewport (audit 2026-07-02 §4). */}
          <span className="hidden md:inline-flex">
            <IstClock />
          </span>
          {/* --ay-text on the faint warn tint keeps WCAG AA in every theme — text-warn on
              bg-warn/20 measured 3.81:1 and failed axe on EVERY mock-stack page (e2e repair). */}
          {mockMode && (
            <span className="rounded bg-warn/10 px-1.5 py-0.5 text-xs font-semibold text-ay-text ring-1 ring-warn">
              MOCK
            </span>
          )}
          <WsPill />
          <select
            aria-label="Theme"
            value={theme}
            onChange={(e) => setTheme(e.target.value as ThemeId)}
            className="hidden h-8 rounded border border-ay-border bg-surface-2 px-1.5 text-xs text-ay-text md:block"
          >
            {THEMES.map((t) => (
              <option key={t.id} value={t.id}>
                {t.label}
              </option>
            ))}
          </select>
          <Button variant="outline" size="sm" onClick={onLogout}>
            Logout
          </Button>
        </div>
      </header>

      <main id="main" tabIndex={-1} className="p-4" data-testid="app-shell">
        <Outlet />
      </main>
    </div>
  );
}
