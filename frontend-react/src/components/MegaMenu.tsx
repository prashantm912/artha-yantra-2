import { useEffect, useRef, useState } from 'react';
import { NavLink } from 'react-router-dom';
import { cn } from '../lib/cn.ts';

// oipulse "All Menu" mega-dropdown (master plan §20): full-width panel, columns by section. Opens on
// click, closes on outside-click / navigation. Sections mirror the oipulse menu map; items fill in
// per wave — PR-F wires only Options → OI Analysis. Works as a dropdown on mobile too (hybrid shell).

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
    title: 'Options',
    items: [
      { label: 'Options Chain', to: '/options/options-chain' },
      { label: 'OI Analysis', to: '/options/oi-analysis' }, // per-strike intraday time-rows (§20.7.5)
      { label: 'OI Spurt', to: '/options/oi-spurt' },
      { label: 'Trending OI', to: '/options/trending-oi' }, // Wave 2
      { label: 'Big OI Movement', to: '/options/big-oi-movement' }, // Wave 2
      { label: 'Options Premium', to: '/options/options-premium' }, // Wave 2
      { label: 'Straddle Chart', to: '/options/straddle-chart' }, // combined CE+PE premium candles (§20.7.6)
      { label: 'Options Chart', to: '/options/options-chart' }, // Wave 3 — per-leg premium candle + OI line
      { label: 'Multiple OI Chart', to: '/options/multiple-oi-chart' }, // Wave 3 — multi-leg OI overlay + price
      { label: 'OI Chart', to: '/options/oi-chart' }, // Wave 3 — Call/Put OI + OI-vs-premium (off strike-series)
      { label: 'OI Statistics', to: '/options/oi-statistics' }, // Wave 3 — OI distribution + PCR
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
      { label: 'OI Buzz' }, // deferred (constituent treemap)
      { label: 'Banks', to: '/futures/banks' }, // Wave 3 — time × 6-bank OI matrix
    ],
  },
  { title: 'Equity', items: [{ label: 'Breadth' }, { label: 'Sector Stats' }] },
  {
    title: 'FII / DII',
    items: [
      { label: 'Capital Market', to: '/fii-dii/capital-market' }, // Wave 2
      { label: 'Participant OI', to: '/fii-dii/participant-wise-oi' }, // Wave 2
      { label: 'Long-Short Ratio', to: '/fii-dii/long-short-ratio' }, // Wave 2
    ],
  },
  {
    title: 'Features',
    items: [
      { label: 'Connecting Dots', to: '/features/connecting-dots' }, // multi-factor matrix (§20.7.8)
      { label: 'Vix & Index' },
    ],
  },
];

export function MegaMenu() {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    function onDoc(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener('mousedown', onDoc);
    return () => document.removeEventListener('mousedown', onDoc);
  }, [open]);

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        aria-haspopup="true"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        className="h-9 rounded-md border border-ay-border bg-surface-1 px-3 text-sm text-ay-text hover:border-accent"
      >
        All Menu ▾
      </button>
      {open && (
        <div className="absolute left-0 top-11 z-20 grid w-[min(92vw,52rem)] grid-cols-2 gap-4 rounded-lg border border-ay-border bg-surface-1 p-4 shadow-xl sm:grid-cols-3 md:grid-cols-5">
          {SECTIONS.map((section) => (
            <div key={section.title}>
              <div className="mb-1 text-xs font-semibold uppercase text-ay-muted">
                {section.title}
              </div>
              <ul className="space-y-0.5">
                {section.items.map((item) => (
                  <li key={item.label}>
                    {item.to ? (
                      <NavLink
                        to={item.to}
                        onClick={() => setOpen(false)}
                        className={({ isActive }) =>
                          cn(
                            'block rounded px-1.5 py-1 text-sm hover:bg-surface-2',
                            isActive ? 'text-accent' : 'text-ay-text',
                          )
                        }
                      >
                        {item.label}
                      </NavLink>
                    ) : (
                      <span className="block px-1.5 py-1 text-sm text-ay-muted/60" title="Coming soon">
                        {item.label}
                      </span>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
