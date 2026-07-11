import { useMemo, useState } from 'react';
import { cn } from '../../lib/cn.ts';
import { formatDecimal, isNegative, multiplyByInt, subtractDecimal } from '../../lib/decimal.ts';
import {
  bookLabel,
  PAPER_BOOKS,
  riskEnabled,
  useClosePosition,
  usePaperAccount,
  usePaperPositions,
  useRiskSettings,
  type PaperPosition,
  type RiskSetting,
} from '../../api/paper.ts';
import { useLiveTicks } from '../../api/ticks.ts';
import { Select } from '../../components/atoms/Select.tsx';
import { InfoTip } from '../../components/atoms/InfoTip.tsx';

// The cockpit's live PAPER BOOK panel: the operator's open positions + live P&L + total exposure +
// the per-book risk-limit guard, so they watch their book while watching signals. Reuses the SAME paper
// engine the /paper page does (usePaperPositions / usePaperAccount / useRiskSettings / useClosePosition
// — no new backend) and layers the sub-second WS LTP overlay (useLiveTicks) on top of the BE's 5s
// server mark, exactly like /paper + /scalper. PAPER ONLY — Close simulates the exit, never a live
// order. The risk chips are READ-ONLY status here; the full /paper page owns editing the limits.
//
// Book framing (audit M18): paper is split into three per-family books (scalper / minervini /
// manas-arora). This panel used to read the ALL-BOOKS aggregate (no `book`) yet sat in the scalper
// cockpit above a single risk-chip row — reading as "the scalper book" when it was really the
// aggregate, and showing one kill-switch/max-open/daily-loss row as if it were global (risk is
// PER-BOOK, so an un-scoped chip row is meaningless). Now a book selector scopes every read to the
// chosen book (default Scalper) and the risk chips reflect THAT book; the "All books" option shows the
// honest aggregate P&L and — since there is no aggregate risk limit — swaps the chip row for a
// per-book note instead of a misleading one.

/** Book slugs plus an explicit all-books aggregate option; the selector default is the scalper book. */
const BOOK_OPTIONS = [
  ...PAPER_BOOKS.map((b) => ({ value: b.book, label: b.label })),
  { value: 'all', label: 'All books' },
];

const money = (v: string) => formatDecimal(v, 2);
const toneClass = (v: string) => (isNegative(v) ? 'text-bear' : 'text-bull');

/** A read-only risk-limit status chip — on = the guard is armed (red kill-switch, neutral otherwise). */
function RiskChip({
  label,
  items,
  riskKey,
  danger,
}: {
  label: string;
  items: RiskSetting[] | undefined;
  riskKey: string;
  danger?: boolean;
}) {
  const on = riskEnabled(items, riskKey);
  const value = items?.find((r) => r.key === riskKey)?.value?.['value'];
  return (
    <span
      className={cn(
        'rounded px-1.5 py-0.5 text-xs ring-1',
        on
          ? danger
            ? 'bg-bear/15 text-bear ring-bear/50'
            : 'text-ay-text ring-accent/50'
          : 'text-ay-muted ring-ay-border',
      )}
    >
      {label}: {on ? (value != null ? String(value) : 'on') : 'off'}
    </span>
  );
}

export function PaperBookPanel() {
  // Which book to show. Default the scalper book (this IS the scalper cockpit); 'all' = the honest
  // all-books aggregate. `scoped` (undefined for 'all') feeds every read so the query key + `?book=`
  // track the selection, exactly like the /paper page.
  const [book, setBook] = useState<string>('scalper');
  const scoped = book === 'all' ? undefined : book;

  const positions = usePaperPositions(scoped);
  const account = usePaperAccount(scoped);
  const risk = useRiskSettings(scoped);
  const close = useClosePosition();

  const items = useMemo(() => positions.data?.items ?? [], [positions.data]);
  const acct = account.data ?? null;
  const riskItems = risk.data?.items;

  // live MTM overlay — sub-second LTP push over the WS tick bridge, on top of the 5s server mark.
  const positionSymbols = useMemo(
    () => items.map((p) => `${p.exchange}:${p.tradingsymbol}`),
    [items],
  );
  const live = useLiveTicks(positionSymbols);
  const mtm = (p: PaperPosition) => {
    const mark = live[`${p.exchange}:${p.tradingsymbol}`] ?? p.markPrice;
    let unrealized = p.unrealizedPnl;
    if (mark) {
      const perUnit =
        p.side === 'BUY'
          ? subtractDecimal(mark, p.avgEntryPrice)
          : subtractDecimal(p.avgEntryPrice, mark);
      unrealized = multiplyByInt(perUnit, p.qty);
    }
    return { mark, unrealized };
  };

  return (
    <div className="flex flex-col gap-3">
      {/* Book selector — names WHAT this panel shows so the aggregate is never mistaken for one book. */}
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-xs text-ay-muted">
          {book === 'all' ? 'All books (aggregate)' : `${bookLabel(book) ?? book} book`}
        </span>
        <Select
          value={book}
          options={BOOK_OPTIONS}
          onChange={setBook}
          ariaLabel="Paper book"
          title="Which paper book to show — a specific family's positions + risk limits, or the all-books aggregate P&L"
        />
      </div>

      {/* Account header — equity · day P&L · total exposure (capital used) · open count. */}
      {acct && (
        <div className="card shadow-e1 flex flex-wrap items-center gap-x-5 gap-y-1 text-sm">
          <span>
            <span className="text-ay-muted">Equity </span>
            <span className="font-semibold tabular-nums">{money(acct.equity)}</span>
          </span>
          <span>
            <span className="text-ay-muted">Day P&L </span>
            <span className={cn('font-semibold tabular-nums', toneClass(acct.dayPnl))}>
              {money(acct.dayPnl)}
            </span>
          </span>
          <span>
            <span className="text-ay-muted">Exposure </span>
            <span className="font-semibold tabular-nums">{money(acct.capitalUsed)}</span>
          </span>
          <span>
            <span className="text-ay-muted">Open </span>
            <span className="font-semibold tabular-nums">{acct.openPositions}</span>
          </span>
        </div>
      )}

      {/* Risk-limit guard (read-only status; edit on /paper). Risk is PER-BOOK, so the chips only make
          sense scoped to one book — for the all-books aggregate we show a per-book note instead. */}
      {book === 'all' ? (
        <p className="text-xs text-ay-muted">
          Risk limits are per-book — pick a book above to see its kill switch, max-open and daily-loss
          guard.
        </p>
      ) : (
        <div className="flex flex-wrap items-center gap-1.5" aria-label="Risk limits">
          <RiskChip label="Kill switch" items={riskItems} riskKey="kill_switch" danger />
          <RiskChip label="Max open" items={riskItems} riskKey="max_open_paper_positions" />
          <RiskChip label="Daily loss ₹" items={riskItems} riskKey="daily_loss_limit" />
        </div>
      )}

      {/* Open positions with live MTM. */}
      <h3 className="flex items-center gap-1.5 text-h3 text-ay-text">
        Open positions
        <InfoTip
          text="Your live paper positions, marked to the latest price — Mark is the current price, uP&L is the open profit/loss, and Close simulates exiting at that mark."
          label="Open positions"
        />
      </h3>
      <div className="max-h-[24rem] overflow-auto rounded-lg border border-ay-border">
        <table className="w-full border-collapse text-sm">
          <thead className="sticky top-0 bg-surface-1 text-left text-xs uppercase text-ay-muted">
            <tr>
              <th className="px-2 py-2 font-medium">Instrument</th>
              <th className="px-2 py-2 font-medium">Side</th>
              <th className="px-2 py-2 text-right font-medium">Qty</th>
              <th className="px-2 py-2 text-right font-medium">Mark</th>
              <th className="px-2 py-2 text-right font-medium">uP&L</th>
              <th className="px-2 py-2 text-right font-medium">SL / TP</th>
              <th className="px-2 py-2">
                <span className="ay-sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {items.map((p) => {
              const m = mtm(p);
              return (
                <tr key={p.id} className="border-t border-ay-border">
                  <td className="px-2 py-2">
                    {p.exchange}:{p.tradingsymbol}
                  </td>
                  <td className="px-2 py-2">
                    <span
                      className={cn(
                        'text-xs font-semibold',
                        p.side === 'BUY' ? 'text-bull' : 'text-bear',
                      )}
                    >
                      {p.side}
                    </span>
                  </td>
                  <td className="px-2 py-2 text-right tabular-nums">{p.qty}</td>
                  <td className="px-2 py-2 text-right tabular-nums">{m.mark ? money(m.mark) : '—'}</td>
                  <td
                    className={cn(
                      'px-2 py-2 text-right tabular-nums',
                      m.unrealized && toneClass(m.unrealized),
                    )}
                  >
                    {m.unrealized ? money(m.unrealized) : '—'}
                  </td>
                  <td className="px-2 py-2 text-right tabular-nums text-xs text-ay-muted">
                    {p.stopLoss ? money(p.stopLoss) : '—'} / {p.takeProfit ? money(p.takeProfit) : '—'}
                  </td>
                  <td className="px-2 py-2 text-right">
                    <button
                      type="button"
                      onClick={() => close.mutate({ id: p.id })}
                      title="Simulate exiting this position at the current mark — paper only, never a live order"
                      className="px-1.5 text-xs text-accent hover:underline"
                    >
                      Close
                    </button>
                  </td>
                </tr>
              );
            })}
            {items.length === 0 && (
              <tr>
                <td colSpan={7} className="px-2 py-6 text-center text-ay-muted">
                  No open positions — take a signal above to open a paper trade.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
