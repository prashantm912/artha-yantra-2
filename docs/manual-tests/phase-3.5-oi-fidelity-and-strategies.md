# Manual test guide — Phase 3.5: scalper OI-analytics fidelity + 4 defined-risk strategies

Branch `feat/phase3.5-oi-and-scalpers`. Covers the Phase-3.5 Tier-2 OI-analytics fidelity gaps
(T2.1–T2.8) plus the four feasible index-option intraday strategies (#4 Gap Theory, #12 Trend Change,
#2 Open=High/Open=Low, #9 Morning Trade). PowerShell-first (this box).

**What this IS / is NOT.** The scalper confluence is **LIVE-only** (`MarketOiClient` reads current
snapshots; it is never part of a deterministic replay — parity is the V009 side-channel persisted at
entry). So the primary proof is the **test suite**; the mock-stack walk shows the 4 new strategies
seeding and a scalper signal carrying the new confluence dots. The **frontend is deferred to React
(Phase 4)** — there is nothing new to click in the Angular UI.

## 0. Prerequisites
```powershell
Push-Location C:\Trading\ArthaYantra\artha-yantra-2
$mvn = (Get-ChildItem "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-*\*\bin\mvn.cmd" | Select-Object -First 1).FullName
$env:MAVEN_OPTS = "-Djavax.net.ssl.trustStoreType=Windows-ROOT"
```

## 1. Automated proof — Tier-1 fidelity (derivations + dots + #5 pre-gate)
```powershell
& $mvn -pl services/strategy-signal-service -am test -Dtest='MarketOiClientDerivationTest,ConnectTheDotsScorerTest,ScalperGatesTest,ScalperConfluenceGateTest,ScalperOiPropsTest'
```
**Expect:** `BUILD SUCCESS`, all green. Highlights:
- `MarketOiClientDerivationTest` — per-side ΔOI, `callPutDeltaImbalancePct` (incl. the **flat-OI caveat**
  → null, never a spurious %), `crossedThisWindow`/`gapWidening`, the 6-strike CE/PE IV averages (ATM
  excluded, 0..1 fraction scale, the 40/40 both-high fixture), the sentiment slope, the spurt magnitudes.
- `ConnectTheDotsScorerTest` — the 4 new dots (`drastic_oi`, `sentiment_slope`, `oi_spurt`, `iv_pair`),
  the change-based `trending_cross` (a static PE tilt no longer confirms), the 40/40 `standAside` veto,
  and a degrade case per dot (null data → no support, never blocks). **18 dots total.**
- `ScalperGatesTest` — `callPutDeltaFilter` (#5 ≥50% PASS/FAIL/degrade-null→PASS) + the window-aware
  `timeWindow` overload.
- `MarketCalendarTest` / `MarketOiClientTest` — **monthly-expiry OI suppression** (S24 caveat): on the
  month's last weekly index expiry, `oi()` skips the chain-OI reads (keeps the basis), so every OI dot
  degrades to non-confirming and nothing blocks — `isMonthlyIndexExpiryDay` + the suppression test.

## 2. Automated proof — the 4 strategies
```powershell
& $mvn -pl services/strategy-signal-service -am test -Dtest='GapStateTest,GapTheoryGateTest,MarketStructureTest,TrendChangeGateTest,OpenHighLowTest,OpenHighLowGateTest,ScalperStrategyLoadTest'
```
**Expect:** all green. `ScalperStrategyLoadTest` now seeds + validates **all 8** scalper strategies (4
core + the 4 new) and asserts each new one's tag (`gap-theory`, `trend-change`, `open-high-low`,
`opening-tick`). The gate tests prove: #4 blocks until the gap fills then trades with-trend; #12 needs a
structure break + the Tier-1 ≥50% OI shift + a 2-candle confirm; #2 needs the front-Future OH/OL HIGH
tier + the ≤50% spurt reject rules (both-OH → stand-aside); #9 enters at 09:16 (default strategies do
not) with VWAP degraded before 10:30 and a first-candle stop.

## 3. Parity + full gate — the frozen golden vectors stay byte-identical
```powershell
& $mvn -pl services/strategy-signal-service,services/market-data-service -am verify
& $mvn -pl libs/strategy-engine,services/backtest-service -am test -Dtest='GoldenDeterminismTest,BacktestParityTest'
```
**Expect:** `BUILD SUCCESS`; JaCoCo + Modulith green; **`GoldenDeterminismTest` 5/5 and
`BacktestParityTest` 5/5 byte-identical**. The new dots ride the V009 side-channel only — they do not
touch `ScoreBreakdown` / the golden byte-string.

## 4. Mock-stack walk (optional — see the strategies seed + emit)
```powershell
.\ay.ps1 up          # mock profile (artha_mock / redis db1), no Kite creds needed
```
- **Seeding:** the boot log shows `ScalperStrategySeeder` registering 8 scalper strategies (the 4 NIFTY
  core + `scalp-gap-theory-banknifty`, `scalp-trend-change-banknifty`, `scalp-open-high-low-nifty`,
  `scalp-morning-trade-nifty`). Confirm in the registry: `GET /api/v1/strategies` lists them.
- **New market-data fields:** `GET /api/v1/market/options/spurt?name=NIFTY 50&expiry=<e>` → each row
  carries `ltpChangePct`; `summary` carries `oiChangePct`/`priceChangePct`.
  `GET /api/v1/market/options/active-strikes?name=NIFTY 50&expiry=<e>&buckets=20` → adds `sentimentSeries`
  (absent without `buckets`).
- **Scalper signal side-channel:** when a scalper ENTRY fires on the mock feed, `GET
  /api/v1/signals/{id}` → `scalperDetail.dots` now includes the 4 new dot names; a 40/40 IV setup carries
  the `iv pair 40/40 stand-aside` reason. (The mock OI feed may not exercise every dot — the test suite
  is the exhaustive proof.)

## 5. Deferred (NOT in this phase — documented so the gate is honest)
- **#2 per-strike ATM±3 OH/OL confluence** + the **OiPulse ≥90% AI badge** — need a per-strike-OHLC
  market-data endpoint / the OiPulse model (Phase-4 OiPulse-parity). #2 ships front-Future OH/OL + the
  OI-quadrant probability tier (the honest equivalent of the badge).
- **#4 counter-trend gap-fill scalp** (risky, scalping-only) — automated path is with-trend-after-fill.
- **#3 Market Movers** (F&O stock universe → Track-1), **#8 BTST/STBT** (overnight carry + SPAN short
  leg), **#7/#11 short-premium** (SPAN #47) — all out of the index-option-intraday scope.
- **drasticFloor=50000** is a documented, DB-tunable, index-agnostic v1 placeholder (the source gives no
  number) — tune per index when live OI magnitudes are observed.
