import { useMemo, useState } from 'react';
import { formatDecimal, isNegative } from '../../lib/decimal.ts';
import { nearestStrike } from '../../lib/strikes.ts';
import { loadDensity, saveDensity, type Density } from '../../lib/density.ts';
import { useChainTable, useVix } from '../../api/oiAnalytics.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { ColumnSettings } from '../../components/ColumnSettings.tsx';
import { DensityToggle } from '../../components/DensityToggle.tsx';
import { OptionsChainTable } from '../../components/OptionsChainTable.tsx';
import { OPTIONAL_COLUMN_META } from '../../components/optionsChainColumns.ts';
import { PageHeader, LiveDot } from '../../components/PageHeader.tsx';
import { BeatStrip, BeatItem, BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';

// Options Chain — the faithful oipulse chain (§20.7): 18 visible cols off /chain-table (live black76
// greeks + interval deltas), side-coloured OI bars, OI-interpretation badges, ATM/ITM tints, max-cell
// highlights, LTP flash, per-strike PCR, a Go button + Column-Setting modal, and the live header strip.
// Money/IV stay decimal strings (never parseFloat). Header gaps (INDIA VIX, underlying DH/DL/DO,
// prev-PCR) are marked pending — they need endpoints the chain-table feed does not yet carry.
// Revamp Phase 5 (§4.2): visible H1 ("Options chain" — name preserved for e2e) + signature lockup,
// elevated two-line metric strip (surfaces the title= data) under the aria-live wrapper, and the one
// orchestrated load beat. The bespoke OptionsChainTable (incl. its role="region"/aria-label wrapper +
// CALL/STRIKE/PUT headers) stays byte-identical — every chain selector survives.

/** Whole calendar days from today (IST-agnostic display) to the ISO expiry date. */
function daysToExpiry(expiry: string | null): number | null {
  if (!expiry) return null;
  const exp = Date.parse(`${expiry}T00:00:00`);
  if (Number.isNaN(exp)) return null;
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return Math.max(0, Math.round((exp - today.getTime()) / 86_400_000));
}

/** "12.97 (+2.37%)" from the VIX quote; "—" until it loads. */
function vixLabel(ltp: string | null | undefined, changePct: string | null | undefined): string {
  if (!ltp) return '—';
  if (!changePct) return formatDecimal(ltp, 2);
  const sign = isNegative(changePct) ? '' : '+';
  return `${formatDecimal(ltp, 2)} (${sign}${formatDecimal(changePct, 2)}%)`;
}

// Two-line elevated metric card (§4.2.B): primary value over a muted secondary line that surfaces the
// data formerly hidden in title=. The aria-live wrapper announces value changes (kept on the strip).
function ChainMetric({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="card shadow-e1 min-w-[8rem]">
      <div className="text-caption uppercase tracking-wide text-ay-muted">{label}</div>
      <div className="text-h3 nums font-semibold leading-tight text-ay-text">{value}</div>
      {sub && <div className="mt-0.5 truncate text-caption text-ay-muted">{sub}</div>}
    </div>
  );
}

export function OptionsChainPage() {
  const chainQ = useChainTable();
  const vixQ = useVix();
  const [optional, setOptional] = useState<Record<string, boolean>>({});
  const [density, setDensity] = useState<Density>(() => loadDensity());

  const changeDensity = (d: Density) => {
    setDensity(d);
    saveDensity(d);
  };

  const chain = chainQ.data ?? null;
  const rows = useMemo(() => chain?.rows ?? [], [chain]);
  const atm = useMemo(
    () => nearestStrike(rows.map((r) => r.strike), chain?.spot ?? null),
    [rows, chain],
  );
  const dte = daysToExpiry(chain?.expiry ?? null);
  const optionalKeys = useMemo(
    () => OPTIONAL_COLUMN_META.filter((c) => optional[c.key]).map((c) => c.key),
    [optional],
  );

  const vixSub = vixQ.data?.dayHigh
    ? `DH ${formatDecimal(vixQ.data.dayHigh, 2)} · DL ${vixQ.data.dayLow ? formatDecimal(vixQ.data.dayLow, 2) : '—'} · DO ${vixQ.data.dayOpen ? formatDecimal(vixQ.data.dayOpen, 2) : '—'}`
    : 'day range pending';

  return (
    <LoadBeat>
      <PageHeader
        title="Options chain"
        help="The full call/put option chain for the chosen underlying and expiry — strikes run down the centre with calls on the left and puts on the right; heavy OI marks likely support (puts) and resistance (calls)."
        subtitle={
          chain
            ? `${chain.underlying} · ${chain.expiry ?? '—'} expiry · black-76 live greeks`
            : 'black-76 live greeks'
        }
        right={<LiveDot stale={!!chain?.stale} detail={chain?.asOf ? chain.asOf.slice(11, 19) : undefined} />}
      />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry />
        <GoButton onClick={() => chainQ.refetch()} loading={chainQ.isFetching} />
        <ColumnSettings
          columns={OPTIONAL_COLUMN_META}
          visible={optional}
          onToggle={(key) => setOptional((v) => ({ ...v, [key]: !v[key] }))}
        />
        <DensityToggle value={density} onChange={changeDensity} />
      </div>

      {/* Live header strip (§20.7.4). Max-pain/Sentiment intentionally NOT here — they belong to the
          separate OI Statistics / Active Strikes pages. aria-live announces value changes. */}
      <BeatStrip
        className="mb-3 grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-5"
        aria-live="polite"
      >
        <BeatItem>
          <ChainMetric label="INDIA VIX" value={vixLabel(vixQ.data?.ltp, vixQ.data?.changePct)} sub={vixSub} />
        </BeatItem>
        <BeatItem>
          <ChainMetric label="Total PCR" value={chain?.pcr ? formatDecimal(chain.pcr, 2) : '—'} sub="put/call OI ratio" />
        </BeatItem>
        <BeatItem>
          <ChainMetric label="ATM" value={atm ?? '—'} sub="nearest-spot strike" />
        </BeatItem>
        <BeatItem>
          <ChainMetric label="Days to expiry" value={dte != null ? String(dte) : '—'} sub="calendar days" />
        </BeatItem>
        <BeatItem>
          <ChainMetric
            label={chain?.underlying ?? 'Underlying'}
            value={chain?.spot ? formatDecimal(chain.spot, 2) : '—'}
            sub="spot · DH/DL/DO pending"
          />
        </BeatItem>
      </BeatStrip>

      {chain == null && !chainQ.isLoading && (
        <p className="mb-3 text-body-sm text-ay-muted">
          No chain — pick an underlying + expiry with a live option chain.
        </p>
      )}

      <BeatBlock>
        <OptionsChainTable
          rows={rows}
          spot={chain?.spot ?? null}
          atmStrike={atm}
          optionalKeys={optionalKeys}
          density={density}
        />
      </BeatBlock>
    </LoadBeat>
  );
}
