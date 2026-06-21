import { useMemo, useState } from 'react';
import { cn } from '../lib/cn.ts';
import type { ConnectingDotsRow } from '../api/types.ts';
import { FACTOR_COLUMNS, factorMeta, trendMeta } from '../core/connectingDots.ts';

// oipulse "Connecting Dots" matrix (§20.7.8): 13 columns — Date Time · Trend (5-state) · 11 factor
// columns (3-state ↑/↓/↔). Rows are newest interval first (the BE sorts them). Client-side pagination
// (25/page), extreme-trend rows get a faint maroon tint, and the signals legend sits below. The glyph
// carries the signal for non-colour users (aria-label), matching the OiBadge4 a11y pattern.

const PAGE_SIZE = 25;

/** One 3-state factor cell — coloured glyph + sr/hover label. */
function FactorCell({ code }: { code: number }) {
  const m = factorMeta(code);
  return (
    <span className={cn('font-semibold tabular-nums', m.tone)} aria-label={m.label} title={m.label}>
      {m.glyph}
    </span>
  );
}

/** The 5-state composite Trend badge (glyph + label, toned). */
function TrendCell({ code }: { code: number }) {
  const m = trendMeta(code);
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded border border-ay-border px-1.5 py-0.5 text-xs font-medium',
        m.tone,
      )}
      aria-label={m.label}
      title={m.label}
    >
      <span aria-hidden="true">{m.glyph}</span>
      {m.label}
    </span>
  );
}

function Legend() {
  const pills: Array<{ text: string; tone: string }> = [
    { text: 'Ext. Bullish ↑', tone: 'text-bull' },
    { text: 'Ext. Bearish ↓', tone: 'text-bear' },
    { text: '↑ = Bullish', tone: 'text-bull' },
    { text: '↓ = Bearish', tone: 'text-bear' },
    { text: '↔ = Neutral', tone: 'text-accent' },
  ];
  return (
    <div className="mt-3 flex flex-wrap gap-2 rounded border border-ay-border bg-surface-1 p-2">
      {pills.map((p) => (
        <span
          key={p.text}
          className={cn('rounded border border-ay-border px-2 py-0.5 text-xs font-medium', p.tone)}
        >
          {p.text}
        </span>
      ))}
    </div>
  );
}

interface ConnectingDotsTableProps {
  rows: ConnectingDotsRow[];
  emptyMessage?: string;
}

export function ConnectingDotsTable({
  rows,
  emptyMessage = 'No matrix data — pick an index + interval with an active session.',
}: ConnectingDotsTableProps) {
  const [page, setPage] = useState(0);
  const pageCount = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
  const safePage = Math.min(page, pageCount - 1);
  const shown = useMemo(
    () => rows.slice(safePage * PAGE_SIZE, safePage * PAGE_SIZE + PAGE_SIZE),
    [rows, safePage],
  );
  const from = rows.length === 0 ? 0 : safePage * PAGE_SIZE + 1;
  const to = Math.min(rows.length, (safePage + 1) * PAGE_SIZE);

  return (
    <div>
      <div
        className="overflow-x-auto rounded border border-ay-border"
        tabIndex={0}
        role="region"
        aria-label="Connecting Dots factor matrix"
      >
        <table className="w-full border-collapse whitespace-nowrap text-xs">
          <thead className="bg-surface-1 text-ay-muted">
            <tr>
              <th scope="col" className="px-2 py-1 text-left font-medium">Date Time</th>
              <th scope="col" className="px-2 py-1 text-center font-medium">Trend</th>
              {FACTOR_COLUMNS.map((c) => (
                <th key={c.key} scope="col" className="px-2 py-1 text-center font-medium">
                  {c.label}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {shown.map((r) => {
              const tm = trendMeta(r.trend);
              return (
                <tr
                  key={r.timeInterval}
                  className={cn(
                    'border-t border-ay-border text-ay-text',
                    tm.extreme && 'bg-[color-mix(in_srgb,var(--ay-bear)_8%,transparent)]',
                  )}
                >
                  <td className="px-2 py-1 text-left font-semibold tabular-nums">{r.timeInterval}</td>
                  <td className="px-2 py-1 text-center"><TrendCell code={r.trend} /></td>
                  {FACTOR_COLUMNS.map((c) => (
                    <td key={c.key} className="px-2 py-1 text-center">
                      <FactorCell code={r[c.key] as number} />
                    </td>
                  ))}
                </tr>
              );
            })}
            {shown.length === 0 && (
              <tr>
                <td colSpan={13} className="px-2 py-4 text-center text-ay-muted">
                  {emptyMessage}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {rows.length > 0 && (
        <div className="mt-2 flex items-center justify-between text-xs text-ay-muted">
          <span className="tabular-nums">
            {from} – {to} of {rows.length}
          </span>
          <div className="flex gap-2">
            <button
              type="button"
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              disabled={safePage === 0}
              className="rounded border border-ay-border px-2 py-1 text-ay-text hover:border-accent disabled:opacity-40"
            >
              ‹ Previous
            </button>
            <button
              type="button"
              onClick={() => setPage((p) => Math.min(pageCount - 1, p + 1))}
              disabled={safePage >= pageCount - 1}
              className="rounded border border-ay-border px-2 py-1 text-ay-text hover:border-accent disabled:opacity-40"
            >
              Next ›
            </button>
          </div>
        </div>
      )}

      <Legend />
    </div>
  );
}
