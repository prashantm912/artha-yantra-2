import { useMemo } from 'react';
import { Building2, CircleDot, Clock, Landmark, Sunrise, TrendingDown, TrendingUp } from 'lucide-react';
import { useMarketStatus, usePreOpen } from '../../api/preOpen.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatStrip, BeatItem, BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { cn } from '../../lib/cn.ts';
import { compareDecimal, formatDecimal, isNegative } from '../../lib/decimal.ts';
import { FIELD_HELP } from '../../core/fieldHelp.ts';
import type { MarketStatus, PreOpenIndex } from '../../api/types.ts';

// Pre-Open Market (§equity/pre-open-market). Two Upstox reads: a session-phase banner for NSE/BSE/MCX
// (pre-open highlighted via --ay-bull/accent, closed muted) and the NSE pre-open snapshot table (Nifty
// 50 / Bank / Fin / Sensex). The phase text + an icon carry the meaning (never colour alone, a11y #2).
// Net change + %change are sign-aware — the sign + an explicit arrow carry the meaning; --ay-bull/-bear
// are supportive. Prices arrive as decimal STRINGS and are formatted exact (never parseFloat). The page
// is useful 24/7: outside 09:00–09:15 IST it shows the live phase + the last/closing index quotes.

const EXCHANGE_ICON: Record<string, typeof Landmark> = {
  NSE: Landmark,
  BSE: Building2,
  MCX: CircleDot,
};

/** Humanises the raw Upstox phase enum (PRE_OPEN_START → "Pre-Open Start", NORMAL_OPEN → "Normal Open"). */
function phaseLabel(status: string): string {
  if (status === 'UNKNOWN') return 'Unknown';
  const titled = (w: string) => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase();
  // Keep the PRE_OPEN prefix as the compound "Pre-Open" (matches the badge); the rest is space-joined.
  if (status.startsWith('PRE_OPEN')) {
    const rest = status.slice('PRE_OPEN'.length).replace(/^_/, '');
    return rest ? `Pre-Open ${titled(rest)}` : 'Pre-Open';
  }
  return status.split('_').map(titled).join(' ');
}

/** The HH:mm:ss IST clock from an ISO +05:30 asOf, for the card / live detail. */
function asOfClock(iso: string | null | undefined): string | undefined {
  if (!iso) return undefined;
  const t = iso.match(/T(\d{2}:\d{2}:\d{2})/);
  return t ? `${t[1]} IST` : undefined;
}

/** One exchange session-phase card. Pre-open is accented (bull); open is neutral; closed/unknown muted. */
function PhaseCard({ s }: { s: MarketStatus }) {
  const Icon = EXCHANGE_ICON[s.exchange] ?? Landmark;
  const open = s.status.startsWith('NORMAL');
  const unknown = s.status === 'UNKNOWN';
  const tone = s.preOpen
    ? 'border-bull bg-bull/10'
    : open
      ? 'border-accent bg-surface-1'
      : 'border-ay-border bg-surface-1';
  return (
    <div className={cn('flex flex-col gap-1 rounded-lg border p-4', tone)}>
      <div className="flex items-center justify-between">
        <span className="flex items-center gap-1.5 text-body font-medium text-ay-text">
          <Icon aria-hidden="true" className="size-4" />
          {s.exchange}
        </span>
        {s.preOpen && (
          <span className="flex items-center gap-1 text-caption font-semibold text-bull">
            <Sunrise aria-hidden="true" className="size-3.5" />
            Pre-Open
          </span>
        )}
      </div>
      <span className={cn('text-body-sm font-medium', unknown ? 'text-ay-muted' : 'text-ay-text')}>
        {phaseLabel(s.status)}
      </span>
      <span className="flex items-center gap-1 text-caption text-ay-muted">
        <Clock aria-hidden="true" className="size-3" />
        {asOfClock(s.asOf) ?? 'No update'}
      </span>
    </div>
  );
}

/** Signed change cell: sign + arrow + --ay-bull/--ay-bear tone (text cue first), muted dash when null. */
function ChangeCell({ value, suffix = '' }: { value: string | null; suffix?: string }) {
  if (value == null) return <span className="text-ay-muted">—</span>;
  const zero = compareDecimal(value, '0') === 0;
  const neg = isNegative(value);
  const tone = zero ? '' : neg ? 'text-bear' : 'text-bull';
  const Arrow = zero ? null : neg ? TrendingDown : TrendingUp;
  const sign = !neg && !zero ? '+' : '';
  return (
    <span className={cn('nums inline-flex items-center justify-end gap-0.5', tone)}>
      {Arrow && <Arrow aria-hidden="true" className="size-3.5" />}
      {sign}
      {formatDecimal(value, 2)}
      {suffix}
    </span>
  );
}

export function PreOpenMarketPage() {
  const statusQ = useMarketStatus();
  const preOpenQ = usePreOpen();
  const indices = preOpenQ.data?.indices ?? [];
  const asOf = indices.find((r) => r.asOf)?.asOf;

  const columns = useMemo<DataColumn<PreOpenIndex>[]>(
    () => [
      {
        id: 'name',
        header: 'Index',
        help: 'The index whose pre-open snapshot this row shows (Nifty 50, Bank, Fin, Sensex).',
        align: 'left',
        pin: 'left',
        sortValue: (r) => r.name,
        sortType: 'text',
        render: (r) => <span className="font-medium text-ay-text">{r.name}</span>,
        mobileLabel: 'Index',
      },
      {
        id: 'ltp',
        header: 'LTP',
        help: FIELD_HELP.ltp,
        align: 'right',
        sortValue: (r) => r.ltp,
        sortType: 'decimal',
        render: (r) =>
          r.ltp == null ? <span className="text-ay-muted">—</span> : <span className="nums">{formatDecimal(r.ltp, 2)}</span>,
        mobileLabel: 'LTP',
      },
      {
        id: 'prevClose',
        header: 'Prev Close',
        help: FIELD_HELP.prevClose,
        align: 'right',
        sortValue: (r) => r.prevClose,
        sortType: 'decimal',
        render: (r) =>
          r.prevClose == null ? (
            <span className="text-ay-muted">—</span>
          ) : (
            <span className="nums text-ay-muted">{formatDecimal(r.prevClose, 2)}</span>
          ),
        mobileLabel: 'Prev Close',
      },
      {
        id: 'netChange',
        header: 'Net Chg',
        help: FIELD_HELP.netChange,
        align: 'right',
        sortValue: (r) => r.netChange,
        sortType: 'decimal',
        render: (r) => <ChangeCell value={r.netChange} />,
        mobileLabel: 'Net Chg',
      },
      {
        id: 'changePct',
        header: '% Chg',
        help: FIELD_HELP.changePct,
        align: 'right',
        sortValue: (r) => r.changePct,
        sortType: 'decimal',
        render: (r) => <ChangeCell value={r.changePct} suffix="%" />,
        mobileLabel: '% Chg',
      },
    ],
    [],
  );

  return (
    <LoadBeat>
      <PageHeader
        title="Pre-Open Market"
        help="Shows each exchange's current session phase plus the NSE pre-open index snapshot — read net change and %change green-up / red-down to gauge the opening bias."
        subtitle="Exchange session phase + the NSE pre-open index snapshot from Upstox — pre-open is highlighted; net change and %change are sign-aware (green up, red down)"
      />

      {/* Session-phase banner — NSE / BSE / MCX, pre-open accented. */}
      <section aria-label="Exchange session phase" className="mb-6">
        <QueryState
          query={statusQ}
          empty={{ icon: Sunrise, title: 'No market status available.', hint: 'The Upstox market-status feed is not configured on this stack.' }}
          errorTitle="Couldn't load market status"
          skeleton={<Skeleton variant="metric-strip" cols={3} />}
        >
          {(rows) => (
            <BeatStrip className="grid grid-cols-1 gap-3 sm:grid-cols-3">
              {rows.map((s) => (
                <BeatItem key={s.exchange}>
                  <PhaseCard s={s} />
                </BeatItem>
              ))}
            </BeatStrip>
          )}
        </QueryState>
      </section>

      {/* NSE pre-open index snapshot. */}
      <section aria-label="Pre-open index snapshot">
        <div className="mb-2 flex items-center justify-between">
          <h2 className="text-h2 text-ay-text">Index Snapshot</h2>
          {asOfClock(asOf) && <span className="nums text-caption text-ay-muted">As of {asOfClock(asOf)}</span>}
        </div>
        <QueryState
          query={preOpenQ}
          isEmpty={(d) => d.indices.length === 0}
          empty={{ icon: Sunrise, title: 'No index pre-open snapshot available.', hint: 'The Upstox index feed is not configured on this stack.' }}
          errorTitle="Couldn't load the pre-open snapshot"
          skeleton={<Skeleton variant="table-rows" rows={4} cols={5} />}
        >
          {() => (
            <BeatBlock>
              <DataTable
                columns={columns}
                rows={indices}
                rowKey={(r) => r.key}
                pageSize={10}
                emptyMessage="No index pre-open snapshot available."
                ariaLabel="Pre-open index snapshot"
              />
            </BeatBlock>
          )}
        </QueryState>
      </section>
    </LoadBeat>
  );
}
