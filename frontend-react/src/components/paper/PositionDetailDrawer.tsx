import { useEffect, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { formatDecimal, isNegative } from '../../lib/decimal.ts';
import { cn } from '../../lib/cn.ts';
import { ApiError } from '../../api/client.ts';
import { SentimentBadge } from '../atoms/SentimentBadge.tsx';
import { QueryState } from '../QueryState.tsx';
import { Skeleton } from '../Skeletons.tsx';
import { OrderEventTimeline } from './OrderEventTimeline.tsx';
import {
  bookLabel,
  useEditBrackets,
  usePaperPositionDetail,
  type FeeBreakdown,
  type PaperOrderLeg,
  type PaperPositionDetail,
} from '../../api/paper.ts';
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '../ui/sheet.tsx';

// Phase-2 slice B (app-platform audit §6.4/§6.5): the position DETAIL pane + trade-chain view. One
// GET /paper/positions/{id} fetch drives the whole drawer — provenance (opening signal, F9 advised
// lots / margin snapshot, book, sub-account), the entry/exit order legs with a recomputed fee
// breakdown, an editable bracket form (PATCH .../brackets, HOLD-tier — live-effective on the next 15s
// evaluator pass), and the signal → entry → position → exit trade chain assembled from the same
// payload. The adjacent order-event timeline consumes the append-only paper.events lifecycle stream,
// including explicit BRACKET_HIT and SETTLED events.

const money = (v: string | null | undefined): string => (v == null ? '—' : formatDecimal(v, 2));
const toneClass = (v: string | null | undefined) =>
  v == null ? undefined : isNegative(v) ? 'text-bear' : 'text-bull';

function fmtTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Kolkata',
    day: '2-digit',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(iso));
}

/** A compact CE/PE + strike summary off the opening signal's scalper side-channel, when present. */
function optionLeg(detail: unknown): string | null {
  if (!detail || typeof detail !== 'object') return null;
  const d = detail as Record<string, unknown>;
  const type = typeof d.optionType === 'string' ? d.optionType : null;
  const strike = d.strike == null ? null : String(d.strike);
  if (!type && !strike) return null;
  return [type, strike].filter(Boolean).join(' ');
}

function Field({ label, value, tone }: { label: string; value: ReactNode; tone?: boolean }) {
  return (
    <div>
      <div className="text-caption uppercase tracking-wide text-ay-muted">{label}</div>
      <div className={cn('mt-0.5 text-sm nums', tone && typeof value === 'string' && toneClass(value))}>
        {value}
      </div>
    </div>
  );
}

const FEE_LEGS: { key: keyof FeeBreakdown; label: string }[] = [
  { key: 'brokerage', label: 'Brokerage' },
  { key: 'stt', label: 'STT' },
  { key: 'exchangeTxn', label: 'Exchange' },
  { key: 'gst', label: 'GST' },
  { key: 'stamp', label: 'Stamp' },
  { key: 'sebi', label: 'SEBI' },
];

/** One order leg node in the trade chain: side/qty/fill + the recomputed statutory fee breakdown. */
function OrderNode({ leg, entrySide }: { leg: PaperOrderLeg; entrySide: string }) {
  const isEntry = leg.side === entrySide;
  return (
    <div className="rounded-md border border-ay-border bg-surface-1 p-2 text-xs">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="font-medium text-ay-text">
          {isEntry ? 'Entry' : 'Exit'} · {leg.side} {leg.qty} @ {money(leg.fillPrice)}
        </span>
        <span className="text-ay-muted">{fmtTime(leg.filledAt ?? leg.placedAt)} IST</span>
      </div>
      <div className="mt-1 flex flex-wrap gap-x-3 gap-y-0.5 text-[11px] text-ay-muted">
        <span>slippage {money(leg.slippageApplied)}</span>
        {leg.fillSimulator && <span>· {leg.fillSimulator}</span>}
      </div>
      {leg.fees && (
        <div className="mt-1 flex flex-wrap gap-x-3 gap-y-0.5 text-[11px] tabular-nums text-ay-muted">
          {FEE_LEGS.map(
            (f) =>
              leg.fees?.[f.key] != null && (
                <span key={f.key}>
                  {f.label} {money(leg.fees[f.key])}
                </span>
              ),
          )}
          <span className="font-medium text-ay-text">Fees {money(leg.fees.total)}</span>
        </div>
      )}
    </div>
  );
}

/** The editable stop-loss / take-profit form (OPEN positions only). */
function BracketEditor({ detail }: { detail: PaperPositionDetail }) {
  const edit = useEditBrackets();
  const [sl, setSl] = useState(detail.stopLoss ?? '');
  const [tp, setTp] = useState(detail.takeProfit ?? '');

  // Re-seed the draft when the server levels change (a poll, or a prior edit landed).
  useEffect(() => {
    setSl(detail.stopLoss ?? '');
    setTp(detail.takeProfit ?? '');
  }, [detail.stopLoss, detail.takeProfit]);

  const dirty = sl !== (detail.stopLoss ?? '') || tp !== (detail.takeProfit ?? '');
  const save = () => {
    const body: { id: number; stopLoss?: string; takeProfit?: string } = { id: detail.id };
    if (sl.trim() && sl !== (detail.stopLoss ?? '')) body.stopLoss = sl.trim();
    if (tp.trim() && tp !== (detail.takeProfit ?? '')) body.takeProfit = tp.trim();
    if (body.stopLoss || body.takeProfit) edit.mutate(body);
  };

  return (
    <section aria-label="Edit brackets" className="rounded-md border border-ay-border p-3">
      <h3 className="mb-2 text-caption font-semibold uppercase tracking-wide text-ay-muted">
        Brackets — live-effective on the next evaluator pass
      </h3>
      <div className="flex flex-wrap items-end gap-2">
        <label className="flex flex-col gap-1 text-xs text-ay-muted">
          Stop-loss
          <input
            value={sl}
            onChange={(e) => setSl(e.target.value)}
            inputMode="decimal"
            aria-label="Stop-loss level"
            className="h-9 w-28 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text nums"
          />
        </label>
        <label className="flex flex-col gap-1 text-xs text-ay-muted">
          Take-profit
          <input
            value={tp}
            onChange={(e) => setTp(e.target.value)}
            inputMode="decimal"
            aria-label="Take-profit level"
            className="h-9 w-28 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text nums"
          />
        </label>
        <button
          type="button"
          disabled={!dirty || edit.isPending}
          onClick={save}
          className="h-9 rounded-md border border-ay-border px-3 text-sm font-medium hover:border-accent disabled:opacity-50"
        >
          Save brackets
        </button>
      </div>
      {edit.isError && (
        <p className="mt-2 text-xs text-bear">
          {edit.error instanceof ApiError ? edit.error.message : 'Could not update the brackets.'}
        </p>
      )}
    </section>
  );
}

interface PositionDetailDrawerProps {
  positionId: number | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function PositionDetailDrawer({ positionId, open, onOpenChange }: PositionDetailDrawerProps) {
  const query = usePaperPositionDetail(open ? positionId : null);

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent
        side="right"
        className="w-full gap-0 overflow-y-auto border-ay-border bg-surface-1 sm:max-w-lg"
      >
        <SheetHeader className="border-b border-ay-border">
          <SheetTitle className="text-h3 text-ay-text">Position detail</SheetTitle>
          <SheetDescription className="text-caption text-ay-muted">
            Provenance, brackets and the signal → entry → exit trade chain for one paper position.
          </SheetDescription>
        </SheetHeader>

        <div className="p-4">
          <QueryState
            query={query}
            errorTitle="Couldn't load the position"
            skeleton={<Skeleton variant="table-rows" rows={6} />}
          >
            {(d) => {
              const legOf = optionLeg(d.openingSignal?.scalperDetail);
              const familyBook = ['scalper', 'minervini', 'manas-arora'].includes(d.book);
              return (
                <div className="flex flex-col gap-5">
                  {/* Identity + live P&L */}
                  <section className="flex flex-col gap-2">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-sm font-semibold text-ay-text">
                        {d.exchange}:{d.tradingsymbol}
                      </span>
                      <SentimentBadge label={d.side} tone={d.side === 'BUY' ? 'bull' : 'bear'} />
                      <span className="rounded bg-surface-2 px-1.5 py-0.5 text-[11px] text-ay-muted">
                        {d.status}
                      </span>
                      <span className="rounded bg-surface-2 px-1.5 py-0.5 text-[11px] text-ay-muted">
                        {bookLabel(d.book) ?? d.book}
                      </span>
                    </div>
                    <div className="grid grid-cols-2 gap-x-4 gap-y-3 sm:grid-cols-3">
                      <Field label="Qty" value={String(d.qty)} />
                      <Field label="Avg entry" value={money(d.avgEntryPrice)} />
                      <Field label="Mark" value={money(d.markPrice)} />
                      {d.status === 'OPEN' ? (
                        <Field label="Unrealized" value={money(d.unrealizedPnl)} tone />
                      ) : (
                        <Field label="Realized" value={money(d.realizedPnl)} tone />
                      )}
                      <Field
                        label="Advised / actual lots"
                        value={`${d.advisedLots ?? '—'} / ${d.qty}`}
                      />
                      <Field
                        label="Margin snapshot"
                        value={
                          d.marginSnapshot == null
                            ? '—'
                            : `${money(d.marginSnapshot)}${d.marginPct == null ? '' : ` (${formatDecimal(d.marginPct, 1)}%)`}`
                        }
                      />
                    </div>
                    {d.subaccountIdx != null && (
                      <div className="text-xs text-ay-muted">Sub-account #{d.subaccountIdx}</div>
                    )}
                  </section>

                  {/* Bracket editor — OPEN only (an exit never re-brackets) */}
                  {d.status === 'OPEN' && <BracketEditor detail={d} />}

                  {/* Trade chain: signal → entry → exit → close */}
                  <section aria-label="Trade chain" className="flex flex-col gap-2">
                    <h3 className="text-caption font-semibold uppercase tracking-wide text-ay-muted">
                      Trade chain
                    </h3>

                    {d.openingSignal ? (
                      <div className="rounded-md border border-ay-border bg-surface-1 p-2 text-xs">
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <span className="font-medium text-ay-text">
                            Signal #{d.openingSignal.signalId} · {d.openingSignal.side}
                            {legOf ? ` · ${legOf}` : ''}
                          </span>
                          {familyBook && (
                            <Link
                              to={`/signals/${d.book}`}
                              className="text-accent hover:underline"
                              onClick={() => onOpenChange(false)}
                            >
                              Open signals
                            </Link>
                          )}
                        </div>
                        <div className="mt-1 flex flex-wrap gap-x-3 gap-y-0.5 text-[11px] text-ay-muted">
                          <span>entry {money(d.openingSignal.entryPrice)}</span>
                          {d.openingSignal.compositeScore != null && (
                            <span>· composite {formatDecimal(d.openingSignal.compositeScore, 3)}</span>
                          )}
                          <span>· {fmtTime(d.openingSignal.generatedAt)} IST</span>
                          <span>· {d.openingSignal.status}</span>
                        </div>
                      </div>
                    ) : (
                      <div className="rounded-md border border-dashed border-ay-border p-2 text-xs text-ay-muted">
                        Hand order — no originating signal.
                      </div>
                    )}

                    {d.orders.length > 0 ? (
                      d.orders.map((leg) => (
                        <OrderNode key={leg.orderId} leg={leg} entrySide={d.side} />
                      ))
                    ) : (
                      <div className="rounded-md border border-dashed border-ay-border p-2 text-xs text-ay-muted">
                        No order legs recorded for this position's lifetime.
                      </div>
                    )}

                    {d.status !== 'OPEN' && (
                      <div className="rounded-md border border-ay-border bg-surface-1 p-2 text-xs">
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <span className="font-medium text-ay-text">
                            Closed · {d.closeReason ?? 'CLOSED'}
                          </span>
                          <span className={cn('nums', toneClass(d.realizedPnl))}>
                            realized {money(d.realizedPnl)}
                          </span>
                        </div>
                        <div className="mt-1 text-[11px] text-ay-muted">
                          {fmtTime(d.closedAt)} IST
                        </div>
                      </div>
                    )}
                  </section>

                  <section
                    aria-labelledby={`position-${d.id}-order-events-heading`}
                    className="flex flex-col gap-2"
                  >
                    <h3
                      id={`position-${d.id}-order-events-heading`}
                      className="text-caption font-semibold uppercase tracking-wide text-ay-muted"
                    >
                      Order events
                    </h3>
                    <OrderEventTimeline positionId={d.id} />
                  </section>
                </div>
              );
            }}
          </QueryState>
        </div>
      </SheetContent>
    </Sheet>
  );
}
