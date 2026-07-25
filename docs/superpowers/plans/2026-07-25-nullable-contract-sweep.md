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
- **3d: market-data remainder — OPEN:** context digests' blocked() shells (OptionsDigest/
  FuturesDigest/EquityDigest/FiiDigest/DayContext/ExpiryCompare + their sub-records), equity
  families (Returns r1d..r1y, BreadthDay, Breadth/SectorHeatmap/SectorStats/StockChange),
  Minervini Geometry/Row/CandidateAnalysis, IngestHealthBoard LastRun/SourceHealth,
  CrossSourceOiCanary, KiteStatus, DeepSwingRunResponse engineSha/Image, plus the whole UNSURE
  list below (resolve or skip-with-note per item). Same method: types-array annotation, twins
  annotated together, $ref/JsonNode/NON_NULL skipped.

Ledger row: task_79d12a4d. Full agent tables (evidence file:line per component) archived below.

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
