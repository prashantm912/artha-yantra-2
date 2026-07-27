> **ARCHIVED 2026-07-28 — SWEEP CLOSED 2026-07-25.** All 4 slices + both carve-outs DONE (#996, #999-#1003, #1005, #1008, #1034). Residual BuzzMatrix.cells is a documented permanent limitation, not an open item. Method + constraints kept for the next contract sweep.

# Nullable-contract sweep (chip task_79d12a4d) — evidence + slice plan (2026-07-25)

Since `RecordRequiredModelConverter` (task_ade97df8) every response-record component is
`required` in the published specs. `required` is honest (Jackson always writes the key), but a
component whose VALUE can be null at runtime while the spec claims plain `"type": "number"` is a
contract lie. #996 fixed 7 fields (stopLoss/target/riskReward on TicketPrefill/OpeningSignal/
CompareColumn) and built the machinery: `@Schema(types = {"X", "null"})` (the ONLY working
spelling at OpenAPI 3.1 — swagger-core 2.2.30 silently drops `nullable=true`) + the
`openapi_relabel_30.py` lossless `[X,"null"]` → `nullable: true` downgrade for the breaking gate.
This doc carries the platform-wide inventory (two read-only audit agents, 2026-07-25, verdicts
computed from construction call sites) and the slice plan.

## Verified constraints (checked against real specs 2026-07-25)

1. **`@JsonInclude(NON_NULL)` components need NOTHING.** The converter already excludes them from
   `required` (checked: ActResponse/JournalDraft have no required array; every `freshness`
   envelope component is optional). Key-absent ≠ value-null; the spec is already honest there.
2. **`$ref`-typed nullable components CANNOT be annotated.** The relabel script refuses
   `$ref`-with-siblings, and 3.0 has no `type: "null"`, so `anyOf[$ref,{type:null}]` fails the
   gate too. Skip record-typed and JsonNode-typed components (JsonNode's `{}` schema validates
   null anyway — arguably not a lie). Affected (documented, not fixed): OrderLeg.fees,
   PositionDetail.openingSignal, OpeningSignal.*Detail JsonNodes, ActResponse.ticket/.journal,
   ChainTableLeg.deltas, every `freshness` (already honest per #1), Insight.priorityDetail.
3. **Duplicate simple names collapse to ONE spec schema** — annotate ALL twins identically or the
   spec depends on scan order. strategy-signal: Criterion ×3, Thresholds ×2, StrategyGraduation ×2
   (GraduationController + GraduationService), RailCount ×2, GraduationBoard ×2. market-data:
   Status ×4 (backfill services), Heatmap ×2, Row ×3, TrendPoint ×2, StrikeMove ×2,
   Report/SetupStat/YearReturn/SlotCell/PortfolioStat/BacktestResult (minervini vs manas twins),
   ExchangeResult ×2, CanaryResult ×3, PremiumRow/Setup/Candle collisions.
   **Update (slice 3f, task_1c04803f): the twins whose field sets DIFFER are no longer collapsed** —
   `@Schema(name = "...")` gives each its own component key (springdoc honours it for the key AND
   every `$ref`; the `required` converter follows since it maps off the resolved `$ref` name). Fixed:
   Row, ScreenResponse, CandidateAnalysis, FunnelRow, Funnel, BacktestResult, backfill Status ×4,
   ExchangeResult ×2, TrendPoint ×2. ⚠️ **`ExchangeResult` was mis-listed above as field-identical and was in fact the
   WORST case in the whole inventory** (caught by the cross-vendor review, not by this doc's own
   audit): `KiteSessionService.ExchangeResult(connected, kiteUserId, tokenValidUntil)` vs
   `BhavcopyBackfillService.ExchangeResult(days, bhavRows, candleRows)` — ZERO fields in common, and
   the bhavcopy twin won the scan, so `POST /api/v1/auth/kite/session` published three bhavcopy int
   counters and none of its own three fields. Now `KiteSessionExchangeResult` / `BhavcopyExchangeResult`.
   Still collapsed BY DESIGN (genuinely field-identical, annotated identically, so the collapse is
   harmless): Report, SetupStat, YearReturn, SlotCell, PortfolioStat — leave them alone unless one
   side's fields diverge, at which point they need the same treatment. **Lesson: "field-identical" must
   be verified field-by-field against BOTH declarations, not assumed from a shared name + shared
   neighbourhood.**
4. **Request-body records are OUT of scope** — the converter applies `required` to responses only;
   annotating request records changes client-facing semantics. (Inventory kept below for a
   possible later pass.)
5. **Framework leaks, not annotatable:** edge-gateway `WebSession` — **FIXED (task_98984789)**:
   springdoc does not recognise `WebSession` as an injected WebFlux type (it does recognise
   `ServerWebExchange`), so it published the `logout(WebSession)` argument as a **required QUERY
   parameter** carrying a serialized session object — a request shape the gateway never accepted
   (the FE has always called `POST /auth/logout` with no params, `session.store.ts:67`).
   `@Parameter(hidden = true)` removes both the param and the schema. ⚠️ The breaking gate reports
   this as incompatible (`Delete session in query` — openapi-diff cannot know the param was
   fiction), so it ships with the `Contract break: APPROVED` line; that is the escape hatch working
   as designed, not a bypass. **`PageInstrument` was a FALSE ALARM** — it is NOT Spring Data's
   `Page`; it is the repo's own generic record `Page<T>(items,total,limit,offset)`
   (`InstrumentsController.java:26`), and `PageInstrument` is just springdoc's name for the
   generic instantiation. The schema is clean and correctly `required`-ed. No action.

## Slices

- **3a (this branch): strategy-signal** — full YES table below + TradeDto.closedAt (UNSURE →
  annotate, cheap) + FE fix `graduation.ts:53-55` (Promotion.expectancy/sharpe/maxDrawdownPct
  typed non-null while V024 columns are nullable — the one hard FE mismatch).
- **3b: backtest-service + edge-gateway** — SHIPPED (see the slice PR): CompareMetrics (all 10),
  ExperimentSummary (9), ExperimentCompareRun (8), ProvenanceBlock (12 of 13 — direct read of
  JobsController.submissionProvenance corrected the agent's "11"; `profile` is the only
  always-set component), RunComparability (4), DatasetEpoch (8 — V015 DDL: symbols/exchange/
  window_start/window_end/interval/job_link/source/note all nullable), Trace
  (maxComposite/sampleBucket; sampleBreakdown = JsonNode, skip), JobAnnotationResponse.note.
  **UNSUREs resolved NO (DDL evidence):** CounterfactualResult.windowFrom/To/ranAt (V013
  NOT NULL), SavedView.createdAt (NOT NULL DEFAULT now()), JobAnnotationResponse.tags (V020
  `TEXT[] NOT NULL DEFAULT '{}'`). **ErrorResponse: NO CHANGE** — the compact constructor
  normalizes a null details map to Map.of() (never null on the wire); message's null-capability
  is unproven (only a test exercises a raw null) and annotating it would force re-capturing all
  four specs — revisit only with production evidence. **edge-gateway: nothing annotatable**; the
  `WebSession` framework-type leak (AuthController.logout parameter) is a separate spec-hygiene
  fix, deliberately not bundled here. No FE counterparts (experiments surface unconsumed by FE).
- **3c: market-data, OI/futures/market-surface core — SHIPPED (see the slice PR):** Leg (20:
  ltp/bid/ask/volume/oi/prevOi/iv/9 greeks incl. 2nd+3rd order/ivReason/priceSource), Chain +
  ChainTable spot/forward/pcr, OiStats pcr/maxPain, ActiveStrikesResponse.sentimentPct, LegDeltas
  (4 of 5 — `interpretation` skipped here pending the enum probe; **resolved in 3e: it is NOT
  nullable, see below**), PcrSeriesPoint, Spurt family (SpurtRow/StrikeSpurt/
  SpurtSummary/SpurtChain.asOf), BigOi + BigOiLog families, Heatmap Cell.value/maxAbs/asOf,
  Trend + Premium families, StraddleChart, CalendarSpreadChart, OpenHighStats (2 records),
  ActiveStrike SentimentPoint/ActiveStrikeIvPoint, TermStructure + ContractLeg, FutSpurt(Chain),
  Movers/MoverRow/Banks/BankRow, BankGrid(Row), Dow/VixQuote, Candle.oi, MarginResponse (9),
  Instrument (11 derivative-optional fields). **DataFreshness needs NOTHING** (class-level
  NON_NULL — converter already excludes all members; the audit's "members" rows are moot). FE:
  types.ts SpurtSummary fixed (spotDelta `| null`, added missing oiChangePct/priceChangePct) +
  OptionsSpurtPage null-guard.
- **3d: market-data remainder — SHIPPED (see the slice PR; closes the sweep):** 184 components
  across 63 schemas, three read-only investigator tables (context digests / equity+minervini /
  ops-status, 2026-07-25), every YES evidence-backed by construction call site or nullable DDL.
  Context digests: the blocked() shells make every digest's `asOf` (+ EquityDigest.tradeDate/
  returnsWindow) nullable; sub-records per their own folds (Pcr ×5, MaxPain.atOpen/drift,
  Straddle ×4, AtmIv, AlignedPoint ×6, UnderlyingQuadrant ltp/pricePct/oiPct/interpretation —
  a String, `.name()` upstream, NOT the enum — TermStructureState spot/calendarSpread/nearBasis,
  AdvanceDecline ratios, AboveMa pcts, BreadthThrust.advRatioMa, SectorRotation rankPrior/Delta,
  IndexConcentration, DiiDivergence, FiiFuturesLongShort, ParticipantPositioning,
  FiiDerivativeAvailability, Vix change/changePct/asOf, GlobalCue name/ltp (wire-defensive),
  IndexPriceAction gapOpenPct/rangeVsAvg/rangeState, HolidayProximity ×3, SourceTrust lastRun*).
  Equity: ReturnsRow r1d..r1y, BreadthDay (V044 DDL), BreadthSummary/DeliveryRow, StockChange,
  SectorAgg.avgChangePct, SectorIndexCard ×7 (`asText(null)`), DeliveryDay ×10 (V014 DDL),
  ContribRow.points + IndexContribution indexLevel/advancePoints/declinePoints, ScanRow ×5
  (V028), NewsItem ×5 (Upstox wire), EquityFundamentals ×11 (V032). Minervini/Manas: Geometry
  ×10, SetupView ×4, Regime.advanceRatio, HorizonStat ×6, HitRateReport.from/to, and the
  IDENTICAL twins annotated in pairs — Report (variant/fromDate/runAt/note), ScreenResponse
  (screenDate), Funnel (screenDate). Ops: LastRun rowsWritten/finishedAt/error + BoardReport
  fromDay/toDay (V040), CrossSourceOiCanary UnderlyingDivergence ×3, KiteStatus ×4,
  DeepSwingRunResponse engineSha/engineImage, SyncStatus ×3, BackfillJobRow ×4 (V030),
  ExportContract.strike (V025 "NULL for FUT"), CompletenessRow ×2 + CompletenessReport.date
  (V047), BhavcopyCloseReport.tradeDate, CheckResult.since, NormalizedTick.openInterest,
  ScreenDiff dates + DiffRow.rsRank (V031/V038).
  **UNSUREs resolved NO:** CandlesResponse.asOf (sole construction `now(clock)`),
  WarmStatus (all — throws/defaults/constants), MaxPain.now (fold only on non-empty buckets),
  IndexPriceAction.avgRange20 + CoverageRow.minExpiry/maxExpiry (defensive branches dead —
  DDL NOT NULL), CloseMismatch (SQL-filtered), CanaryReport, Movers OHLC (already 3c).
  **~~SKIP-with-note (twin field-set mismatch)~~ — UNBLOCKED + SHIPPED in slice 3f (chip
  task_1c04803f), see below.** **SKIP not-a-response:** CanaryResult ×3 (no controller returns it).
  **$ref real-null parking (constraint #2, un-annotatable):** Report.portfolio/
  portfolioRsPriority/portfolioRsPriorityNet, BacktestResult.rotation, Funnel.regime,
  SourceHealth.lastRun, FuturesDigest.banks/termStructure, the digest shells' record members,
  DayContext options/vix/indexPriceAction. **Response-level nullability noted:**
  FundamentalsController.get returns `orElse(null)` = empty-body 200 (not a component lie).
  FE: zero changes needed — hand-written types verified null-honest for every 3d surface
  (ingestHealth.ts, settings.ts KiteStatus/SyncStatus optional-loose, types.ts BreadthDay).

- **3f: market-data twin-collision dedupe + the fields it unblocked — SHIPPED (chip
  task_1c04803f).** Constraint #3 said "annotate ALL twins identically or the spec depends on scan
  order"; for the twins whose field sets DIFFER there is no identical annotation to write, so 3d
  parked them. The fix is `@Schema(name = "...")`, which springdoc honours for the component KEY and
  every `$ref` (verified by capture) — and the `RecordRequiredModelConverter` keys its
  always-emitted map off the resolved `$ref` name, so the `required` arrays follow for free.
  **Renamed (20 spec components out of 9 collapsed names; Java record names UNCHANGED, wire keys
  UNCHANGED):**
  `Row` → `MinerviniRow`/`ManasRow`, `CandidateAnalysis` → `Minervini`/`ManasCandidateAnalysis`,
  `FunnelRow` → `Minervini`/`ManasFunnelRow`, `BacktestResult` → `Minervini`/`ManasBacktestResult`,
  backfill `Status` ×4 → `BhavcopyBackfillStatus`/`OiBackfillStatus`/`ExpiredBackfillStatus`/
  `EquityDailyBackfillStatus`, `ExchangeResult` → `KiteSessionExchangeResult`/`BhavcopyExchangeResult`
  (found by the cross-vendor review — see constraint #3's warning; it was the only collision where the
  two twins shared NO field at all, so `POST /api/v1/auth/kite/session` published a wholly foreign
  schema), `TrendPoint` → `OiTrendPoint`/`ExpiryCompareTrendPoint` (the instance filed independently
  as task_1023f3bb and merged into this chip's ledger row: `OiTrendingService.TrendPoint` has
  `trend`, `ExpiryCompareService.TrendPoint` has `pcr`, the OI twin won, so the expiry-compare
  response was documented with a `trend` it never sends and without the `pcr` it always does).
  **Also renamed, NOT in the original chip scope but forced by it:**
  `ScreenResponse` and `Funnel` — 3d correctly called them field-identical twins, but that identity
  was only skin-deep (`items`/the three lists `$ref` the row schema), so once the rows split the
  parents stopped being interchangeable and had to split too.
  **Nullable annotations then landed — 90 NEW `@Schema(types=…)`; 105 nullable properties published
  across the 20 affected schemas once the 15 pre-existing #1003 annotations are counted (4×
  `screenDate`, 4 on the newly-surfaced `SetupView`, and the 7 already on the two `TrendPoint`
  twins):**
  MinerviniRow 12 + ManasRow 12 (nullable DDL V031:12-23,29 / V036:15-31 + V038, read via
  `rs.getBigDecimal`/`getObject` — MinerviniScreenRepository:94,97-102,
  ManasScreenRepository:126-133; `close` NOT NULL both, left alone), MinerviniCandidateAnalysis 14 +
  ManasCandidateAnalysis 13 (the two not-scanned shells pass explicit nulls —
  MinerviniController:227-229,238-240, ManasController:286-288,297-299), MinerviniFunnelRow 6 +
  ManasFunnelRow 8 (LEFT-JOIN misses + the explicit `pctToPivot` ternary), Minervini/Manas
  BacktestResult `fromDate`+`runAt` ×2 (idle/running/failed shells), the 4 Statuses 3/7/5/5
  (never-run shells + the RUNNING snapshots). **`ScreenerService.Row` (8c) was a FALSE ENTRY in 3d's
  list** — `ScreenerController.screen` returns `Map<String, Object>` (:23), so springdoc never
  resolves that record at all; it is not in the spec and cannot collide. No rename, no annotation.
  **Two hidden schemas surfaced as a side effect:** `ManasController.SetupView` (already annotated by
  3d but UNREACHABLE while the Manas `CandidateAnalysis` was the losing twin) now publishes, and
  every renamed schema's `required` array grew from the old cross-twin intersection to its own true
  set (the old arrays were the WINNER's properties ∩ the LAST-resolved twin's always-emitted set —
  `Status` published 8 properties but only 5 required). **The breaking gate reports exactly ONE
  incompatibility, and it is the point of the change:** `POST /api/v1/auth/kite/session` →
  `Missing property: days / bhavRows / candleRows`, i.e. the three bhavcopy counters that endpoint
  never emitted. Ships with a `Contract break: APPROVED (…)` line. Every other renamed endpoint reads
  `Backward compatible` — openapi-diff resolves `$ref`s inline, so a rename with the same resolved
  shape is invisible to it, and response `required` only widened (an increase is compatible; only
  `required.decreased` breaks).

Ledger row: task_79d12a4d (slices 3a-3c) + task_0b14da09 (3d) + task_5187d6d6 (3e, the nullable-ENUM
carve-out) + task_1c04803f (3f, the twin-collision dedupe). Full agent
tables (evidence file:line per component) archived below.

## strategy-signal YES table (agent 1, computed)

| Schema.component | Evidence |
|---|---|
| Insight.priority/.cooldownUntil/.expiresAt | V032 nullable cols; InsightRepository:315-323 |
| NotificationEventRow.signalId/.strategyId/.insightId/.detail | V004:10 + V033; mapper :54-60 |
| RejectedRow.side/.composite/.threshold | V015:17,23,24 nullable; mapper :187-191 |
| Contrast.meanComposite{Fired,Rejected}/.meanSupportRatio{Fired,Rejected} | RejectionReader:211,225 null on empty |
| CompareResult.differsMost | SignalCompareReader:213-222 best=null |
| CompareColumn.priority/.band/.optionLegCost/.entryPrice | :128-129 orElse(null); :148-161; V003:16 |
| ComponentPoint.points/.c | :251-255 decimal() null |
| TicketPrefill.qty | :70 ternary null |
| Dossier.slug/.name/.graduatedAt | StrategyEvidenceReader:131-132,172 |
| OpenSell.unrealizedPct | V037:26 nullable |
| StrategyGraduation.winRate/.profitFactor (BOTH twins) | GraduationService:216,250 |
| Promotion.expectancy/.sharpe/.maxDrawdownPct | V024:13-15 nullable |
| PositionDto.markPrice/.unrealizedPnl/.stopLoss/.takeProfit/.buyingPowerWarning | PaperService:1032-1044 |
| OrderLeg.signalId/.filledAt/.fillPrice/.fillSimulator/.slippageApplied | V005 nullable; :791,860-861 |
| OpeningSignal.entryPrice | V003:16 |
| PositionDetail.markPrice/.unrealizedPnl/.closedAt/.closeReason/.stopLoss/.takeProfit/.advisedLots/.marginSnapshot/.marginPct/.subaccountIdx/.openingSignalId | :817-818,845-856; V005:44, V023:15-17, V014:20, V026:10 |
| PaperEventDto.reason/.realizedPnl | PaperController:60-61,214 |
| MarginHeat.unpricedReason + 5 margin amounts | PaperMarginController:64,101-103 |
| Funds.availableCash/.collateral/.m2mRealized/.m2mUnrealized/.utilisedDebits | OrderGateway:96 notConfigured() |
| AuditRow.fromVersion/.toVersion/.diffSummary | V002:52-54; mapper :330-332 |
| Entry(journal).signalId/.paperPositionId/.backtestRunId/.backtestTradeId/.disciplineRating/.emotionRating | JournalRepository:18,185-192 |
| ShadowVariantView.campaignId/.createdBy/.disabledAt | V031:31-36 |
| VariantSpecView.rails/.compositeThreshold | stored-spec echo, both optional |
| RailOverrideView.threshold/.passWhen | disable-only override stores neither |
| VariantSummary.pnlNet | ShadowPositionRepository:152 sum() no coalesce |
| SellDecisionRow (11 fields) / SwingSellDecision (8 fields) | V037:20-33 all nullable |
| TradeDto.closedAt | V005:44 nullable; UNSURE→annotate |

Skipped with reason: Insight.evidence (DDL NOT NULL; parse-exception path only),
Insight.priorityDetail + OpeningSignal JsonNodes + OrderLeg.fees + PositionDetail.openingSignal +
ActResponse/JournalDraft members ($ref / NON_NULL — constraints #1/#2).

## backtest/market-data tables (agent 2, computed) — see slice notes above; UNSURE lists

backtest UNSURE: CounterfactualResult.windowFrom/.windowTo/.ranAt; SavedView.createdAt;
JobAnnotationResponse.tags. edge-gateway UNSURE: ErrorResponse.details/.message.
market-data UNSURE (resolve during 3c): BuzzMatrix, OpenHigh/Setup, SectorIndexCard,
News/NewsItem, ContribRow/IndexContribution, ScanRow/PreOpenScanView, EquityFundamentals,
DeliveryDay, minervini/manas sim aggregates (Funnel/HitRate/Report/SetupStat/YearReturn/
PortfolioStat/BacktestResult/SweepCell/SlotCell/RotationResult/Regime), WarmStatus, SyncStatus,
SubscriptionView, BackfillJobRow, CoverageRow/ExportContract, CompletenessRow/Report,
CloseMismatch/BhavcopyCloseReport, CanaryReport/CheckResult, NormalizedTick, ScreenDiff/DiffRow,
backfill Status ×4, Movers OHLC, CandlesResponse.asOf, ExpirySide.asOf.

Request-record inventory (out of scope, constraint #4): strategy-signal ActRequest,
FeedbackRequest, JournalBody, OrderBody, CloseBody, BracketBody, ResetBody, AccountBody,
UpdateBody, Create/Update/Publish/Rollback/Validate/Notification/CloneRequest, RegisterRequest,
TakenRequest. backtest BacktestRunRequest+StressOverrides+SessionOverrides,
CounterfactualRunRequest+Entry+Variant+ExitKnobs, DatasetEpochRequest, JobAnnotationRequest,
SavedViewRequest, DeepSwingRunRequest. market-data QueryRequest, ExportRequest, NameRequest,
ItemRequest, Backfill/ExpiredBackfill/EquityDailyBackfillRequest, DownloadRequest, BulkRequest,
RefreshRequest, RollupRequest, SnapshotRequest, SubscribeRequest, SessionRequest,
DeepSwingRunRequest, MarginRequest/MarginLeg.

FE notes: `graduation.ts:53-55` = hard mismatch (fix in 3a). `types.ts:212-216` SpurtSummary =
mismatch (fix in 3c). journal.ts links optional-without-null (loose, runtime-safe — leave).
Everything else hand-written is already `| null`-honest; the spec, not the FE, was the liar.

## 3e — the nullable-ENUM carve-out, resolved (chip task_5187d6d6, 2026-07-25)

Slices 3a–3d skipped every enum-typed component on the assumption that
`@Schema(types = {"string","null"})` would clobber springdoc's inline `enum: [...]`. **Probed
empirically (swagger-core 2.2.30 ModelConverters, openapi31 mode) — the assumption was wrong:**

- the type array AND the full enum list both survive: `{"type":["string","null"],"enum":[...]}`;
- `openapi-typescript@7` renders it `"LONG_BUILDUP" | … | null` — exactly right;
- `openapi_relabel_30.py` downgrades it cleanly to `{"type":"string","enum":[...],"nullable":true}`
  (valid 3.0, script rc=0) and openapi-diff reports **backward compatible** — no script change needed;
- `@Schema(nullable = true)` on an enum is the same silent no-op it is everywhere else at 3.1.

Caveat, recorded deliberately: the `enum` list does not itself contain `null`, so a STRICT
JSON-Schema validator would still reject a null (enum is an exhaustive value list). Nothing in this
repo validates responses against the spec — the consumers are codegen, openapi-diff and human
readers, and all three read the type array correctly. Documented rather than worked around.

⚠️ **`BuzzMatrix.cells` is a genuinely nullable enum that CANNOT be annotated — the one permanent
gap.** Its elements are null on the first bucket and on capture gaps (`FuturesBuzzService.java`
`row.add(null)`), but element nullability of a DOUBLY-nested generic (`List<List<OiInterpretation>>`)
is not expressible: measured against swagger-core 2.2.30, `@ArraySchema(schema = @Schema(types=…))`
collapses the inner array and DROPS the enum (`items: {type:["string","null"]}`), and
`@Schema(types=…)` pollutes the OUTER type to `["array","string","null"]`. Both mangle the schema, so
neither ships — the record carries a javadoc saying so instead. Closing it would need an
`OpenApiCustomizer` patching `cells.items.items` post-generation; that machinery is not worth one
schema today. **This is the only known remaining nullable-contract lie in the platform.**
(`BuzzMatrix.asOf` — null on an empty series — IS expressible and was annotated here.)

**A methodological note worth keeping:** the first enum survey scanned only top-level
`properties[].type == "string" && enum`, which is why `cells` was missed; the cross-vendor reviewer
caught it. Survey nested schemas RECURSIVELY (`items`/`items`/`additionalProperties`), not just the
property level. The corrected recursive scan finds **exactly 12 bare-enum sites remaining = 11
verified non-nullable + `BuzzMatrix.cells`** (nullable but inexpressible, above). The 11:
SubscriptionView.mode/.priority, TrendPoint.trend, SpurtSummary/StrikeSpurt/StrikeMove/LegDeltas/
LogEvent/FutSpurt/MoverRow `.interpretation`, OiStructure.verdict. Separately, the 2 annotated
nullable enums are BankRow/BankGridRow `.interpretation` — so 14 enum sites in market-data total.

**Only 2 of the 15 enum sites across all four specs are both genuinely nullable and fixable**
(evidence pass,
2026-07-25, verdicts from construction call sites): `BankRow.interpretation`
(`FuturesMoversService.java:144-163` — `interp = null` then `if (p.old() != null)`, no else) and
`BankGridRow.interpretation` (`FuturesBankGridService.java:80-104`, identical shape). Both
annotated. The other 12 are fed directly or transitively by `OiInterpretation.classify`, which is a
total function over two booleans and **cannot return null** (`OiInterpretation.java:16-23`), or by a
total assignment (`TrendPoint.trend`), or are seeded non-null (`SubscriptionView.mode/priority`).
`FamilyTrust.state` (strategy-signal, the only enum outside market-data) is non-null at every
construction site — the fallbacks pass `DataTrust.DEGRADED`.

⚠️ **`LegDeltas.interpretation` — the chip's own headline example — is NOT nullable, and its javadoc
is wrong.** The javadoc claims "All-null when there is no prior snapshot bucket", but
`OptionsAnalyticsController.java:1294-1296` does `if (old == null) continue;`, so the whole
`LegDeltas` object is absent (`ChainTableLeg.deltas` is null) rather than present-with-null-members.
Its other four members remain individually nullable (annotated in 3c).

**Found in passing, chipped as task_1023f3bb:** `TrendPoint` is a springdoc simple-name COLLISION
between two records with DIFFERENT shapes — `OiTrendingService`'s (has `trend`) and
`ExpiryCompareService`'s (has `pcr`). One schema wins; the expiry-compare response is therefore
documented with a `trend` field it never sends and without the `pcr` it does. Constraint 3 above
covers identical twins; this is the worse, uncovered case.

## Final state (2026-07-25, end of the wave)

**Shipped — 8 PRs.** #996 machinery + first 7 fields · #998 CLAUDE.md stale gate claim ·
#999 strategy-signal 104 · #1000 backtest 54 · #1001 market-data core 149 · #1003 market-data
remainder 184 (+ its own findings doc, `2026-07-25-nullable-ref-downgrade-findings.md`) ·
#1005 edge-gateway WebSession hygiene · #1008 the enum carve-out. Ledger rows: task_79d12a4d,
task_0b14da09, task_98984789, task_5187d6d6 — all DONE. **~500 response components that could be
null at runtime no longer claim otherwise**, across all four services, with per-component
construction-site evidence rather than blanket annotation.

**What the wave proved about the tooling** (each cost a probe, none was in any doc before):
`@Schema(nullable = true)` is a silent no-op at OpenAPI 3.1 — `types = {"X", "null"}` is the only
working spelling; it preserves `format`, `items` and inline `enum` lists; the relabel script
downgrades it losslessly for the 3.0 breaking gate; `openapi-typescript@7` renders it `| null`;
and `required` is orthogonal (key-present ≠ value-non-null), so it correctly stays.

**Known gaps, deliberate and evidence-backed:**

| Gap | Why it stands | Tracked |
|---|---|---|
| `BuzzMatrix.cells` elements | Doubly-nested generic — `@ArraySchema` collapses the inner array and drops the enum; `@Schema` pollutes the outer type. Both mangle. Needs an `OpenApiCustomizer`. | §3e above; owner call |
| Nullable `$ref` components (record/JsonNode-typed) | Constraint 2 — the relabel refuses `$ref`-with-siblings; 3.0 cannot express ref-or-null | task_bd871971 (researched) |
| Duplicate simple-name collapse | springdoc keys by SIMPLE name; twins with DIFFERENT field sets silently lose the loser's fields (`TrendPoint` = the proven instance) | task_1c04803f (+task_1023f3bb) |
| Request-body records | Out of scope by construction — `required` is response-only | inventory above |
| Strict-validator nullability of enums | `enum` lists lack `null`, so a strict JSON-Schema validator would still reject null. Nothing here validates responses against the spec; codegen/diff/readers all read the type array correctly | §3e above |

**Two chip premises were WRONG and are corrected here so they are not re-litigated:**
`PageInstrument` is the repo's own generic record `Page<T>`, not Spring Data's (constraint 5);
`LegDeltas.interpretation` is not nullable — the loop drops the whole object (§3e).

**Method lessons worth reusing:** survey schemas RECURSIVELY (a top-level-only scan cannot see
`items/items` — that is how `BuzzMatrix.cells` was missed, and a cross-vendor reviewer caught it);
resolve every UNSURE by DDL or call site, never annotate speculatively (three UNSUREs in 3b turned
out non-nullable); and re-capture is not proof — diff the captured spec structurally, because the
first `nullable = true` attempt produced a byte-identical spec and would have shipped as a no-op.

**DEPLOY STATUS — nothing from this wave is live.** Every JAR delta is annotation/metadata-only
(it changes `/v3/api-docs`, not behaviour), so restarting the live engine for it would be a
needless restart. Five artifacts carry changes: market-data, strategy-signal, backtest,
edge-gateway, plus the FE type fixes. They ride the next substantive deploy of each service.
