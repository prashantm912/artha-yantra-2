import { useEffect, useMemo, useState } from 'react';
import { compareDecimal } from '../../lib/decimal.ts';
import { nearestStrike } from '../../lib/strikes.ts';
import { useOiAnalysis, useStrikeSeries } from '../../api/oiAnalytics.ts';
import { foldOptionsOiChart } from '../../core/optionsOiChartFold.ts';
import type { OiInterval } from '../../stores/symbolContext.store.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { Select } from '../../components/atoms/Select.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { CallPutOiChart, OiVsPriceChart } from '../../components/OptionsOiCharts.tsx';

// Options OI Chart (§options/oi-chart) — the chart counterpart of Options OI Analysis, ZERO new backend:
// rides the SAME strike-series feed (useStrikeSeries) and folds CE+PE OI + premium into three line charts.
// A Strike selector drives the feed (ATM default from the latest oi-analysis bucket). Interval set
// 3/5/15/30/60 (no 1m/10m — our OiInterval gaps). Decimal strings — only chart coords cross to number.

const ANALYSIS_INTERVALS: readonly OiInterval[] = ['3m', '5m', '15m', '30m', '60m'];

export function OptionsOiChartPage() {
  const analysisQ = useOiAnalysis();
  const points = useMemo(() => analysisQ.data ?? [], [analysisQ.data]);
  const strikes = useMemo(
    () => [...new Set(points.map((p) => p.strike))].sort((a, b) => compareDecimal(a, b)),
    [points],
  );
  const spot = useMemo(() => points.find((p) => p.spot != null)?.spot ?? null, [points]);
  const atm = useMemo(() => nearestStrike(strikes, spot), [strikes, spot]);

  const [strike, setStrike] = useState<string | null>(null);
  useEffect(() => {
    if (atm && (strike == null || !strikes.includes(strike))) setStrike(atm);
  }, [atm, strike, strikes]);

  const seriesQ = useStrikeSeries(strike);
  const viz = useMemo(() => foldOptionsOiChart(seriesQ.data?.items ?? []), [seriesQ.data]);
  const hasData = viz.times.length > 0;

  return (
    <div>
      <PageHeader title="Options OI chart" />

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

      {!hasData && !seriesQ.isLoading && (
        <p className="mb-3 text-sm text-ay-muted">
          No intraday OI — pick an underlying + expiry + strike with captured snapshots.
        </p>
      )}

      {hasData && (
        <>
          <section className="card shadow-e1">
            <h2 className="mb-2 text-h3 text-ay-text">Call Vs. Put OI Analysis</h2>
            <CallPutOiChart viz={viz} />
          </section>
          <div className="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
            <section className="card shadow-e1">
              <h2 className="mb-2 text-h3 text-ay-text">Call OI Analysis</h2>
              <OiVsPriceChart times={viz.times} oi={viz.callOi} price={viz.callPrice} oiTone="bull" legLabel="Call" />
            </section>
            <section className="card shadow-e1">
              <h2 className="mb-2 text-h3 text-ay-text">Put OI Analysis</h2>
              <OiVsPriceChart times={viz.times} oi={viz.putOi} price={viz.putPrice} oiTone="bear" legLabel="Put" />
            </section>
          </div>
        </>
      )}
    </div>
  );
}
