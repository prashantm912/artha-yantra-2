import { useEffect, useMemo } from 'react';
import { useConnectingDots } from '../../api/oiAnalytics.ts';
import { useSymbolContext, type OiInterval } from '../../stores/symbolContext.store.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { ConnectingDotsTable } from '../../components/ConnectingDotsTable.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';

// Connecting Dots (§20.7.8): the per-interval multi-factor sentiment matrix. INDICES only (oipulse has
// 4; our backend covers NIFTY 50 + NIFTY BANK today — FINNIFTY/MIDCPNIFTY pending instrument cover).
// Interval set 3/5/10/15/30/60 (no 1m), riding the shared OiInterval (10m via the new BE OiInterval.M10).

const INDICES = ['NIFTY 50', 'NIFTY BANK'] as const;
const CD_INTERVALS: readonly OiInterval[] = ['3m', '5m', '10m', '15m', '30m', '60m'];

export function ConnectingDotsPage() {
  const name = useSymbolContext((s) => s.name);
  const setName = useSymbolContext((s) => s.setName);
  const interval = useSymbolContext((s) => s.interval);
  const setInterval = useSymbolContext((s) => s.setInterval);

  // Index-only page: snap a non-index (carried from another page) to the default index, and a 1m
  // interval (not offered here) to 3m.
  useEffect(() => {
    if (!(INDICES as readonly string[]).includes(name)) setName(INDICES[0]);
  }, [name, setName]);
  useEffect(() => {
    if (!CD_INTERVALS.includes(interval)) setInterval('3m');
  }, [interval, setInterval]);

  const cdQ = useConnectingDots();
  const rows = useMemo(() => cdQ.data?.rows ?? [], [cdQ.data]);

  return (
    <div>
      <PageHeader title="Connecting Dots" />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry={false} names={INDICES} allowedIntervals={CD_INTERVALS} />
        <GoButton onClick={() => cdQ.refetch()} loading={cdQ.isFetching} />
      </div>

      {cdQ.data == null && !cdQ.isLoading && (
        <p className="mb-3 text-sm text-ay-muted">
          No matrix — pick an index + interval with an active (or captured) session.
        </p>
      )}

      <ConnectingDotsTable rows={rows} />
    </div>
  );
}
