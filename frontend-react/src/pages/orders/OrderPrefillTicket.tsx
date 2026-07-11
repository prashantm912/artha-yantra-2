import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { cn } from '../../lib/cn.ts';
import { usePlacePaperOrder } from '../../api/paper.ts';

// §18.4 / §18.1 pre-fill order ticket. A scalp-alert phone push deep-links here
// (`/orders?sig=&symbol=&side=&qty=…`), landing the owner on a paper ticket already filled from the
// alert. Additive + self-contained: it renders ONLY when the URL carries prefill params, so the plain
// /orders read surface is byte-unchanged when reached from the nav. PAPER ONLY — it places through the
// EXISTING paper engine (usePlacePaperOrder), carrying the signal id for provenance / option-leg
// resolution; never a live broker order. The full scalper manual-verify checklist stays on /signals +
// /scalper; this is the away-from-desk quick-log path the owner reaches from a push.

interface Prefill {
  signalId?: number;
  exchange: string;
  tradingsymbol: string;
  side: 'BUY' | 'SELL';
  qty: string;
  book?: string;
  sl?: string;
  tp?: string;
  price?: string;
}

/** Parses the deep-link params into a Prefill, or null when the essential ones (symbol + side) are absent. */
function parsePrefill(params: URLSearchParams): Prefill | null {
  const symbol = params.get('symbol');
  const side = params.get('side');
  if (!symbol || (side !== 'BUY' && side !== 'SELL')) return null;
  const colon = symbol.indexOf(':');
  const exchange = colon > 0 ? symbol.slice(0, colon) : '';
  const tradingsymbol = colon > 0 ? symbol.slice(colon + 1) : symbol;
  if (!exchange || !tradingsymbol) return null;
  const sig = params.get('sig');
  return {
    signalId: sig && /^\d+$/.test(sig) ? Number(sig) : undefined,
    exchange,
    tradingsymbol,
    side,
    qty: params.get('qty') ?? '1',
    book: params.get('book') ?? undefined,
    sl: params.get('sl') ?? undefined,
    tp: params.get('tp') ?? undefined,
    price: params.get('price') ?? undefined,
  };
}

const inputCls =
  'h-8 w-full rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text';

/** Thin wrapper: parse the URL, render the ticket only when prefill params are present (else nothing). */
export function OrderPrefillTicket() {
  const [params] = useSearchParams();
  const prefill = parsePrefill(params);
  if (!prefill) return null;
  // A fresh prefill (different deep link) re-mounts the form so its inputs re-seed from the new params.
  return (
    <PrefillForm
      key={`${prefill.signalId ?? ''}:${prefill.exchange}:${prefill.tradingsymbol}:${prefill.side}`}
      prefill={prefill}
    />
  );
}

/** The editable paper ticket seeded from the deep link; Place posts through the existing paper engine. */
function PrefillForm({ prefill }: { prefill: Prefill }) {
  const [, setParams] = useSearchParams();
  const place = usePlacePaperOrder();
  const [qty, setQty] = useState(prefill.qty);
  const [sl, setSl] = useState(prefill.sl ?? '');
  const [tp, setTp] = useState(prefill.tp ?? '');
  const [placed, setPlaced] = useState(false);

  // Clearing the params drops the ticket so a refresh/back doesn't re-open a stale one.
  const clear = () => setParams(new URLSearchParams(), { replace: true });

  const submit = () => {
    if (!qty || Number(qty) <= 0) return;
    place.mutate(
      {
        signalId: prefill.signalId,
        exchange: prefill.exchange,
        tradingsymbol: prefill.tradingsymbol,
        side: prefill.side,
        qty: Number(qty),
        price: prefill.price || undefined,
        stopLoss: sl || undefined,
        takeProfit: tp || undefined,
        book: prefill.book,
      },
      { onSuccess: () => setPlaced(true) },
    );
  };

  const sym = `${prefill.exchange}:${prefill.tradingsymbol}`;

  if (placed) {
    return (
      <section
        className="card shadow-e1 mb-6 flex items-center justify-between gap-3"
        role="status"
        aria-label="Paper order placed"
      >
        <span className="text-sm text-ay-text">
          Paper order placed — {prefill.side} {qty} {sym}.
        </span>
        <button
          type="button"
          onClick={clear}
          className="h-8 rounded-md border border-ay-border px-3 text-xs hover:border-accent"
        >
          Dismiss
        </button>
      </section>
    );
  }

  return (
    <section
      className="card shadow-e1 mb-6 flex flex-col gap-2"
      role="group"
      aria-label={`Paper ticket for ${sym}`}
    >
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold text-ay-text">Pre-filled paper ticket</h2>
        <span className="text-caption uppercase tracking-wide text-ay-muted">from alert</span>
      </div>
      <div className="flex items-center justify-between text-xs text-ay-muted">
        <span className="truncate">
          {sym}{' '}
          <span className={cn('font-semibold', prefill.side === 'BUY' ? 'text-bull' : 'text-bear')}>
            {prefill.side}
          </span>
          {prefill.book ? <span className="ml-1">· {prefill.book}</span> : null}
        </span>
      </div>

      <div className="grid grid-cols-3 gap-2">
        <label className="flex flex-col gap-1">
          <span className="text-xs text-ay-muted">Qty</span>
          <input
            type="number"
            min={1}
            value={qty}
            onChange={(e) => setQty(e.target.value)}
            aria-label="Qty"
            title="Number of units to trade (lots × lot size)"
            className={inputCls}
          />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-ay-muted">SL</span>
          <input
            value={sl}
            onChange={(e) => setSl(e.target.value)}
            aria-label="Stop loss"
            placeholder="none"
            title="Price at which the position auto-exits to cap your loss; blank for none"
            className={inputCls}
          />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-ay-muted">Target</span>
          <input
            value={tp}
            onChange={(e) => setTp(e.target.value)}
            aria-label="Take profit"
            placeholder="none"
            title="Price at which the position auto-exits to lock in your gain; blank for none"
            className={inputCls}
          />
        </label>
      </div>

      {place.isError && (
        <p className="text-xs text-bear" role="alert">
          Couldn't place the paper order — check the quantity is a whole lot multiple, then retry.
        </p>
      )}

      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={submit}
          disabled={place.isPending || !qty}
          className={cn(
            'h-8 flex-1 rounded-md px-3 text-xs font-semibold text-surface-0 hover:opacity-90 disabled:opacity-50',
            prefill.side === 'BUY' ? 'bg-bull' : 'bg-bear',
          )}
        >
          {place.isPending ? '…' : `Place paper ${prefill.side}`}
        </button>
        <button
          type="button"
          onClick={clear}
          className="h-8 rounded-md border border-ay-border px-2 text-xs hover:border-accent"
        >
          Dismiss
        </button>
      </div>
      <p className="text-xs text-ay-muted">Paper only — simulates the fill, never a live order.</p>
    </section>
  );
}
