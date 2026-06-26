import { Rows2, Rows3 } from 'lucide-react';
import { cn } from '../lib/cn.ts';
import type { Density } from '../lib/density.ts';

// Two aria-pressed icon-buttons toggling the chain-grid row density (Comfortable / Compact). Each
// carries an explicit accessible name (aria-label); the lucide glyph is aria-hidden. Focus rides the
// global :focus-visible rule (index.css §1.4) — no bare focus:outline-none.
interface DensityToggleProps {
  value: Density;
  onChange: (value: Density) => void;
}

export function DensityToggle({ value, onChange }: DensityToggleProps) {
  return (
    <div className="inline-flex h-9 overflow-hidden rounded-md border border-ay-border bg-surface-2">
      <button
        type="button"
        aria-label="Comfortable density"
        title="Comfortable rows — more spacing, fewer rows on screen"
        aria-pressed={value === 'comfortable'}
        onClick={() => onChange('comfortable')}
        className={cn(
          'flex w-9 items-center justify-center text-ay-text',
          value === 'comfortable' ? 'bg-accent text-surface-0' : 'hover:bg-surface-1',
        )}
      >
        <Rows3 aria-hidden="true" className="size-4" />
      </button>
      <button
        type="button"
        aria-label="Compact density"
        title="Compact rows — tighter spacing, more rows on screen"
        aria-pressed={value === 'compact'}
        onClick={() => onChange('compact')}
        className={cn(
          'flex w-9 items-center justify-center border-l border-ay-border text-ay-text',
          value === 'compact' ? 'bg-accent text-surface-0' : 'hover:bg-surface-1',
        )}
      >
        <Rows2 aria-hidden="true" className="size-4" />
      </button>
    </div>
  );
}
