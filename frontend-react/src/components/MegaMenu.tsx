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
      { label: 'OI Analysis' }, // per-strike intraday — Wave 1 (§20.6); distinct from the chain
      { label: 'OI Spurt' },
      { label: 'OI Statistics' },
      { label: 'Options Premium' },
      { label: 'Trending OI' },
      { label: 'Active Strikes OI' },
      { label: 'Big OI Movement' },
    ],
  },
  { title: 'Futures', items: [{ label: 'OI Analysis' }, { label: 'OI Spurt' }, { label: 'Banks' }] },
  { title: 'Equity', items: [{ label: 'Breadth' }, { label: 'Sector Stats' }] },
  { title: 'FII / DII', items: [{ label: 'Capital Market' }, { label: 'Participant OI' }] },
  { title: 'Features', items: [{ label: 'Connecting Dots' }, { label: 'Vix & Index' }] },
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
