import { useEffect, useState } from 'react';

interface DateInputProps {
  value: string | null;
  onChange: (value: string | null) => void;
  ariaLabel: string;
  /** Native hover tooltip explaining the control (#16). */
  title?: string;
  /** Earliest selectable session (default = well before any captured data). */
  min?: string;
  /** Latest selectable session (default = today). */
  max?: string;
}

/**
 * Native date picker styled with --ay-* tokens. Typed input is DRAFTED locally and only sane
 * ISO dates inside [min, max] propagate upward (audit 2026-07-02 §9.3: a naturally-typed
 * "02-07-72026" reached the server as year 72026) — partial keystrokes (year "0002…") no longer
 * fire queries, and the min/max attributes give the native picker the same bounds.
 */
export function DateInput({
  value,
  onChange,
  ariaLabel,
  title,
  min = '2020-01-01',
  max,
}: DateInputProps) {
  const [draft, setDraft] = useState(value ?? '');
  useEffect(() => setDraft(value ?? ''), [value]);
  const maxDate = max ?? new Date().toISOString().slice(0, 10);

  return (
    <input
      type="date"
      aria-label={ariaLabel}
      title={title}
      value={draft}
      min={min}
      max={maxDate}
      onChange={(e) => {
        const v = e.target.value;
        setDraft(v);
        if (!v) {
          onChange(null);
        } else if (v >= min && v <= maxDate) {
          // the sane-range check works lexicographically: out-of-range years ("0002…", "72026…")
          // fail one of the two bounds, so partial/garbage input never fires a query
          onChange(v);
        }
      }}
      className="h-9 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text outline-none focus:border-accent invalid:border-bear"
    />
  );
}
