// Broker order-read surface (§18.1 / Phase-4b). Read-only: the live broker's orderbook, net
// positions, tradebook and funds, served by strategy-signal-service under /api/v1/orders/** and
// routed through the gateway. Decimals arrive as JSON strings (the exact-decimal contract) — they are
// formatted via lib/decimal, never parseFloat. The default (DisabledOrderGateway) returns empty lists
// and a NOT_CONFIGURED funds row, so the page degrades to clean empty states with no broker wired.

import { useQuery } from '@tanstack/react-query';
import { apiFetch, listItems } from './client.ts';

/** One orderbook row — a placed order with its lifecycle status. */
export interface OrderbookEntry {
  symbol: string;
  exchange: string;
  action: string;
  qty: string;
  price: string;
  triggerPrice: string;
  pricetype: string;
  product: string;
  orderId: string;
  status: string;
  timestamp: string;
}

/** One net-position row. `qty` is signed; `side` is its derived BUY/SELL/FLAT. */
export interface PositionEntry {
  symbol: string;
  exchange: string;
  side: string;
  qty: string;
  product: string;
  avgPrice: string;
  ltp: string | null;
  mtmPnl: string | null;
}

/** One tradebook (fill) row. */
export interface TradebookEntry {
  symbol: string;
  exchange: string;
  action: string;
  qty: string;
  price: string;
  tradeValue: string;
  product: string;
  orderId: string;
  tradeTime: string;
}

/** The funds/margin snapshot. `status` is OK or NOT_CONFIGURED (no broker wired). */
export interface Funds {
  status: string;
  availableCash: string | null;
  collateral: string | null;
  m2mRealized: string | null;
  m2mUnrealized: string | null;
  utilisedDebits: string | null;
}

const ORDERS = 'orders';
const REFETCH_MS = 5000;

export function useOrderbook() {
  return useQuery({
    queryKey: [ORDERS, 'orderbook'],
    queryFn: async () => listItems(await apiFetch<{ items?: OrderbookEntry[] }>('/orders/orderbook')),
    refetchInterval: REFETCH_MS,
  });
}

export function usePositions() {
  return useQuery({
    queryKey: [ORDERS, 'positions'],
    queryFn: async () => listItems(await apiFetch<{ items?: PositionEntry[] }>('/orders/positions')),
    refetchInterval: REFETCH_MS,
  });
}

export function useTradebook() {
  return useQuery({
    queryKey: [ORDERS, 'tradebook'],
    queryFn: async () => listItems(await apiFetch<{ items?: TradebookEntry[] }>('/orders/tradebook')),
    refetchInterval: REFETCH_MS,
  });
}

export function useFunds() {
  return useQuery({
    queryKey: [ORDERS, 'funds'],
    queryFn: () => apiFetch<Funds>('/orders/funds'),
    refetchInterval: REFETCH_MS,
  });
}
