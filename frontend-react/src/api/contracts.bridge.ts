// Compile-time drift alarms binding the hand-written wire types to contracts/gen (audit
// handwritten-dtos-unbound-to-contracts): every field the app READS must still exist on the
// springdoc-generated schema. A backend rename/remove on a typed endpoint now fails `tsc -b`
// instead of rendering "—" at runtime. Key-presence only, deliberately: springdoc types
// BigDecimal money as number while the wire (and our types, C-2.25) carries JSON strings, so a
// full structural assignability check would be red on every money field by design. Map-returning
// endpoints (signals list, backtest results, …) are not enumerated in the spec and stay
// runtime-verified — see the register entry for the accepted boundary.
//
// Zero runtime: type-only imports, nothing exported, never bundled (no module imports this file);
// `tsc -b` (the build script) still type-checks it because it sits inside the src include.

import type { components } from '../../../contracts/gen/strategy-signal-service';

type Schemas = components['schemas'];

/** Compiles only while every K is still a key of the generated Wire schema. */
type AssertKeys<Wire, K extends keyof Wire> = K;

// --- paper family (PaperPage / cockpit book) --------------------------------------------------
type _PositionDto = AssertKeys<
  Schemas['PositionDto'],
  | 'id'
  | 'exchange'
  | 'tradingsymbol'
  | 'side'
  | 'qty'
  | 'avgEntryPrice'
  | 'markPrice'
  | 'unrealizedPnl'
  | 'realizedPnl'
  | 'status'
  | 'openedAt'
  | 'stopLoss'
  | 'takeProfit'
>;
type _TradeDto = AssertKeys<
  Schemas['TradeDto'],
  | 'id'
  | 'exchange'
  | 'tradingsymbol'
  | 'side'
  | 'qty'
  | 'avgEntryPrice'
  | 'realizedPnl'
  | 'openedAt'
  | 'closedAt'
>;
type _AccountDto = AssertKeys<
  Schemas['AccountDto'],
  | 'startingCapital'
  | 'cash'
  | 'equity'
  | 'realized'
  | 'unrealized'
  | 'dayPnl'
  | 'openPositions'
  | 'capitalUsed'
  | 'usageByClass'
  | 'marginPercents'
>;

// --- live-broker funds (OrdersPage) -----------------------------------------------------------
type _Funds = AssertKeys<
  Schemas['Funds'],
  'status' | 'availableCash' | 'collateral' | 'm2mRealized' | 'm2mUnrealized' | 'utilisedDebits'
>;

// --- shadow-variant league (RejectionsPage strip / rollup) ------------------------------------
type _VariantSummary = AssertKeys<
  Schemas['VariantSummary'],
  'variant' | 'open' | 'closed' | 'wins' | 'losses' | 'pnlPoints' | 'pnlNet'
>;

// --- dot-health canary (09:42 agent / health surfaces) ----------------------------------------
type _DotHealth = AssertKeys<
  Schemas['DotHealth'],
  'asOf' | 'session' | 'rowsScanned' | 'rowsInspected' | 'dots'
>;
type _DotState = AssertKeys<Schemas['DotState'], 'dot' | 'alive' | 'required' | 'detail'>;

// --- insights decision-support (INT-I1 feed / Focus / explain drawer) --------------------------
type _Insight = AssertKeys<
  Schemas['Insight'],
  | 'id'
  | 'generatedAt'
  | 'type'
  | 'severity'
  | 'scope'
  | 'title'
  | 'explanation'
  | 'evidence'
  | 'priority'
  | 'priorityDetail'
  | 'dataTrust'
  | 'trustReasons'
  | 'suppressed'
  | 'status'
>;
type _InsightListResponse = AssertKeys<Schemas['InsightListResponse'], 'items' | 'limit' | 'offset'>;
type _FocusResponse = AssertKeys<Schemas['FocusResponse'], 'signalQueue' | 'attentionQueue' | 'suppressed'>;
type _InsightSummaryResponse = AssertKeys<
  Schemas['InsightSummaryResponse'],
  'bySeverity' | 'byStatus' | 'suppressed'
>;
type _InsightCount = AssertKeys<Schemas['Count'], 'key' | 'count'>;
type _TriageResponse = AssertKeys<Schemas['TriageResponse'], 'id' | 'status'>;

// keep the checked aliases "used" for lint without emitting anything
export type ContractBridges = [
  _PositionDto,
  _TradeDto,
  _AccountDto,
  _Funds,
  _VariantSummary,
  _DotHealth,
  _DotState,
  _Insight,
  _InsightListResponse,
  _FocusResponse,
  _InsightSummaryResponse,
  _InsightCount,
  _TriageResponse,
];
