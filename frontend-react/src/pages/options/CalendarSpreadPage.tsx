import { useEffect, useMemo, useState } from 'react';
import { CandlestickChart } from 'lucide-react';
import { formatDecimal } from '../../lib/decimal.ts';
import { nearestStrike } from '../../lib/strikes.ts';
import { useChainTable, useCalendarSpreadChart } from '../../api/oiAnalytics.ts';
import { useExpiries } from '../../api/instruments.ts';
import { useSymbolContext } from '../../stores/symbolContext.store.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { EmptyCard } from '../../components/EmptyCard.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { Select, type SelectOption } from '../../components/atoms/Select.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { Metric } from '../../components/atoms/Metric.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { CalendarSpreadChart } from '../../components/CalendarSpreadChart.tsx';
import { BeatStrip, BeatItem, BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';

// Calendar Spread Chart (oipulse strategies/calendar-spread). Charts the premium DIFFERENTIAL of one
// strike+type across two expiries (near − far) as candles with VWAP/20-EMA/Near/Far overlays — a
// horizontal-spread / time-decay view. The two expiries are page-owned (the shared FilterBar carries a
// single expiry); strikes + ATM default come from the live /chain-table. Money = decimal strings; only
// the chart COORDINATES cross to numbers (core/calendarSpreadSeries).

const INTERVAL_OPTIONS: SelectOption[] = [1, 3, 5, 10, 15, 30, 60].map((m) => ({
  value: String(m),
  label: `${m} min`,
}));

const TYPE_OPTIONS: SelectOption[] = [
  { value: 'CE', label: 'CE' },
  { value: 'PE', label: 'PE' },
];

export function CalendarSpreadPage() {
  const name = useSymbolContext((s) => s.name);
  const chainQ = useChainTable();
  const rows = useMemo(() => chainQ.data?.rows ?? [], [chainQ.data]);
  const strikes = useMemo(() => rows.map((r) => r.strike), [rows]);
  const spot = chainQ.data?.spot ?? null;
  const atm = useMemo(() => nearestStrike(strikes, spot), [strikes, spot]);

  const expiriesQ = useExpiries(name);
  const expiries = useMemo(() => expiriesQ.data ?? [], [expiriesQ.data]);

  const [intervalMin, setIntervalMin] = useState(3);
  const [optionType, setOptionType] = useState('CE');
  const [strike, setStrike] = useState<string | null>(null);
  const [nearExpiry, setNearExpiry] = useState<string | null>(null);
  const [farExpiry, setFarExpiry] = useState<string | null>(null);

  // Default the strike to ATM once the chain loads (and re-default if it moved).
  useEffect(() => {
    if (atm && (strike == null || !strikes.includes(strike))) setStrike(atm);
  }, [atm, strike, strikes]);

  // Default the two expiries to the nearest two once the list loads.
  useEffect(() => {
    if (expiries.length === 0) return;
    if (nearExpiry == null || !expiries.includes(nearExpiry)) setNearExpiry(expiries[0]);
    if (farExpiry == null || !expiries.includes(farExpiry)) {
      setFarExpiry(expiries[1] ?? expiries[0]);
    }
  }, [expiries, nearExpiry, farExpiry]);

  const expiryOptions: SelectOption[] = expiries.map((e) => ({ value: e, label: e }));
  const spreadQ = useCalendarSpreadChart(strike, optionType, nearExpiry, farExpiry, intervalMin);
  const data = spreadQ.data ?? null;

  return (
    <LoadBeat>
      <PageHeader
        title="Calendar spread chart"
        help="Charts the premium difference (near expiry − far expiry) of one strike and option type as candles — a calendar/horizontal spread. A rising line means the near leg is gaining on the far (time decay favouring the spread); pick the same strike at two expiries to build it."
      />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry={false} showInterval={false} />
        <Select
          ariaLabel="Time interval"
          title="Candle size for the spread chart (in minutes)"
          value={String(intervalMin)}
          options={INTERVAL_OPTIONS}
          onChange={(v) => setIntervalMin(parseInt(v, 10) || 3)}
        />
        <Select
          ariaLabel="Strike price"
          title="The strike charted on both expiries (defaults to at-the-money)"
          value={strike}
          options={strikes}
          onChange={setStrike}
          placeholder="Strike"
          disabled={strikes.length === 0}
        />
        <Select
          ariaLabel="Option type"
          title="Call (CE) or Put (PE) leg"
          value={optionType}
          options={TYPE_OPTIONS}
          onChange={setOptionType}
        />
        <Select
          ariaLabel="Near expiry"
          title="The closer (near) expiry leg"
          value={nearExpiry}
          options={expiryOptions}
          onChange={setNearExpiry}
          placeholder="Near expiry"
          disabled={expiries.length === 0}
        />
        <Select
          ariaLabel="Far expiry"
          title="The later (far) expiry leg — must be after the near expiry"
          value={farExpiry}
          options={expiryOptions}
          onChange={setFarExpiry}
          placeholder="Far expiry"
          disabled={expiries.length === 0}
        />
        <GoButton onClick={() => spreadQ.refetch()} loading={spreadQ.isFetching} />
      </div>

      <BeatStrip className="card shadow-e1 mb-3 flex flex-wrap items-center gap-2" aria-live="polite">
        <BeatItem>
          <Metric
            label={data?.underlying ?? chainQ.data?.underlying ?? 'Underlying'}
            value={data?.underlyingLtp ? formatDecimal(data.underlyingLtp, 2) : '—'}
          />
        </BeatItem>
        <BeatItem>
          <Metric label="Strike" value={data?.strike ?? strike ?? '—'} />
        </BeatItem>
        <BeatItem>
          <Metric label="Type" value={data?.optionType ?? optionType} />
        </BeatItem>
        <BeatItem>
          <Metric
            label="Near − Far"
            value={
              data?.nearExpiry && data?.farExpiry
                ? `${data.nearExpiry} − ${data.farExpiry}`
                : '—'
            }
          />
        </BeatItem>
        <BeatItem>
          <Metric label="Last updated" value={data?.asOf ? data.asOf.slice(11, 19) : '—'} />
        </BeatItem>
      </BeatStrip>

      <QueryState
        query={spreadQ}
        isEmpty={() => data == null}
        empty={{
          icon: CandlestickChart,
          title: 'Pick the two legs first.',
          hint: 'Choose an underlying, strike, type and two expiries to load the calendar spread.',
        }}
        errorTitle="Couldn't load calendar spread"
        skeleton={<Skeleton variant="chart-block" className="h-64 sm:h-80 lg:h-[440px]" />}
      >
        {() =>
          data != null &&
          (data.items.length === 0 ? (
            <EmptyCard
              icon={CandlestickChart}
              title="No overlapping intraday candles."
              hint="One of the two legs has no intraday trades in this session — pick a more liquid strike or expiry pair."
            />
          ) : (
            <BeatBlock>
              <div className="grid grid-cols-1 gap-3 lg:grid-cols-[1fr_280px]">
                <section className="card shadow-e1">
                  <h2 className="mb-2 text-h3 text-ay-text">Calendar Spread Chart</h2>
                  <CalendarSpreadChart
                    items={data.items}
                    underlying={data.underlying}
                    strike={data.strike}
                    optionType={data.optionType}
                    nearExpiry={data.nearExpiry}
                    farExpiry={data.farExpiry}
                  />
                </section>
                <section className="card shadow-e1">
                  <h2 className="mb-2 text-h3 text-ay-text">Positions</h2>
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="text-left text-xs uppercase text-ay-muted">
                        <th className="py-1">Leg</th>
                        <th className="py-1">Expiry</th>
                        <th className="py-1">Strike</th>
                        <th className="py-1">Type</th>
                      </tr>
                    </thead>
                    <tbody className="tabular-nums">
                      <tr className="border-t border-ay-border">
                        <td className="py-1">Near</td>
                        <td className="py-1">{data.nearExpiry}</td>
                        <td className="py-1">{data.strike}</td>
                        <td className="py-1">{data.optionType}</td>
                      </tr>
                      <tr className="border-t border-ay-border">
                        <td className="py-1">Far</td>
                        <td className="py-1">{data.farExpiry}</td>
                        <td className="py-1">{data.strike}</td>
                        <td className="py-1">{data.optionType}</td>
                      </tr>
                    </tbody>
                  </table>
                </section>
              </div>
            </BeatBlock>
          ))
        }
      </QueryState>
    </LoadBeat>
  );
}
