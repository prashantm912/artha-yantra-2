import { useMemo, useState } from 'react';
import { Bell } from 'lucide-react';
import { cn } from '../../lib/cn.ts';
import { Select } from '../../components/atoms/Select.tsx';
import { DateInput } from '../../components/atoms/DateInput.tsx';
import { Pager } from '../../components/atoms/Pager.tsx';
import { Button } from '../../components/atoms/Button.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { LoadBeat, BeatBlock } from '../../components/LoadBeat.tsx';
import { BandChip, SeverityBadge } from '../../components/insights/InsightBits.tsx';
import { TrustChip } from '../../components/insights/TrustChip.tsx';
import { InsightExplainDrawer } from '../../components/insights/InsightExplainDrawer.tsx';
import {
  insightBand,
  useAckInsight,
  useDismissInsight,
  useInsights,
  INSIGHT_SEVERITIES,
  INSIGHT_STATUSES,
  INSIGHT_TYPES,
  type Insight,
} from '../../api/insights.ts';

// /insights — the in-app notification center the platform lacked (INT design §8.2), the feed half of
// increment I1 (shadow mode: read + triage only). Filters by type/severity/status/scope/day + a
// page-level free-text search over title+explanation+scope; a "show suppressed" toggle surfaces what was
// muted and why (§8.7 — never an unexplained suppression); bulk ack/dismiss; row → the shared explain
// drawer. The PROPOSE take/ticket one-clicks and the WS live-merge are I3.

const PAGE_SIZE = 100;

function fmtTime(iso: string): string {
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Kolkata',
    day: '2-digit',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(iso));
}

const STATUS_TONE: Record<string, string> = {
  OPEN: 'text-ay-text',
  ACKED: 'text-accent',
  ACTED: 'text-bull',
  DISMISSED: 'text-ay-muted',
  EXPIRED: 'text-ay-muted',
};

export function InsightsPage() {
  const [type, setType] = useState('');
  const [severity, setSeverity] = useState('');
  const [status, setStatus] = useState('');
  const [scope, setScope] = useState('');
  const [day, setDay] = useState<string | null>(null);
  const [includeSuppressed, setIncludeSuppressed] = useState(false);
  const [search, setSearch] = useState('');
  const [offset, setOffset] = useState(0);
  const [selectedRows, setSelectedRows] = useState<Set<string>>(new Set());
  const [drawerInsight, setDrawerInsight] = useState<Insight | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);

  const ack = useAckInsight();
  const dismiss = useDismissInsight();

  const q = useInsights({
    type: type || null,
    severity: severity || null,
    status: status || null,
    scope: scope || null,
    day,
    includeSuppressed,
    limit: PAGE_SIZE,
    offset,
  });

  const rows = useMemo(() => q.data?.items ?? [], [q.data]);

  // Free-text search is a page-level pre-filter over what the server returned (the server offers
  // per-field filters, not full-text) — matches title, explanation and scope, case-insensitive.
  const filtered = useMemo(() => {
    const needle = search.trim().toLowerCase();
    if (!needle) return rows;
    return rows.filter(
      (r) =>
        r.title.toLowerCase().includes(needle) ||
        r.explanation.toLowerCase().includes(needle) ||
        r.scope.toLowerCase().includes(needle),
    );
  }, [rows, search]);

  const resetPage = () => {
    setOffset(0);
    setSelectedRows(new Set());
  };

  const openDrawer = (insight: Insight) => {
    setDrawerInsight(insight);
    setDrawerOpen(true);
  };

  const toggleRow = (id: string) =>
    setSelectedRows((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const allSelected = filtered.length > 0 && filtered.every((r) => selectedRows.has(r.id));
  const toggleAll = () =>
    setSelectedRows((prev) => {
      if (filtered.every((r) => prev.has(r.id))) return new Set();
      return new Set(filtered.map((r) => r.id));
    });

  const bulkAck = () => {
    selectedRows.forEach((id) => ack.mutate(id));
    setSelectedRows(new Set());
  };
  const bulkDismiss = () => {
    selectedRows.forEach((id) => dismiss.mutate(id));
    setSelectedRows(new Set());
  };

  const typeOptions = [{ value: '', label: 'All types' }, ...INSIGHT_TYPES.map((t) => ({ value: t, label: t }))];
  const severityOptions = [
    { value: '', label: 'All severities' },
    ...INSIGHT_SEVERITIES.map((s) => ({ value: s, label: s })),
  ];
  const statusOptions = [
    { value: '', label: 'All statuses' },
    ...INSIGHT_STATUSES.map((s) => ({ value: s, label: s })),
  ];

  return (
    <LoadBeat>
      <PageHeader
        title="Insights"
        subtitle="The decision-support feed — priority-ranked signals plus context, risk, data-trust and hygiene attention items"
        help="Every insight the intelligence layer generated, newest first: what it is, how urgent (severity), its scope, and whether the data behind it is trustworthy. Filter by type/severity/status/scope/day, search the text, or reveal muted (suppressed) items. Click a row to see the full explanation, its priority breakdown and the evidence behind every number; acknowledge or dismiss to triage. Shadow mode: this is display + triage only — nothing here places an order."
      />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <Select value={type} ariaLabel="Filter by type" options={typeOptions} onChange={(v) => { setType(v); resetPage(); }} />
        <Select value={severity} ariaLabel="Filter by severity" options={severityOptions} onChange={(v) => { setSeverity(v); resetPage(); }} />
        <Select value={status} ariaLabel="Filter by status" options={statusOptions} onChange={(v) => { setStatus(v); resetPage(); }} />
        <input
          type="text"
          value={scope}
          aria-label="Filter by scope"
          placeholder="Scope (e.g. market)"
          onChange={(e) => { setScope(e.target.value); }}
          onBlur={resetPage}
          className="h-9 w-40 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text outline-none focus:border-accent"
        />
        <DateInput value={day} ariaLabel="Filter by day" onChange={(v) => { setDay(v || null); resetPage(); }} />
        <input
          type="search"
          value={search}
          aria-label="Search insights"
          placeholder="Search title / text…"
          onChange={(e) => setSearch(e.target.value)}
          className="h-9 w-48 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text outline-none focus:border-accent"
        />
        <label className="flex items-center gap-1.5 text-xs text-ay-muted">
          <input
            type="checkbox"
            checked={includeSuppressed}
            onChange={(e) => { setIncludeSuppressed(e.target.checked); resetPage(); }}
            className="size-3.5 accent-accent"
          />
          Show suppressed
        </label>
      </div>

      {/* Bulk-action bar — appears once at least one row is selected (§8.2 bulk ack/dismiss). */}
      {selectedRows.size > 0 && (
        <div className="mb-3 flex items-center gap-2 rounded-md border border-ay-border bg-surface-2/40 px-3 py-2 text-body-sm">
          <span className="text-ay-muted">{selectedRows.size} selected</span>
          <span className="ml-auto flex gap-2">
            <Button variant="outline" size="sm" disabled={ack.isPending} onClick={bulkAck}>
              Ack selected
            </Button>
            <Button variant="outline" size="sm" disabled={dismiss.isPending} onClick={bulkDismiss}>
              Dismiss selected
            </Button>
          </span>
        </div>
      )}

      <QueryState
        query={q}
        isEmpty={() => filtered.length === 0}
        empty={{
          icon: Bell,
          title: search
            ? 'No insights match your search on this page.'
            : 'No insights for this selection.',
          hint: includeSuppressed ? undefined : 'Muted items are hidden — enable “Show suppressed” to see them.',
        }}
        errorTitle="Couldn't load insights"
        skeleton={<Skeleton variant="table-rows" />}
      >
        {() => (
          <BeatBlock>
            <div
              className="max-h-[70vh] overflow-auto rounded-md border"
              style={{ contain: 'paint' }}
              tabIndex={0}
              role="region"
              aria-label="Insights feed table"
            >
              <table className="w-full text-sm">
                <thead className="sticky top-0 bg-surface-1 text-left text-xs text-ay-muted">
                  <tr>
                    <th className="px-2 py-2 font-medium">
                      <input
                        type="checkbox"
                        aria-label="Select all insights on this page"
                        checked={allSelected}
                        onChange={toggleAll}
                        className="size-3.5 accent-accent"
                      />
                    </th>
                    <th className="px-2 py-2 font-medium">Time (IST)</th>
                    <th className="px-2 py-2 font-medium">Type</th>
                    <th className="px-2 py-2 font-medium">Severity</th>
                    <th className="px-2 py-2 font-medium">Band</th>
                    <th className="px-2 py-2 font-medium">Scope</th>
                    <th className="px-2 py-2 font-medium">Title</th>
                    <th className="px-2 py-2 font-medium">Trust</th>
                    <th className="px-2 py-2 font-medium">Status</th>
                  </tr>
                </thead>
                <tbody>
                  {filtered.map((r) => (
                    <tr
                      key={r.id}
                      className={cn('border-t hover:bg-surface-2/50', r.suppressed && 'opacity-60')}
                    >
                      <td className="px-2 py-1.5">
                        <input
                          type="checkbox"
                          aria-label={`Select insight: ${r.title}`}
                          checked={selectedRows.has(r.id)}
                          onChange={() => toggleRow(r.id)}
                          className="size-3.5 accent-accent"
                        />
                      </td>
                      <td className="whitespace-nowrap px-2 py-1.5 text-ay-muted">{fmtTime(r.generatedAt)}</td>
                      <td className="whitespace-nowrap px-2 py-1.5 text-xs text-ay-muted">{r.type}</td>
                      <td className="px-2 py-1.5">
                        <SeverityBadge severity={r.severity} />
                      </td>
                      <td className="px-2 py-1.5">
                        <BandChip band={insightBand(r)} />
                      </td>
                      <td className="whitespace-nowrap px-2 py-1.5 text-xs text-ay-muted">{r.scope}</td>
                      <td className="px-2 py-1.5">
                        <button
                          type="button"
                          onClick={() => openDrawer(r)}
                          className="text-left text-ay-text hover:text-accent"
                        >
                          {r.title}
                          {r.suppressed && (
                            <span className="ml-1.5 rounded bg-surface-2 px-1 py-0.5 text-[10px] uppercase text-ay-muted">
                              muted
                            </span>
                          )}
                        </button>
                      </td>
                      <td className="px-2 py-1.5">
                        <TrustChip state={r.dataTrust} reasons={r.trustReasons} />
                      </td>
                      <td className={cn('whitespace-nowrap px-2 py-1.5 text-xs', STATUS_TONE[r.status] ?? 'text-ay-text')}>
                        {r.status}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="mt-2 text-xs text-ay-muted">
              Click any title to open the explanation, priority breakdown and evidence. {filtered.length}
              {filtered.length === 1 ? ' insight' : ' insights'}
              {search ? ` matching “${search}”` : ''}
              {offset > 0 ? ` (from row ${offset + 1})` : ''}.
            </p>
            <Pager offset={offset} limit={PAGE_SIZE} count={rows.length} onOffset={(next) => { setOffset(next); setSelectedRows(new Set()); }} />
          </BeatBlock>
        )}
      </QueryState>

      <InsightExplainDrawer insight={drawerInsight} open={drawerOpen} onOpenChange={setDrawerOpen} />
    </LoadBeat>
  );
}
