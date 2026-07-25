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
- **3b: backtest-service + edge-gateway** — CompareMetrics (all 10), ExperimentSummary,
  ExperimentCompareRun, ProvenanceBlock (11 of 13), RunComparability (4), DatasetEpoch, Trace (3),
  JobAnnotationResponse.note; resolve UNSUREs (CounterfactualResult window/ranAt, SavedView
  createdAt, ErrorResponse.details/message — details normalized at the ApiException path but a
  direct `new ErrorResponse(code,msg,null)` is legal). No FE counterparts (experiments surface
  unconsumed by FE).
- **3c: market-data** — the high-confidence YES set (Leg iv/greeks/prevOi/quote fields, StrikeRow/
  Chain pcr+spot, OiStats pcr/maxPain, ActiveStrikes sentimentPct, LegDeltas all, PcrSeriesPoint,
  Spurt family, BigOi family, Heatmap.Cell.value/maxAbs/asOf, Trend/Premium families,
  StraddleChart, OpenHighStats, digests' blocked() shells (Options/Futures/Equity/Fii +
  DayContext.Vix etc.), DataFreshness members, TermStructure, FutSpurt, Movers/Banks asOf,
  BankGrid, Dow/VixQuote, Candle.oi, MarginResponse unpriced(), Instrument derivatives fields,
  Returns r1d..r1y, BreadthDay, sector families, Minervini Geometry/Row/CandidateAnalysis,
  IngestHealthBoard, CrossSourceOiCanary, KiteStatus, DeepSwingRunResponse engineSha/Image) +
  FE fix `types.ts:212-216` (SpurtSummary.spotDelta non-null + missing oiChangePct/priceChangePct)
  + the UNSURE list (resolve or skip-with-note per item during the slice).

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
