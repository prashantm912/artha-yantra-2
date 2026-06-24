import { useMemo } from 'react';
import { cn } from '../../lib/cn.ts';
import { formatDecimal, isNegative, multiplyByInt, subtractDecimal } from '../../lib/decimal.ts';
import {
  riskEnabled,
  useClosePosition,
  usePaperAccount,
  usePaperPositions,
  useRiskSettings,
  type PaperPosition,
  type RiskSetting,
} from '../../api/paper.ts';
import { useLiveTicks } from '../../api/ticks.ts';

// The cockpit's live PAPER BOOK panel: the operator's open positions + live P&L + total exposure +
// the global risk-limit guard, so they watch their book while watching signals. Reuses the SAME paper
// engine the /paper page does (usePaperPositions / usePaperAccount / useRiskSettings / useClosePosition
// — no new backend) and layers the sub-second WS LTP overlay (useLiveTicks) on top of the BE's 5s
// server mark, exactly like /paper + /scalper. PAPER ONLY — Close simulates the exit, never a live
// order. The risk chips are READ-ONLY status here; the full /paper page owns editing the limits.

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
  const positions = usePaperPositions();
  const account = usePaperAccount();
  const risk = useRiskSettings();
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
      {/* Account header — equity · day P&L · total exposure (capital used) · open count. */}
      {acct && (
        <div className="flex flex-wrap items-center gap-x-5 gap-y-1 text-sm">
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

      {/* Risk-limit guard (read-only status; edit on /paper). */}
      <div className="flex flex-wrap items-center gap-1.5" aria-label="Risk limits">
        <RiskChip label="Kill switch" items={riskItems} riskKey="kill_switch" danger />
        <RiskChip label="Max open" items={riskItems} riskKey="max_open_paper_positions" />
        <RiskChip label="Daily loss ₹" items={riskItems} riskKey="daily_loss_limit" />
      </div>

      {/* Open positions with live MTM. */}
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
