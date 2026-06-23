import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { cn } from '../../lib/cn.ts';
import { Select } from '../../components/atoms/Select.tsx';
import {
  NOTIFY_CHANNELS,
  STRATEGY_STATUSES,
  sparkPoints,
  useBacktestSummaries,
  useSetNotifications,
  useStrategies,
  type StrategySummary,
} from '../../api/strategies.ts';

// /strategies (master plan §20 parity, E-11 screen 1): published / draft / archived strategies with
// status, version, the last-backtest Sharpe + equity sparkline, tags and the notification toggle. Row
// actions edit (editor lands with PR-C5) and open history. Create / Import open the editor (PR-C5).

function statusTone(status: string): string {
  if (status === 'published') return 'text-bull ring-bull/40';
  if (status === 'archived') return 'text-ay-muted ring-ay-border';
  return 'text-warn ring-warn/40';
}

export function StrategiesListPage() {
  const [qInput, setQInput] = useState('');
  const [q, setQ] = useState('');
  const [status, setStatus] = useState<string | null>(null);

  // debounce the search box so typing doesn't fire a request per keystroke
  useEffect(() => {
    const t = setTimeout(() => setQ(qInput.trim()), 300);
    return () => clearTimeout(t);
  }, [qInput]);

  const list = useStrategies(q, status);
  const rows = useMemo(() => list.data?.items ?? [], [list.data]);
  const versionIds = useMemo(
    () => rows.map((s) => s.publishedVersionId ?? s.currentVersionId).filter((v): v is string => !!v),
    [rows],
  );
  const summaries = useBacktestSummaries(versionIds);
  const setNotifications = useSetNotifications();

  const summaryFor = (s: StrategySummary) => {
    const id = s.publishedVersionId ?? s.currentVersionId;
    return id ? (summaries.data?.[id] ?? null) : null;
  };

  return (
    <div>
      <h1 className="ay-sr-only">Strategies</h1>
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <input
          value={qInput}
          onChange={(e) => setQInput(e.target.value)}
          placeholder="Search name / slug"
          aria-label="Search strategies"
          className="h-9 flex-1 rounded-md border border-ay-border bg-surface-1 px-3 text-sm text-ay-text"
        />
        <Select
          value={status}
          options={[...STRATEGY_STATUSES]}
          onChange={(v) => setStatus(v || null)}
          ariaLabel="Status filter"
          placeholder="All statuses"
        />
        <Link
          to="/strategies/new/edit"
          className="h-9 rounded-md bg-accent px-4 text-sm font-medium leading-9 text-surface-0 hover:opacity-90"
        >
          + Create
        </Link>
      </div>

      <div className="overflow-auto rounded-lg border border-ay-border">
        <table className="w-full border-collapse text-sm">
          <thead className="bg-surface-1 text-left text-xs uppercase text-ay-muted">
            <tr>
              <th className="px-2 py-2 font-medium">Name</th>
              <th className="px-2 py-2 font-medium">Status</th>
              <th className="px-2 py-2 font-medium">Version</th>
              <th className="px-2 py-2 font-medium">Last backtest</th>
              <th className="px-2 py-2 font-medium">Tags</th>
              <th className="px-2 py-2 font-medium">Notify</th>
              <th className="px-2 py-2 font-medium">Updated</th>
              <th className="px-2 py-2"><span className="ay-sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((s) => {
              const bt = summaryFor(s);
              const pts = bt ? sparkPoints(bt.equity) : '';
              return (
                <tr key={s.id} className="border-t border-ay-border">
                  <td className="px-2 py-2 font-medium">{s.name}</td>
                  <td className="px-2 py-2">
                    <span className={cn('rounded px-1.5 py-0.5 text-xs font-semibold ring-1', statusTone(s.status))}>
                      {s.status}
                    </span>
                  </td>
                  <td className="px-2 py-2 tabular-nums">{s.publishedVersion ?? s.currentVersion ?? '—'}</td>
                  <td className="px-2 py-2">
                    {bt ? (
                      <span className="flex items-center gap-2 whitespace-nowrap">
                        <span className="tabular-nums">Sharpe {bt.sharpe ?? '—'}</span>
                        {pts && (
                          <svg viewBox="0 0 80 22" width="80" height="22" aria-hidden="true" className="text-accent">
                            <polyline points={pts} fill="none" stroke="currentColor" strokeWidth="1.5" />
                          </svg>
                        )}
                      </span>
                    ) : (
                      '—'
                    )}
                  </td>
                  <td className="px-2 py-2">
                    <span className="flex flex-wrap gap-1">
                      {s.tags.map((t) => (
                        <span key={t} className="rounded bg-surface-2 px-1.5 py-0.5 text-xs text-ay-muted">
                          {t}
                        </span>
                      ))}
                    </span>
                  </td>
                  <td className="px-2 py-2">
                    <span className="flex items-center gap-2">
                      <input
                        type="checkbox"
                        checked={s.notificationsEnabled ?? false}
                        onChange={(e) =>
                          setNotifications.mutate({
                            id: s.id,
                            enabled: e.target.checked,
                            channel: s.notificationChannel ?? 'NTFY',
                          })
                        }
                        aria-label={`Notifications for ${s.name}`}
                      />
                      {s.notificationsEnabled && (
                        <Select
                          value={s.notificationChannel ?? 'NTFY'}
                          options={[...NOTIFY_CHANNELS]}
                          onChange={(v) => setNotifications.mutate({ id: s.id, enabled: true, channel: v })}
                          ariaLabel={`Channel for ${s.name}`}
                          className="h-7"
                        />
                      )}
                    </span>
                  </td>
                  <td className="px-2 py-2 tabular-nums">{s.updatedAt ? s.updatedAt.slice(0, 10) : '—'}</td>
                  <td className="px-2 py-2 text-right">
                    <Link to={`/strategies/${s.id}/edit`} className="px-1.5 text-xs text-accent hover:underline">
                      Edit
                    </Link>
                    <Link to={`/strategies/${s.id}/versions`} className="px-1.5 text-xs text-accent hover:underline">
                      History
                    </Link>
                  </td>
                </tr>
              );
            })}
            {rows.length === 0 && (
              <tr>
                <td colSpan={8} className="px-2 py-6 text-center text-ay-muted">
                  {list.isLoading ? 'Loading…' : 'No strategies yet — create one to get started.'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
