import { useEffect, useMemo, useState } from 'react';
import { cn } from '../../lib/cn.ts';
import { nearestStrike } from '../../lib/strikes.ts';
import { useChainTable, useMultiLegOiChart } from '../../api/oiAnalytics.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { Select, type SelectOption } from '../../components/atoms/Select.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { LegMultiSelect } from '../../components/atoms/LegMultiSelect.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { MultiLegOiChart } from '../../components/MultiLegOiChart.tsx';
import { legHsl, legSwatchClass } from '../../core/legColors.ts';

// Multiple OI Chart (§options/multiple-oi-chart). Multi-select option legs ("57200 CE") → overlay each
// leg's OI line (right axis) + the underlying price (left, dotted). Strike list + ATM default come from
// the live /chain-table. Money = decimal strings; only chart coords cross to numbers (core/multiLegOiSeries).

const INTERVAL_OPTIONS: SelectOption[] = [1, 3, 5, 10, 15, 30, 60].map((m) => ({
  value: String(m),
  label: `${m} min`,
}));

export function MultipleOiChartPage() {
  const chainQ = useChainTable();
  const strikes = useMemo(() => chainQ.data?.rows.map((r) => r.strike) ?? [], [chainQ.data]);
  const spot = chainQ.data?.spot ?? null;
  const atm = useMemo(() => nearestStrike(strikes, spot), [strikes, spot]);
  const options = useMemo(() => strikes.flatMap((s) => [`${s} CE`, `${s} PE`]), [strikes]);

  const [intervalMin, setIntervalMin] = useState(3);
  const [selected, setSelected] = useState<string[]>([]);

  // Default to ATM CE + ATM PE once the chain loads (only while nothing is picked).
  useEffect(() => {
    if (atm && selected.length === 0) setSelected([`${atm} CE`, `${atm} PE`]);
  }, [atm, selected.length]);

  const toggle = (leg: string) =>
    setSelected((cur) => (cur.includes(leg) ? cur.filter((l) => l !== leg) : [...cur, leg]));

  const q = useMultiLegOiChart(selected, intervalMin);
  const data = q.data ?? null;

  return (
    <div>
      <PageHeader title="Multiple OI chart" />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry showInterval={false} />
        <Select
          ariaLabel="Time interval"
          value={String(intervalMin)}
          options={INTERVAL_OPTIONS}
          onChange={(v) => setIntervalMin(parseInt(v, 10) || 3)}
        />
        <LegMultiSelect options={options} selected={selected} onToggle={toggle} />
        <GoButton onClick={() => q.refetch()} loading={q.isFetching} />
      </div>

      {/* Selected-leg chips with colour swatches matching the chart lines. */}
      {selected.length > 0 && (
        <div className="mb-3 flex flex-wrap items-center gap-1.5">
          {selected.map((leg, i) => {
            const swatch = legSwatchClass(i);
            return (
              <span
                key={leg}
                className="inline-flex items-center gap-1 rounded border border-ay-border bg-surface-1 px-2 py-0.5 text-xs text-ay-text"
              >
                <span
                  aria-hidden="true"
                  className={cn('h-2.5 w-2.5 rounded-sm', swatch ?? '')}
                  style={swatch ? undefined : { backgroundColor: legHsl(i) }}
                />
                {leg}
                <button
                  type="button"
                  aria-label={`Remove ${leg}`}
                  onClick={() => toggle(leg)}
                  className="text-ay-muted hover:text-bear"
                >
                  ×
                </button>
              </span>
            );
          })}
        </div>
      )}

      <section className="card shadow-e1">
        <h2 className="mb-2 text-h3 text-ay-text">Individual OI</h2>

        {selected.length === 0 ? (
          <p className="text-sm text-ay-muted">Select ≥1 strike to plot its OI.</p>
        ) : data == null && !q.isLoading ? (
          <p className="text-sm text-ay-muted">No data for the selected legs/session.</p>
        ) : (
          <MultiLegOiChart data={data} />
        )}
      </section>
    </div>
  );
}
