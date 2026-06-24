import { useCallback, useMemo, useState } from 'react';
import type { EChartsOption } from 'echarts';
import { useOptionsPremium } from '../../api/oiAnalytics.ts';
import type { PremiumRow } from '../../api/types.ts';
import { BarChart3 } from 'lucide-react';
import { FilterBar } from '../../components/FilterBar.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { Metric } from '../../components/atoms/Metric.tsx';
import { Select } from '../../components/atoms/Select.tsx';
import { EChart, type ChartTheme } from '../../components/atoms/EChart.tsx';
import { formatDecimal } from '../../lib/decimal.ts';

// Options Premium (oipulse §options/options-premium): grouped Call/Put premium bars per strike around
// ATM. Confirmed live 2026-06-18 — the bars are EXTRINSIC value (LTP − intrinsic), and "Show LTP"
// switches to raw LTP. Call green, Put red, ATM markLine. Plotting is numeric (an echarts series); the
// extrinsic maths derive from the decimal LTP/spot — never a displayed/compared price.

const WINDOWS = ['10', '15', '25', 'All'] as const;
type Window = (typeof WINDOWS)[number];

const n = (s: string | null): number => (s == null ? 0 : Number(s));

/** N strikes nearest the ATM (by |strike − atm|), then re-sorted ascending for the axis. */
function windowed(items: PremiumRow[], atm: string | null, win: Window): PremiumRow[] {
  if (win === 'All' || atm == null) return [...items].sort((a, b) => n(a.strike) - n(b.strike));
  const count = Number(win);
  const atmN = n(atm);
  return [...items]
    .sort((a, b) => Math.abs(n(a.strike) - atmN) - Math.abs(n(b.strike) - atmN))
    .slice(0, count)
    .sort((a, b) => n(a.strike) - n(b.strike));
}

export function OptionsPremiumPage() {
  const q = useOptionsPremium();
  const [win, setWin] = useState<Window>('10');
  const [showLtp, setShowLtp] = useState(false);

  const chain = q.data ?? null;
  const spot = chain?.spot ?? null;
  const rows = useMemo(() => windowed(chain?.items ?? [], chain?.atmStrike ?? null, win), [chain, win]);

  const makeOption = useCallback(
    (t: ChartTheme): EChartsOption => {
      const strikes = rows.map((r) => r.strike);
      const spotN = n(spot);
      const call = rows.map((r) => {
        const raw = n(r.ce);
        return showLtp ? raw : raw - Math.max(0, spotN - n(r.strike)); // extrinsic = LTP − intrinsic
      });
      const put = rows.map((r) => {
        const raw = n(r.pe);
        return showLtp ? raw : raw - Math.max(0, n(r.strike) - spotN);
      });
      return {
        aria: { enabled: true },
        textStyle: { color: t.text },
        legend: { top: 0, textStyle: { color: t.muted }, data: ['Call Premium', 'Put Premium'] },
        grid: { left: 56, right: 16, top: 36, bottom: 48 },
        tooltip: {
          trigger: 'axis',
          backgroundColor: t.surface1,
          borderColor: t.border,
          textStyle: { color: t.text },
          axisPointer: { type: 'shadow' },
        },
        xAxis: {
          type: 'category',
          data: strikes,
          axisLine: { lineStyle: { color: t.border } },
          axisLabel: { color: t.muted },
        },
        yAxis: {
          type: 'value',
          scale: true,
          splitLine: { lineStyle: { color: t.grid } },
          axisLabel: { color: t.muted },
        },
        graphic: [
          {
            type: 'text',
            right: 24,
            bottom: 36,
            style: { text: 'OiPulse', fill: t.muted, fontSize: 11, opacity: 0.5 },
          },
        ],
        series: [
          {
            name: 'Call Premium',
            type: 'bar',
            data: call,
            itemStyle: { color: t.bull },
            markLine: chain?.atmStrike
              ? {
                  symbol: 'none',
                  data: [{ xAxis: chain.atmStrike }],
                  lineStyle: { color: t.muted, type: 'dashed' },
                  label: { formatter: 'ATM', color: t.muted },
                }
              : undefined,
          },
          { name: 'Put Premium', type: 'bar', data: put, itemStyle: { color: t.bear } },
        ],
      };
    },
    [rows, spot, showLtp, chain],
  );

  return (
    <div>
      <PageHeader title="Options Premium" subtitle="Grouped Call/Put premium bars per strike around ATM — extrinsic value or raw LTP" />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry showInterval={false} />
        <Select
          ariaLabel="Show strikes"
          value={win}
          options={[...WINDOWS]}
          onChange={(v) => setWin(v as Window)}
        />
        <label className="flex items-center gap-1 text-sm text-ay-text">
          <input type="checkbox" checked={showLtp} onChange={(e) => setShowLtp(e.target.checked)} />
          Show LTP
        </label>
        <GoButton onClick={() => q.refetch()} loading={q.isFetching} />
      </div>

      <p className="mb-3 flex flex-wrap items-center gap-2 text-sm text-ay-muted">
        {spot && <Metric label="Spot" value={formatDecimal(spot, 2)} />}
        {chain?.atmStrike && <Metric label="ATM" value={chain.atmStrike} />}
        {chain?.atmStraddle && <Metric label="ATM straddle" value={formatDecimal(chain.atmStraddle, 2)} />}
        <span className="text-xs">
          · bars = {showLtp ? 'raw LTP' : 'extrinsic value (LTP − intrinsic)'} · Call green / Put red
        </span>
      </p>

      <QueryState
        query={q}
        isEmpty={() => chain == null}
        empty={{
          icon: BarChart3,
          title: 'No premium data — pick an underlying + expiry with a captured snapshot.',
        }}
        errorTitle="Couldn't load options premium"
        skeleton={<Skeleton variant="chart-block" height={440} />}
      >
        {() => (
          <div className="card shadow-e1">
            <div className="mb-2 flex items-center justify-between">
              <h2 className="text-h3 text-ay-text">Premium per strike</h2>
              <div className="flex items-center gap-3 text-caption text-ay-muted">
                <span className="inline-flex items-center gap-1">
                  <span aria-hidden="true" className="inline-block size-2.5 rounded-sm bg-bull" />
                  Call
                </span>
                <span className="inline-flex items-center gap-1">
                  <span aria-hidden="true" className="inline-block size-2.5 rounded-sm bg-bear" />
                  Put
                </span>
              </div>
            </div>
            <EChart
              makeOption={makeOption}
              height={440}
              ariaLabel="Call versus Put premium bars per strike around ATM"
            />
          </div>
        )}
      </QueryState>
    </div>
  );
}
