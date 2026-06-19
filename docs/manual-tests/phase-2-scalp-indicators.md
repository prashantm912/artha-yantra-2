# Manual test guide — Phase 2: scalp indicators + 3m interval

Branch `feat/phase2-indicators` (merged with Phase 1 in PR #40 / `feat/openalgo-phase1-phase2`). Covers
master-plan **Phase 2** (§7 indicator port; §6 greeks scope). PowerShell-first (this box).

**What Phase 2 IS / is NOT.** Phase 2 is a **pure-Java, deterministic** extension of the existing
`libs/strategy-engine` — no network hop, no live appliance, nothing to click. Like the Phase-0 spine,
its proof is the **test suite**: reference-vector (`IndicatorVectorTest`) + behavior (`PsarBehaviorTest`)
+ registry-contract (`RegistryAndSeriesTest`) tests. The indicators become user-visible only when a
strategy YAML references them — that wiring is **S12 (Phase 3)**, not here.

**§6 greeks:** Phase-2 v1 ships the first-order set (delta/gamma/theta/vega/rho/IV — already in
`libs/black76-math`, golden-tested). The 10 higher-order greeks are **deferred** (§17.6) until a named
consumer exists, so there is nothing new to test for §6 here beyond "the 490 golden vectors still pass".

## 0. Prerequisites
```powershell
Push-Location C:\Trading\ArthaYantra\artha-yantra-2
$mvn = (Get-ChildItem "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-*\*\bin\mvn.cmd" | Select-Object -First 1).FullName
$env:MAVEN_OPTS = "-Djavax.net.ssl.trustStoreType=Windows-ROOT"
```

## 1. Automated proof — the §7 deliverable
```powershell
& $mvn -pl libs/strategy-engine -am verify
```
**Expect:** `BUILD SUCCESS`, JaCoCo gate passes, and these run green:
- `IndicatorVectorTest` (**22** vectors incl. the 3 new closed-form ones: `VWMA_period20`, `BASIS_PCT`,
  `ADVANCE_DECLINE_RATIO`) — each engine value matches its committed CSV to 8 dp.
- `PsarBehaviorTest` (**3**) — sustained rise keeps PSAR below close, sustained fall above, V-shape flips,
  warm-up null (PSAR is behavior-tested, NOT vector-pinned — ta4j-internal acceleration ratchet, like
  SUPERTREND).
- `SupertrendBehaviorTest` (3) — unchanged, still green.
- `RegistryAndSeriesTest` — `IndicatorRegistry.knownNames()` is exactly the 24 ids (the 20 prior +
  `VWMA`, `PSAR`, `BASIS_PCT`, `ADVANCE_DECLINE_RATIO`).

```powershell
& $mvn -pl libs/strategy-schema -am test
```
**Expect:** `CorpusTest` + `ParameterPathsTest` green — the `interval` enum now accepts `3m` and the
advisory `indicatorName` enum lists the 4 new ids (advisory only: an unknown name is a registry warning
at save / refusal at publish, never a schema violation).

## 2. Parity JAR — the same engine in live + replay
```powershell
& $mvn -pl services/strategy-signal-service -am package -DskipTests
& $mvn -pl services/backtest-service -am package -DskipTests
```
**Expect:** both build; both embed the **identical** `strategy-engine` JAR (CLAUDE.md: full reactor
`-am`, never a bare `-pl` on the leaf — a stale embedded lib breaks live↔replay parity).

## 3. (Dev only) regenerate the reference vectors
```powershell
python tools/indicator-vectors/generate_vectors.py
git status --short libs/strategy-engine/src/test/resources/vectors/
```
**Expect:** the generator rewrites every CSV but `git` shows changes ONLY for the 3 new files — the 19
prior CSVs are LF-frozen (`* text=auto`) and re-emit byte-identically. This is the A4-exception
"generate ONCE, freeze" pattern — **never** regenerate inside a test.

## 4. (Optional, needs the running stack) the indicator is registry-known
With the app up (`.\ay.ps1 up`), open the strategy editor and add an indicator named `VWMA` (or `PSAR` /
`BASIS_PCT` / `ADVANCE_DECLINE_RATIO`).
**Expect:** it saves without an "unknown indicator" warning (the registry knows it); a made-up name like
`FOOBAR` warns at save and is refused at publish. A strategy whose primary timeframe is `3m` validates
against the schema — but a 3m candle **read** only returns data once the Phase-1 `candles_3m` aggregate
(V019) is materialized in the running stack (the interval code itself is unit-tested without a DB).

## 5. Handoff to S12 (Phase 3)
The new ids are registry-known + parity-safe. The **end-to-end** freeze (each scalper sub-strategy
byte-identical across two tick-wise replays) is the **S12** Phase-23 golden harness
(`GoldenDeterminismTest` against frozen `expected/*.signals.json`) — out of Phase-2 scope, listed here so
the boundary is explicit.

---

### Pass criteria (Phase-2 exit gate)
- [ ] `mvnw -pl libs/strategy-engine -am verify` green incl. `IndicatorVectorTest` (22), `PsarBehaviorTest`,
      `RegistryAndSeriesTest` (24 ids).
- [ ] `mvnw -pl libs/strategy-schema -am test` green (`3m` + advisory enums).
- [ ] `strategy-signal-service` + `backtest-service` both package the identical engine JAR.
- [ ] `mvnw -pl libs/black76-math -am test` green (the 490 first-order golden vectors unchanged — §6 no-op).
