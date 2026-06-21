import { useEffect, useMemo, useRef, useState } from 'react';
import { cn } from '../../lib/cn.ts';

// A small checkbox-dropdown multi-select (cloned from ColumnSettings): a trigger button (aria-haspopup +
// aria-expanded), outside-click close, a searchable list of native checkbox rows. Native checkboxes give
// keyboard + screen-reader support with ZERO custom ARIA (no listbox/combobox) — the repo a11y bar. Used
// by the Multiple OI Chart to pick option legs ("57200 CE").

interface LegMultiSelectProps {
  options: string[];
  selected: string[];
  onToggle: (value: string) => void;
}

export function LegMultiSelect({ options, selected, onToggle }: LegMultiSelectProps) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return undefined;
    function onDoc(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener('mousedown', onDoc);
    return () => document.removeEventListener('mousedown', onDoc);
  }, [open]);

  const filtered = useMemo(() => {
    const q = search.trim().toUpperCase();
    return q ? options.filter((o) => o.toUpperCase().includes(q)) : options;
  }, [options, search]);

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        aria-haspopup="true"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        className="h-9 rounded-md border border-ay-border bg-surface-1 px-3 text-sm text-ay-text hover:border-accent"
      >
        {selected.length > 0 ? `${selected.length} selected` : 'Select strikes'} ▾
      </button>
      {open && (
        <div className="absolute left-0 top-11 z-20 w-56 rounded-lg border border-ay-border bg-surface-1 p-2 shadow-xl">
          <input
            type="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search strike"
            aria-label="Search strike"
            className="mb-1 h-8 w-full rounded border border-ay-border bg-surface-1 px-2 text-sm text-ay-text outline-none focus:border-accent"
          />
          <ul className="max-h-64 space-y-0.5 overflow-y-auto">
            {filtered.map((o) => (
              <li key={o}>
                <label className={cn('flex cursor-pointer items-center gap-2 rounded px-1.5 py-1 text-sm text-ay-text', 'hover:bg-surface-2')}>
                  <input
                    type="checkbox"
                    className="accent-accent"
                    checked={selected.includes(o)}
                    onChange={() => onToggle(o)}
                  />
                  {o}
                </label>
              </li>
            ))}
            {filtered.length === 0 && (
              <li className="px-1.5 py-1 text-xs text-ay-muted">No matches</li>
            )}
          </ul>
        </div>
      )}
    </div>
  );
}
