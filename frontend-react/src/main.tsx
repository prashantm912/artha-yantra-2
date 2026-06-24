import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router-dom';
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

const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 30_000, retry: false, refetchOnWindowFocus: false } },
});

// Gap-heal: on every WS reconnect, re-fetch the REST snapshots (replaces Angular's reconnects$).
wsClient.onReconnect(() => void queryClient.invalidateQueries());

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);
