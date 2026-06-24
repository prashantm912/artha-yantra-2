import { useEffect, useRef, useState } from 'react';
import { Columns3 } from 'lucide-react';
import { cn } from '../lib/cn.ts';

// oipulse "Column Setting" control (§20.7.3): a popover of checkboxes toggling the optional chain
// columns. Closes on outside-click. The visible-set lives in the page (so it can drive the table).
export interface ColumnToggle {
  key: string;
  label: string;
}

interface ColumnSettingsProps {
  columns: ColumnToggle[];
  visible: Record<string, boolean>;
  onToggle: (key: string) => void;
}

export function ColumnSettings({ columns, visible, onToggle }: ColumnSettingsProps) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return undefined;
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
        className="inline-flex h-9 items-center gap-1.5 rounded-md border border-ay-border bg-surface-1 px-3 text-sm text-ay-text hover:border-accent"
      >
        <Columns3 aria-hidden="true" className="size-4" />
        Columns
      </button>
      {open && (
        <div className="absolute right-0 top-11 z-20 w-56 rounded-lg border border-ay-border bg-surface-1 p-2 shadow-xl">
          <div className="mb-1 px-1 text-xs font-semibold uppercase text-ay-muted">
            Optional columns
          </div>
          <ul className="space-y-0.5">
            {columns.map((c) => (
              <li key={c.key}>
                <label
                  className={cn(
                    'flex cursor-pointer items-center gap-2 rounded px-1.5 py-1 text-sm text-ay-text',
                    'hover:bg-surface-2',
                  )}
                >
                  <input
                    type="checkbox"
                    checked={!!visible[c.key]}
                    onChange={() => onToggle(c.key)}
                  />
                  {c.label}
                </label>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
