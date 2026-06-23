import { useCallback, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import type { EChartsOption } from 'echarts';
import { EChart, type ChartTheme } from '../../components/atoms/EChart.tsx';
import { Select } from '../../components/atoms/Select.tsx';
import { CHART_INTERVALS, useCandles, useChartSignals, type ChartMark } from '../../api/charts.ts';
import { useBacktestTrades } from '../../api/backtests.ts';

// /charts (master plan §20 parity, A13): a focused ECharts candlestick + volume view with the
// interval/instrument toolbar and the engine-overlay marks the "View on chart" deep-links carry —
// backtest trades (?runId) or signals on the symbol. Indicator overlays + a live streaming datafeed
// are deferred (the premium chart library — LWC / openalgo-chart / TradingView — is a later swap).

const xlabel = (bucket: string) => bucket.slice(0, 16).replace('T', ' ');

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

  const makeOption = useCallback(
    (t: ChartTheme): EChartsOption => {
      const x = bars.map((c) => xlabel(c.bucket));
      const nearestX = (timeIso: string): string | null => {
        if (!bars.length) return null;
        const target = Date.parse(timeIso);
        let best = bars[0];
        let bestDiff = Infinity;
        for (const c of bars) {
          const diff = Math.abs(Date.parse(c.bucket) - target);
          if (diff < bestDiff) {
            bestDiff = diff;
            best = c;
          }
        }
        return xlabel(best.bucket);
      };
      return {
        textStyle: { color: t.text },
        tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
        axisPointer: { link: [{ xAxisIndex: 'all' }] },
        grid: [
          { left: 56, right: 16, top: 16, height: '62%' },
          { left: 56, right: 16, top: '76%', height: '18%' },
        ],
        xAxis: [
          { type: 'category', data: x, axisLine: { lineStyle: { color: t.border } }, axisLabel: { color: t.muted } },
          { type: 'category', gridIndex: 1, data: x, axisLabel: { show: false }, axisTick: { show: false } },
        ],
        yAxis: [
          { scale: true, axisLabel: { color: t.muted }, splitLine: { lineStyle: { color: t.grid } } },
          { gridIndex: 1, splitNumber: 2, axisLabel: { color: t.muted }, splitLine: { show: false } },
        ],
        dataZoom: [{ type: 'inside', xAxisIndex: [0, 1] }],
        series: [
          {
            type: 'candlestick',
            data: bars.map((c) => [Number(c.open), Number(c.close), Number(c.low), Number(c.high)]),
            itemStyle: { color: t.bull, color0: t.bear, borderColor: t.bull, borderColor0: t.bear },
            markPoint: {
              symbol: 'pin',
              symbolSize: 36,
              data: marks
                .map((m) => {
                  const coordX = nearestX(m.timeIso);
                  return coordX
                    ? { coord: [coordX, Number(m.price)] as [string, number], value: m.label, itemStyle: { color: m.bullish ? t.bull : t.bear } }
                    : null;
                })
                .filter((d): d is NonNullable<typeof d> => d !== null),
            },
          },
          {
            type: 'bar',
            xAxisIndex: 1,
            yAxisIndex: 1,
            data: bars.map((c) => c.volume),
            itemStyle: { color: t.muted },
          },
        ] as EChartsOption['series'],
      };
    },
    [bars, marks],
  );

  return (
    <div>
      <h1 className="ay-sr-only">Charts</h1>
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
            className="h-9 w-56 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text"
          />
          <button type="submit" className="h-9 rounded-md border border-ay-border px-3 text-sm hover:border-accent">
            Load
          </button>
        </form>
        <Select value={interval} options={[...CHART_INTERVALS]} onChange={setInterval} ariaLabel="Interval" />
        {runId && <span className="text-xs text-ay-muted">overlaying trades from run {runId.slice(0, 8)}</span>}
      </div>

      {bars.length > 0 ? (
        <EChart makeOption={makeOption} height={460} ariaLabel={`${symbol} ${interval} candlestick chart`} />
      ) : (
        <div className="grid h-[460px] place-items-center rounded-lg border border-ay-border text-ay-muted">
          {candles.isLoading ? 'Loading candles…' : `No candles for ${symbol} @ ${interval}.`}
        </div>
      )}
    </div>
  );
}
