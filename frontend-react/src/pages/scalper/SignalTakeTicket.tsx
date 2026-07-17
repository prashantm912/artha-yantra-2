import { useCallback, useState } from 'react';
import { cn } from '../../lib/cn.ts';
import { formatDecimal } from '../../lib/decimal.ts';
import { useSignalDetail, type SignalDto } from '../../api/signals.ts';
import { PAPER_BOOKS, usePlacePaperOrder } from '../../api/paper.ts';
import { ManualVerifyChecklist } from '../../components/ManualVerifyChecklist.tsx';
import { InfoTip } from '../../components/atoms/InfoTip.tsx';
import { Select } from '../../components/atoms/Select.tsx';

// The inline "Take (paper)" ticket for the Scalping Cockpit's signals feed (rendered when its row's
// trigger is open). DEFINED-RISK and prefilled from the signal — instrument · side · entry · SL ·
// target · suggested qty — submitted through the EXISTING paper engine: POST /paper/orders carrying the
// signal's id for provenance (usePlacePaperOrder). PAPER ONLY: it simulates the fill, never a live
// broker order. Scalper signals carry a manual-verify checklist (hydrated via GET /signals/{id}); the
// Place button stays disabled until the owner confirms, mirroring the /signals + /scalper gating.

/** Editable fields of the prefilled ticket; '' = market price / no SL-TP; book '' = the signal's own book. */
interface Draft {
  qty: string;
  price: string;
  sl: string;
  tp: string;
  book: string;
}

function draftFrom(s: SignalDto): Draft {
  return {
    qty: s.suggestedQty ?? '1',
    price: s.entryPrice ?? '',
    sl: s.stopLoss ?? '',
    tp: s.target ?? '',
    // Default to the signal's own book when the frame carries it (the REST snapshot omits it); '' routes
    // to the signal-family default the BE derives.
    book: s.book && PAPER_BOOKS.some((b) => b.book === s.book) ? s.book : '',
  };
}

/** '' = let the BE route to the signal's own family; else an explicit paper book. */
const BOOK_OPTIONS = [
  { value: '', label: 'Signal book (default)' },
  ...PAPER_BOOKS.map((b) => ({ value: b.book, label: b.label })),
];

const inputCls =
  'h-8 w-full rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text';

export function SignalTakeTicket({
  signal,
  onDone,
}: {
  signal: SignalDto;
  /** Closes the ticket (after a successful fill or Cancel). */
  onDone: () => void;
}) {
  const place = usePlacePaperOrder();
  const [draft, setDraft] = useState<Draft>(() => draftFrom(signal));

  // Hydrate the scalper enrichment (the list row / live frame MAY omit it) → manual-verify checklist.
  const detail = useSignalDetail(signal.id);
  const scalperDetail = detail.data?.scalperDetail ?? null;
  const [confirmed, setConfirmed] = useState(false);
  const onConfirmedChange = useCallback((v: boolean) => setConfirmed(v), []);
  const takeBlocked = scalperDetail != null && !confirmed;

  const submit = () => {
    if (!draft.qty || Number(draft.qty) <= 0) return;
    place.mutate(
      {
        signalId: signal.id,
        exchange: signal.exchange,
        tradingsymbol: signal.tradingsymbol,
        side: signal.side,
        qty: Number(draft.qty),
        price: draft.price || undefined,
        stopLoss: draft.sl || undefined,
        takeProfit: draft.tp || undefined,
        book: draft.book || undefined,
      },
      { onSuccess: onDone },
    );
  };

  return (
    <div
      className="card shadow-e1 flex flex-col gap-2"
      role="group"
      aria-label={`Paper ticket for ${signal.exchange}:${signal.tradingsymbol}`}
    >
      <h3 className="flex items-center gap-1.5 text-h3 text-ay-text">
        Take (paper)
        <InfoTip
          text="A defined-risk paper ticket pre-filled from this signal — adjust quantity, stop-loss and target, then place a simulated trade into your paper book; never a live broker order."
          label="Take (paper)"
        />
      </h3>
      <div className="flex items-center justify-between text-xs text-ay-muted">
        <span className="truncate">
          {signal.exchange}:{signal.tradingsymbol}{' '}
          <span className={cn('font-semibold', signal.side === 'BUY' ? 'text-bull' : 'text-bear')}>
            {signal.side}
          </span>
        </span>
        <span className="tabular-nums">
          entry {signal.entryPrice ? formatDecimal(signal.entryPrice, 2) : 'mkt'}
        </span>
      </div>

      <div className="flex flex-col gap-1">
        <span className="text-xs text-ay-muted">Book</span>
        <Select
          value={draft.book}
          options={BOOK_OPTIONS}
          onChange={(v) => setDraft((d) => ({ ...d, book: v }))}
          ariaLabel="Paper book"
          title="Which paper book to log this trade into; default routes to the signal's own strategy family."
          className="h-8 w-full"
        />
      </div>

      <div className="grid grid-cols-3 gap-2">
        <label className="flex flex-col gap-1">
          <span className="text-xs text-ay-muted">Qty</span>
          <input
            type="number"
            min={1}
            value={draft.qty}
            onChange={(e) => setDraft((d) => ({ ...d, qty: e.target.value }))}
            aria-label="Qty"
            title="Number of units to trade (lots × lot size)"
            className={inputCls}
          />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-ay-muted">SL</span>
          <input
            value={draft.sl}
            onChange={(e) => setDraft((d) => ({ ...d, sl: e.target.value }))}
            aria-label="Stop loss"
            placeholder="none"
            title="Price at which the position auto-exits to cap your loss; blank for none"
            className={inputCls}
          />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-ay-muted">Target</span>
          <input
            value={draft.tp}
            onChange={(e) => setDraft((d) => ({ ...d, tp: e.target.value }))}
            aria-label="Take profit"
            placeholder="none"
            title="Price at which the position auto-exits to lock in your gain; blank for none"
            className={inputCls}
          />
        </label>
      </div>

      {scalperDetail && (
        <ManualVerifyChecklist detail={scalperDetail} onConfirmedChange={onConfirmedChange} />
      )}

      {/* Without this the ticket rendered ONLY isPending: a server refusal (422 stale-signal,
          lot-multiple, risk veto, or a non-ENTRY signal) left the button silently doing nothing —
          indistinguishable, to the owner, from a take that succeeded. Mirrors OrderPrefillTicket. */}
      {place.isError && (
        <p className="text-xs text-bear" role="alert">
          Couldn&apos;t place the paper order — it was refused. Check the quantity is a whole lot
          multiple and the signal is still fresh, then retry.
        </p>
      )}

      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={submit}
          disabled={place.isPending || !draft.qty || takeBlocked}
          className={cn(
            'h-8 flex-1 rounded-md px-3 text-xs font-semibold text-surface-0 hover:opacity-90 disabled:opacity-50',
            signal.side === 'BUY' ? 'bg-bull' : 'bg-bear',
          )}
        >
          {place.isPending ? '…' : `Place paper ${signal.side}`}
        </button>
        <button
          type="button"
          onClick={onDone}
          className="h-8 rounded-md border border-ay-border px-2 text-xs hover:border-accent"
        >
          Cancel
        </button>
      </div>
      <p className="text-xs text-ay-muted">Paper only — simulates the fill, never a live order.</p>
    </div>
  );
}
