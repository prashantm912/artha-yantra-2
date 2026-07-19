import { useMemo, useState } from 'react';
import { cn } from '../../lib/cn.ts';
import { Select } from '../../components/atoms/Select.tsx';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import {
  fmtNum,
  fmtSigned,
  gateSummary,
  CANDIDATE_STATES,
  type Candidate,
  type Generation,
} from '../../api/evolution.ts';
import { StateBadge } from './EvolutionBits.tsx';

// The §10 Leaderboard tab: the campaign's candidates as a sortable table — RobustScore, rank,
// gates-passed chip, state, flags, generation — with a state + generation filter and a free-text
// param search (Prompt-1 P2-2 saved-views spirit, kept lightweight). A row opens the candidate card.

type SortKey = 'score' | 'rank' | 'state';

function scoreOf(c: Candidate): number | null {
  const s = c.scorecard?.robustScore;
  return typeof s === 'number' ? s : null;
}

export function Leaderboard({
  candidates,
  generations,
  championVersionId,
  onOpen,
}: {
  candidates: Candidate[];
  generations: Generation[];
  championVersionId?: string | null;
  onOpen: (c: Candidate) => void;
}) {
  const [state, setState] = useState('');
  const [genId, setGenId] = useState('');
  const [search, setSearch] = useState('');
  const [sort, setSort] = useState<SortKey>('score');

  const genLabel = useMemo(() => {
    const m = new Map<string, number>();
    for (const g of generations) m.set(g.id, g.n);
    return m;
  }, [generations]);

  const rows = useMemo(() => {
    const needle = search.trim().toLowerCase();
    const filtered = candidates.filter((c) => {
      if (state && (c.state ?? 'PROPOSED') !== state) return false;
      if (genId && c.generationId !== genId) return false;
      if (needle) {
        const paramStr = JSON.stringify(c.params ?? {}).toLowerCase();
        if (!paramStr.includes(needle) && !c.id.toLowerCase().includes(needle)) return false;
      }
      return true;
    });
    const sorted = [...filtered];
    if (sort === 'score') {
      sorted.sort((a, b) => (scoreOf(b) ?? -Infinity) - (scoreOf(a) ?? -Infinity));
    } else if (sort === 'rank') {
      sorted.sort((a, b) => (a.scorecard?.rank ?? Infinity) - (b.scorecard?.rank ?? Infinity));
    } else {
      sorted.sort((a, b) => (a.state ?? '').localeCompare(b.state ?? ''));
    }
    return sorted;
  }, [candidates, state, genId, search, sort]);

  const stateOptions = [{ value: '', label: 'All states' }, ...CANDIDATE_STATES.map((s) => ({ value: s, label: s }))];
  const genOptions = [
    { value: '', label: 'All generations' },
    ...[...generations].sort((a, b) => b.n - a.n).map((g) => ({ value: g.id, label: `Gen ${g.n}` })),
  ];
  const sortOptions = [
    { value: 'score', label: 'Sort: RobustScore' },
    { value: 'rank', label: 'Sort: Rank' },
    { value: 'state', label: 'Sort: State' },
  ];

  // The leaderboard grid via the shared DataTable — same columns, order, formatting and left
  // alignment as the hand-rolled table it replaced (adoption adds zebra + sticky header + the
  // md:hidden card list; density is normalized). Rows are pre-sorted by the toolbar Select above,
  // so no column carries a sortValue (headers stay non-sortable, matching the original). Row-click →
  // onOpen via DataTable's onRowClick: the desktop row is a mouse-only convenience (audit M23 — it
  // keeps its table `row` semantics), and the RobustScore cell's in-cell button is the keyboard/AT
  // activator (preserved from the original hand-rolled table); the mobile card is a keyboard button.
  const columns = useMemo<DataColumn<Candidate>[]>(
    () => [
      {
        id: 'rank',
        header: 'Rank',
        align: 'left',
        mono: true,
        mobileLabel: 'Rank',
        cellClassName: () => 'text-ay-muted',
        render: (c) => (c.scorecard?.rank != null ? `#${c.scorecard.rank}` : '—'),
      },
      {
        id: 'score',
        header: 'RobustScore',
        align: 'left',
        mono: true,
        mobileLabel: 'RobustScore',
        // The RobustScore cell carries a real in-cell button — the M23 keyboard/AT activator (the
        // desktop row itself is a mouse-only convenience, no keyboard). stopPropagation so a click
        // on it doesn't also fire the row's mouse onClick.
        render: (c) => {
          const isChampion = championVersionId != null && c.versionId === championVersionId;
          return (
            <>
              <button
                type="button"
                aria-label={`Open scorecard for candidate ${c.id.slice(0, 8)}`}
                onClick={(e) => {
                  e.stopPropagation();
                  onOpen(c);
                }}
                className="tabular-nums text-ay-text hover:text-accent"
              >
                {fmtNum(scoreOf(c), 3)}
              </button>
              {isChampion && (
                <span className="ml-1.5 rounded bg-surface-2 px-1 py-0.5 text-[10px] uppercase text-bull">
                  champion
                </span>
              )}
            </>
          );
        },
      },
      {
        id: 'vsChamp',
        header: 'vs champ',
        align: 'left',
        mono: true,
        mobileLabel: 'vs champ',
        cellClassName: (c) => ((c.scorecard?.comparator?.delta ?? 0) >= 0 ? 'text-bull' : 'text-bear'),
        render: (c) =>
          c.scorecard?.comparator?.delta != null ? fmtSigned(c.scorecard.comparator.delta) : '—',
      },
      {
        id: 'gates',
        header: 'Gates',
        align: 'left',
        mobileLabel: 'Gates',
        render: (c) => {
          const summary = gateSummary(c.scorecard?.gates);
          return (
            <span
              className={cn(
                'rounded px-1.5 py-0.5 text-[11px] font-medium ring-1',
                summary.failed === 0 && summary.total > 0
                  ? 'text-bull ring-bull/40'
                  : summary.failed > 0
                    ? 'text-bear ring-bear/40'
                    : 'text-ay-muted ring-ay-border',
              )}
              title={summary.failed > 0 ? `Failing: ${summary.failedIds.join(', ')}` : undefined}
            >
              {summary.passed}/{summary.total}
            </span>
          );
        },
      },
      {
        id: 'state',
        header: 'State',
        align: 'left',
        mobileLabel: 'State',
        render: (c) => <StateBadge state={c.state} />,
      },
      {
        id: 'gen',
        header: 'Gen',
        align: 'left',
        mobileLabel: 'Gen',
        cellClassName: () => 'text-xs text-ay-muted',
        render: (c) =>
          genLabel.get(c.generationId) != null ? `Gen ${genLabel.get(c.generationId)}` : '—',
      },
      {
        id: 'flags',
        header: 'Flags',
        align: 'left',
        mobileLabel: 'Flags',
        cellClassName: () => 'text-xs text-warn',
        render: (c) => (c.scorecard?.flags && c.scorecard.flags.length > 0 ? c.scorecard.flags.length : ''),
      },
    ],
    [championVersionId, genLabel, onOpen],
  );

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <Select value={state} ariaLabel="Filter by state" options={stateOptions} onChange={setState} />
        <Select value={genId} ariaLabel="Filter by generation" options={genOptions} onChange={setGenId} />
        <Select value={sort} ariaLabel="Sort candidates" options={sortOptions} onChange={(v) => setSort(v as SortKey)} />
        <input
          type="search"
          value={search}
          aria-label="Search candidates by param or id"
          placeholder="Search params / id…"
          onChange={(e) => setSearch(e.target.value)}
          className="h-9 w-44 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text outline-none focus:border-accent"
        />
      </div>

      {rows.length === 0 ? (
        <p className="rounded-md border border-ay-border bg-surface-1 px-4 py-8 text-center text-body-sm text-ay-muted">
          No candidates match this filter.
        </p>
      ) : (
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(c) => c.id}
          ariaLabel="Candidates leaderboard"
          onRowClick={(c) => onOpen(c)}
          maxHeight="65vh"
        />
      )}
      <p className="text-xs text-ay-muted">
        {rows.length} candidate{rows.length === 1 ? '' : 's'}. Click a row to open its scorecard, gate
        checklist, RobustScore breakdown and evidence.
      </p>
    </div>
  );
}
