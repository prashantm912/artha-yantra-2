// Compile-time drift alarms binding the hand-written wire types to contracts/gen (audit
// handwritten-dtos-unbound-to-contracts): every field the app READS must still exist on the
// springdoc-generated schema. A backend rename/remove on a typed endpoint now fails `tsc -b`
// instead of rendering "—" at runtime. Key-presence only, deliberately — but NOT for the money
// reason this comment used to give: PR #1203 retyped every response-reachable BigDecimal in
// TradeDto/AccountDto/PositionDto (strategy-signal) to `string`, so springdoc and our hand-written
// types (C-2.25) now agree on THOSE fields. #1203 did not sweep the whole service, though —
// `Insight.priority` was a BigDecimal-as-string field it never reached (fixed below). It stays
// key-presence-only because other divergences remain, measured 2026-08-02 by assigning each
// generated schema into (and out of) its hand-written counterpart under `tsc --strict` and reading
// the errors.
//
// Caveat on the method itself: a WIRE→HAND assignment failure is a real type incompatibility, but
// assignability is NOT exact-key equality — an optional hand-written field that the wire always
// sends passes silently in that direction too, so this catches type mismatches, not presence
// mismatches. Example: `SignalDto`'s `scalperDetail`/`expiresAt`/`suggestedQty`/
// `tradeableExchange`/`tradeableTradingsymbol`/`strategyVersionId` are optional in signals.ts but
// always-present on the generated REST RESPONSE — invisible to this check either way, not proof
// of exact equality. That "always-present" claim is scoped to the REST response, not "the wire"
// generally: `SignalDto` also models the live STOMP frame (signals.ts:11), and per
// signals.ts:155-158 the STOMP frame MAY omit `scalperDetail` — so that one field's optionality on
// the hand type is load-bearing for STOMP, not just slack against a REST guarantee it never needs.
//
// Within that caveat, the type-level divergences found: (1) several fields the app narrows to a TS
// string-literal union (`side`, `signalType`, `status`, `severity`, `dataTrust`, `op`, …) while
// springdoc emits a plain `string` — the transport DTOs don't declare Java enums for these; (2) the
// transport DTOs declare some fields as raw `JsonNode` (`SignalViews.java`, `Insight.java`), which
// springdoc can only type `unknown` — the app gives these a real shape it knows from what the DTO
// actually serializes, which OpenAPI *could* express if the backend exposed typed records instead
// of `JsonNode` (`scoreBreakdown`, `scalperDetail`, `diagnostic`, `evidence`, `priorityDetail`);
// and (3) a few hand-written optional fields (`Entry.signalId` & 3 siblings,
// `StrategySummary.currentVersion`) are typed `T | undefined` where the wire is `T | null` (always
// present, nullable) — a staleness bug in the hand types, not fixed here.
//
// `Insight.priority` (hand-typed `number | null` against a `string | null` wire) WAS a real bug
// and is fixed in this PR. `TradeDto.closedAt` looked like the same class (wire `string | null`,
// hand-written `string`) but investigation showed it isn't one: every write path that sets a
// position's status to CLOSED sets `closed_at` atomically in the same statement
// (`PaperPositionRepository.close()`), so a `TradeDto` row can never actually carry a null one —
// the wire's nullable annotation is the stale side here, a backend fix out of scope for this PR.
//
// Full findings + file:line evidence for every case: the `chore/contracts-bridge-tightening` PR
// description (2026-08-02 investigation) — tightening AssertKeys itself is a separate, follow-up
// change.
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
type _DotState = AssertKeys<
  Schemas['DotState'],
  'dot' | 'alive' | 'required' | 'frozen' | 'neverCrossing' | 'detail'
>;

// --- registry CRUD (D3: the whole surface was Map-returning, so the spec had no shape at all) ---
type _StrategyListItem = AssertKeys<
  Schemas['StrategyListItem'],
  | 'id'
  | 'slug'
  | 'name'
  | 'currentVersion'
  | 'publishedVersion'
  | 'currentVersionId'
  | 'publishedVersionId'
  | 'status'
  | 'tags'
  | 'author'
  | 'enabled'
  | 'notificationsEnabled'
  | 'notificationChannel'
  | 'updatedAt'
>;
type _StrategyListResponse = AssertKeys<
  Schemas['StrategyListResponse'],
  'items' | 'limit' | 'offset'
>;
type _StrategyDetail = AssertKeys<
  Schemas['StrategyDetail'],
  | 'id'
  | 'versionId'
  | 'publishedVersionId'
  | 'publishedVersion'
  | 'slug'
  | 'name'
  | 'description'
  | 'tags'
  | 'enabled'
  | 'version'
  | 'status'
  | 'config'
  | 'configYaml'
  | 'checksum'
  | 'notes'
  | 'createdAt'
  | 'updatedAt'
  | 'notificationsEnabled'
  | 'notificationChannel'
>;
type _DraftVersionResponse = AssertKeys<
  Schemas['DraftVersionResponse'],
  'id' | 'version' | 'status' | 'checksum'
>;
type _PublishResponse = AssertKeys<
  Schemas['PublishResponse'],
  'id' | 'version' | 'versionId' | 'status'
>;
type _RollbackResponse = AssertKeys<
  Schemas['RollbackResponse'],
  'id' | 'newVersion' | 'copiedFrom' | 'status'
>;
type _ArchiveResponse = AssertKeys<Schemas['ArchiveResponse'], 'id' | 'status'>;
type _NotificationsResponse = AssertKeys<
  Schemas['NotificationsResponse'],
  'id' | 'notificationsEnabled' | 'notificationChannel'
>;
type _VersionListItem = AssertKeys<
  Schemas['VersionListItem'],
  'versionId' | 'version' | 'status' | 'checksum' | 'author' | 'notes' | 'createdAt'
>;
type _VersionListResponse = AssertKeys<Schemas['VersionListResponse'], 'items'>;
type _DiffResponse = AssertKeys<Schemas['DiffResponse'], 'structured' | 'yamlFrom' | 'yamlTo'>;
type _ValidateResponse = AssertKeys<Schemas['ValidateResponse'], 'valid' | 'errors' | 'warnings'>;

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
  _StrategyListItem,
  _StrategyListResponse,
  _StrategyDetail,
  _DraftVersionResponse,
  _PublishResponse,
  _RollbackResponse,
  _ArchiveResponse,
  _NotificationsResponse,
  _VersionListItem,
  _VersionListResponse,
  _DiffResponse,
  _ValidateResponse,
  _Insight,
  _InsightListResponse,
  _FocusResponse,
  _InsightSummaryResponse,
  _InsightCount,
  _TriageResponse,
];
