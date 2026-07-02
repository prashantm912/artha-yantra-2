import { useEffect, useMemo, useState } from 'react';
import { PageHeader } from '../../components/PageHeader.tsx';
import { FilterBar } from '../../components/FilterBar.tsx';
import { Select } from '../../components/atoms/Select.tsx';
import { LoadBeat, BeatBlock } from '../../components/LoadBeat.tsx';
import { cn } from '../../lib/cn.ts';
import { nearestStrike } from '../../lib/strikes.ts';
import { useChainTable } from '../../api/oiAnalytics.ts';
import { computeRisk, type RiskInput } from '../../core/riskCalculator.ts';

// Risk Calculator (§features/risk-calculator — oipulse "Risk Calculator"). A pure, client-side
// position-sizing utility: enter the account, the per-trade risk %, the entry + protective stop and a
// reward:risk multiple, and it sizes the position so the loss at the stop equals the risk budget, then
// derives the reward-target, the 1%-of-capital profit-lock price and the deployment. The maths stay
// local (core/riskCalculator); the optional option picker (audit §10.2-12 parity) reads the live
// chain-table to autofill the entry with a leg's LTP. Long vs short is inferred from entry-vs-stop.

const numberCls = 'h-9 w-full rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text';

const inr = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 2 });
const num = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 2 });

function money(v: number): string {
  return `₹${inr.format(Math.round(v))}`;
}

function Field({
  label,
  value,
  onChange,
  min,
  step,
  help,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  min?: number;
  step?: number;
  help: string;
}) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs text-ay-muted">{label}</span>
      <input
        type="number"
        inputMode="decimal"
        min={min}
        step={step}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-label={label}
        title={help}
        className={numberCls}
      />
    </label>
  );
}

function Stat({
  label,
  value,
  tone,
  help,
}: {
  label: string;
  value: string;
  tone?: 'bull' | 'bear' | 'muted';
  help: string;
}) {
  return (
    <div
      className="flex flex-col gap-1 rounded-md border border-ay-border bg-surface-1 p-3"
      title={help}
    >
      <span className="text-xs uppercase tracking-wide text-ay-muted">{label}</span>
      <span
        className={cn(
          'text-lg font-semibold tabular-nums',
          tone === 'bull' && 'text-bull',
          tone === 'bear' && 'text-bear',
          tone === 'muted' && 'text-ay-muted',
          !tone && 'text-ay-text',
        )}
      >
        {value}
      </span>
    </div>
  );
}

export function RiskCalculatorPage() {
  const [capital, setCapital] = useState('200000');
  const [riskPct, setRiskPct] = useState('1');
  const [entry, setEntry] = useState('100');
  const [stop, setStop] = useState('90');
  const [lotSize, setLotSize] = useState('1');
  const [rewardRisk, setRewardRisk] = useState('2');

  // Option picker (oipulse parity): pick side + strike off the live chain, "Use LTP" fills the entry.
  const chainQ = useChainTable();
  const rows = useMemo(() => chainQ.data?.rows ?? [], [chainQ.data]);
  const strikes = useMemo(() => rows.map((r) => r.strike), [rows]);
  const atm = useMemo(() => nearestStrike(strikes, chainQ.data?.spot ?? null), [strikes, chainQ.data]);
  const [side, setSide] = useState<'CE' | 'PE'>('CE');
  const [strike, setStrike] = useState<string | null>(null);
  useEffect(() => {
    if (atm && (strike == null || !strikes.includes(strike))) setStrike(atm);
  }, [atm, strike, strikes]);
  const pickedRow = rows.find((r) => r.strike === strike) ?? null;
  const pickedLeg = (side === 'CE' ? pickedRow?.ce : pickedRow?.pe)?.leg ?? null;
  const pickedLtp = pickedLeg?.ltp ?? null;

  const input: RiskInput = {
    capital: Number(capital) || 0,
    riskPct: Number(riskPct) || 0,
    entry: Number(entry) || 0,
    stop: Number(stop) || 0,
    lotSize: Number(lotSize) || 1,
    rewardRisk: Number(rewardRisk) || 0,
  };

  const r = useMemo(
    () => computeRisk(input),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [capital, riskPct, entry, stop, lotSize, rewardRisk],
  );

  return (
    <LoadBeat>
      <PageHeader
        title="Risk Calculator"
        subtitle="Position sizing from your account, per-trade risk and the entry/stop — plus the reward-target and 1% lock"
        help="Sizes a position so the loss at your stop equals the chosen percent of capital. Enter the account, the risk per trade, the entry and protective stop, and a reward:risk multiple; long vs short is read from whether the stop sits below or above the entry. The option picker fills the entry from a live option LTP (lot size stays manual); the sizing maths are computed locally — nothing is sent or saved."
      />

      {/* Option picker — autofills the entry with the picked leg's live LTP (lot size stays manual). */}
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry showInterval={false} />
        <Select
          ariaLabel="Option side"
          title="Call or put leg to price from the live chain"
          value={side}
          options={['CE', 'PE']}
          onChange={(v) => setSide(v as 'CE' | 'PE')}
        />
        <Select
          ariaLabel="Option strike"
          title="The strike whose live LTP fills the entry (defaults to at-the-money)"
          value={strike}
          options={strikes}
          onChange={setStrike}
          placeholder="Strike"
          disabled={strikes.length === 0}
        />
        <button
          type="button"
          disabled={pickedLtp == null}
          onClick={() => pickedLtp != null && setEntry(pickedLtp)}
          title="Fill the entry price with this option's live last-traded price."
          className="h-9 rounded-md border border-ay-border px-3 text-sm text-ay-text hover:border-accent disabled:opacity-50"
        >
          Use LTP{pickedLtp != null ? ` (${num.format(Number(pickedLtp))})` : ''}
        </button>
        {pickedLeg && <span className="text-xs text-ay-muted">{pickedLeg.tradingsymbol}</span>}
      </div>

      <BeatBlock>
        <div className="grid gap-4 lg:grid-cols-2">
          {/* Inputs */}
          <div className="flex flex-col gap-3 rounded-md border border-ay-border bg-surface-0 p-4">
            <h2 className="text-sm font-semibold text-ay-text">Inputs</h2>
            <div className="grid grid-cols-2 gap-3">
              <Field
                label="Account capital (₹)"
                value={capital}
                onChange={setCapital}
                min={0}
                step={1000}
                help="The total trading capital your per-trade risk is a fraction of."
              />
              <Field
                label="Risk per trade (%)"
                value={riskPct}
                onChange={setRiskPct}
                min={0}
                step={0.1}
                help="How much of capital you accept losing if the stop is hit (1% is a common cap)."
              />
              <Field
                label="Entry price"
                value={entry}
                onChange={setEntry}
                min={0}
                step={0.05}
                help="Your planned entry price."
              />
              <Field
                label="Stop-loss price"
                value={stop}
                onChange={setStop}
                min={0}
                step={0.05}
                help="The protective stop. Below entry = long; above entry = short."
              />
              <Field
                label="Lot size"
                value={lotSize}
                onChange={setLotSize}
                min={1}
                step={1}
                help="Contract/lot size — 1 for cash equity, the F&O lot for options or futures. Size is floored to whole lots."
              />
              <Field
                label="Reward : risk"
                value={rewardRisk}
                onChange={setRewardRisk}
                min={0}
                step={0.5}
                help="The target reward-to-risk multiple — 2 means a target twice the stop distance away."
              />
            </div>
          </div>

          {/* Results */}
          <div className="flex flex-col gap-3 rounded-md border border-ay-border bg-surface-0 p-4">
            <div className="flex items-center justify-between">
              <h2 className="text-sm font-semibold text-ay-text">Position</h2>
              {r.side && (
                <span
                  className={cn(
                    'rounded px-2 py-0.5 text-xs font-semibold ring-1',
                    r.side === 'LONG' ? 'text-bull ring-bull/40' : 'text-bear ring-bear/40',
                  )}
                >
                  {r.side}
                </span>
              )}
            </div>

            {r.valid ? (
              <div className="grid grid-cols-2 gap-3">
                <Stat
                  label="Quantity"
                  value={`${num.format(r.units)} (${r.lots} lot${r.lots === 1 ? '' : 's'})`}
                  help="The number of units to trade, floored to whole lots so the realised risk never exceeds the budget."
                />
                <Stat
                  label="Risk amount"
                  value={money(r.riskAmount)}
                  tone="bear"
                  help="The rupee loss budget = capital × risk%. The position is sized so the stop loses about this much."
                />
                <Stat
                  label="Risk / unit"
                  value={`₹${num.format(r.perUnitRisk)}`}
                  help="The per-unit loss if the stop is hit = |entry − stop|."
                />
                <Stat
                  label="Loss at stop"
                  value={money(r.sizedRisk)}
                  tone="bear"
                  help="The actual loss on the sized (lot-floored) position if the stop is hit — at or below the risk budget."
                />
                <Stat
                  label={`Target (${num.format(input.rewardRisk)}R)`}
                  value={`₹${num.format(r.target)}`}
                  tone="bull"
                  help="The reward-target price = entry ± per-unit risk × reward:risk (direction by side)."
                />
                <Stat
                  label="Profit at target"
                  value={money(r.rewardAmount)}
                  tone="bull"
                  help="The rupee profit on the sized position at the reward-target."
                />
                <Stat
                  label="1% lock price"
                  value={`₹${num.format(r.onePctTarget)}`}
                  help="The price at which the sized position has gained 1% of capital — the Siva intraday profit-lock level."
                />
                <Stat
                  label="Capital deployed"
                  value={`${money(r.capitalDeployed)} (${num.format(r.deploymentPct)}%)`}
                  tone="muted"
                  help="Capital tied up at entry = quantity × entry, and its share of the account."
                />
              </div>
            ) : (
              <p className="text-sm text-ay-muted">
                Enter a positive capital, risk % and entry, with a stop different from the entry, to size
                a position.
              </p>
            )}
          </div>
        </div>
      </BeatBlock>
    </LoadBeat>
  );
}
