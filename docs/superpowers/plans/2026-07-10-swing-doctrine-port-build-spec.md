# SwingDoctrine port — build spec (audit M31 fork consolidation)

**Status:** design locked 2026-07-10 (grilled via `/improve-codebase-architecture` Candidate 01).
Deploy decision (Q5): **build → prove byte-identical → merge + deploy now** (owner overrode the
"hold — watch the live-paper month" posture for this parity-neutral, characterization-proven change).

**Progress (2026-07-10):** FOUNDATION BUILT + COMPILE-CLEAN (additive; nothing live touched, old
engines still the live path) — `swing/SwingCandidate`, `swing/PyramidPolicy` (+`NONE`),
`swing/SwingDoctrine`, `swing/SwingBatchEngine` (the stateless `runDaily(doctrine)` core, multi-lot,
mirrors the Manas superset). `mvnw -pl strategy-signal -am compile` green. **Remaining (the
parity-gated finish, resume here):** `MinerviniDoctrine`/`ManasDoctrine` + `ManasPyramidPolicy` +
`SwingBatchRecorder` + `SwingSellDecisionService` → characterization test GREEN (byte-identical, the
gate) → swap the 2 schedulers/controllers to thin shells + delete the 2 old engines / 2 sell-decisions
/ 2 recorders → `-am verify` (H11/H12/Modularity/goldens) → adversarial review → merge + redeploy +
live-verify the next 20:00/20:05 batch. Marker keys MUST stay `"minervini"`/`"manas-arora"` (P0-4
canary contract); the SwingBatchAlert ntfy text is not a parity surface.

Collapses the ~65–70% duplicated batch-orchestration skeleton across the Minervini↔Manas swing fork
(audit M31, 7 file-pairs) into one `SwingBatchEngine` driven by a `SwingDoctrine` port — so the
H2 anchor-adoption / H3 gate-recheck / P0-3 exit-retry exit-safety logic lives ONCE, not in two
hand-maintained copies that already drift. Parity-neutral: reuses the FROZEN
`EntryEvaluator`/`ExitEvaluator`/`IndicatorBank` unchanged; no golden vectors touched.

## Locked decisions
- **Q1 — multi-lot is the one path.** `SwingBatchEngine` always groups open anchors by symbol
  (`openLotsBySymbol`), drives the exit off `oldestLot(group)`, closes all lots. Pyramiding is a
  `PyramidPolicy` the doctrine supplies (default `NONE` → `allowsAdd`=false → held-skip). Minervini is
  the degenerate 1-lot case: `entryBlocked()` already caps it at ≤1 anchor/symbol → singleton groups →
  byte-identical to today's per-anchor loop (`ManasAroraSwingEngine.java:586` proves the superset).
- **Q2 — neutral `SwingCandidate`, eligibility as data.** The doctrine's `candidates()` returns
  family-neutral records; the engine has zero family-type knowledge and no generics.
- **Q3 — one stateless engine + config-on-doctrine + thin static satellites.** `SwingBatchEngine` is a
  singleton `runDaily(SwingDoctrine)`; the doctrine carries book/universe/funnel/flags/context-seed/
  detail/pyramid. `SwingBatchRecorder.runAndRecord(doctrine)` owns the `swing_batch_runs` marker + the
  `try/run/catch→SwingBatchAlert` envelope ONCE. Per-family surface = two 3-line `@Scheduled` shells
  (static crons 20:00/20:05 IST) + two ~15-line controllers (genuinely per-path). No new Spring
  mechanism.
- **Q4 — sell-decision folds onto the same port**, same PR, via a `contextSeedsFromDetail` hook.
- **Q5 — deploy now** after byte-identical characterization proof + adversarial review.

## New modules (all in a shared `strategysignal.swing` package)
Modulith graph: `minervini` / `manas` → `swing` → `signals` (acyclic; ModularityTest enforces).

```java
// SwingCandidate — family-neutral funnel candidate
record SwingCandidate(String symbol,
                      Map<String,BigDecimal> contextSeeds,   // seed names→pivot values
                      Set<String> eligibleSetups,            // null = all setups eligible
                      ObjectNode detailBase) {}              // engine adds pyramidLot when lot>1

// SwingStrategy (engine's neutral loaded record) — adds setupToken (nullable)
record SwingStrategy(UUID versionId, String slug, String name, String version, String checksum,
                     String setupToken, StrategyDefinition definition) {}

interface SwingDoctrine {
  Books book();
  String universeMode();            // "minervini_funnel" / "manas_arora_funnel"
  String batchName();               // swing_batch_runs marker + alert label
  boolean enabled();
  int warmupDays(); int minBars(); long ttlMinutes();
  String cron();                    // for the (thin) scheduler shell to read, if desired
  String setupToken(JsonNode config, String slug);            // null (Minervini) / "breakout"|"vcp"
  List<SwingCandidate> candidates();                          // wraps the family FunnelClient
  Map<String,BigDecimal> contextSeedsFromDetail(JsonNode anchorDetail);  // sell-decision re-eval
  void stampDetail(long signalId, String detailJson);        // stampMinervini/ManasAroraDetail
  PyramidPolicy pyramid();          // NONE for Minervini
}

interface PyramidPolicy {
  PyramidPolicy NONE = ...;  // allowsAdd=false, maxLots=1
  boolean allowsAdd(List<SignalRow> lots, SwingCandidate c, EngineCandle bar);
  boolean wouldBreachRiskCap(StrategyDefinition def, String symbol, IndicatorBank bank,
                             int index, BigDecimal entryPrice, EmissionGuard guard);
}
```

- `SwingBatchEngine` — `runDaily(SwingDoctrine)`, `entryPass`, `exitPass`, `AnchorResolution`+
  `adoptVersion`, `entryBlocked`, `emitEntry`, `emitExit`, `retryFetch`, `series`, `flat`, generic
  `buildBank(def, symbol, series, Map contextSeeds)`, `loadPublishedSwingStrategies`. Shared collaborators
  injected once (registry, candles, signals, publisher, events, emissionGuard, tx, objectMapper, clock).
- `SwingBatchRecorder` — `runAndRecord(SwingDoctrine)`: marker + alert envelope, once.
- `SwingSellDecisionService` — `report(SwingDoctrine)`: reuses `engine.buildBank` + `contextSeedsFromDetail`.
- `MinerviniDoctrine`, `ManasDoctrine` (adapters) + `ManasPyramidPolicy`.
- Thin per-family shells: `MinerviniSwingScheduler`/`Controller`, `ManasAroraSwingScheduler`/`Controller`.

## Context-seed mapping (the one stringly seam — names are static-final constants per doctrine)
- Minervini `contextSeedsFromCandidate`: always 3 entries `{MINERVINI_PIVOT: pivot|0, MINERVINI_CHEAT:
  cheat|0, MINERVINI_THRUST: thrust?1:0}` (unconditional 0-fill reproduces today's `buildBank`).
- Manas `contextSeedsFromCandidate`: only non-null of `{MANAS_PIVOT, MANAS_BREAKOUT_PIVOT, MANAS_VCP_PIVOT}`.
- `eligibleSetups`: Minervini `null`; Manas the setups with a valid pivot (`{breakout}`/`{vcp}`/both).
- Sell-decision `contextSeedsFromDetail(detail)`: read pivot/cheat/thrust (Minervini) or pivot +
  setup→breakout/vcp (Manas) from the persisted `*_detail` JSON.

## Parity gate (the goal)
1. **Characterization test** — run the OLD engines vs `SwingBatchEngine.runDaily(doctrine)` over one
   shared in-memory fixture (reuse the H11/H12 fake harness): assert IDENTICAL `signals.insert` args,
   detail JSON, `SignalEmitted`/`SignalExited` events, and `SwingRun` counts — for BOTH families AND the
   Manas pyramid-ON multi-lot case. This is the byte-identical proof (the two risks to pin: the H3
   in-batch "already fired this symbol" tracker, and Manas setup-routing → `eligible`).
2. Existing **H11/H12** stay green. **ModularityTest** passes for the new `swing` package.
3. No `strategy-engine` change → goldens/`BacktestParityTest` trivially unaffected.

## Sequence
1. Characterization test pinning the CURRENT two engines' emissions (the oracle).
2. Add the new `swing` package types (port, candidate, policy) — additive, no behavior change.
3. `SwingBatchEngine` + `SwingBatchRecorder`; `MinerviniDoctrine`/`ManasDoctrine`/`ManasPyramidPolicy`.
4. `SwingSellDecisionService` onto the port.
5. Swap the satellites (scheduler/controller/recorder) to the shared path; delete the two old engines +
   two old sell-decision services + two old recorders.
6. Characterization test green on NEW == OLD; full `-am verify` (H11/H12/Modularity/goldens).
7. Adversarial review (timescale-domain + a general reviewer) — this is HOLD-tier parity.
8. Merge; rebuild + redeploy strategy-signal; live-verify the next 20:00/20:05 batch fires identically.
