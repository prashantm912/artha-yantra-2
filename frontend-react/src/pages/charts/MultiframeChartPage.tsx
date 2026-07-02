import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { AdvanceChart } from '../../components/charts/AdvanceChart.tsx';
import { Select } from '../../components/atoms/Select.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { BeatBlock } from '../../components/LoadBeat.tsx';
import { CHART_INTERVALS, useCandles } from '../../api/charts.ts';

// Multiframe Chart (oipulse §advance-chart/multiframe-chart) — a 2x2 grid of Advance Charts on the SAME
// instrument at four timeframes, for the top-down (higher-TF → lower-TF) read the study calls for. One
// symbol drives all four cells; each cell's interval is independently selectable. Reuses the AdvanceChart
// component (the tested core/indicators study set) per cell.

const DEFAULT_INTERVALS = ['15m', '5m', '3m', '1m'];
// Index symbols have no 1m candles — the default 4th pane sat permanently on "No candles"
// (audit 2026-07-02 §5); indices swap it for 30m.
const INDEX_DEFAULT_INTERVALS = ['15m', '5m', '3m', '30m'];
const INDEX_SYMBOLS = /NIFTY|SENSEX|BANKEX|VIX/i;

/** One grid cell — its own candle read + interval selector over the shared symbol. */
function Frame({
  symbol,
  interval,
  onInterval,
}: {
  symbol: string;
  interval: string;
  onInterval: (v: string) => void;
}) {
  const candles = useCandles(symbol, interval);
  const bars = candles.data?.items ?? [];
  return (
    <section className="flex min-h-0 flex-col rounded-md border border-ay-border bg-surface-0">
      <header className="flex items-center justify-between gap-2 border-b border-ay-border px-2 py-1.5">
        <span className="truncate text-caption font-semibold text-ay-muted">{symbol}</span>
        <Select value={interval} options={[...CHART_INTERVALS]} onChange={onInterval} ariaLabel={`Interval for ${symbol}`} title="Candle timeframe for this pane." />
      </header>
      <div className="min-h-0 flex-1 p-1">
        {bars.length > 0 ? (
          <AdvanceChart
            bars={bars}
            intraday={interval !== '1d' && interval !== '1w'}
            className="h-full"
            ariaLabel={`${symbol} ${interval} chart`}
          />
        ) : (
          <div className="grid h-full place-items-center text-caption text-ay-muted">
            {candles.isLoading ? 'Loading…' : 'No candles.'}
          </div>
        )}
      </div>
    </section>
  );
}

export function MultiframeChartPage() {
  const [params] = useSearchParams();
  const [symbol, setSymbol] = useState(params.get('symbol') ?? 'NSE:NIFTY 50');
  const [draft, setDraft] = useState(symbol);
  const [intervals, setIntervals] = useState<string[]>(
    INDEX_SYMBOLS.test(symbol) ? INDEX_DEFAULT_INTERVALS : DEFAULT_INTERVALS,
  );

  const setIntervalAt = (i: number, v: string) =>
    setIntervals((prev) => prev.map((x, k) => (k === i ? v : x)));

  // Loading an index while the 1m default pane is up (or vice versa) re-defaults that pane only.
  const loadSymbol = (next: string) => {
    setSymbol(next);
    setIntervals((prev) =>
      prev.map((iv, i) => {
        const wanted = INDEX_SYMBOLS.test(next) ? INDEX_DEFAULT_INTERVALS[i] : DEFAULT_INTERVALS[i];
        return iv === DEFAULT_INTERVALS[i] || iv === INDEX_DEFAULT_INTERVALS[i] ? wanted : iv;
      }),
    );
  };

  return (
    <div className="flex h-[calc(100vh-7rem)] flex-col">
      <PageHeader
        title="Multiframe Chart"
        subtitle="One instrument across four timeframes — the top-down (higher-TF → lower-TF) read"
        help="A 2x2 grid of Advance Charts on the same instrument, each at its own timeframe, for a top-down multi-timeframe read. Type an instrument and Load; pick each pane's interval independently."
      />

      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (draft.trim()) loadSymbol(draft.trim());
        }}
        className="mb-2 flex items-center gap-2"
      >
        <input
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          placeholder="EXCHANGE:SYMBOL"
          aria-label="Instrument"
          title="Type EXCHANGE:SYMBOL (e.g. NSE:NIFTY 50) and Load to chart it across all four panes."
          className="h-9 w-full sm:w-64 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text"
        />
        <button type="submit" className="h-9 rounded-md border border-ay-border px-3 text-sm hover:border-accent" title="Load this instrument into all four panes.">
          Load
        </button>
      </form>

      <BeatBlock className="grid min-h-0 flex-1 grid-cols-1 gap-2 md:grid-cols-2 md:grid-rows-2">
        {intervals.map((iv, i) => (
          <Frame key={i} symbol={symbol} interval={iv} onInterval={(v) => setIntervalAt(i, v)} />
        ))}
      </BeatBlock>
    </div>
  );
}
