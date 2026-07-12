import { useMemo, useState } from 'react';
import { cn } from '../../lib/cn.ts';
import { Select } from '../../components/atoms/Select.tsx';
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
        <div
          className="max-h-[65vh] overflow-auto rounded-md border"
          style={{ contain: 'paint' }}
          tabIndex={0}
          role="region"
          aria-label="Candidates leaderboard"
        >
          <table className="w-full text-sm">
            <thead className="sticky top-0 bg-surface-1 text-left text-xs text-ay-muted">
              <tr>
                <th className="px-2 py-2 font-medium">Rank</th>
                <th className="px-2 py-2 font-medium">RobustScore</th>
                <th className="px-2 py-2 font-medium">vs champ</th>
                <th className="px-2 py-2 font-medium">Gates</th>
                <th className="px-2 py-2 font-medium">State</th>
                <th className="px-2 py-2 font-medium">Gen</th>
                <th className="px-2 py-2 font-medium">Flags</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((c) => {
                const card = c.scorecard;
                const summary = gateSummary(card?.gates);
                const isChampion = championVersionId != null && c.versionId === championVersionId;
                return (
                  <tr
                    key={c.id}
                    onClick={() => onOpen(c)}
                    className="cursor-pointer border-t hover:bg-surface-2/50"
                  >
                    <td className="whitespace-nowrap px-2 py-1.5 tabular-nums text-ay-muted">
                      {card?.rank != null ? `#${card.rank}` : '—'}
                    </td>
                    <td className="px-2 py-1.5">
                      <button
                        type="button"
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
                    </td>
                    <td
                      className={cn(
                        'whitespace-nowrap px-2 py-1.5 tabular-nums',
                        (card?.comparator?.delta ?? 0) >= 0 ? 'text-bull' : 'text-bear',
                      )}
                    >
                      {card?.comparator?.delta != null ? fmtSigned(card.comparator.delta) : '—'}
                    </td>
                    <td className="whitespace-nowrap px-2 py-1.5">
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
                    </td>
                    <td className="px-2 py-1.5">
                      <StateBadge state={c.state} />
                    </td>
                    <td className="whitespace-nowrap px-2 py-1.5 text-xs text-ay-muted">
                      {genLabel.get(c.generationId) != null ? `Gen ${genLabel.get(c.generationId)}` : '—'}
                    </td>
                    <td className="px-2 py-1.5 text-xs text-warn">
                      {card?.flags && card.flags.length > 0 ? card.flags.length : ''}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      <p className="text-xs text-ay-muted">
        {rows.length} candidate{rows.length === 1 ? '' : 's'}. Click a row to open its scorecard, gate
        checklist, RobustScore breakdown and evidence.
      </p>
    </div>
  );
}
