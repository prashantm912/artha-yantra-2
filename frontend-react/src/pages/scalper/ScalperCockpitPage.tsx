import { useCallback, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { formatDecimal, isNegative, multiplyByInt, subtractDecimal } from '../../lib/decimal.ts';
import { cn } from '../../lib/cn.ts';
import { Select } from '../../components/atoms/Select.tsx';
import { useSignalDetail, useSignals, useSignalsLive, type SignalDto } from '../../api/signals.ts';
import { ManualVerifyChecklist } from '../../components/ManualVerifyChecklist.tsx';
import {
  useClosePosition,
  usePaperAccount,
  usePaperPositions,
  usePlacePaperOrder,
  type PaperPosition,
} from '../../api/paper.ts';
import { useExpiries, useUnderlyings } from '../../api/instruments.ts';
import { optionExchange, useOptionChain, type ChainLeg } from '../../api/scalper.ts';
import { useLiveTicks } from '../../api/ticks.ts';
import { PageHeader } from '../../components/PageHeader.tsx';

// /scalper (master plan §20 / Phase 4b): the scalper cockpit — live ACTIVE signal feed (left), a
// pre-filled PAPER order ticket (middle; click a signal to load it, or enter an instrument manually),
// and open positions + account P&L (right, live-refetched). Paper only — POST /paper/orders simulates
// the fill; this never places a live broker order.
// Revamp rollout (Trading screens): the sr-only h1 becomes the visible signature PageHeader (text
// preserved). The dense 3-column live ticket layout is deliberately NOT restructured — every panel
// keeps its bespoke live-state copy, WS ticks and the paper-order ticket wiring.

const money = (v: string) => formatDecimal(v, 2);
const tone = (v: string) => (isNegative(v) ? 'text-bear' : 'text-bull');

interface Ticket {
  signalId: number | null;
  instrument: string;
  side: 'BUY' | 'SELL';
  qty: string;
  price: string; // '' = market
  sl: string; // '' = none
  tp: string; // '' = none
}

const EMPTY: Ticket = { signalId: null, instrument: '', side: 'BUY', qty: '1', price: '', sl: '', tp: '' };

export function ScalperCockpitPage() {
  const signals = useSignals('ACTIVE');
  useSignalsLive('ACTIVE');
  const positions = usePaperPositions();
  const account = usePaperAccount();
  const place = usePlacePaperOrder();
  const close = useClosePosition();

  const [ticket, setTicket] = useState<Ticket>(EMPTY);

  // Hydrate the ticketed signal's scalper detail (the live frame may omit it) → manual-verify checklist.
  const detail = useSignalDetail(ticket.signalId);
  const scalperDetail = detail.data?.scalperDetail ?? null;
  const [confirmed, setConfirmed] = useState(false);
  const onConfirmedChange = useCallback((v: boolean) => setConfirmed(v), []);
  const placeBlocked = scalperDetail != null && !confirmed;

  // option quick-pick (strike ladder → fills the ticket)
  const [qpOpen, setQpOpen] = useState(false);
  const underlyings = useUnderlyings();
  const [underlying, setUnderlying] = useState('NIFTY 50');
  const expiries = useExpiries(qpOpen ? underlying : '');
  const [expiry, setExpiry] = useState<string | null>(null);
  const chain = useOptionChain(underlying, expiry, qpOpen);

  // live ticks (WS bridge) for the open positions + the ticketed instrument
  const positionSymbols = useMemo(
    () => (positions.data?.items ?? []).map((p) => `${p.exchange}:${p.tradingsymbol}`),
    [positions.data],
  );
  const live = useLiveTicks([...positionSymbols, ticket.instrument]);
  const ticketLtp = live[ticket.instrument] ?? null;
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

  const feed = useMemo(() => signals.data?.items ?? [], [signals.data]);
  const acct = account.data ?? null;

  const pickLeg = (leg: ChainLeg) => {
    if (!leg.tradingsymbol) return;
    setTicket({
      signalId: null,
      instrument: `${optionExchange(underlying)}:${leg.tradingsymbol}`,
      side: 'BUY',
      qty: ticket.qty || '1',
      price: leg.ltp ?? '',
      sl: '',
      tp: '',
    });
  };
  const inputCls = 'h-9 w-full rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text';

  const loadSignal = (s: SignalDto) =>
    setTicket({
      signalId: s.id,
      instrument: `${s.exchange}:${s.tradingsymbol}`,
      side: s.side,
      qty: s.suggestedQty ?? '1',
      price: s.entryPrice ?? '',
      sl: s.stopLoss ?? '',
      tp: s.target ?? '',
    });

  const placeOrder = () => {
    const sep = ticket.instrument.indexOf(':');
    if (sep < 0 || !ticket.qty) return;
    place.mutate(
      {
        signalId: ticket.signalId ?? undefined,
        exchange: ticket.instrument.slice(0, sep),
        tradingsymbol: ticket.instrument.slice(sep + 1),
        side: ticket.side,
        qty: Number(ticket.qty),
        price: ticket.price || undefined,
        stopLoss: ticket.sl || undefined,
        takeProfit: ticket.tp || undefined,
      },
      { onSuccess: () => setTicket((t) => ({ ...t, signalId: null })) },
    );
  };

  return (
    <div>
      <PageHeader title="Scalper cockpit" subtitle="Live signals · pre-filled paper order ticket · open positions & P&L — paper only, never a live broker order" />
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[1fr_1fr_1.2fr]">
        {/* Live signals */}
        <section className="min-w-0">
          <h2 className="mb-2 text-sm font-semibold">Live signals</h2>
          <div className="max-h-[calc(100vh-12rem)] overflow-auto rounded-lg border border-ay-border">
            {feed.length > 0 ? (
              <ul className="divide-y divide-ay-border">
                {feed.map((s) => (
                  <li key={s.id}>
                    <button
                      type="button"
                      onClick={() => loadSignal(s)}
                      className={cn(
                        'grid w-full grid-cols-[3.2rem_1fr_auto] items-center gap-2 px-2 py-2 text-left text-sm hover:bg-surface-2',
                        ticket.signalId === s.id && 'bg-surface-2',
                      )}
                    >
                      <span className="tabular-nums text-ay-muted">{s.generatedAt.slice(11, 16)}</span>
                      <span className="truncate">{s.exchange}:{s.tradingsymbol}</span>
                      <span className={cn('text-xs font-semibold', s.side === 'BUY' ? 'text-bull' : 'text-bear')}>
                        {s.signalType} {s.side}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="p-3 text-sm text-ay-muted">No live signals — publish a strategy.</p>
            )}
          </div>
        </section>

        {/* Order ticket (paper) */}
        <section className="min-w-0">
          <div className="mb-2 flex items-center justify-between">
            <h2 className="text-sm font-semibold">
              Paper order ticket {ticket.signalId != null && <span className="text-xs text-accent">· signal #{ticket.signalId}</span>}
            </h2>
            <button type="button" onClick={() => setQpOpen((v) => !v)} className="text-xs text-accent hover:underline">
              {qpOpen ? 'Hide chain ▲' : 'Option quick-pick ▾'}
            </button>
          </div>

          {qpOpen && (
            <div className="mb-3 rounded-lg border border-ay-border bg-surface-1 p-2">
              <div className="mb-2 flex flex-wrap items-center gap-2">
                <Select value={underlying} options={underlyings.data ?? [underlying]} onChange={(v) => { setUnderlying(v); setExpiry(null); }} ariaLabel="Underlying" />
                <Select value={expiry} options={expiries.data ?? []} onChange={(v) => setExpiry(v || null)} ariaLabel="Expiry" placeholder="nearest" />
                {chain.data?.spot && <span className="text-xs text-ay-muted">spot {money(chain.data.spot)}</span>}
              </div>
              <div className="max-h-56 overflow-auto rounded border border-ay-border">
                <table className="w-full border-collapse text-xs">
                  <thead className="sticky top-0 bg-surface-2 text-ay-muted">
                    <tr>
                      <th className="px-2 py-1 text-left font-medium">CE ltp</th>
                      <th className="px-2 py-1 text-center font-medium">Strike</th>
                      <th className="px-2 py-1 text-right font-medium">PE ltp</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(chain.data?.rows ?? []).map((r) => (
                      <tr key={r.strike} className="border-t border-ay-border">
                        <td className="px-1 py-0.5">
                          <button type="button" onClick={() => pickLeg(r.ce)} className="w-full rounded px-1 py-0.5 text-left tabular-nums text-bull hover:bg-surface-2">
                            {r.ce.ltp ? money(r.ce.ltp) : '—'}
                          </button>
                        </td>
                        <td className="px-2 py-0.5 text-center tabular-nums font-semibold">{money(r.strike)}</td>
                        <td className="px-1 py-0.5">
                          <button type="button" onClick={() => pickLeg(r.pe)} className="w-full rounded px-1 py-0.5 text-right tabular-nums text-bear hover:bg-surface-2">
                            {r.pe.ltp ? money(r.pe.ltp) : '—'}
                          </button>
                        </td>
                      </tr>
                    ))}
                    {(chain.data?.rows ?? []).length === 0 && (
                      <tr><td colSpan={3} className="px-2 py-3 text-center text-ay-muted">{chain.isLoading ? 'Loading chain…' : 'No chain.'}</td></tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          <div className="flex flex-col gap-3 rounded-lg border border-ay-border bg-surface-1 p-3">
            <label className="flex flex-col gap-1">
              <span className="flex items-center justify-between text-xs text-ay-muted">
                <span>Instrument (EXCHANGE:SYMBOL)</span>
                {ticketLtp && (
                  <span className="tabular-nums text-ay-text">
                    LTP {money(ticketLtp)}{' '}
                    <button type="button" onClick={() => setTicket((t) => ({ ...t, price: ticketLtp }))} className="text-accent hover:underline">
                      use
                    </button>
                  </span>
                )}
              </span>
              <input
                value={ticket.instrument}
                onChange={(e) => setTicket((t) => ({ ...t, instrument: e.target.value, signalId: null }))}
                placeholder="NSE:RELIANCE"
                aria-label="Instrument"
                className={inputCls}
              />
            </label>
            <div className="grid grid-cols-3 gap-2">
              <div className="flex flex-col gap-1">
                <span className="text-xs text-ay-muted">Side</span>
                <Select
                  value={ticket.side}
                  options={['BUY', 'SELL']}
                  onChange={(v) => setTicket((t) => ({ ...t, side: v as 'BUY' | 'SELL' }))}
                  ariaLabel="Side"
                />
              </div>
              <label className="flex flex-col gap-1">
                <span className="text-xs text-ay-muted">Qty</span>
                <input type="number" min={1} value={ticket.qty} onChange={(e) => setTicket((t) => ({ ...t, qty: e.target.value }))} aria-label="Qty" className={inputCls} />
              </label>
              <label className="flex flex-col gap-1">
                <span className="text-xs text-ay-muted">Price (blank=mkt)</span>
                <input value={ticket.price} onChange={(e) => setTicket((t) => ({ ...t, price: e.target.value }))} aria-label="Price" className={inputCls} />
              </label>
            </div>
            <div className="grid grid-cols-2 gap-2">
              <label className="flex flex-col gap-1">
                <span className="text-xs text-ay-muted">Stop loss (auto-exit)</span>
                <input value={ticket.sl} onChange={(e) => setTicket((t) => ({ ...t, sl: e.target.value }))} aria-label="Stop loss" placeholder="none" className={inputCls} />
              </label>
              <label className="flex flex-col gap-1">
                <span className="text-xs text-ay-muted">Take profit (auto-exit)</span>
                <input value={ticket.tp} onChange={(e) => setTicket((t) => ({ ...t, tp: e.target.value }))} aria-label="Take profit" placeholder="none" className={inputCls} />
              </label>
            </div>
            {scalperDetail && (
              <ManualVerifyChecklist detail={scalperDetail} onConfirmedChange={onConfirmedChange} />
            )}
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={placeOrder}
                disabled={place.isPending || ticket.instrument.indexOf(':') < 0 || !ticket.qty || placeBlocked}
                className={cn(
                  'h-9 flex-1 rounded-md px-4 text-sm font-semibold text-surface-0 hover:opacity-90 disabled:opacity-50',
                  ticket.side === 'BUY' ? 'bg-bull' : 'bg-bear',
                )}
              >
                {place.isPending ? '…' : `Place paper ${ticket.side}`}
              </button>
              <button
                type="button"
                onClick={() => setTicket(EMPTY)}
                className="h-9 rounded-md border border-ay-border px-3 text-sm hover:border-accent"
              >
                Clear
              </button>
            </div>
            <p className="text-xs text-ay-muted">Paper only — simulates the fill, never a live broker order.</p>
          </div>
        </section>

        {/* Positions + P&L */}
        <section className="min-w-0">
          <div className="mb-2 flex items-center justify-between">
            <h2 className="text-sm font-semibold">Positions & P&L</h2>
            <Link to="/paper" className="text-xs text-accent hover:underline">
              Ledger →
            </Link>
          </div>
          {acct && (
            <div className="mb-3 flex flex-wrap gap-x-5 gap-y-1 rounded-lg border border-ay-border bg-surface-1 p-2 text-sm">
              <span><span className="text-ay-muted">Equity </span><span className="font-semibold tabular-nums">{money(acct.equity)}</span></span>
              <span><span className="text-ay-muted">Day P&L </span><span className={cn('font-semibold tabular-nums', tone(acct.dayPnl))}>{money(acct.dayPnl)}</span></span>
              <span><span className="text-ay-muted">Open </span><span className="font-semibold tabular-nums">{positions.data?.items.length ?? 0}</span></span>
            </div>
          )}
          <div className="max-h-[calc(100vh-16rem)] overflow-auto rounded-lg border border-ay-border">
            <table className="w-full border-collapse text-sm">
              <thead className="sticky top-0 bg-surface-1 text-left text-xs uppercase text-ay-muted">
                <tr>
                  <th className="px-2 py-2 font-medium">Instrument</th>
                  <th className="px-2 py-2 font-medium">Side</th>
                  <th className="px-2 py-2 text-right font-medium">Qty</th>
                  <th className="px-2 py-2 text-right font-medium">Mark</th>
                  <th className="px-2 py-2 text-right font-medium">uP&L</th>
                  <th className="px-2 py-2 text-right font-medium">SL / TP</th>
                  <th className="px-2 py-2"><span className="ay-sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                {(positions.data?.items ?? []).map((p) => {
                  const m = mtm(p);
                  return (
                  <tr key={p.id} className="border-t border-ay-border">
                    <td className="px-2 py-2">{p.exchange}:{p.tradingsymbol}</td>
                    <td className="px-2 py-2">
                      <span className={cn('text-xs font-semibold', p.side === 'BUY' ? 'text-bull' : 'text-bear')}>{p.side}</span>
                    </td>
                    <td className="px-2 py-2 text-right tabular-nums">{p.qty}</td>
                    <td className="px-2 py-2 text-right tabular-nums">{m.mark ? money(m.mark) : '—'}</td>
                    <td className={cn('px-2 py-2 text-right tabular-nums', m.unrealized && tone(m.unrealized))}>
                      {m.unrealized ? money(m.unrealized) : '—'}
                    </td>
                    <td className="px-2 py-2 text-right tabular-nums text-xs text-ay-muted">
                      {p.stopLoss ? money(p.stopLoss) : '—'} / {p.takeProfit ? money(p.takeProfit) : '—'}
                    </td>
                    <td className="px-2 py-2 text-right">
                      <button type="button" onClick={() => close.mutate({ id: p.id })} className="px-1.5 text-xs text-accent hover:underline">
                        Close
                      </button>
                    </td>
                  </tr>
                  );
                })}
                {(positions.data?.items ?? []).length === 0 && (
                  <tr>
                    <td colSpan={7} className="px-2 py-6 text-center text-ay-muted">No open positions.</td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </section>
      </div>
    </div>
  );
}
