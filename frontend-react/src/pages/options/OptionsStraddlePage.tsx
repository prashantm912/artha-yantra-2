import { useEffect, useMemo, useState } from 'react';
import { compareDecimal, formatDecimal, isNegative, subtractDecimal } from '../../lib/decimal.ts';
import { useChainTable, useStraddleChart } from '../../api/oiAnalytics.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { Select, type SelectOption } from '../../components/atoms/Select.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { StraddleChart } from '../../components/StraddleChart.tsx';

// Straddle/Strangle Chart (§20.7.6). The combined CE+PE premium candlestick + VWAP/20-EMA/Call/Put
// overlays. Strike list + ATM default come from the live /chain-table (no snapshot dependency). A
// "Strangle" toggle splits the single strike into a Call-strike + Put-strike pair. Money = decimal
// strings; only the chart COORDINATES cross to numbers (core/straddleSeries).

// oipulse's straddle interval set — faithful, INCLUDING 10-min (the shared OiInterval lacks it). The BE
// takes raw minutes, so this page owns its interval selector instead of the shared FilterBar interval.
const INTERVAL_OPTIONS: SelectOption[] = [1, 3, 5, 10, 15, 30, 60].map((m) => ({
  value: String(m),
  label: `${m} min`,
}));

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

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <span className="rounded border border-ay-border bg-surface-1 px-2 py-1 text-xs text-ay-text">
      <span className="text-ay-muted">{label} </span>
      <span className="font-semibold tabular-nums">{value}</span>
    </span>
  );
}

export function OptionsStraddlePage() {
  const chainQ = useChainTable();
  const rows = useMemo(() => chainQ.data?.rows ?? [], [chainQ.data]);
  const strikes = useMemo(() => rows.map((r) => r.strike), [rows]);
  const spot = chainQ.data?.spot ?? null;
  const atm = useMemo(() => nearestStrike(strikes, spot), [strikes, spot]);

  const [strangle, setStrangle] = useState(false);
  const [intervalMin, setIntervalMin] = useState(3);
  const [strike, setStrike] = useState<string | null>(null);
  const [callStrike, setCallStrike] = useState<string | null>(null);
  const [putStrike, setPutStrike] = useState<string | null>(null);

  // Default the three strike selectors to the ATM once the chain loads (and re-default if it moved).
  useEffect(() => {
    if (!atm) return;
    if (strike == null || !strikes.includes(strike)) setStrike(atm);
    if (callStrike == null || !strikes.includes(callStrike)) setCallStrike(atm);
    if (putStrike == null || !strikes.includes(putStrike)) setPutStrike(atm);
  }, [atm, strike, callStrike, putStrike, strikes]);

  const baseStrike = strangle ? callStrike : strike;
  const straddleQ = useStraddleChart(
    baseStrike,
    strangle ? callStrike : null,
    strangle ? putStrike : null,
    intervalMin,
  );
  const data = straddleQ.data ?? null;

  return (
    <div>
      <h1 className="ay-sr-only">Options straddle chart</h1>

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry showInterval={false} />
        <Select
          ariaLabel="Time interval"
          value={String(intervalMin)}
          options={INTERVAL_OPTIONS}
          onChange={(v) => setIntervalMin(parseInt(v, 10) || 3)}
        />
        <label className="flex h-9 items-center gap-1.5 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text">
          <input
            type="checkbox"
            checked={strangle}
            onChange={(e) => setStrangle(e.target.checked)}
            className="accent-accent"
          />
          Strangle
        </label>
        {strangle ? (
          <>
            <Select
              ariaLabel="Call strike"
              value={callStrike}
              options={strikes}
              onChange={setCallStrike}
              placeholder="Call strike"
              disabled={strikes.length === 0}
            />
            <Select
              ariaLabel="Put strike"
              value={putStrike}
              options={strikes}
              onChange={setPutStrike}
              placeholder="Put strike"
              disabled={strikes.length === 0}
            />
          </>
        ) : (
          <Select
            ariaLabel="Strike price"
            value={strike}
            options={strikes}
            onChange={setStrike}
            placeholder="Strike"
            disabled={strikes.length === 0}
          />
        )}
        <GoButton onClick={() => straddleQ.refetch()} loading={straddleQ.isFetching} />
      </div>

      {/* Underlying + strike header strip (§20.7.6). */}
      <div className="mb-3 flex flex-wrap items-center gap-2" aria-live="polite">
        <Metric
          label={data?.underlying ?? chainQ.data?.underlying ?? 'Underlying'}
          value={data?.underlyingLtp ? formatDecimal(data.underlyingLtp, 2) : '—'}
        />
        <Metric
          label="DO"
          value={data?.underlyingDayOpen ? formatDecimal(data.underlyingDayOpen, 2) : '—'}
        />
        <Metric
          label="Strikes"
          value={
            strangle
              ? `${callStrike ?? '—'} / ${putStrike ?? '—'}`
              : (strike ?? '—')
          }
        />
        <Metric label="Interval" value={data?.interval ?? '—'} />
        <Metric label="Last updated" value={data?.asOf ? data.asOf.slice(11, 19) : '—'} />
      </div>

      {data != null && data.items.length === 0 && !straddleQ.isLoading && (
        <p className="mb-3 text-sm text-ay-muted">
          No candles for this strike/session — pick a strike with intraday option trades.
        </p>
      )}
      {data == null && !straddleQ.isLoading && (
        <p className="mb-3 text-sm text-ay-muted">
          No straddle data — pick an underlying + expiry + strike.
        </p>
      )}

      {data != null && data.items.length > 0 && (
        <>
          <h2 className="mb-1 text-center text-sm font-semibold text-ay-text">
            Options {strangle ? 'Strangle' : 'Straddle'} Chart
          </h2>
          <StraddleChart
            items={data.items}
            callStrike={data.callStrike}
            putStrike={data.putStrike}
            underlying={data.underlying}
          />
        </>
      )}
    </div>
  );
}
