import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { AdvanceChart } from '../../components/charts/AdvanceChart.tsx';
import { Select } from '../../components/atoms/Select.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { BeatBlock } from '../../components/LoadBeat.tsx';
import { CHART_INTERVALS, useCandles } from '../../api/charts.ts';

// Multiframe Chart (oipulse §advance-chart/multiframe-chart) — a 2x2 grid of Advance Charts for the
// top-down (higher-TF → lower-TF) read the study calls for. Each cell carries its OWN symbol and its own
// interval (FG-03); the toolbar loads one instrument into all four at once, so the default view is still
// one instrument across four timeframes. Reuses the AdvanceChart component (the tested core/indicators
// study set) per cell.

// Every default MUST be one of CHART_INTERVALS — /market/candles 400s on anything outside
// CandleQueryService.INTERVALS (1m/3m/5m/15m/1h/1d/1w). Indices used to default their 4th pane to '30m',
// which the API does not serve, so that pane 400'd twice on every index load and its <select> silently
// fell back to displaying '1m'. The "index symbols have no 1m candles" premise behind that swap is false
// (NIFTY 50, SENSEX, BANKEX and INDIA VIX all carry 1m), so the index special case is gone.
const DEFAULT_INTERVALS = ['15m', '5m', '3m', '1m'];

const replaceAt = (prev: string[], i: number, v: string) => prev.map((x, k) => (k === i ? v : x));

/** One grid cell — its own symbol and interval, and the candle read for that pair. */
function Frame({
  pane,
  symbol,
  draft,
  interval,
  onDraft,
  onSymbol,
  onInterval,
}: {
  pane: number;
  symbol: string;
  draft: string;
  interval: string;
  onDraft: (v: string) => void;
  onSymbol: (v: string) => void;
  onInterval: (v: string) => void;
}) {
  const candles = useCandles(symbol, interval);
  const bars = candles.data?.items ?? [];
  return (
    <section className="flex min-h-0 flex-col rounded-md border border-ay-border bg-surface-0">
      <header className="flex items-center gap-2 border-b border-ay-border px-2 py-1.5">
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (draft.trim()) onSymbol(draft.trim());
          }}
          className="flex min-w-0 flex-1 items-center gap-2"
        >
          <input
            value={draft}
            onChange={(e) => onDraft(e.target.value)}
            placeholder="EXCHANGE:SYMBOL"
            aria-label={`Instrument for pane ${pane}`}
            title="Type EXCHANGE:SYMBOL and Load to chart it in this pane only."
            className="h-9 min-w-0 flex-1 rounded-md border border-ay-border bg-surface-1 px-2 text-caption text-ay-text"
          />
          <button
            type="submit"
            aria-label={`Load pane ${pane}`}
            title="Load this instrument into this pane only."
            className="h-9 shrink-0 rounded-md border border-ay-border px-2 text-caption hover:border-accent"
          >
            Load
          </button>
        </form>
        <Select value={interval} options={[...CHART_INTERVALS]} onChange={onInterval} ariaLabel={`Interval for pane ${pane}`} title="Candle timeframe for this pane." className="shrink-0" />
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
  const initial = params.get('symbol') ?? 'NSE:NIFTY 50';
  const [symbols, setSymbols] = useState<string[]>(() => DEFAULT_INTERVALS.map(() => initial));
  const [drafts, setDrafts] = useState<string[]>(() => DEFAULT_INTERVALS.map(() => initial));
  const [draft, setDraft] = useState(initial);
  const [intervals, setIntervals] = useState<string[]>(DEFAULT_INTERVALS);

  // The toolbar keeps the original idiom: one instrument into all four panes at once.
  const loadAll = (next: string) => {
    setSymbols((prev) => prev.map(() => next));
    setDrafts((prev) => prev.map(() => next));
  };

  return (
    <div className="flex h-[calc(100vh-7rem)] flex-col">
      <PageHeader
        title="Multiframe Chart"
        subtitle="Four charts, each with its own instrument and timeframe — the top-down (higher-TF → lower-TF) read"
        help="A 2x2 grid of Advance Charts. Type an instrument and Load to chart it across all four panes at once — the top-down multi-timeframe read. Each pane can then be pointed at its own instrument and interval independently, to compare instruments side by side."
      />

      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (draft.trim()) loadAll(draft.trim());
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
          <Frame
            key={i}
            pane={i + 1}
            symbol={symbols[i]}
            draft={drafts[i]}
            interval={iv}
            onDraft={(v) => setDrafts((prev) => replaceAt(prev, i, v))}
            onSymbol={(v) => setSymbols((prev) => replaceAt(prev, i, v))}
            onInterval={(v) => setIntervals((prev) => replaceAt(prev, i, v))}
          />
        ))}
      </BeatBlock>
    </div>
  );
}
