import { useCallback, useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import type { EChartsOption } from 'echarts';
import { formatDecimal, isNegative, multiplyByInt, subtractDecimal } from '../../lib/decimal.ts';
import { cn } from '../../lib/cn.ts';
import { EChart, type ChartTheme } from '../../components/atoms/EChart.tsx';
import { SentimentBadge } from '../../components/atoms/SentimentBadge.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { LoadBeat } from '../../components/LoadBeat.tsx';
import { useLiveTicks } from '../../api/ticks.ts';
import type { PaperPosition } from '../../api/paper.ts';
import { PositionDetailDrawer } from '../../components/paper/PositionDetailDrawer.tsx';
import {
  bookLabel,
  riskEnabled,
  useClosePosition,
  usePaperAccount,
  usePaperPnl,
  usePaperPositions,
  usePaperTrades,
  useResetLedger,
  useRiskSettings,
  useUpdateCapital,
  useUpdateRisk,
} from '../../api/paper.ts';

// /paper cockpit page (master plan §20 parity, F-43): the paper-trading ledger — open positions with
// server-computed mark-to-market (refreshed on a short interval), the closed-trade ledger, the
// realized-equity curve (ECharts), the account header and the global risk limits (kill switch /
// max-open / daily-loss). Journal deep-links land with PR-C9.
// Revamp rollout (Trading screens): the sr-only h1 becomes the visible signature PageHeader (text
// preserved). The account + summary Stat tiles ride card shadow-e1 + uppercase wide-tracked caption
// labels + sign-aware mono values. All live MTM / WS ticks / risk-limit / table behaviour unchanged.

const money = (v: string) => formatDecimal(v, 2);
const pct = (v: string) => `${formatDecimal(multiplyByInt(v, 100), 1)}%`;
const toneClass = (v: string) => (isNegative(v) ? 'text-bear' : 'text-bull');

function Stat({ label, value, tone }: { label: string; value: string; tone?: boolean }) {
  return (
    <div className="card shadow-e1 nums">
      <div className="text-caption uppercase tracking-wide text-ay-muted">{label}</div>
      <div className={cn('mt-1 text-xl font-bold', tone && toneClass(value))}>{value}</div>
    </div>
  );
}

export function PaperPage() {
  // /paper/:book scopes to one per-family book; bare /paper defaults to the scalper book.
  const { book: bookParam } = useParams<{ book: string }>();
  const book = bookParam ?? 'scalper';
  const label = bookLabel(book) ?? 'Scalper';

  const positions = usePaperPositions(book);
  const trades = usePaperTrades(book);
  const pnl = usePaperPnl(book);
  const account = usePaperAccount(book);
  const risk = useRiskSettings(book);
  const updateCapital = useUpdateCapital(book);
  const updateRisk = useUpdateRisk(book);
  const closePosition = useClosePosition();
  const resetLedger = useResetLedger(book);

  const [capitalDraft, setCapitalDraft] = useState('');
  const [maxOpenDraft, setMaxOpenDraft] = useState('');
  const [dailyLossDraft, setDailyLossDraft] = useState('');
  // The position whose detail drawer is open (an open position id OR a closed trade's id = its position).
  const [detailId, setDetailId] = useState<number | null>(null);

  const acct = account.data ?? null;
  const summary = pnl.data?.summary ?? null;

  // live MTM overlay — sub-second LTP push over the WS tick bridge, on top of the 5s server mark
  const positionSymbols = useMemo(
    () => (positions.data?.items ?? []).map((p) => `${p.exchange}:${p.tradingsymbol}`),
    [positions.data],
  );
  const live = useLiveTicks(positionSymbols);
  const mtm = (p: PaperPosition) => {
    const mark = live[`${p.exchange}:${p.tradingsymbol}`] ?? p.markPrice;
    let unrealized = p.unrealizedPnl;
    if (mark) {
      const perUnit =
        p.side === 'BUY' ? subtractDecimal(mark, p.avgEntryPrice) : subtractDecimal(p.avgEntryPrice, mark);
      unrealized = multiplyByInt(perUnit, p.qty);
    }
    return { mark, unrealized };
  };
  const riskItems = risk.data?.items;
  const killOn = riskEnabled(riskItems, 'kill_switch');
  const autoOn = riskEnabled(riskItems, 'auto_paper_trade');

  const points = useMemo(() => pnl.data?.points ?? [], [pnl.data]);
  const makeOption = useCallback(
    (t: ChartTheme): EChartsOption => ({
      textStyle: { color: t.text },
      grid: { left: 56, right: 16, top: 16, bottom: 36 },
      tooltip: { trigger: 'axis' },
      xAxis: {
        type: 'category',
        data: points.map((p) => p.date),
        axisLine: { lineStyle: { color: t.border } },
        axisLabel: { color: t.muted },
      },
      yAxis: {
        type: 'value',
        scale: true,
        axisLabel: { color: t.muted },
        splitLine: { lineStyle: { color: t.grid } },
      },
      series: [
        {
          type: 'line',
          data: points.map((p) => Number(p.equity)),
          showSymbol: false,
          lineStyle: { color: t.accent },
          itemStyle: { color: t.accent },
          areaStyle: { opacity: 0.12 },
        },
      ],
    }),
    [points],
  );

  return (
    <LoadBeat>
      <PageHeader title={`${label} paper trading`} subtitle={`${label} book — open positions, closed trades, realized equity and its own risk limits`} help={`The ${label} paper book: its own capital, live mark-to-market on open positions, the closed-trade ledger, the realized-equity curve and per-book risk limits — no real money. Scalper / Minervini / Manas Arora each have a separate book, so their trades never mix.`} />

      {acct && (
        <>
          <section className="mb-3 flex flex-wrap items-center gap-x-6 gap-y-2 border-b border-ay-border pb-3">
            <Stat label="Equity" value={money(acct.equity)} />
            <Stat label="Free cash" value={money(acct.cash)} />
            <Stat label="Day P&L" value={money(acct.dayPnl)} tone />
            <Stat label="Capital used" value={money(acct.capitalUsed)} />
            <div className="flex items-center gap-2">
              <span className="text-xs text-ay-muted">Starting capital</span>
              <input
                value={capitalDraft}
                onChange={(e) => setCapitalDraft(e.target.value)}
                placeholder={acct.startingCapital}
                aria-label="Starting capital"
                title="The opening cash balance of the paper account — sets the baseline for equity and returns."
                className="h-9 w-full sm:w-36 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text"
              />
              <button
                type="button"
                onClick={() => capitalDraft.trim() && updateCapital.mutate(capitalDraft.trim())}
                title="Apply the new starting capital."
                className="h-9 rounded-md border border-ay-border px-3 text-sm hover:border-accent"
              >
                Set
              </button>
            </div>
            <div className="flex-1" />
            <button
              type="button"
              onClick={() => updateRisk.mutate({ key: 'auto_paper_trade', value: { enabled: !autoOn } })}
              title={`Auto-paper-trade for the ${label} book — when on, every emitted ${label}-book entry is taken automatically at its suggested quantity (this book only).`}
              className={cn(
                'h-9 rounded-md px-3 text-sm font-semibold ring-1',
                autoOn ? 'bg-bull/15 text-bull ring-bull/50' : 'text-ay-muted ring-ay-border hover:border-accent',
              )}
            >
              ◎ {autoOn ? 'AUTO-PAPER ON' : 'Auto-paper'}
            </button>
            <button
              type="button"
              onClick={() => updateRisk.mutate({ key: 'kill_switch', value: { enabled: !killOn } })}
              title={`Kill switch for the ${label} book — when on, blocks new ${label}-book paper entries ONLY (audit M19: this is per-book, not global — use the Telegram /pause command to halt every book at once).`}
              className={cn(
                'h-9 rounded-md px-3 text-sm font-semibold ring-1',
                killOn ? 'bg-bear/15 text-bear ring-bear/50' : 'text-ay-muted ring-ay-border hover:border-accent',
              )}
            >
              ⏻ {killOn ? 'KILL SWITCH ON' : 'Kill switch'}
            </button>
          </section>

          <section className="mb-3 flex flex-wrap items-center gap-x-8 gap-y-2 text-sm text-ay-muted">
            <span className={cn('flex items-center gap-1', riskEnabled(riskItems, 'max_open_paper_positions') && 'font-semibold text-ay-text')}>
              Max open
              <input
                value={maxOpenDraft}
                onChange={(e) => setMaxOpenDraft(e.target.value)}
                aria-label="Max open positions"
                title="The most open paper positions allowed at once — new entries are blocked once this cap is hit."
                className="h-8 w-20 rounded border border-ay-border bg-surface-1 px-2 text-ay-text"
              />
              <button
                type="button"
                onClick={() =>
                  updateRisk.mutate({
                    key: 'max_open_paper_positions',
                    value: { enabled: true, value: Number(maxOpenDraft) },
                  })
                }
                title="Turn on the max-open-positions limit at the entered value."
                className="rounded px-2 py-1 text-accent hover:underline"
              >
                Apply
              </button>
              <button
                type="button"
                onClick={() => updateRisk.mutate({ key: 'max_open_paper_positions', value: { enabled: false } })}
                title="Disable the max-open-positions limit."
                className="rounded px-2 py-1 hover:underline"
              >
                Off
              </button>
            </span>
            <span className={cn('flex items-center gap-1', riskEnabled(riskItems, 'daily_loss_limit') && 'font-semibold text-ay-text')}>
              Daily loss ₹
              <input
                value={dailyLossDraft}
                onChange={(e) => setDailyLossDraft(e.target.value)}
                aria-label="Daily loss limit"
                title="The most you can lose in a day (in ₹) before new paper entries are blocked."
                className="h-8 w-24 rounded border border-ay-border bg-surface-1 px-2 text-ay-text"
              />
              <button
                type="button"
                onClick={() =>
                  updateRisk.mutate({
                    key: 'daily_loss_limit',
                    value: { enabled: true, mode: 'inr', value: Number(dailyLossDraft) },
                  })
                }
                title="Turn on the daily-loss limit at the entered ₹ amount."
                className="rounded px-2 py-1 text-accent hover:underline"
              >
                Apply
              </button>
              <button
                type="button"
                onClick={() => updateRisk.mutate({ key: 'daily_loss_limit', value: { enabled: false } })}
                title="Disable the daily-loss limit."
                className="rounded px-2 py-1 hover:underline"
              >
                Off
              </button>
            </span>
          </section>
        </>
      )}

      <section className="mb-4 flex flex-wrap items-center gap-x-6 gap-y-2">
        {summary && (
          <>
            <Stat label="Realized P&L" value={money(summary.realizedTotal)} tone />
            <Stat label="Trades" value={String(summary.trades)} />
            <Stat label="Win rate" value={summary.winRate ? pct(summary.winRate) : '—'} />
            <Stat label="Expectancy" value={summary.expectancy ? money(summary.expectancy) : '—'} />
          </>
        )}
        <div className="flex-1" />
        <button
          type="button"
          disabled={resetLedger.isPending}
          onClick={() => {
            // Audit H7: reset is an IRREVERSIBLE hard-delete of this book's open + closed positions
            // and orders (the F7 graduation + reliability evidence). Confirm, naming the book, so a
            // stray click on /paper/:book can't wipe the forward-paper track (the API layer already
            // sends confirm:true, so the FE guard is the only safeguard).
            if (
              window.confirm(
                `Permanently delete ALL ${label}-book paper positions and trades? This wipes the ` +
                  `forward-paper track (open + closed) and CANNOT be undone.`,
              )
            ) {
              resetLedger.mutate();
            }
          }}
          className="h-9 rounded-md px-3 text-sm font-medium text-bear ring-1 ring-bear/50 hover:bg-bear/10 disabled:opacity-50"
        >
          🗑 Reset {label} ledger
        </button>
      </section>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[1.5fr_1fr]">
        <section className="min-w-0">
          <h2 className="mb-2 text-base font-semibold">Open positions</h2>
          <QueryState
            query={positions}
            isEmpty={(d) => (d.items?.length ?? 0) === 0}
            empty={{ title: 'No open positions — take a signal or place a paper order.' }}
            errorTitle="Couldn't load open positions"
            skeleton={<Skeleton variant="table-rows" rows={4} cols={7} className="rounded-lg border border-ay-border p-2" />}
          >
            {(data) => (
              <div className="overflow-auto rounded-lg border border-ay-border">
                <table className="w-full border-collapse text-sm">
                  <thead className="bg-surface-1 text-left text-xs uppercase text-ay-muted">
                    <tr>
                      <th className="px-2 py-2 font-medium">Instrument</th>
                      <th className="px-2 py-2 font-medium">Side</th>
                      <th className="px-2 py-2 text-right font-medium">Qty</th>
                      <th className="px-2 py-2 text-right font-medium">Avg</th>
                      <th className="px-2 py-2 text-right font-medium">Mark</th>
                      <th className="px-2 py-2 text-right font-medium">Unrealized</th>
                      <th className="px-2 py-2"><span className="ay-sr-only">Actions</span></th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.items.map((p) => {
                      const m = mtm(p);
                      return (
                      <tr key={p.id} className="border-t border-ay-border">
                        <td className="px-2 py-2">
                          {p.exchange}:{p.tradingsymbol}
                        </td>
                        <td className="px-2 py-2">
                          <SentimentBadge label={p.side} tone={p.side === 'BUY' ? 'bull' : 'bear'} />
                        </td>
                        <td className="px-2 py-2 text-right nums">{p.qty}</td>
                        <td className="px-2 py-2 text-right nums">{money(p.avgEntryPrice)}</td>
                        <td className="px-2 py-2 text-right nums">{m.mark ? money(m.mark) : '—'}</td>
                        <td className={cn('px-2 py-2 text-right nums', m.unrealized && toneClass(m.unrealized))}>
                          {m.unrealized ? money(m.unrealized) : '—'}
                        </td>
                        <td className="px-2 py-2 text-right">
                          <button
                            type="button"
                            onClick={() => setDetailId(p.id)}
                            className="rounded px-2 py-1 text-xs text-accent hover:underline"
                          >
                            Detail
                          </button>
                          <button
                            type="button"
                            onClick={() => closePosition.mutate({ id: p.id })}
                            className="rounded px-2 py-1 text-xs text-accent hover:underline"
                          >
                            Close
                          </button>
                        </td>
                      </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </QueryState>

          <h2 className="mb-2 mt-4 text-base font-semibold">Closed trades</h2>
          <div className="max-h-80 overflow-auto rounded-lg border border-ay-border">
            <table className="w-full border-collapse text-sm">
              <thead className="sticky top-0 bg-surface-1 text-left text-xs uppercase text-ay-muted">
                <tr>
                  <th className="px-2 py-2 font-medium">Instrument</th>
                  <th className="px-2 py-2 font-medium">Side</th>
                  <th className="px-2 py-2 text-right font-medium">Qty</th>
                  <th className="px-2 py-2 text-right font-medium">Avg</th>
                  <th className="px-2 py-2 text-right font-medium">Realized</th>
                  <th className="px-2 py-2 font-medium">Closed</th>
                </tr>
              </thead>
              <tbody>
                {(trades.data?.items ?? []).map((t) => (
                  <tr key={t.id} className="border-t border-ay-border">
                    <td className="px-2 py-2">
                      <button
                        type="button"
                        onClick={() => setDetailId(t.id)}
                        className="text-left text-accent hover:underline"
                      >
                        {t.exchange}:{t.tradingsymbol}
                      </button>
                    </td>
                    <td className="px-2 py-2">{t.side}</td>
                    <td className="px-2 py-2 text-right tabular-nums">{t.qty}</td>
                    <td className="px-2 py-2 text-right tabular-nums">{money(t.avgEntryPrice)}</td>
                    <td className={cn('px-2 py-2 text-right tabular-nums', toneClass(t.realizedPnl))}>
                      {money(t.realizedPnl)}
                    </td>
                    <td className="px-2 py-2 tabular-nums">{t.closedAt?.slice(0, 19)}</td>
                  </tr>
                ))}
                {(trades.data?.items ?? []).length === 0 && (
                  <tr>
                    <td colSpan={6} className="px-2 py-6 text-center text-ay-muted">
                      No closed trades yet.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </section>

        <section className="min-w-0">
          <h2 className="mb-2 text-base font-semibold">Realized equity</h2>
          {points.length > 0 ? (
            <EChart makeOption={makeOption} height={256} ariaLabel="Realized equity curve" />
          ) : (
            <div className="grid h-64 place-items-center rounded-lg border border-ay-border text-ay-muted">
              No realized-equity history yet.
            </div>
          )}
        </section>
      </div>

      <PositionDetailDrawer
        positionId={detailId}
        open={detailId != null}
        onOpenChange={(o) => {
          if (!o) setDetailId(null);
        }}
      />
    </LoadBeat>
  );
}
