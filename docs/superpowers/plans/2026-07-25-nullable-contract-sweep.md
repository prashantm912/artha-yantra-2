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
4. **Request-body records are OUT of scope** — the converter applies `required` to responses only;
   annotating request records changes client-facing semantics. (Inventory kept below for a
   possible later pass.)
5. **Framework leaks, not annotatable:** edge-gateway `WebSession` (Spring type leaked by the
   `logout(WebSession)` parameter — fix is hiding the param from springdoc), `PageInstrument`
   (Spring Data `Page<Instrument>` — annotate `Instrument` instead).

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
  (4 of 5 — `interpretation` SKIPPED: a types-array annotation risks clobbering the inline string
  enum; nullable-enum stays a known gap), PcrSeriesPoint, Spurt family (SpurtRow/StrikeSpurt/
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
  **SKIP-with-note (twin field-set mismatch — spec ALREADY scan-order-dependent, pre-existing):**
  Row ×6 (3 response-reachable: MinerviniController 18c / ManasController 23c /
  ScreenerService 8c), CandidateAnalysis ×2 (21c vs 24c), FunnelRow ×2, BacktestResult ×2
  (minervini has sweep+rotation), backfill Status ×4 (8/11/13/14 fields, only 5 common) —
  per-field null evidence archived in the investigator reports if a rename/`@Schema(name=)`
  dedupe ever unblocks them. **SKIP not-a-response:** CanaryResult ×3 (no controller returns it).
  **$ref real-null parking (constraint #2, un-annotatable):** Report.portfolio/
  portfolioRsPriority/portfolioRsPriorityNet, BacktestResult.rotation, Funnel.regime,
  SourceHealth.lastRun, FuturesDigest.banks/termStructure, the digest shells' record members,
  DayContext options/vix/indexPriceAction. **Response-level nullability noted:**
  FundamentalsController.get returns `orElse(null)` = empty-body 200 (not a component lie).
  FE: zero changes needed — hand-written types verified null-honest for every 3d surface
  (ingestHealth.ts, settings.ts KiteStatus/SyncStatus optional-loose, types.ts BreadthDay).

Ledger row: task_79d12a4d (slices 3a-3c) + task_0b14da09 (3d). Full agent tables (evidence
file:line per component) archived below.

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
