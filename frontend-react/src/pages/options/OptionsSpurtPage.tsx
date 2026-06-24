import { useMemo, useState } from 'react';
import { formatDecimal } from '../../lib/decimal.ts';
import { useOptionsSpurt } from '../../api/oiAnalytics.ts';
import type { SpurtRow } from '../../api/types.ts';
import type { OiInterpretation } from '../../core/oiInterpretation.ts';
import { Crosshair } from 'lucide-react';
import { FilterBar } from '../../components/FilterBar.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { OiBadge4 } from '../../components/atoms/OiBadge4.tsx';
import { SpurtQuadrant } from '../../components/SpurtQuadrant.tsx';

// Options OI Spurt — the oipulse 4-quadrant OI-action scanner (§20.3). Every CE/PE strike of the
// expiry is bucketed by its interval interpretation (our 4-state primitive IS the quadrant), each
// quadrant sorted by |ΔOI| and paginated. A shared strike search filters all four; the summary badge
// rolls up the underlying bias. Off the existing /spurt feed (enriched with prevLtp/ltpChange/volume).

interface Quadrant {
  state: OiInterpretation;
  title: string;
  subtitle: string;
}

// 2×2 order matches oipulse: Long/Short Build-Up on top, Short/Long Unwinding below.
const QUADRANTS: Quadrant[] = [
  { state: 'LONG_BUILDUP', title: 'Rise in OI & Rise in Price', subtitle: 'Long Build Up' },
  { state: 'SHORT_BUILDUP', title: 'Rise in OI & Fall in Price', subtitle: 'Short Build Up' },
  { state: 'SHORT_COVERING', title: 'Fall in OI & Rise in Price', subtitle: 'Short Unwinding' },
  { state: 'LONG_UNWINDING', title: 'Fall in OI & Fall in Price', subtitle: 'Long Unwinding' },
];

export function OptionsSpurtPage() {
  const spurtQ = useOptionsSpurt();
  const [search, setSearch] = useState('');

  const chain = spurtQ.data ?? null;
  const items = useMemo(() => chain?.items ?? [], [chain]);

  const byQuadrant = useMemo(() => {
    const q = search.trim();
    const filtered = q ? items.filter((r) => r.strike.includes(q)) : items;
    const groups: Record<OiInterpretation, SpurtRow[]> = {
      LONG_BUILDUP: [],
      SHORT_BUILDUP: [],
      SHORT_COVERING: [],
      LONG_UNWINDING: [],
    };
    for (const r of filtered) groups[r.interpretation].push(r);
    return groups;
  }, [items, search]);

  const summary = chain?.summary ?? null;

  return (
    <div>
      <PageHeader title="Options OI Spurt" subtitle="4-quadrant OI-action scanner — every CE/PE strike bucketed by its interval interpretation" />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry />
        <input
          type="search"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search strike"
          aria-label="Search strike"
          className="h-9 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text outline-none focus:border-accent"
        />
        <GoButton onClick={() => spurtQ.refetch()} loading={spurtQ.isFetching} />
      </div>

      <p className="mb-3 flex flex-wrap items-center gap-2 text-sm text-ay-muted" aria-live="polite">
        OI bias <OiBadge4 value={summary?.interpretation ?? null} />
        {summary && (
          <span className="tabular-nums">· spot Δ {formatDecimal(summary.spotDelta, 2)}</span>
        )}
        <span className="text-xs">· strength = %ΔLTP &gt; 50 AND %ΔOI &gt; 50 (bold rows)</span>
      </p>

      <QueryState
        query={spurtQ}
        isEmpty={() => items.length === 0}
        empty={{
          icon: Crosshair,
          title:
            'No spurt data — pick an underlying + expiry with at least two captured snapshot buckets.',
        }}
        errorTitle="Couldn't load OI spurt"
        skeleton={
          <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
            <Skeleton variant="table-rows" rows={6} cols={5} />
            <Skeleton variant="table-rows" rows={6} cols={5} />
            <Skeleton variant="table-rows" rows={6} cols={5} />
            <Skeleton variant="table-rows" rows={6} cols={5} />
          </div>
        }
      >
        {() => (
          <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
            {QUADRANTS.map((q) => (
              <SpurtQuadrant
                key={q.state}
                title={q.title}
                subtitle={q.subtitle}
                rows={byQuadrant[q.state]}
              />
            ))}
          </div>
        )}
      </QueryState>
    </div>
  );
}
