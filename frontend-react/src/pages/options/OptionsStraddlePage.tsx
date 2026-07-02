import { useEffect, useMemo, useState } from 'react';
import { formatDecimal } from '../../lib/decimal.ts';
import { nearestStrike } from '../../lib/strikes.ts';
import { CandlestickChart } from 'lucide-react';
import { useChainTable, useStraddleChart } from '../../api/oiAnalytics.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { EmptyCard } from '../../components/EmptyCard.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { Select, type SelectOption } from '../../components/atoms/Select.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { Metric } from '../../components/atoms/Metric.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { StraddleChart } from '../../components/StraddleChart.tsx';
import { BeatStrip, BeatItem, BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';

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
    <LoadBeat>
      <PageHeader title="Options straddle chart" help="Charts the combined call+put premium of a strike (the straddle) as candles with VWAP and moving-average overlays — a falling line means option sellers are winning, a rising line favours buyers; the Strangle toggle splits it into separate call and put strikes." />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry showInterval={false} />
        <Select
          ariaLabel="Time interval"
          title="Candle size for the straddle chart (in minutes)"
          value={String(intervalMin)}
          options={INTERVAL_OPTIONS}
          onChange={(v) => setIntervalMin(parseInt(v, 10) || 3)}
        />
        <label
          className="flex h-9 items-center gap-1.5 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text"
          title="Split the single straddle strike into a separate call strike and put strike (a strangle)"
        >
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
              title="The call leg's strike for the strangle"
              value={callStrike}
              options={strikes}
              onChange={setCallStrike}
              placeholder="Call strike"
              disabled={strikes.length === 0}
            />
            <Select
              ariaLabel="Put strike"
              title="The put leg's strike for the strangle"
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
            title="The strike whose combined call+put (straddle) premium is charted (defaults to at-the-money)"
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
      <BeatStrip className="card shadow-e1 mb-3 flex flex-wrap items-center gap-2" aria-live="polite">
        <BeatItem>
          <Metric
            label={data?.underlying ?? chainQ.data?.underlying ?? 'Underlying'}
            value={data?.underlyingLtp ? formatDecimal(data.underlyingLtp, 2) : '—'}
          />
        </BeatItem>
        <BeatItem>
          <Metric
            label="DO"
            value={data?.underlyingDayOpen ? formatDecimal(data.underlyingDayOpen, 2) : '—'}
          />
        </BeatItem>
        <BeatItem>
          <Metric
            label="Strikes"
            value={
              strangle
                ? `${callStrike ?? '—'} / ${putStrike ?? '—'}`
                : (strike ?? '—')
            }
          />
        </BeatItem>
        <BeatItem>
          <Metric label="Interval" value={data?.interval ?? '—'} />
        </BeatItem>
        <BeatItem>
          <Metric label="Last updated" value={data?.asOf ? data.asOf.slice(11, 19) : '—'} />
        </BeatItem>
      </BeatStrip>

      <QueryState
        query={straddleQ}
        isEmpty={() => data == null}
        // §3-P0: only claim "pick an underlying" when something is genuinely unpicked — a settled
        // empty on a fully-picked selection is a data gap, not a missing selection.
        empty={
          baseStrike == null
            ? {
                icon: CandlestickChart,
                title: 'Pick an underlying first.',
                hint: 'Choose an underlying, expiry and strike to load the straddle chart.',
              }
            : {
                icon: CandlestickChart,
                title: 'No straddle data for this selection yet.',
                hint: 'Snapshots accrue during market hours — hit Go again shortly or pick another expiry.',
              }
        }
        errorTitle="Couldn't load straddle chart"
        skeleton={<Skeleton variant="chart-block" className="h-64 sm:h-80 lg:h-[440px]" />}
      >
        {() =>
          data != null &&
          (data.items.length === 0 ? (
            <EmptyCard
              icon={CandlestickChart}
              title="No intraday candles (EOD only)."
              hint="This strike/session has no intraday option trades — pick a strike that traded intraday."
            />
          ) : (
            <BeatBlock>
              <section className="card shadow-e1">
                <h2 className="mb-2 text-h3 text-ay-text">
                  Options {strangle ? 'Strangle' : 'Straddle'} Chart
                </h2>
                <StraddleChart
                  items={data.items}
                  callStrike={data.callStrike}
                  putStrike={data.putStrike}
                  underlying={data.underlying}
                />
              </section>
            </BeatBlock>
          ))
        }
      </QueryState>
    </LoadBeat>
  );
}
