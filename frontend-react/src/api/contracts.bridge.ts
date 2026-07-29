// Compile-time drift alarms binding the hand-written wire types to contracts/gen (audit
// handwritten-dtos-unbound-to-contracts): every field the app READS must still exist on the
// springdoc-generated schema. A backend rename/remove on a typed endpoint now fails `tsc -b`
// instead of rendering "—" at runtime. Key-presence only, deliberately: springdoc types
// BigDecimal money as number while the wire (and our types, C-2.25) carries JSON strings, so a
// full structural assignability check would be red on every money field by design.
//
// The Map-returning endpoints are being retyped to records (ledger D3 slice 1), and each one that
// lands moves out of "runtime-verified only" and into this file. Crossed over on 2026-07-29: the
// SIGNALS family (history, detail, take/dismiss, rejections, rail-counts), the PAPER read envelopes
// (positions/trades/pnl) and the JOURNAL list. What is still unbound is whatever remains a
// `Map<String, Object>` — `MapReturnRatchetTest` holds the authoritative per-service count.
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

// --- paper read envelopes (PaperPage tables + equity curve) -----------------------------------
type _PositionList = AssertKeys<Schemas['PositionList'], 'items'>;
type _TradePage = AssertKeys<Schemas['TradePage'], 'items' | 'limit' | 'offset'>;
type _Pnl = AssertKeys<Schemas['Pnl'], 'points' | 'summary'>;
type _EquityPoint = AssertKeys<Schemas['EquityPoint'], 'date' | 'equity'>;
type _PnlSummary = AssertKeys<
  Schemas['PnlSummary'],
  'realizedTotal' | 'trades' | 'winRate' | 'expectancy'
>;

// --- journal (JournalPage list + entry form) ---------------------------------------------------
type _JournalPage = AssertKeys<Schemas['JournalPage'], 'items'>;
type _JournalEntry = AssertKeys<
  Schemas['Entry'],
  | 'id'
  | 'signalId'
  | 'paperPositionId'
  | 'backtestRunId'
  | 'backtestTradeId'
  | 'note'
  | 'tags'
  | 'disciplineRating'
  | 'emotionRating'
  | 'createdAt'
  | 'updatedAt'
>;

// --- live-broker funds (OrdersPage) -----------------------------------------------------------
type _Funds = AssertKeys<
  Schemas['Funds'],
  'status' | 'availableCash' | 'collateral' | 'm2mRealized' | 'm2mUnrealized' | 'utilisedDebits'
>;

// --- signals family (SignalsPage history + detail drawer + take/dismiss) ----------------------
// Only the REST-carried keys. The app's `SignalDto` additionally declares `strategyName`,
// `strategyId`, `version`, `checksum` and `book`, which the STOMP `/topic/signals` frame supplies
// and the REST snapshot does not — asserting them here would fail against the true wire shape.
type _SignalDto = AssertKeys<
  Schemas['SignalDto'],
  | 'id'
  | 'strategyVersionId'
  | 'exchange'
  | 'tradingsymbol'
  | 'interval'
  | 'signalType'
  | 'side'
  | 'entryPrice'
  | 'stopLoss'
  | 'target'
  | 'compositeScore'
  | 'scoreBreakdown'
  | 'status'
  | 'generatedAt'
  | 'expiresAt'
  | 'suggestedQty'
  | 'tradeableExchange'
  | 'tradeableTradingsymbol'
  | 'scalperDetail'
>;
type _SignalPage = AssertKeys<Schemas['SignalPage'], 'items' | 'limit' | 'offset'>;

// --- rejection diagnostics (RejectionsPage table + rail rollup) --------------------------------
type _RejectionRow = AssertKeys<
  Schemas['RejectionRow'],
  | 'id'
  | 'strategyVersionId'
  | 'strategySlug'
  | 'exchange'
  | 'tradingsymbol'
  | 'interval'
  | 'side'
  | 'blockingRail'
  | 'blockingOperand'
  | 'blockingThreshold'
  | 'blockingMargin'
  | 'blockingReason'
  | 'compositeScore'
  | 'compositeThreshold'
  | 'diagnostic'
  | 'barTime'
  | 'generatedAt'
>;
type _RejectionPage = AssertKeys<Schemas['RejectionPage'], 'items'>;
type _RailCount = AssertKeys<Schemas['RailCount'], 'rail' | 'count'>;
type _RailCountList = AssertKeys<Schemas['RailCountList'], 'items'>;

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
type _DotState = AssertKeys<Schemas['DotState'], 'dot' | 'alive' | 'required' | 'frozen' | 'detail'>;

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
  _PositionList,
  _TradePage,
  _Pnl,
  _EquityPoint,
  _PnlSummary,
  _JournalPage,
  _JournalEntry,
  _SignalDto,
  _SignalPage,
  _RejectionRow,
  _RejectionPage,
  _RailCount,
  _RailCountList,
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
