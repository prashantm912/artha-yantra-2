import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { formatDecimal } from '../../lib/decimal.ts';
import { cn } from '../../lib/cn.ts';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { LoadBeat, BeatBlock } from '../../components/LoadBeat.tsx';
import { MinerviniCandidateChart } from '../../components/charts/MinerviniCandidateChart.tsx';
import {
  useManasCandidate,
  useDeepDailyCandles,
  type ManasCandidate,
  type ManasSetupView,
} from '../../api/manasArora.ts';

// /equity/manas-arora/:symbol — the per-candidate analyzer. A daily chart with 50/150/200-day MA
// overlays + volume + the base pivot line, and three tabs of the persisted reasoning: the 6 selection
// gates + universe/liquidity flags, the vcp + breakout base geometry (both setups), and the low-cap
// fundamentals. Reached by clicking a symbol on the screener / funnel. Works for any symbol (geometry
// computed on demand for non-passers). Mirrors MinerviniCandidatePage.

const GATE_TITLES = [
  'Price ≥ ₹30',
  '200-day MA rising ≥ 3 months',
  '50-day MA > 200-day MA',
  'Price > 200-day MA',
  'Price ≥ 100% above 52-week low',
  'New 52-week high made recently',
];

type Tab = 'selection' | 'geometry' | 'fundamentals';

/** The best valid setup: the one whose pivot is nearest at/above the close, else any valid setup. */
function bestSetup(c: ManasCandidate): ManasSetupView | null {
  const valid = c.setups.filter((s) => s.valid && s.pivot != null);
  if (valid.length === 0) return null;
  const close = c.close != null ? Number(c.close) : null;
  if (close == null) return valid[0];
  return valid
    .slice()
    .sort((a, b) => {
      const pa = Number(a.pivot);
      const pb = Number(b.pivot);
      const aboveA = pa >= close ? 0 : 1;
      const aboveB = pb >= close ? 0 : 1;
      if (aboveA !== aboveB) return aboveA - aboveB;
      return Math.abs(pa - close) - Math.abs(pb - close);
    })[0];
}

export function ManasAroraCandidatePage() {
  const { symbol = '' } = useParams();
  const sym = symbol.toUpperCase();
  const [tab, setTab] = useState<Tab>('selection');
  const candidate = useManasCandidate(sym);
  const candles = useDeepDailyCandles(sym);

  return (
    <LoadBeat>
      <PageHeader
        title={sym}
        subtitle="Manas Arora candidate analyzer — selection gates, vcp + breakout base geometry, fundamentals"
        help="Per-candidate analysis. The chart overlays the 50/150/200-day moving averages and marks the base breakout pivot. The tabs show the persisted 6-gate selection reasoning (+ universe/liquidity flags), the base geometry for both setups (vcp + breakout), and the low-cap fundamentals. Reached from the screener or funnel; works for any symbol."
      />
      <Link to="/equity/manas-arora" className="mb-3 inline-block text-sm text-accent hover:underline">
        ← Back to screener
      </Link>

      <QueryState
        query={candidate}
        empty={{ title: 'No analysis for this symbol.' }}
        errorTitle="Couldn't load the candidate"
        skeleton={<Skeleton variant="table-rows" rows={8} cols={2} />}
      >
        {(c) => {
          const setup = bestSetup(c);
          return (
            <div className="flex flex-col gap-3">
              <SummaryStrip c={c} setup={setup} />

              <BeatBlock className="rounded-lg border border-ay-border p-2">
                <QueryState
                  query={candles}
                  empty={{ title: 'No daily history for this symbol.' }}
                  errorTitle="Couldn't load candles"
                  skeleton={<Skeleton variant="chart-block" />}
                >
                  {(bars) =>
                    bars.length === 0 ? (
                      <div className="py-16 text-center text-sm text-ay-muted">
                        No daily history.
                      </div>
                    ) : (
                      <MinerviniCandidateChart
                        bars={bars}
                        pivot={setup?.pivot != null ? Number(setup.pivot) : null}
                        height={360}
                        ariaLabel={`${sym} daily chart with 50/150/200-day moving averages`}
                      />
                    )
                  }
                </QueryState>
              </BeatBlock>

              <div
                role="tablist"
                aria-label="Analyzer sections"
                className="flex rounded-md border border-ay-border p-0.5"
              >
                {(
                  [
                    ['selection', 'Selection'],
                    ['geometry', 'Base geometry'],
                    ['fundamentals', 'Fundamentals'],
                  ] as [Tab, string][]
                ).map(([t, label]) => (
                  <button
                    key={t}
                    type="button"
                    role="tab"
                    aria-selected={tab === t}
                    onClick={() => setTab(t)}
                    className={cn(
                      'h-8 rounded px-3 text-sm',
                      tab === t ? 'bg-accent text-surface-0' : 'text-ay-text hover:bg-surface-2',
                    )}
                  >
                    {label}
                  </button>
                ))}
              </div>

              {tab === 'selection' && <SelectionTab c={c} />}
              {tab === 'geometry' && <GeometryTab c={c} />}
              {tab === 'fundamentals' && <FundamentalsTab c={c} />}
            </div>
          );
        }}
      </QueryState>
    </LoadBeat>
  );
}

function SummaryStrip({ c, setup }: { c: ManasCandidate; setup: ManasSetupView | null }) {
  return (
    <div className="flex flex-wrap items-center gap-x-6 gap-y-2 rounded-lg border border-ay-border bg-surface-1 px-3 py-2 text-sm">
      <Stat label="Close" value={c.close ? formatDecimal(c.close, 2) : '—'} />
      <Stat label="% from high" value={pct(c.withinHighPct)} />
      <Stat label="% above low" value={pct(c.aboveLowPct)} />
      <Stat label="Gates" value={`${c.gatesPassed}/6`} />
      <span
        className={cn(
          'rounded px-2 py-0.5 text-xs font-semibold',
          c.passesAll ? 'bg-bull/20 text-bull' : 'bg-surface-2 text-ay-muted',
        )}
      >
        {c.passesAll ? 'PASSES ALL' : 'Not a full pass'}
      </span>
      <span
        className={cn(
          'rounded px-2 py-0.5 text-xs font-semibold',
          setup ? 'bg-accent/20 text-accent' : 'bg-surface-2 text-ay-muted',
        )}
      >
        {setup ? `${setup.setupType.toUpperCase()} ${setup.footprint ?? ''}` : 'No valid base'}
      </span>
      {!c.scanned && (
        <span
          className="text-xs text-ay-muted"
          title="Not in the persisted daily screen — analyzed on demand."
        >
          on-demand
        </span>
      )}
    </div>
  );
}

function SelectionTab({ c }: { c: ManasCandidate }) {
  const flags: [string, boolean][] = [
    ['Within 25% of 52-week high (universe)', c.withinHigh],
    ['Above 50-day MA (soft)', c.aboveSma50],
    ['Liquid volume (20-day)', c.liquidVolume],
    ['Liquid depth (50-day)', c.liquidDepth],
    ['Low-cap float gate (§4.4)', c.lowCap],
  ];
  return (
    <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
      <BeatBlock className="rounded-lg border border-ay-border">
        <h3 className="border-b border-ay-border px-3 py-2 text-sm font-semibold text-ay-text">
          6 selection gates + flags
        </h3>
        <ul className="divide-y divide-ay-border">
          {c.gates.map((g, i) => (
            <li key={i} className="flex items-center gap-2 px-3 py-1.5 text-sm">
              <GateMark on={g} />
              <span className="text-ay-text">{GATE_TITLES[i]}</span>
            </li>
          ))}
          {flags.map(([label, on]) => (
            <li key={label} className="flex items-center gap-2 px-3 py-1.5 text-sm">
              <GateMark on={on} />
              <span className="text-ay-muted">{label}</span>
            </li>
          ))}
        </ul>
      </BeatBlock>
      <BeatBlock className="rounded-lg border border-ay-border">
        <h3 className="border-b border-ay-border px-3 py-2 text-sm font-semibold text-ay-text">
          Structure
        </h3>
        <dl className="grid grid-cols-2 gap-x-4 gap-y-1.5 px-3 py-2 text-sm">
          <Row k="50-day MA" v={c.sma50 ? formatDecimal(c.sma50, 2) : '—'} />
          <Row k="200-day MA" v={c.sma200 ? formatDecimal(c.sma200, 2) : '—'} />
          <Row k="52-week high" v={c.high52w ? formatDecimal(c.high52w, 2) : '—'} />
          <Row k="52-week low" v={c.low52w ? formatDecimal(c.low52w, 2) : '—'} />
          <Row k="% from high" v={pct(c.withinHighPct)} />
          <Row k="% above low" v={pct(c.aboveLowPct)} />
          <Row k="Avg vol 20d" v={c.avgVol20 ? formatDecimal(c.avgVol20, 0) : '—'} />
          <Row k="Avg vol 50d" v={c.avgVol50 ? formatDecimal(c.avgVol50, 0) : '—'} />
          <Row k="Turnover 50d" v={c.turnover50 ? formatDecimal(c.turnover50, 0) : '—'} />
        </dl>
      </BeatBlock>
    </div>
  );
}

function GeometryTab({ c }: { c: ManasCandidate }) {
  if (c.setups.length === 0) {
    return (
      <BeatBlock className="rounded-lg border border-ay-border px-3 py-6 text-center text-sm text-ay-muted">
        No base geometry computed for this symbol.
      </BeatBlock>
    );
  }
  return (
    <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
      {c.setups.map((s) => (
        <SetupCard key={s.setupType} s={s} />
      ))}
    </div>
  );
}

function SetupCard({ s }: { s: ManasSetupView }) {
  return (
    <BeatBlock className="rounded-lg border border-ay-border">
      <h3 className="flex items-center gap-2 border-b border-ay-border px-3 py-2 text-sm font-semibold text-ay-text">
        <span className="uppercase">{s.setupType}</span>
        {s.valid ? (
          <span className="rounded bg-bull/20 px-1.5 text-xs text-bull">valid</span>
        ) : (
          <span className="rounded bg-surface-2 px-1.5 text-xs text-ay-muted">no base</span>
        )}
        {s.footprint && <span className="text-accent">{s.footprint}</span>}
      </h3>
      {s.valid ? (
        <dl className="grid grid-cols-2 gap-x-4 gap-y-1.5 px-3 py-2 text-sm">
          <Row k="Buy pivot" v={s.pivot ? formatDecimal(s.pivot, 2) : '—'} />
          <Row k="Base length" v={s.baseWeeks != null ? `${s.baseWeeks}w` : '—'} />
        </dl>
      ) : (
        <p className="px-3 py-3 text-sm text-ay-muted">
          No valid base{s.rejectReason ? ` — ${s.rejectReason}` : ''}.
        </p>
      )}
    </BeatBlock>
  );
}

function FundamentalsTab({ c }: { c: ManasCandidate }) {
  return (
    <BeatBlock className="rounded-lg border border-ay-border">
      <h3 className="border-b border-ay-border px-3 py-2 text-sm font-semibold text-ay-text">
        Low-cap fundamentals
      </h3>
      <dl className="grid grid-cols-2 gap-x-4 gap-y-1.5 px-3 py-2 text-sm">
        <Row k="Free-float mcap (cr)" v={c.freeFloatMcapCr ? formatDecimal(c.freeFloatMcapCr, 0) : '—'} />
        <Row k="Free-float %" v={c.freeFloatPct ? `${formatDecimal(c.freeFloatPct, 1)}%` : '—'} />
      </dl>
      <p className="border-t border-ay-border px-3 py-2 text-xs text-ay-muted">
        The §4.4 low-cap gate keeps free-float mcap and free-float % under their caps. Free-float is
        watchlist confirmation (never used in a backtest); an unknown free-float passes the gate rather
        than being a silent drop.
      </p>
    </BeatBlock>
  );
}

function GateMark({ on }: { on: boolean }) {
  return (
    <span
      className={cn(
        'inline-flex h-5 w-5 items-center justify-center rounded text-[11px] font-bold',
        on ? 'bg-bull/20 text-bull' : 'bg-bear/20 text-bear',
      )}
      aria-label={on ? 'pass' : 'fail'}
    >
      {on ? '✓' : '✕'}
    </span>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <span className="flex items-baseline gap-1.5">
      <span className="text-xs uppercase text-ay-muted">{label}</span>
      <span className="font-semibold tabular-nums text-ay-text">{value}</span>
    </span>
  );
}

function Row({ k, v }: { k: string; v: string }) {
  return (
    <>
      <dt className="text-ay-muted">{k}</dt>
      <dd className="text-right tabular-nums text-ay-text">{v}</dd>
    </>
  );
}

function pct(v?: string | null): string {
  if (v == null) return '—';
  const n = Number(v) * 100;
  return Number.isFinite(n) ? `${n.toFixed(1)}%` : '—';
}
