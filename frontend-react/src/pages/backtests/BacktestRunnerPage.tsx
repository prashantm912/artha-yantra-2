import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { cn } from '../../lib/cn.ts';
import { Select } from '../../components/atoms/Select.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { useStrategies } from '../../api/strategies.ts';
import {
  DIRECTIONS,
  FOLD_AGGREGATIONS,
  INTERVALS,
  OBJECTIVE_METRICS,
  SWEEP_METHODS,
  useSubmitRun,
  useSubmitSweep,
} from '../../api/backtests.ts';

// /backtests/run (master plan §20 parity, E-8): the runner (full-parameter backtest) and the sweep
// launcher (method / max-trials / objective incl. fold aggregation / constraints) → 202 {jobId} → the
// jobs monitor. On the mock stack, windowed runs need benchmark history (see the guide).

const dayISO = (offsetDays = 0) => new Date(Date.now() - offsetDays * 86_400_000).toISOString().slice(0, 10);

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs text-ay-muted">{label}</span>
      {children}
    </label>
  );
}

const numberCls = 'h-9 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text';

export function BacktestRunnerPage() {
  const navigate = useNavigate();
  const strategies = useStrategies('', null);
  const submitRun = useSubmitRun();
  const submitSweep = useSubmitSweep();

  const [tab, setTab] = useState<'backtest' | 'sweep'>('backtest');
  const [strategyId, setStrategyId] = useState<string | null>(null);
  const [interval, setInterval] = useState('1d');
  const [from, setFrom] = useState(dayISO(60));
  const [to, setTo] = useState(dayISO(0));
  const [capital, setCapital] = useState('100000');
  const [seed, setSeed] = useState('42');
  // sweep-only
  const [method, setMethod] = useState('tpe');
  const [maxTrials, setMaxTrials] = useState('30');
  const [objMetric, setObjMetric] = useState('sharpe');
  const [direction, setDirection] = useState('maximize');
  const [foldAgg, setFoldAgg] = useState('mean');
  const [minTrades, setMinTrades] = useState('30');

  const strategyOptions = useMemo(
    () => (strategies.data?.items ?? []).map((s) => ({ value: s.id, label: s.name })),
    [strategies.data],
  );

  const iso = (d: string) => new Date(`${d}T00:00:00`).toISOString();

  const runBacktest = () => {
    if (!strategyId) return;
    submitRun.mutate(
      { strategyId, from: iso(from), to: iso(to), interval, initialCapital: capital, seed: Number(seed) },
      { onSuccess: () => navigate('/backtests/jobs') },
    );
  };

  const launchSweep = () => {
    if (!strategyId) return;
    submitSweep.mutate(
      {
        strategyId,
        from: iso(from),
        to: iso(to),
        interval,
        initialCapital: capital,
        method,
        maxTrials: Number(maxTrials),
        objective: { metric: objMetric, direction, fold_aggregation: foldAgg },
        constraints: { min_trades: Number(minTrades) },
        seed: Number(seed),
      },
      { onSuccess: () => navigate('/backtests/jobs') },
    );
  };

  return (
    <div>
      <PageHeader title="Backtest runner" subtitle="Run a full-parameter backtest or launch a parameter sweep" />
      <div role="tablist" className="mb-4 flex gap-1 border-b border-ay-border">
        {(['backtest', 'sweep'] as const).map((t) => (
          <button
            key={t}
            type="button"
            role="tab"
            aria-selected={tab === t}
            onClick={() => setTab(t)}
            className={cn(
              '-mb-px border-b-2 px-4 py-2 text-sm font-medium capitalize',
              tab === t ? 'border-accent text-accent' : 'border-transparent text-ay-muted hover:text-ay-text',
            )}
          >
            {t}
          </button>
        ))}
      </div>

      <div className="grid max-w-2xl grid-cols-1 gap-3 sm:grid-cols-2">
        <Field label="Strategy">
          <Select
            value={strategyId}
            options={strategyOptions}
            onChange={setStrategyId}
            ariaLabel="Strategy"
            placeholder="Select strategy"
          />
        </Field>
        <Field label="Interval">
          <Select value={interval} options={[...INTERVALS]} onChange={setInterval} ariaLabel="Interval" />
        </Field>
        <Field label="From">
          <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} aria-label="From" className={numberCls} />
        </Field>
        <Field label="To">
          <input type="date" value={to} onChange={(e) => setTo(e.target.value)} aria-label="To" className={numberCls} />
        </Field>
        <Field label="Initial capital (₹)">
          <input type="number" min={1000} value={capital} onChange={(e) => setCapital(e.target.value)} aria-label="Initial capital" className={numberCls} />
        </Field>
        <Field label="Seed">
          <input type="number" value={seed} onChange={(e) => setSeed(e.target.value)} aria-label="Seed" className={numberCls} />
        </Field>

        {tab === 'sweep' && (
          <>
            <Field label="Method">
              <Select value={method} options={[...SWEEP_METHODS]} onChange={setMethod} ariaLabel="Method" />
            </Field>
            <Field label="Max trials">
              <input type="number" min={2} value={maxTrials} onChange={(e) => setMaxTrials(e.target.value)} aria-label="Max trials" className={numberCls} />
            </Field>
            <Field label="Objective metric">
              <Select value={objMetric} options={[...OBJECTIVE_METRICS]} onChange={setObjMetric} ariaLabel="Objective metric" />
            </Field>
            <Field label="Direction">
              <Select value={direction} options={[...DIRECTIONS]} onChange={setDirection} ariaLabel="Direction" />
            </Field>
            <Field label="Fold aggregation">
              <Select value={foldAgg} options={[...FOLD_AGGREGATIONS]} onChange={setFoldAgg} ariaLabel="Fold aggregation" />
            </Field>
            <Field label="Min trades">
              <input type="number" min={1} value={minTrades} onChange={(e) => setMinTrades(e.target.value)} aria-label="Min trades" className={numberCls} />
            </Field>
          </>
        )}
      </div>

      <div className="mt-4">
        {tab === 'backtest' ? (
          <button
            type="button"
            onClick={runBacktest}
            disabled={!strategyId || submitRun.isPending}
            className="h-9 rounded-md bg-accent px-4 text-sm font-medium text-surface-0 hover:opacity-90 disabled:opacity-50"
          >
            ▶ Run backtest
          </button>
        ) : (
          <button
            type="button"
            onClick={launchSweep}
            disabled={!strategyId || submitSweep.isPending}
            className="h-9 rounded-md bg-accent px-4 text-sm font-medium text-surface-0 hover:opacity-90 disabled:opacity-50"
          >
            ⚙ Launch sweep
          </button>
        )}
      </div>
    </div>
  );
}
