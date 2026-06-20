import { useState } from 'react';
import { THEMES, applyTheme, loadTheme, type ThemeId } from './lib/theme.ts';

// PR-F placeholder shell. Proves the Vite/React/Tailwind/multi-theme skeleton builds and that
// data-theme swapping re-themes the whole app. Replaced by AppShell (hybrid topbar + mega-menu)
// as the foundation layers in.
export function App() {
  const [theme, setTheme] = useState<ThemeId>(loadTheme);

  function pick(id: ThemeId) {
    setTheme(id);
    applyTheme(id);
  }

  return (
    <div className="min-h-screen bg-surface-0 text-ay-text font-sans">
      <h1 className="ay-sr-only">ArthaYantra</h1>
      <header className="flex items-center justify-between border-b border-ay-border bg-surface-1 px-4 py-3">
        <span className="font-semibold text-accent">ArthaYantra</span>
        <select
          aria-label="Theme"
          className="rounded border border-ay-border bg-surface-2 px-2 py-1 text-sm text-ay-text"
          value={theme}
          onChange={(e) => pick(e.target.value as ThemeId)}
        >
          {THEMES.map((t) => (
            <option key={t.id} value={t.id}>
              {t.label}
            </option>
          ))}
        </select>
      </header>

      <main className="p-6">
        <p className="text-ay-muted">React Foundation (PR-F) — skeleton online.</p>
        <div className="mt-4 flex gap-3">
          <span className="rounded px-3 py-1 text-sm font-medium text-bull ring-1 ring-bull/40">Bull</span>
          <span className="rounded px-3 py-1 text-sm font-medium text-bear ring-1 ring-bear/40">Bear</span>
          <span className="rounded px-3 py-1 text-sm font-medium text-warn ring-1 ring-warn/40">Warn</span>
          <span className="rounded px-3 py-1 text-sm font-medium text-accent ring-1 ring-accent/40">Accent</span>
        </div>
      </main>
    </div>
  );
}
