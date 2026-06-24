import { useCallback, useMemo, useState } from 'react';
import type { EChartsOption } from 'echarts';
import { useChainTable } from '../../api/oiAnalytics.ts';
import { useSymbolContext } from '../../stores/symbolContext.store.ts';
import type { ChainLeg } from '../../api/types.ts';
import {
  buildPayoff,
  type OptionType,
  type PayoffLeg,
  type Side,
} from '../../core/payoffEngine.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { Metric } from '../../components/atoms/Metric.tsx';
import { EChart, type ChartTheme } from '../../components/atoms/EChart.tsx';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';

// Strategy Builder (greeks/payoff pipeline): assemble legs from the live chain, see the expiry payoff
// curve + net greeks + max-profit/loss/breakevens. Pure client-side math (payoffEngine) over the
// premiums + per-share greeks the chain already serves; lot size from a per-underlying default.

const LOT_SIZES: Record<string, number> = {
  'NIFTY 50': 75,
  'NIFTY BANK': 35,
  'NIFTY FIN SERVICE': 65,
  'NIFTY MID SELECT': 120,
  SENSEX: 20,
  BANKEX: 30,
};

const num = (s: string | null): number | null => (s == null ? null : Number(s));
const inr = (v: number): string => `₹${v.toLocaleString('en-IN', { maximumFractionDigits: 0 })}`;

interface LegRow extends PayoffLeg {
  id: number;
}

export function StrategyBuilderPage() {
  const name = useSymbolContext((s) => s.name);
  const chainQ = useChainTable();
  const chain = chainQ.data ?? null;

  const [legs, setLegs] = useState<LegRow[]>([]);
  const [form, setForm] = useState<{ strike: string; type: OptionType; side: Side; lots: number }>({
    strike: '',
    type: 'CE',
    side: 'BUY',
    lots: 1,
  });
  const [lotSize, setLotSize] = useState<number>(LOT_SIZES[name] ?? 1);

  // strike → { CE, PE } legs from the chain (premium + greeks).
  const byStrike = useMemo(() => {
    const map = new Map<string, { CE: ChainLeg | null; PE: ChainLeg | null }>();
    for (const row of chain?.rows ?? []) {
      map.set(row.strike, { CE: row.ce?.leg ?? null, PE: row.pe?.leg ?? null });
    }
    return map;
  }, [chain]);

  const strikes = useMemo(() => (chain?.rows ?? []).map((r) => r.strike), [chain]);
  const spot = chain?.spot ? Number(chain.spot) : null;

  const addLeg = useCallback(() => {
    const cell = byStrike.get(form.strike);
    const leg = form.type === 'CE' ? cell?.CE : cell?.PE;
    const premium = num(leg?.ltp ?? null);
    if (premium == null) return; // no priced premium for this leg
    setLegs((prev) => [
      ...prev,
      {
        id: prev.reduce((m, l) => Math.max(m, l.id), 0) + 1,
        strike: Number(form.strike),
        type: form.type,
        side: form.side,
        lots: Math.max(1, Math.trunc(form.lots)),
        premium,
        delta: num(leg?.delta ?? null),
        gamma: num(leg?.gamma ?? null),
        theta: num(leg?.theta ?? null),
        vega: num(leg?.vega ?? null),
        rho: num(leg?.rho ?? null),
      },
    ]);
  }, [byStrike, form]);

  const removeLeg = (id: number) => setLegs((prev) => prev.filter((l) => l.id !== id));

  const payoff = useMemo(() => {
    if (!legs.length || spot == null) return null;
    const min = Math.max(0, spot * 0.85);
    const max = spot * 1.15;
    return buildPayoff(legs, lotSize, { min, max, step: (max - min) / 100 });
  }, [legs, lotSize, spot]);

  const makeOption = useCallback(
    (t: ChartTheme): EChartsOption => ({
      aria: { enabled: true },
      textStyle: { color: t.text },
      grid: { left: 72, right: 16, top: 16, bottom: 40 },
      tooltip: {
        trigger: 'axis',
        backgroundColor: t.surface1,
        borderColor: t.border,
        textStyle: { color: t.text },
      },
      xAxis: {
        type: 'category',
        data: (payoff?.points ?? []).map((p) => p.spot),
        axisLine: { lineStyle: { color: t.border } },
        axisLabel: { color: t.muted },
        name: 'Underlying',
      },
      yAxis: {
        type: 'value',
        name: 'P&L ₹',
        scale: true,
        splitLine: { lineStyle: { color: t.grid } },
        axisLabel: { color: t.muted },
      },
      series: [
        {
          type: 'line',
          showSymbol: false,
          data: (payoff?.points ?? []).map((p) => p.pnl),
          lineStyle: { color: t.accent },
          areaStyle: { color: t.accent, opacity: 0.08 },
          markLine: {
            silent: true,
            symbol: 'none',
            data: [{ yAxis: 0, lineStyle: { color: t.muted } }],
            label: { show: false },
          },
        },
      ],
    }),
    [payoff],
  );

  const legColumns: DataColumn<LegRow>[] = [
    { id: 'side', header: 'Side', align: 'left', render: (l) => l.side, mobileLabel: 'Side' },
    { id: 'lots', header: 'Lots', render: (l) => String(l.lots), mobileLabel: 'Lots' },
    { id: 'strike', header: 'Strike', render: (l) => `${l.strike} ${l.type}`, mobileLabel: 'Strike' },
    { id: 'premium', header: 'Premium', render: (l) => l.premium.toFixed(2), mobileLabel: 'Premium' },
    {
      id: 'remove',
      header: '',
      render: (l) => (
        <button
          type="button"
          onClick={() => removeLeg(l.id)}
          className="rounded border border-ay-border px-2 py-0.5 text-xs text-ay-muted hover:text-ay-bear"
          aria-label={`Remove ${l.side} ${l.lots} ${l.strike} ${l.type}`}
        >
          ✕
        </button>
      ),
    },
  ];

  const g = payoff?.netGreeks;

  return (
    <div>
      <PageHeader
        title="Strategy Builder"
        subtitle="Assemble legs from the live chain — expiry payoff, net greeks and breakevens"
      />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry />
        <GoButton onClick={() => void chainQ.refetch()} loading={chainQ.isFetching} />
      </div>

      <div className="mb-4 flex flex-wrap items-end gap-2 rounded-md border border-ay-border bg-surface-1 p-2">
        <label className="flex flex-col text-xs text-ay-muted">
          Strike
          <select
            value={form.strike}
            onChange={(e) => setForm((f) => ({ ...f, strike: e.target.value }))}
            className="h-9 rounded-md border border-ay-border bg-surface-2 px-2 text-sm text-ay-text"
          >
            <option value="">—</option>
            {strikes.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col text-xs text-ay-muted">
          Type
          <select
            value={form.type}
            onChange={(e) => setForm((f) => ({ ...f, type: e.target.value as OptionType }))}
            className="h-9 rounded-md border border-ay-border bg-surface-2 px-2 text-sm text-ay-text"
          >
            <option value="CE">CE</option>
            <option value="PE">PE</option>
          </select>
        </label>
        <label className="flex flex-col text-xs text-ay-muted">
          Side
          <select
            value={form.side}
            onChange={(e) => setForm((f) => ({ ...f, side: e.target.value as Side }))}
            className="h-9 rounded-md border border-ay-border bg-surface-2 px-2 text-sm text-ay-text"
          >
            <option value="BUY">Buy</option>
            <option value="SELL">Sell</option>
          </select>
        </label>
        <label className="flex flex-col text-xs text-ay-muted">
          Lots
          <input
            type="number"
            min={1}
            value={form.lots}
            onChange={(e) => setForm((f) => ({ ...f, lots: Number(e.target.value) }))}
            className="h-9 w-16 rounded-md border border-ay-border bg-surface-2 px-2 text-sm text-ay-text"
          />
        </label>
        <label className="flex flex-col text-xs text-ay-muted">
          Lot size
          <input
            type="number"
            min={1}
            value={lotSize}
            onChange={(e) => setLotSize(Math.max(1, Number(e.target.value)))}
            className="h-9 w-20 rounded-md border border-ay-border bg-surface-2 px-2 text-sm text-ay-text"
          />
        </label>
        <button
          type="button"
          onClick={addLeg}
          disabled={!form.strike}
          className="h-9 rounded-md bg-accent px-3 text-sm font-semibold text-white disabled:opacity-40"
        >
          Add leg
        </button>
      </div>

      {payoff && g ? (
        <>
          <div className="mb-3 flex flex-wrap gap-3" aria-live="polite">
            <Metric label="Net Premium" value={`${payoff.netPremium <= 0 ? 'Dr ' : 'Cr '}${inr(Math.abs(payoff.netPremium))}`} />
            <Metric label="Max Profit" value={payoff.unboundedProfit ? 'Unlimited' : inr(payoff.maxProfit)} />
            <Metric label="Max Loss" value={payoff.unboundedLoss ? 'Unlimited' : inr(payoff.maxLoss)} />
            <Metric label="Breakeven" value={payoff.breakevens.length ? payoff.breakevens.join(' · ') : '—'} />
            <Metric label="Δ / Θ" value={`${g.delta.toFixed(1)} / ${g.theta.toFixed(0)}`} />
            <Metric label="Vega" value={g.vega.toFixed(0)} />
          </div>
          <div className="card shadow-e1 mb-4">
            <EChart makeOption={makeOption} height={320} ariaLabel="Strategy payoff at expiry vs underlying" />
          </div>
        </>
      ) : (
        <p className="mb-4 text-sm text-ay-muted">Add legs from the chain to see the payoff.</p>
      )}

      {legs.length > 0 && (
        <DataTable
          columns={legColumns}
          rows={legs}
          rowKey={(l) => String(l.id)}
          ariaLabel="Strategy legs"
          emptyMessage="No legs."
        />
      )}
    </div>
  );
}
