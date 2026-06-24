import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { CandleChart } from '../../components/charts/CandleChart.tsx';
import { Select } from '../../components/atoms/Select.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { CHART_INTERVALS, useCandles, useChartSignals, type ChartMark } from '../../api/charts.ts';
import { useBacktestTrades } from '../../api/backtests.ts';

// /charts (master plan §20 parity, A13): a lightweight-charts candlestick + volume view (the plan's
// premium chart, MIT — replaced the ECharts MVP) with the interval/instrument toolbar and the
// engine-overlay marks the "View on chart" deep-links carry — backtest trades (?runId) or signals on
// the symbol. Indicator overlays + a live streaming datafeed are deferred.

export function ChartsPage() {
  const [params] = useSearchParams();
  const runId = params.get('runId');
  const [symbol, setSymbol] = useState(params.get('symbol') ?? 'NSE:NIFTY 50');
  const [interval, setInterval] = useState(params.get('interval') ?? '1d');
  const [symbolDraft, setSymbolDraft] = useState(symbol);

  const candles = useCandles(symbol, interval);
  const trades = useBacktestTrades(runId ?? '');
  const signalMarks = useChartSignals(symbol, !runId);

  const bars = useMemo(() => candles.data?.items ?? [], [candles.data]);

  const marks: ChartMark[] = useMemo(() => {
    if (runId) {
      return (trades.data?.items ?? []).flatMap((t) => [
        { timeIso: t.entryTs, price: t.entryPrice, label: `#${t.seq} ${t.side} in`, bullish: t.side === 'LONG' },
        ...(t.exitTs && t.exitPrice
          ? [{ timeIso: t.exitTs, price: t.exitPrice, label: `#${t.seq} out`, bullish: t.side !== 'LONG' }]
          : []),
      ]);
    }
    return signalMarks.data ?? [];
  }, [runId, trades.data, signalMarks.data]);

  return (
    <LoadBeat>
      <PageHeader title="Charts" subtitle="Candlestick + volume — backtest-trade & signal overlays via deep-link" />
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (symbolDraft.trim()) setSymbol(symbolDraft.trim());
          }}
          className="flex items-center gap-2"
        >
          <input
            value={symbolDraft}
            onChange={(e) => setSymbolDraft(e.target.value)}
            placeholder="EXCHANGE:SYMBOL"
            aria-label="Instrument"
            className="h-9 w-full sm:w-56 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text"
          />
          <button type="submit" className="h-9 rounded-md border border-ay-border px-3 text-sm hover:border-accent">
            Load
          </button>
        </form>
        <Select value={interval} options={[...CHART_INTERVALS]} onChange={setInterval} ariaLabel="Interval" />
        {runId && <span className="text-xs text-ay-muted">overlaying trades from run {runId.slice(0, 8)}</span>}
      </div>

      <BeatBlock>
        {bars.length > 0 ? (
          <CandleChart bars={bars} marks={marks} height={460} ariaLabel={`${symbol} ${interval} candlestick chart`} />
        ) : (
          <div className="grid h-[460px] place-items-center rounded-lg border border-ay-border text-ay-muted">
            {candles.isLoading ? 'Loading candles…' : `No candles for ${symbol} @ ${interval}.`}
          </div>
        )}
      </BeatBlock>
    </LoadBeat>
  );
}
