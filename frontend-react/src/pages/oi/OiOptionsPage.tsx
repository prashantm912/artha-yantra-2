import { useMemo } from 'react';
import { cn } from '../../lib/cn.ts';
import { formatDecimal } from '../../lib/decimal.ts';
import { chainSpot, foldStrikes } from '../../api/oiFold.ts';
import {
  useActiveStrikes,
  useOiAnalysis,
  useOiStats,
  useOptionsSpurt,
} from '../../api/oiAnalytics.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { OiBadge4 } from '../../components/atoms/OiBadge4.tsx';
import { DataBar } from '../../components/atoms/DataBar.tsx';
import { MirroredCspTable, type CspColumn } from '../../components/MirroredCspTable.tsx';

// Options OI Analysis — the PR-F anchor page (master plan §20). Composes the reusable
// MirroredCspTable (Tier-A archetype) with this page's 9-column config + a PCR/max-pain/sentiment
// header, driven by the shared FilterBar selection. All money/IV are decimal strings (never parseFloat).

const dec = (v: string | null | undefined, n: number) => (v ? formatDecimal(v, n) : '—');
const oiFmt = (v: number | null | undefined) => (v != null ? v.toLocaleString('en-IN') : '—');
const signedOi = (v: number | null | undefined) =>
  v == null ? '—' : v > 0 ? '+' + v.toLocaleString('en-IN') : v.toLocaleString('en-IN');
const toneClass = (v: number | null | undefined) =>
  v == null || v === 0 ? '' : v > 0 ? 'text-bull' : 'text-bear';

const barCol = (header: string): CspColumn => ({
  header,
  cell: (leg, _row, ctx) => <DataBar value={leg?.oi ?? 0} max={ctx.maxOi} label={oiFmt(leg?.oi)} />,
});
const deltaCol = (header: string): CspColumn => ({
  header,
  align: 'right',
  cell: (leg) => (
    <span className={cn('tabular-nums', toneClass(leg?.oiChange))}>{signedOi(leg?.oiChange)}</span>
  ),
});
const ivCol = (header: string): CspColumn => ({
  header,
  align: 'right',
  cell: (leg) => <span className="tabular-nums">{dec(leg?.iv, 4)}</span>,
});
const ltpCol = (header: string): CspColumn => ({
  header,
  align: 'right',
  cellClass: (ctx) => (ctx.itm ? 'bg-accent/10' : ''),
  cell: (leg, _row, ctx) => (
    <span className="tabular-nums">
      {dec(leg?.ltp, 2)}
      {ctx.itm && <span className="ay-sr-only"> in the money</span>}
    </span>
  ),
});

const CE_COLUMNS = [barCol('CE OI'), deltaCol('CE ΔOI'), ivCol('CE IV'), ltpCol('CE LTP')];
const PE_COLUMNS = [ltpCol('PE LTP'), ivCol('PE IV'), deltaCol('PE ΔOI'), barCol('PE OI')];

export function OiOptionsPage() {
  const stats = useOiStats();
  const active = useActiveStrikes();
  const strikesQ = useOiAnalysis();
  const spurt = useOptionsSpurt();

  const points = useMemo(() => strikesQ.data ?? [], [strikesQ.data]);
  const rows = useMemo(() => foldStrikes(points), [points]);
  const spot = useMemo(() => chainSpot(points), [points]);
  const bias = spurt.data?.summary?.interpretation ?? null;
  const s = stats.data;

  return (
    <div>
      <h1 className="ay-sr-only">Options OI analysis</h1>
      <FilterBar showName showExpiry />

      <p className="mb-2 flex items-center gap-2 text-sm text-ay-muted" aria-live="polite">
        OI bias <OiBadge4 value={bias} />
      </p>

      {s ? (
        <p className="mb-3 text-sm text-ay-muted tabular-nums" aria-live="polite">
          PCR {dec(s.pcr, 4)} · Max pain {dec(s.maxPain, 2)} · CE OI {oiFmt(s.ceOi)} · PE OI{' '}
          {oiFmt(s.peOi)}
          {active.data && <> · Sentiment {dec(active.data.sentimentPct, 2)}%</>} · {rows.length}{' '}
          strike{rows.length === 1 ? '' : 's'}
        </p>
      ) : (
        <p className="mb-3 text-sm text-ay-muted">
          No OI stats — pick an underlying + expiry with captured snapshots.
        </p>
      )}

      <MirroredCspTable rows={rows} spot={spot} ceColumns={CE_COLUMNS} peColumns={PE_COLUMNS} />
    </div>
  );
}
