import { useState } from 'react';
import { SqlEditor } from '../../components/dataops/SqlEditor.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { cn } from '../../lib/cn.ts';
import { downloadQueryCsv, runQuery, type QueryResult } from '../../api/dataops.ts';

// B5 Query/Scan console (route /data-ops/query). Left: preset buttons that load a ready SELECT into the
// editor. Right: SqlEditor + a row-limit input + Run / Download CSV, then a dynamic-column result grid
// (NOT DataTable — column shape is unknown at author time). The server enforces the read-only allowlist.

interface Preset {
  label: string;
  sql: string;
}

const PRESETS: readonly Preset[] = [
  {
    label: 'Recent backfill 1m',
    sql: "SELECT tradingsymbol, bucket, close, volume, oi FROM candles WHERE source='BACKFILL' ORDER BY bucket DESC LIMIT 100",
  },
  {
    label: 'Contracts by expiry',
    sql: 'SELECT underlying_symbol, expiry, instrument_type, count(*) FROM expired_contracts GROUP BY 1,2,3 ORDER BY 2 DESC LIMIT 100',
  },
  {
    label: 'Coverage holes',
    sql: 'SELECT exchange, tradingsymbol, last_bucket, bar_count FROM expired_contracts WHERE NOT complete ORDER BY bar_count LIMIT 100',
  },
];

const ROW_LIMIT_MIN = 1;
const ROW_LIMIT_MAX = 1000;

export function QueryConsolePage() {
  const [sql, setSql] = useState(PRESETS[0].sql);
  const [rowLimit, setRowLimit] = useState(1000);
  const [result, setResult] = useState<QueryResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function run() {
    setError(null);
    setBusy(true);
    try {
      setResult(await runQuery(sql, rowLimit));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function download() {
    setError(null);
    try {
      await downloadQueryCsv(sql);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <div>
      <PageHeader title="Query / Scan console" />

      <div className="grid gap-3 md:grid-cols-[14rem_1fr]">
        {/* Left: preset list */}
        <nav aria-label="Query presets" className="space-y-1.5">
          {PRESETS.map((p) => (
            <button
              key={p.label}
              type="button"
              onClick={() => setSql(p.sql)}
              className={cn(
                'w-full rounded-md border border-ay-border bg-surface-1 px-3 py-2 text-left text-sm text-ay-text hover:border-accent',
                sql === p.sql && 'border-accent text-accent',
              )}
            >
              {p.label}
            </button>
          ))}
        </nav>

        {/* Right: editor + controls + results */}
        <div className="space-y-3">
          <SqlEditor
            value={sql}
            onChange={setSql}
            onRun={run}
            placeholder="SELECT … (read-only; Cmd/Ctrl+Enter to run)"
          />

          <div className="flex flex-wrap items-end gap-3">
            <div className="flex flex-col gap-1">
              <label htmlFor="row-limit" className="text-xs text-ay-muted">
                Row limit
              </label>
              <input
                id="row-limit"
                type="number"
                min={ROW_LIMIT_MIN}
                max={ROW_LIMIT_MAX}
                value={rowLimit}
                onChange={(e) => setRowLimit(Number(e.target.value))}
                className="w-28 rounded-md border border-ay-border bg-surface-1 px-2 py-1.5 text-sm text-ay-text tabular-nums focus:border-accent focus:outline-none"
              />
            </div>

            <button
              type="button"
              onClick={run}
              disabled={busy}
              className="rounded-md bg-accent px-3 py-1.5 text-sm text-surface-0 hover:opacity-90 disabled:opacity-50"
            >
              {busy ? 'Running…' : 'Run'}
            </button>

            <button
              type="button"
              onClick={download}
              className="rounded-md border border-ay-border bg-surface-1 px-3 py-1.5 text-sm text-ay-text hover:border-accent"
            >
              Download CSV
            </button>
          </div>

          {error && (
            <p role="alert" className="text-sm text-bear">
              {error}
            </p>
          )}

          {result && (
            <div className="space-y-1">
              <div
                className="max-h-[60vh] overflow-auto rounded border border-ay-border"
                tabIndex={0}
                role="region"
                aria-label="Query results"
              >
                <table className="w-full border-collapse whitespace-nowrap text-xs">
                  <thead className="sticky top-0 z-10 bg-surface-1 text-ay-muted">
                    <tr>
                      {result.columns.map((c) => (
                        <th key={c} scope="col" className="px-2 py-1 text-left font-medium">
                          {c}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {result.rows.map((row, ri) => (
                      <tr key={ri} className="border-t border-ay-border text-ay-text">
                        {row.map((cell, ci) => (
                          <td
                            key={ci}
                            className={cn(
                              'px-2 py-1 tabular-nums',
                              cell === null && 'text-ay-muted',
                            )}
                          >
                            {cell === null ? '∅' : cell}
                          </td>
                        ))}
                      </tr>
                    ))}
                    {result.rows.length === 0 && (
                      <tr>
                        <td
                          colSpan={Math.max(1, result.columns.length)}
                          className="px-2 py-4 text-center text-ay-muted"
                        >
                          No rows.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
              <p className="text-xs text-ay-muted tabular-nums">
                {result.rowCount} rows{result.truncated ? ' (truncated)' : ''}
              </p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
