import { useCallback, useState } from 'react';
import { cn } from '../../lib/cn.ts';
import { formatDecimal } from '../../lib/decimal.ts';
import { useSignalDetail, type SignalDto } from '../../api/signals.ts';
import { usePlacePaperOrder } from '../../api/paper.ts';
import { ManualVerifyChecklist } from '../../components/ManualVerifyChecklist.tsx';

// The inline "Take (paper)" ticket for the Scalping Cockpit's signals feed (rendered when its row's
// trigger is open). DEFINED-RISK and prefilled from the signal — instrument · side · entry · SL ·
// target · suggested qty — submitted through the EXISTING paper engine: POST /paper/orders carrying the
// signal's id for provenance (usePlacePaperOrder). PAPER ONLY: it simulates the fill, never a live
// broker order. Scalper signals carry a manual-verify checklist (hydrated via GET /signals/{id}); the
// Place button stays disabled until the owner confirms, mirroring the /signals + /scalper gating.

/** Editable fields of the prefilled ticket; '' = market price / no SL-TP. */
interface Draft {
  qty: string;
  price: string;
  sl: string;
  tp: string;
}

function draftFrom(s: SignalDto): Draft {
  return {
    qty: s.suggestedQty ?? '1',
    price: s.entryPrice ?? '',
    sl: s.stopLoss ?? '',
    tp: s.target ?? '',
  };
}

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
      <h3 className="text-h3 text-ay-text">Take (paper)</h3>
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

      <div className="grid grid-cols-3 gap-2">
        <label className="flex flex-col gap-1">
          <span className="text-xs text-ay-muted">Qty</span>
          <input
            type="number"
            min={1}
            value={draft.qty}
            onChange={(e) => setDraft((d) => ({ ...d, qty: e.target.value }))}
            aria-label="Qty"
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
            className={inputCls}
          />
        </label>
      </div>

      {scalperDetail && (
        <ManualVerifyChecklist detail={scalperDetail} onConfirmedChange={onConfirmedChange} />
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
