import { useEffect, useMemo, useState } from 'react';
import { formatDecimal } from '../../lib/decimal.ts';
import { nearestStrike } from '../../lib/strikes.ts';
import { CandlestickChart } from 'lucide-react';
import { useChainTable, useOptionsChart } from '../../api/oiAnalytics.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { Select, type SelectOption } from '../../components/atoms/Select.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { Metric } from '../../components/atoms/Metric.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { OptionsLegChart } from '../../components/OptionsLegChart.tsx';
import { BeatStrip, BeatItem, BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';

// Options Chart (§options/options-chart). Per leg (Call, Put) of one strike: the option-premium
// candlestick (right axis) + OI line (left axis) + VWAP overlay + day H/L markers. One fetch returns both
// legs; the Show toggle picks which render and the layout (side-by-side / up-down / single). Strike list +
// ATM default come from the live /chain-table. Money = decimal strings; only chart coords cross to numbers.
// IV + Volume sub-panes are a deferred follow-up.

const INTERVAL_OPTIONS: SelectOption[] = [1, 3, 5, 10, 15, 30, 60].map((m) => ({
  value: String(m),
  label: `${m} min`,
}));

const SHOW_OPTIONS: SelectOption[] = [
  { value: 'both-h', label: 'Both side-by-side' },
  { value: 'both-v', label: 'Both up-down' },
  { value: 'call', label: 'Call only' },
  { value: 'put', label: 'Put only' },
];

export function OptionsChartPage() {
  const chainQ = useChainTable();
  const rows = useMemo(() => chainQ.data?.rows ?? [], [chainQ.data]);
  const strikes = useMemo(() => rows.map((r) => r.strike), [rows]);
  const spot = chainQ.data?.spot ?? null;
  const atm = useMemo(() => nearestStrike(strikes, spot), [strikes, spot]);

  const [intervalMin, setIntervalMin] = useState(3);
  const [strike, setStrike] = useState<string | null>(null);
  const [show, setShow] = useState('both-h');

  // Default the strike to ATM once the chain loads (and re-default if it moved).
  useEffect(() => {
    if (atm && (strike == null || !strikes.includes(strike))) setStrike(atm);
  }, [atm, strike, strikes]);

  const q = useOptionsChart(strike, intervalMin);
  const data = q.data ?? null;

  const showCall = show !== 'put';
  const showPut = show !== 'call';
  const sideBySide = show === 'both-h';

  return (
    <LoadBeat>
      <PageHeader title="Options chart" />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry showInterval={false} />
        <Select
          ariaLabel="Time interval"
          value={String(intervalMin)}
          options={INTERVAL_OPTIONS}
          onChange={(v) => setIntervalMin(parseInt(v, 10) || 3)}
        />
        <Select ariaLabel="Show" value={show} options={SHOW_OPTIONS} onChange={setShow} />
        <Select
          ariaLabel="Strike price"
          value={strike}
          options={strikes}
          onChange={setStrike}
          placeholder="Strike"
          disabled={strikes.length === 0}
        />
        <GoButton onClick={() => q.refetch()} loading={q.isFetching} />
      </div>

      {/* Underlying + strike header strip. */}
      <BeatStrip className="card shadow-e1 mb-3 flex flex-wrap items-center gap-2" aria-live="polite">
        <BeatItem>
          <Metric
            label={data?.underlying ?? chainQ.data?.underlying ?? 'Underlying'}
            value={data?.underlyingLtp ? formatDecimal(data.underlyingLtp, 2) : '—'}
          />
        </BeatItem>
        <BeatItem>
          <Metric label="DO" value={data?.underlyingDayOpen ? formatDecimal(data.underlyingDayOpen, 2) : '—'} />
        </BeatItem>
        <BeatItem>
          <Metric label="Strike" value={strike ?? '—'} />
        </BeatItem>
        <BeatItem>
          <Metric label="Interval" value={data?.interval ?? '—'} />
        </BeatItem>
        <BeatItem>
          <Metric label="Last updated" value={data?.asOf ? data.asOf.slice(11, 19) : '—'} />
        </BeatItem>
      </BeatStrip>

      <QueryState
        query={q}
        isEmpty={() => data == null}
        empty={{
          icon: CandlestickChart,
          title: 'No options chart — pick an underlying + expiry + strike.',
        }}
        errorTitle="Couldn't load options chart"
        skeleton={
          <div className={sideBySide ? 'flex flex-col gap-4 md:flex-row' : 'flex flex-col gap-4'}>
            {showCall && <Skeleton variant="chart-block" height={400} className="flex-1" />}
            {showPut && <Skeleton variant="chart-block" height={400} className="flex-1" />}
          </div>
        }
      >
        {() =>
          data != null && (
            <BeatBlock className={sideBySide ? 'flex flex-col gap-4 md:flex-row' : 'flex flex-col gap-4'}>
              {showCall && (
                <section className="card shadow-e1 flex-1">
                  <h2 className="mb-2 text-h3 text-ay-text">Call Option Chart</h2>
                  {data.ce.length > 0 ? (
                    <OptionsLegChart items={data.ce} tradingsymbol={data.ceTradingsymbol} legLabel="Call" />
                  ) : (
                    <p className="text-center text-sm text-ay-muted">No Call bars for this strike/session.</p>
                  )}
                </section>
              )}
              {showPut && (
                <section className="card shadow-e1 flex-1">
                  <h2 className="mb-2 text-h3 text-ay-text">Put Option Chart</h2>
                  {data.pe.length > 0 ? (
                    <OptionsLegChart items={data.pe} tradingsymbol={data.peTradingsymbol} legLabel="Put" />
                  ) : (
                    <p className="text-center text-sm text-ay-muted">No Put bars for this strike/session.</p>
                  )}
                </section>
              )}
            </BeatBlock>
          )
        }
      </QueryState>

      <p className="mt-2 text-xs text-ay-muted">IV + Volume sub-panes are a deferred follow-up.</p>
    </LoadBeat>
  );
}
