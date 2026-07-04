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
  useMinerviniCandidate,
  useDeepDailyCandles,
  type MinerviniCandidate,
} from '../../api/minervini.ts';

// /equity/minervini/:symbol (MV-4.4) — the per-candidate analyzer. A daily chart with 50/150/200-day
// MA overlays + volume + the VCP pivot line, and three tabs of the persisted reasoning: the 8
// Trend-Template gates, the VCP base geometry, and the low-cap fundamentals. Reached by clicking a
// symbol on the screener / funnel. Works for any symbol (geometry computed on demand for non-passers).

const GATE_TITLES = [
  'Price > 150- & 200-day MA',
  '150-day MA > 200-day MA',
  '200-day MA rising ≥ 1 month',
  '50-day MA > 150- & 200-day MA',
  'Price > 50-day MA',
  'Price ≥ 25% above 52-week low',
  'Price within 25% of 52-week high',
  'RS-rank ≥ 70',
];
const STAGE_LABEL: Record<number, string> = {
  1: 'Stage 1 · neglect',
  2: 'Stage 2 · advance',
  3: 'Stage 3 · top',
  4: 'Stage 4 · decline',
};

type Tab = 'trend' | 'geometry' | 'fundamentals';

export function MinerviniCandidatePage() {
  const { symbol = '' } = useParams();
  const sym = symbol.toUpperCase();
  const [tab, setTab] = useState<Tab>('trend');
  const candidate = useMinerviniCandidate(sym);
  const candles = useDeepDailyCandles(sym);

  return (
    <LoadBeat>
      <PageHeader
        title={sym}
        subtitle="Minervini candidate analyzer — Trend Template, VCP base geometry, fundamentals"
        help="Per-candidate SEPA analysis. The chart overlays the 50/150/200-day moving averages (the Trend-Template structure) and marks the VCP breakout pivot. The tabs show the persisted 8-gate reasoning, the base geometry (contractions / pivot / footprint), and the low-cap fundamentals. Reached from the screener or funnel; works for any symbol."
      />
      <Link to="/equity/minervini" className="mb-3 inline-block text-sm text-accent hover:underline">
        ← Back to screener
      </Link>

      <QueryState
        query={candidate}
        empty={{ title: 'No analysis for this symbol.' }}
        errorTitle="Couldn't load the candidate"
        skeleton={<Skeleton variant="table-rows" rows={8} cols={2} />}
      >
        {(c) => (
          <div className="flex flex-col gap-3">
            <SummaryStrip c={c} />

            <BeatBlock className="rounded-lg border border-ay-border p-2">
              <QueryState
                query={candles}
                empty={{ title: 'No daily history for this symbol.' }}
                errorTitle="Couldn't load candles"
                skeleton={<Skeleton variant="chart-block" />}
              >
                {(bars) =>
                  bars.length === 0 ? (
                    <div className="py-16 text-center text-sm text-ay-muted">No daily history.</div>
                  ) : (
                    <MinerviniCandidateChart
                      bars={bars}
                      pivot={c.geometry.isVcp && c.geometry.pivot ? Number(c.geometry.pivot) : null}
                      height={360}
                      ariaLabel={`${sym} daily chart with 50/150/200-day moving averages`}
                    />
                  )
                }
              </QueryState>
            </BeatBlock>

            <div role="tablist" aria-label="Analyzer sections" className="flex rounded-md border border-ay-border p-0.5">
              {(
                [
                  ['trend', 'Trend Template'],
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

            {tab === 'trend' && <TrendTab c={c} />}
            {tab === 'geometry' && <GeometryTab c={c} />}
            {tab === 'fundamentals' && <FundamentalsTab c={c} />}
          </div>
        )}
      </QueryState>
    </LoadBeat>
  );
}

function SummaryStrip({ c }: { c: MinerviniCandidate }) {
  return (
    <div className="flex flex-wrap items-center gap-x-6 gap-y-2 rounded-lg border border-ay-border bg-surface-1 px-3 py-2 text-sm">
      <Stat label="Close" value={c.close ? formatDecimal(c.close, 2) : '—'} />
      <Stat label="RS-rank" value={c.rsRank ? formatDecimal(c.rsRank, 0) : '—'} />
      <Stat label="Stage" value={c.stage ? (STAGE_LABEL[c.stage] ?? String(c.stage)) : '—'} />
      <Stat label="Gates" value={`${c.gatesPassed}/8`} />
      <span
        className={cn(
          'rounded px-2 py-0.5 text-xs font-semibold',
          c.passesAll ? 'bg-bull/20 text-bull' : 'bg-surface-2 text-ay-muted',
        )}
      >
        {c.passesAll ? 'PASSES ALL 8' : 'Not a full pass'}
      </span>
      <span
        className={cn(
          'rounded px-2 py-0.5 text-xs font-semibold',
          c.geometry.isVcp ? 'bg-accent/20 text-accent' : 'bg-surface-2 text-ay-muted',
        )}
      >
        {c.geometry.isVcp ? `VCP ${c.geometry.footprint ?? ''}` : 'No valid VCP'}
      </span>
      {!c.scanned && (
        <span className="text-xs text-ay-muted" title="Not in the persisted daily screen — analyzed on demand.">
          on-demand
        </span>
      )}
    </div>
  );
}

function TrendTab({ c }: { c: MinerviniCandidate }) {
  return (
    <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
      <BeatBlock className="rounded-lg border border-ay-border">
        <h3 className="border-b border-ay-border px-3 py-2 text-sm font-semibold text-ay-text">
          8 Trend-Template gates
        </h3>
        <ul className="divide-y divide-ay-border">
          {c.gates.map((g, i) => (
            <li key={i} className="flex items-center gap-2 px-3 py-1.5 text-sm">
              <span
                className={cn(
                  'inline-flex h-5 w-5 items-center justify-center rounded text-[11px] font-bold',
                  g ? 'bg-bull/20 text-bull' : 'bg-bear/20 text-bear',
                )}
                aria-label={g ? 'pass' : 'fail'}
              >
                {g ? '✓' : '✕'}
              </span>
              <span className="text-ay-text">{GATE_TITLES[i]}</span>
            </li>
          ))}
        </ul>
      </BeatBlock>
      <BeatBlock className="rounded-lg border border-ay-border">
        <h3 className="border-b border-ay-border px-3 py-2 text-sm font-semibold text-ay-text">Structure</h3>
        <dl className="grid grid-cols-2 gap-x-4 gap-y-1.5 px-3 py-2 text-sm">
          <Row k="50-day MA" v={c.sma50 ? formatDecimal(c.sma50, 2) : '—'} />
          <Row k="150-day MA" v={c.sma150 ? formatDecimal(c.sma150, 2) : '—'} />
          <Row k="200-day MA" v={c.sma200 ? formatDecimal(c.sma200, 2) : '—'} />
          <Row k="52-week high" v={c.high52w ? formatDecimal(c.high52w, 2) : '—'} />
          <Row k="52-week low" v={c.low52w ? formatDecimal(c.low52w, 2) : '—'} />
          <Row k="% from high" v={pct(c.pctFromHigh)} />
          <Row k="% above low" v={pct(c.pctAboveLow)} />
          <Row k="Avg 50d turnover" v={c.avgTurnover50 ? formatDecimal(c.avgTurnover50, 0) : '—'} />
        </dl>
      </BeatBlock>
    </div>
  );
}

function GeometryTab({ c }: { c: MinerviniCandidate }) {
  const g = c.geometry;
  if (!g.isVcp) {
    return (
      <BeatBlock className="rounded-lg border border-ay-border px-3 py-6 text-center text-sm text-ay-muted">
        No valid VCP base detected{g.rejectReason ? ` — ${g.rejectReason}` : ''}.
        <div className="mt-1 text-xs">
          Volume dry-up: {g.volumeDryUp ? 'yes' : 'no'} · Shakeout: {g.shakeout ? 'yes' : 'no'}
        </div>
      </BeatBlock>
    );
  }
  return (
    <BeatBlock className="rounded-lg border border-ay-border">
      <h3 className="border-b border-ay-border px-3 py-2 text-sm font-semibold text-ay-text">
        VCP base — <span className="text-accent">{g.footprint}</span>
      </h3>
      <dl className="grid grid-cols-2 gap-x-4 gap-y-1.5 px-3 py-2 text-sm md:grid-cols-3">
        <Row k="Buy pivot" v={g.pivot ? formatDecimal(g.pivot, 2) : '—'} />
        <Row k="Contractions" v={g.contractionCount != null ? String(g.contractionCount) : '—'} />
        <Row k="Base length" v={g.baseWeeks != null ? `${g.baseWeeks}w` : '—'} />
        <Row k="Deepest pullback" v={g.deepestPct ? `${formatDecimal(g.deepestPct, 0)}%` : '—'} />
        <Row k="Tightest (final)" v={g.tightestPct ? `${formatDecimal(g.tightestPct, 0)}%` : '—'} />
        <Row k="Base count" v={g.baseCount != null ? String(g.baseCount) : '—'} />
        <Row k="Volume dry-up" v={g.volumeDryUp ? 'yes' : 'no'} />
        <Row k="Shakeout" v={g.shakeout ? 'yes' : 'no'} />
        <Row k="Base days" v={g.baseDurationDays != null ? String(g.baseDurationDays) : '—'} />
      </dl>
      <p className="border-t border-ay-border px-3 py-2 text-xs text-ay-muted">
        Footprint <span className="font-mono">{g.footprint}</span> = base weeks · deepest%/tightest% ·
        contraction count. Buy on a breakout above the pivot on expanding volume; don't chase &gt;~5% extended.
      </p>
    </BeatBlock>
  );
}

function FundamentalsTab({ c }: { c: MinerviniCandidate }) {
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
        The low-cap universe gate keeps free-float mcap &lt; ₹5,000 cr and free-float &lt; 35%.
        Earnings-surprise and analyst estimate-revisions are unmodeled (no free Indian consensus feed) —
        fundamentals implement the Code-33 spirit (EPS + sales + margin acceleration) only. Fundamentals
        are watchlist confirmation, never used in a backtest.
      </p>
    </BeatBlock>
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
