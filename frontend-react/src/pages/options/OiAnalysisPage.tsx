import { useEffect, useMemo, useState } from 'react';
import {
  compareDecimal,
  formatDecimal,
  isNegative,
  subtractDecimal,
} from '../../lib/decimal.ts';
import { useOiAnalysis, useStrikeSeries } from '../../api/oiAnalytics.ts';
import { foldOiAnalysis } from '../../api/oiAnalysisFold.ts';
import type { OiInterval } from '../../stores/symbolContext.store.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { Select } from '../../components/atoms/Select.tsx';
import { OiAnalysisTable } from '../../components/OiAnalysisTable.tsx';

// True Options OI Analysis — per-strike intraday, buckets-on-rows (§20.7.5). A Strike selector drives
// the strike-series feed; the page folds CE+PE per interval into the mirrored time-rows table. The
// strike list + ATM default come from the latest oi-analysis bucket. Decimal strings — never parseFloat.

// oipulse's OI-Analysis interval set is 3/5/10/15/30/60; our OiInterval lacks 10m, so we offer the
// supported subset (1m + Full-Day/10m are the documented gaps).
const ANALYSIS_INTERVALS: readonly OiInterval[] = ['3m', '5m', '15m', '30m', '60m'];

/** Listed strike nearest the spot (ATM) — exact-decimal distance, never parseFloat. */
function nearestStrike(strikes: string[], spot: string | null): string | null {
  if (!spot) return strikes[0] ?? null;
  let best: string | null = null;
  let bestDist: string | null = null;
  for (const s of strikes) {
    const diff = subtractDecimal(s, spot);
    const dist = isNegative(diff) ? diff.slice(1) : diff;
    if (bestDist == null || compareDecimal(dist, bestDist) < 0) {
      bestDist = dist;
      best = s;
    }
  }
  return best;
}

export function OiAnalysisPage() {
  const analysisQ = useOiAnalysis();
  const points = useMemo(() => analysisQ.data ?? [], [analysisQ.data]);

  const strikes = useMemo(
    () => [...new Set(points.map((p) => p.strike))].sort((a, b) => compareDecimal(a, b)),
    [points],
  );
  const spot = useMemo(() => points.find((p) => p.spot != null)?.spot ?? null, [points]);
  const atm = useMemo(() => nearestStrike(strikes, spot), [strikes, spot]);

  const [strike, setStrike] = useState<string | null>(null);
  // Default to the ATM strike once the list loads (and re-default if the underlying/expiry changed it).
  useEffect(() => {
    if (atm && (strike == null || !strikes.includes(strike))) setStrike(atm);
  }, [atm, strike, strikes]);

  const seriesQ = useStrikeSeries(strike);
  const rows = useMemo(() => foldOiAnalysis(seriesQ.data?.items ?? []), [seriesQ.data]);
  const intervalMinutes = useMemo(
    () => parseInt(seriesQ.data?.interval ?? '3m', 10) || 3,
    [seriesQ.data],
  );

  return (
    <div>
      <h1 className="ay-sr-only">Options OI Analysis</h1>

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry allowedIntervals={ANALYSIS_INTERVALS} />
        <Select
          ariaLabel="Strike price"
          value={strike}
          options={strikes}
          onChange={setStrike}
          placeholder="Strike"
          disabled={strikes.length === 0}
        />
        <GoButton onClick={() => seriesQ.refetch()} loading={seriesQ.isFetching} />
      </div>

      {/* Underlying header (§20.7.5). DH/DL/DO pending — strike-series carries spot only. */}
      <p className="mb-3 flex flex-wrap items-center gap-2 text-sm text-ay-muted" aria-live="polite">
        <span className="rounded border border-ay-border bg-surface-1 px-2 py-1 text-xs">
          <span className="text-ay-muted">Underlying </span>
          <span className="font-semibold tabular-nums">
            {spot ? formatDecimal(spot, 2) : '—'}
          </span>
        </span>
        <span className="text-xs">· DH/DL/DO pending (underlying OHLC not in the feed)</span>
      </p>

      {seriesQ.data == null && !seriesQ.isLoading && (
        <p className="mb-3 text-sm text-ay-muted">
          No intraday series — pick an underlying + expiry + strike with captured snapshots.
        </p>
      )}

      <OiAnalysisTable rows={rows} strike={strike} intervalMinutes={intervalMinutes} />
    </div>
  );
}
