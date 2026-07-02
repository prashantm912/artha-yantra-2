import { Fragment, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { toast } from 'sonner';
import { cn } from '../../lib/cn.ts';
import { Select } from '../../components/atoms/Select.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import {
  NOTIFY_CHANNELS,
  STRATEGY_STATUSES,
  sparkPoints,
  useBacktestSummaries,
  useManyStrategyVersions,
  useSetNotifications,
  useStrategies,
  type BacktestSummary,
  type StrategySummary,
  type VersionRow,
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
  // Off by default: the table shows only each strategy's current version. When on, every older version
  // is lazily fetched and shown as an indented sub-row (Edit disabled — older versions are immutable).
  const [showOld, setShowOld] = useState(false);

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
  const setNotifications = useSetNotifications();

  // The version a strategy shows on its main row (published if any, else the newest).
  const shownVersion = (s: StrategySummary) => s.publishedVersion ?? s.currentVersion;

  // Lazily load every strategy's version timeline only while old versions are expanded.
  const allVersions = useManyStrategyVersions(
    rows.map((s) => s.id),
    showOld,
  );
  const oldVersionsFor = (s: StrategySummary): VersionRow[] =>
    (allVersions.byId[s.id] ?? []).filter((v) => v.version !== shownVersion(s));

  // Old versions' UUIDs → batched into the same last-backtest summary fetch as the current versions.
  const oldVersionIds = useMemo(() => {
    if (!showOld) return [];
    return rows.flatMap((s) =>
      (allVersions.byId[s.id] ?? [])
        .filter((v) => v.version !== shownVersion(s))
        .map((v) => v.versionId)
        .filter((x): x is string => !!x),
    );
  }, [showOld, rows, allVersions.byId]);

  const summaries = useBacktestSummaries(useMemo(() => [...versionIds, ...oldVersionIds], [versionIds, oldVersionIds]));

  const summaryById = (id?: string | null): BacktestSummary | null =>
    id ? (summaries.data?.[id] ?? null) : null;
  const summaryFor = (s: StrategySummary) => summaryById(s.publishedVersionId ?? s.currentVersionId);

  return (
    <LoadBeat>
      <PageHeader
        title="Strategies"
        subtitle="Published, draft and archived strategies"
        help="Your saved trading strategies, each showing its status, version, last-backtest Sharpe and equity sparkline; edit a strategy or open its version history."
      />
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <input
          value={qInput}
          onChange={(e) => setQInput(e.target.value)}
          placeholder="Search name / slug"
          aria-label="Search strategies"
          title="Filter the list by strategy name or slug as you type."
          className="h-9 flex-1 rounded-md border border-ay-border bg-surface-1 px-3 text-sm text-ay-text"
        />
        <Select
          value={status}
          options={[...STRATEGY_STATUSES]}
          onChange={(v) => setStatus(v || null)}
          ariaLabel="Status filter"
          placeholder="All statuses"
          title="Show only strategies in the chosen status (published, draft or archived)."
        />
        <label
          className="flex h-9 cursor-pointer items-center gap-1.5 rounded-md border border-ay-border px-3 text-sm hover:border-accent"
          title="Expand each strategy's older versions as indented rows, each with its last backtest. Editing is disabled for old versions (they're immutable)."
        >
          <input
            type="checkbox"
            checked={showOld}
            onChange={(e) => setShowOld(e.target.checked)}
            className="size-4 accent-accent"
            aria-label="Show old versions"
          />
          Show old versions
        </label>
        <Link
          to="/strategies/new/edit"
          title="Start a new strategy in the YAML editor."
          className="h-9 rounded-md bg-accent px-4 text-sm font-medium leading-9 text-surface-0 hover:opacity-90"
        >
          + Create
        </Link>
      </div>

      <QueryState
        query={list}
        isEmpty={() => rows.length === 0}
        empty={{ title: 'No strategies yet — create one to get started.' }}
        errorTitle="Couldn't load strategies"
        skeleton={<Skeleton variant="table-rows" rows={8} cols={8} />}
      >
        {() => (
      <BeatBlock className="overflow-auto rounded-lg border border-ay-border">
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
              const olds = showOld ? oldVersionsFor(s) : [];
              return (
                <Fragment key={s.id}>
                <tr className="border-t border-ay-border">
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
                      {s.tags.slice(0, 3).map((t) => (
                        <span key={t} className="rounded bg-surface-2 px-1.5 py-0.5 text-xs text-ay-muted">
                          {t}
                        </span>
                      ))}
                      {s.tags.length > 3 && (
                        <span
                          title={s.tags.slice(3).join(', ')}
                          className="cursor-default rounded bg-surface-2 px-1.5 py-0.5 text-xs text-ay-muted"
                        >
                          +{s.tags.length - 3}
                        </span>
                      )}
                    </span>
                  </td>
                  <td className="px-2 py-2">
                    <span className="flex items-center gap-2">
                      <input
                        type="checkbox"
                        checked={s.notificationsEnabled ?? false}
                        onChange={(e) =>
                          setNotifications.mutate(
                            {
                              id: s.id,
                              enabled: e.target.checked,
                              channel: s.notificationChannel ?? 'NTFY',
                            },
                            {
                              onSuccess: (res) =>
                                toast.success(
                                  res.notificationsEnabled
                                    ? `Notifications ON for ${s.name} (${res.notificationChannel ?? 'NTFY'})`
                                    : `Notifications OFF for ${s.name}`,
                                ),
                            },
                          )
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
                {olds.map((v) => {
                  const obt = summaryById(v.versionId);
                  const opts = obt ? sparkPoints(obt.equity) : '';
                  return (
                    <tr key={`${s.id}:${v.version}`} className="border-t border-ay-border/50 bg-surface-1/40 text-ay-muted">
                      <td className="px-2 py-1.5 pl-6 text-xs">↳ {s.name}</td>
                      <td className="px-2 py-1.5">
                        <span className={cn('rounded px-1.5 py-0.5 text-xs font-semibold ring-1', statusTone(v.status))}>
                          {v.status}
                        </span>
                      </td>
                      <td className="px-2 py-1.5 tabular-nums text-xs">{v.version}</td>
                      <td className="px-2 py-1.5 text-xs">
                        {obt ? (
                          <span className="flex items-center gap-2 whitespace-nowrap">
                            <span className="tabular-nums">Sharpe {obt.sharpe ?? '—'}</span>
                            {opts && (
                              <svg viewBox="0 0 80 22" width="80" height="22" aria-hidden="true" className="text-accent">
                                <polyline points={opts} fill="none" stroke="currentColor" strokeWidth="1.5" />
                              </svg>
                            )}
                          </span>
                        ) : (
                          '—'
                        )}
                      </td>
                      <td className="px-2 py-1.5" />
                      <td className="px-2 py-1.5" />
                      <td className="px-2 py-1.5 tabular-nums text-xs">{v.createdAt ? v.createdAt.slice(0, 10) : '—'}</td>
                      <td className="px-2 py-1.5 text-right">
                        <span
                          className="cursor-not-allowed px-1.5 text-xs text-ay-muted/50"
                          title="Older versions are immutable — edit the current version instead."
                        >
                          Edit
                        </span>
                      </td>
                    </tr>
                  );
                })}
                </Fragment>
              );
            })}
          </tbody>
        </table>
      </BeatBlock>
        )}
      </QueryState>
    </LoadBeat>
  );
}
