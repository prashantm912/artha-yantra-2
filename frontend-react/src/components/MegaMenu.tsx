import { useEffect, useRef, useState } from 'react';
import { NavLink } from 'react-router-dom';
import { cn } from '../lib/cn.ts';

// oipulse section nav (master plan §20): each top-level section is its OWN menu-bar trigger with a
// single-column dropdown of its pages (was a single "All Menu" mega-panel — split out so the bar is
// scannable, not one giant grid). One dropdown open at a time; outside-click / Esc / navigation
// closes. The bar flex-wraps on mobile (hybrid shell), so it works as dropdowns at 480px too.

interface MenuItem {
  label: string;
  to?: string; // undefined = not yet built (rendered disabled)
}
interface MenuSection {
  title: string;
  items: MenuItem[];
}

const SECTIONS: MenuSection[] = [
  {
    title: 'Trading',
    items: [
      { label: 'Scalping Cockpit', to: '/cockpit' }, // unified live screen — chain · confluence · straddle · signals · OI heatmap
      { label: 'Scalper Cockpit', to: '/scalper' }, // Phase 4b — live signals + paper order ticket + P&L
      { label: 'Dashboard', to: '/dashboard' }, // cockpit — at-a-glance status/signals/paper/jobs (E-2)
      { label: 'Signals', to: '/signals' }, // cockpit — live feed + reasoning breakdown (C-2.26)
      { label: 'Charts', to: '/charts' }, // cockpit — candlestick + trade/signal overlays (A13)
      { label: 'Paper Trading', to: '/paper' }, // cockpit — positions + ledger + risk limits (F-43)
      { label: 'Orders', to: '/orders' }, // §18.1 — broker orderbook/positions/tradebook/funds (read-only)
      { label: 'Journal', to: '/journal' }, // cockpit — weekly-review entries (F-44A)
      { label: 'Watchlists', to: '/watchlists' }, // cockpit — named lists + screener (E-8)
      { label: 'Settings', to: '/settings' }, // cockpit — Kite/theme/data-sync (E-8)
    ],
  },
  {
    title: 'Options',
    items: [
      { label: 'Options Chain', to: '/options/options-chain' },
      { label: 'OI Analysis', to: '/options/oi-analysis' }, // per-strike intraday time-rows (§20.7.5)
      { label: 'OI Spurt', to: '/options/oi-spurt' },
      { label: 'Trending OI', to: '/options/trending-oi' }, // Wave 2
      { label: 'Trending OI - PA', to: '/options/trending-oi-pa' }, // Wave 3 — + premium (PA) columns
      { label: 'Big OI Movement', to: '/options/big-oi-movement' }, // Wave 2
      { label: 'Options Premium', to: '/options/options-premium' }, // Wave 2
      { label: 'Straddle Chart', to: '/options/straddle-chart' }, // combined CE+PE premium candles (§20.7.6)
      { label: 'Options Chart', to: '/options/options-chart' }, // Wave 3 — per-leg premium candle + OI line
      { label: 'Multiple OI Chart', to: '/options/multiple-oi-chart' }, // Wave 3 — multi-leg OI overlay + price
      { label: 'OI Chart', to: '/options/oi-chart' }, // Wave 3 — Call/Put OI + OI-vs-premium (off strike-series)
      { label: 'OI Statistics', to: '/options/oi-statistics' }, // Wave 3 — OI distribution + PCR
      { label: 'OI Heatmap', to: '/options/oi-heatmap' }, // Wave 3 — strike × time ΔOI grid (CE/PE)
      { label: 'OI Expiry Strategy', to: '/options/oi-expiry-strategy' }, // Wave 3 — per-strike EOD OHLC+OI history
      { label: 'Open & High Strategy', to: '/options/open-high-strategy' }, // Wave 3 — CE/PE Open=High/Low scan + trigger probability
      { label: 'Interval-wise OI', to: '/options/interval-wise-oi' }, // Wave 3 — top OI movers ×15m/60m/daily
      { label: 'Active Strikes OI', to: '/options/active-strikes' }, // Wave 3 — OI + Sentiment series
      { label: 'Active Strikes IV', to: '/options/active-strikes-iv' }, // Wave 3 — IV + price series
    ],
  },
  {
    title: 'Futures',
    items: [
      { label: 'OI Spurt', to: '/futures/oi-spurt' }, // Wave 2
      { label: 'Market Movers', to: '/futures/market-movers' }, // Wave 2
      { label: 'EOD OI Analyzer', to: '/futures/eod-oi-analyzer' }, // Wave 2
      { label: 'OI Analysis', to: '/futures/oi-analysis' }, // Wave 3 — per-interval series table
      { label: 'OI Chart', to: '/futures/oi-chart' }, // Wave 3 — candle + OI dual-axis combo
      { label: 'OI Buzz', to: '/futures/oi-buzz' }, // Wave 3 — constituent %change treemap (Futures Heatmap)
      { label: 'Banks', to: '/futures/banks' }, // Wave 3 — time × 6-bank OI matrix
    ],
  },
  {
    title: 'Equity',
    items: [
      { label: 'Pre-Open Market', to: '/equity/pre-open-market' }, // Upstox market-status + index pre-open snapshot
      { label: 'Breadth', to: '/equity/breadth' }, // Wave 3 — advance/decline + delivery leaders (zero-BE)
      { label: 'Equity Returns', to: '/equity/equity-returns' }, // Wave 3 — multi-window returns screener
      { label: 'Delivery Data', to: '/equity/delivery-data' }, // Wave 3 — per-stock delivery series
      { label: 'Sector Heatmap', to: '/equity/sector-heatmap' }, // Wave 3 — sector-grouped %change treemap
      { label: 'Sector Stats', to: '/equity/sector-stats' }, // Wave 3 — per-sector cards + factor table
      { label: 'Index Contribution', to: '/equity/index-contribution' }, // Wave 3 — weighted contribution
      { label: 'Open=High/Low', to: '/equity/open-high-low' }, // polish — O=H/O=L live setups
      { label: 'News', to: '/equity/news' }, // polish — Upstox per-stock news/announcements
    ],
  },
  {
    title: 'FII / DII',
    items: [
      { label: 'Capital Market', to: '/fii-dii/capital-market' }, // Wave 2
      { label: 'FII Derivative Stats', to: '/fii-dii/fii-derivative-stats' }, // Wave 3
      { label: 'Participant OI', to: '/fii-dii/participant-wise-oi' }, // Wave 2
      { label: 'Long-Short Ratio', to: '/fii-dii/long-short-ratio' }, // Wave 2
    ],
  },
  {
    title: 'Strategies',
    items: [
      { label: 'Strategies', to: '/strategies' }, // cockpit — registry list + versions/publish (E-11)
      { label: 'Strategy Builder', to: '/strategies/strategy-builder' }, // Wave 3 — payoff + greeks
    ],
  },
  {
    title: 'Backtests',
    items: [
      { label: 'Runner', to: '/backtests/run' }, // cockpit — backtest + sweep launcher (E-8)
      { label: 'Jobs', to: '/backtests/jobs' }, // cockpit — live job monitor (jobs.progress WS)
      { label: 'Compare', to: '/backtests/compare' }, // cockpit — up-to-6-run metric matrix + equity overlay
    ],
  },
  {
    title: 'Features',
    items: [
      { label: 'Connecting Dots', to: '/features/connecting-dots' }, // multi-factor matrix (§20.7.8)
      { label: 'Vix & Index', to: '/features/vix-index' }, // Wave 3 — VIX vs index dual-axis lines (zero-BE)
      { label: 'Market Holidays', to: '/features/market-holidays' }, // Wave 3 — NSE holiday reference table
      { label: 'World Indices', to: '/features/world-indices' }, // global-index live quotes (Upstox GLOBAL_INDEX)
      { label: 'Risk Calculator', to: '/features/risk-calculator' }, // §1b — pure-client position-sizing utility
    ],
  },
  {
    title: 'Data Ops',
    items: [
      { label: 'Collection Status', to: '/data-ops/status' }, // PR-DO1 — live backfill progress + logs
      { label: 'Coverage', to: '/data-ops/coverage' }, // PR-DO2 — per-underlying contract/candle coverage
      { label: 'Run Backfill', to: '/data-ops/collection' }, // PR-DO3 — guided collection wizard
      { label: 'Query Console', to: '/data-ops/query' }, // PR-DO5 — read-only SQL over the candle store
      { label: 'Export', to: '/data-ops/export' }, // PR-DO6 — per-contract CSV/JSON export
    ],
  },
];

export function MegaMenu() {
  // null = nothing open; otherwise the title of the one open section.
  const [openSection, setOpenSection] = useState<string | null>(null);
  const ref = useRef<HTMLElement>(null);

  useEffect(() => {
    if (openSection === null) return;
    function onDoc(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpenSection(null);
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') setOpenSection(null);
    }
    document.addEventListener('mousedown', onDoc);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDoc);
      document.removeEventListener('keydown', onKey);
    };
  }, [openSection]);

  return (
    <nav ref={ref} aria-label="Main" className="flex flex-wrap items-center gap-0.5">
      {SECTIONS.map((section) => {
        const open = openSection === section.title;
        return (
          <div key={section.title} className="relative">
            <button
              type="button"
              aria-haspopup="true"
              aria-expanded={open}
              onClick={() => setOpenSection((s) => (s === section.title ? null : section.title))}
              className={cn(
                'flex h-9 items-center gap-1 rounded-md px-2.5 text-sm hover:bg-surface-2',
                open ? 'bg-surface-2 text-accent' : 'text-ay-text',
              )}
            >
              {section.title}
              <span aria-hidden="true" className="text-[0.65rem] text-ay-muted">
                ▾
              </span>
            </button>
            {open && (
              <ul className="absolute left-0 top-11 z-30 min-w-52 rounded-lg border border-ay-border bg-surface-1 p-1.5 shadow-xl">
                {section.items.map((item) => (
                  <li key={item.label}>
                    {item.to ? (
                      <NavLink
                        to={item.to}
                        onClick={() => setOpenSection(null)}
                        className={({ isActive }) =>
                          cn(
                            'block rounded px-2 py-1.5 text-sm hover:bg-surface-2',
                            isActive ? 'text-accent' : 'text-ay-text',
                          )
                        }
                      >
                        {item.label}
                      </NavLink>
                    ) : (
                      <span className="block px-2 py-1.5 text-sm text-ay-muted/60" title="Coming soon">
                        {item.label}
                      </span>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </div>
        );
      })}
    </nav>
  );
}
