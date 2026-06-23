import { useEffect, useRef, useState } from 'react';
import { cn } from '../lib/cn.ts';
import type { ScalperDetail } from '../api/signals.ts';

// Scalper manual-verification checklist (Phase 4, owner-locked): the owner ticks each manual check
// before a scalper signal can be Taken/Placed. Gating is SOFT-WARNING + OVERRIDE — the parent enables
// its Take/Place button only when `confirmed` (all ticks OR the explicit override). Client-only: ticks
// are local state, never sent to the server (the DB invariant status=TAKEN ⇒ owner confirmed is met by
// UI gating). One component, two mount points (/signals detail + /scalper ticket). Re-arms (clears all
// ticks + override) ONLY when the signal changes — a scalperDetail re-fetch keeps the ticks.

const DOT_TONE = {
  supports: 'text-bull ring-bull/40',
  opposes: 'text-bear ring-bear/40',
} as const;

export function ManualVerifyChecklist(props: {
  detail: ScalperDetail;
  onConfirmedChange: (confirmed: boolean) => void;
}) {
  const { detail, onConfirmedChange } = props;
  const checks = detail.manual_checks;

  const [ticked, setTicked] = useState<Record<string, boolean>>({});
  const [overridden, setOverridden] = useState(false);
  // Mobile accordion: start collapsed, auto-expand on first signal load (desktop ignores via md:block).
  const [collapsed, setCollapsed] = useState(true);

  // Re-arm on signal change. The signal identity = its tradeable leg; a plain scalperDetail re-fetch
  // (same leg) does NOT re-arm (ticks survive). `tradeable` uniquely keys the selected scalper signal.
  const signalKey = detail.tradeable;
  const armedFor = useRef<string | null>(null);
  if (armedFor.current !== signalKey) {
    armedFor.current = signalKey;
    setTicked({});
    setOverridden(false);
    setCollapsed(false); // auto-expand on the freshly-loaded signal
  }

  const total = checks.length;
  const tickedCount = checks.filter((c) => ticked[c.key]).length;
  const allTicked = total > 0 && tickedCount === total;
  const confirmed = allTicked || overridden;

  useEffect(() => {
    onConfirmedChange(confirmed);
  }, [confirmed, onConfirmedChange]);

  const toggle = (key: string) => setTicked((t) => ({ ...t, [key]: !t[key] }));

  return (
    <section className="rounded-lg border border-ay-border bg-surface-1" aria-label="Manual verification checklist">
      {/* Header — doubles as the mobile accordion toggle (md:+ it is a static heading). */}
      <button
        type="button"
        onClick={() => setCollapsed((v) => !v)}
        aria-expanded={!collapsed}
        className="flex w-full items-center justify-between gap-2 px-3 py-2 text-left md:cursor-default"
      >
        <span className="text-sm font-semibold text-ay-text">
          Manual verify{' '}
          <span className={cn('tabular-nums', confirmed ? 'text-bull' : 'text-warn')}>
            {tickedCount}/{total}
          </span>
        </span>
        <span aria-hidden="true" className="text-xs text-ay-muted md:hidden">
          {collapsed ? '▾' : '▴'}
        </span>
      </button>

      {/* Body — collapsible on mobile (hidden when collapsed), always shown on md:+. */}
      <div className={cn('space-y-3 px-3 pb-3', collapsed && 'hidden md:block')}>
        {/* Automated confluence dots — read-only, colour + text cue. */}
        {detail.dots.length > 0 && (
          <div>
            <p className="mb-1 text-xs text-ay-muted">
              Automated confluence ({detail.confluence_aggregate.toFixed(2)})
            </p>
            <ul className="flex flex-wrap gap-1.5">
              {detail.dots.map((d) => (
                <li
                  key={d.dot}
                  className={cn(
                    'inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-xs ring-1',
                    d.supports ? DOT_TONE.supports : DOT_TONE.opposes,
                  )}
                >
                  <span aria-hidden="true">{d.supports ? '▲' : '▼'}</span>
                  <span>{d.dot}</span>
                  <span className="ay-sr-only">{d.supports ? '(supports)' : '(opposes)'}</span>
                </li>
              ))}
            </ul>
          </div>
        )}

        {/* The manual checks (rendered dynamically). */}
        <ul className="space-y-2">
          {checks.map((c) => (
            <li key={c.key} className="rounded border border-ay-border bg-surface-2 p-2">
              <label className="flex cursor-pointer items-start gap-2">
                <input
                  type="checkbox"
                  className="mt-0.5 accent-accent"
                  checked={!!ticked[c.key]}
                  onChange={() => toggle(c.key)}
                />
                <span className="min-w-0">
                  <span className="block text-sm font-medium text-ay-text">{c.label}</span>
                  {c.assist && <span className="mt-0.5 block text-xs text-ay-muted">{c.assist}</span>}
                  {c.doc_ref && (
                    <span className="mt-0.5 block text-xs text-accent [overflow-wrap:anywhere]">
                      {c.doc_ref}
                    </span>
                  )}
                </span>
              </label>
            </li>
          ))}
        </ul>

        {/* Soft-warning when not all confirmed. */}
        {!confirmed && (
          <p
            role="alert"
            className="rounded border border-warn/40 bg-warn/10 px-2 py-1.5 text-xs text-warn"
          >
            {total - tickedCount} of {total} checks unconfirmed
          </p>
        )}

        {/* Override — confirm despite unchecked. */}
        <label className="flex cursor-pointer items-center gap-2 text-xs text-ay-muted">
          <input
            type="checkbox"
            className="accent-warn"
            checked={overridden}
            onChange={() => setOverridden((v) => !v)}
          />
          <span>Confirm despite unchecked</span>
        </label>
      </div>
    </section>
  );
}
