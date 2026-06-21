import { useEffect, useMemo } from 'react';
import { useBanksAnalysis } from '../../api/oiAnalytics.ts';
import { useSymbolContext, type OiInterval } from '../../stores/symbolContext.store.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { BanksAnalysisTable } from '../../components/BanksAnalysisTable.tsx';

// Banks Analysis (§futures/banks-analysis): the sector-wide time × 6-bank OI matrix. NAME-LESS + expiry-
// less (always the 6 config banks) — only Mode + Date + Interval + Go. Interval set 3/5/10/15/30/60 (no 1m,
// matching oipulse). Forward-only data → 422 maps to the empty state until buckets accrue.

const BANK_INTERVALS: readonly OiInterval[] = ['3m', '5m', '10m', '15m', '30m', '60m'];

export function BanksAnalysisPage() {
  const interval = useSymbolContext((s) => s.interval);
  const setInterval = useSymbolContext((s) => s.setInterval);

  // Snap a non-bank interval (e.g. 1m carried from another page) to the default 3m.
  useEffect(() => {
    if (!BANK_INTERVALS.includes(interval)) setInterval('3m');
  }, [interval, setInterval]);

  const q = useBanksAnalysis();
  const banks = useMemo(() => q.data?.banks ?? [], [q.data]);
  const rows = useMemo(() => q.data?.rows ?? [], [q.data]);
  const intervalMinutes = useMemo(() => parseInt(q.data?.interval ?? '5m', 10) || 5, [q.data]);

  return (
    <div>
      <h1 className="ay-sr-only">Banks Analysis</h1>

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName={false} showExpiry={false} allowedIntervals={BANK_INTERVALS} />
        <GoButton onClick={() => q.refetch()} loading={q.isFetching} />
      </div>

      {q.data == null && !q.isLoading && (
        <p className="mb-3 text-sm text-ay-muted">
          No bank matrix — pick an interval with a captured (or live) session.
        </p>
      )}

      <BanksAnalysisTable banks={banks} rows={rows} intervalMinutes={intervalMinutes} />
    </div>
  );
}
