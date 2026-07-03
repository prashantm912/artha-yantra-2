import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router-dom';
import { toast } from 'sonner';
import { ApiError } from './api/client.ts';
// Self-host fonts (offline/loopback — never the Google CDN). Imported ABOVE index.css so the
// @font-face rules land before the cascade uses --font-sans/-mono/-display.
import '@fontsource-variable/inter'; // 'Inter Variable' — data/UI face (wght 100–900)
import '@fontsource-variable/jetbrains-mono'; // 'JetBrains Mono Variable' — tabular numeric cells
import '@fontsource-variable/newsreader'; // 'Newsreader Variable' — display/title face (≥22px only)
import './index.css';
import { App } from './App.tsx';
import { applyTheme, loadTheme } from './lib/theme.ts';
import { wsClient } from './lib/wsClient.ts';

// Apply the persisted theme before first paint (no flash).
applyTheme(loadTheme());

// Global error → toast (revamp §3.3.4). 422 DATA_GAP is tagged `silenced` and stays quiet (the page
// renders its empty state); genuine 500s/network drops surface a toast AND the page's inline Retry card.
function reportError(error: unknown, fallback: string) {
  if (error instanceof ApiError && error.silenced) return;
  toast.error(error instanceof Error ? error.message : fallback);
}

const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 30_000, retry: false, refetchOnWindowFocus: false } },
  queryCache: new QueryCache({ onError: (error) => reportError(error, 'Failed to load data.') }),
  mutationCache: new MutationCache({ onError: (error) => reportError(error, 'The action failed.') }),
});

// Gap-heal: on WS reconnect, re-fetch the REST snapshots (replaces Angular's reconnects$).
// Debounced 2s and gated on the connection still holding — a flapping socket used to refire the
// ENTIRE query cache per flap, storming services that were still coming back up.
let reconnectHealTimer: number | undefined;
wsClient.onReconnect(() => {
  window.clearTimeout(reconnectHealTimer);
  reconnectHealTimer = window.setTimeout(() => {
    if (wsClient.state === 'connected') void queryClient.invalidateQueries();
  }, 2000);
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);
