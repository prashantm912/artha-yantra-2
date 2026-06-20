# Manual test guide — Phase 3.5b: Open=High per-strike faithful probability (#2)

Branch `feat/open-high-per-strike`. Replaces #2's v1 proxy (front-Future OH/OL × OI-quadrant) with the
**source-faithful** Siva "Open & High" grading: per-strike **Table-1** (OH/OL footprint) + **Table-2**
(price/volume modifier) → HIGH / MILD / LOW / STAND_ASIDE, fed by a new per-strike-OHLC market-data
endpoint derived from the existing snapshot store (no new capture/schema). PowerShell-first.

**What this IS / is NOT.** The scalper path is **LIVE-only** (parity rides the V009 side-channel); the
exhaustive proof is the **test suite**. The mock walk shows the new endpoint + the graded tier. Frontend
is **deferred to React (Phase 4)** — nothing to click in Angular.

## 0. Prerequisites
```powershell
Push-Location C:\Trading\ArthaYantra\artha-yantra-2
$mvn = (Get-ChildItem "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-*\*\bin\mvn.cmd" | Select-Object -First 1).FullName
$env:MAVEN_OPTS = "-Djavax.net.ssl.trustStoreType=Windows-ROOT"
```

## 1. Automated proof — the endpoint + the reader (market-data)
```powershell
& $mvn -pl services/market-data-service -am test -Dtest='OptionsSnapshotReaderIntegrationTest,OptionsAnalyticsControllerIntegrationTest'
```
**Expect:** green. Highlights:
- `OptionsSnapshotReaderIntegrationTest` — `sessionStats(...)` derives per-strike session **open/high/low/last**,
  **dayVolume** (= last−first cumulative volume), **declineVolume** (interval volume on the buckets where
  the premium fell), and **prevClose** (prior trading session's last ltp); `StrikePoint.volume` populated.
- `OptionsAnalyticsControllerIntegrationTest` — `GET /options/strike-session-stats?underlying=NIFTY 50&expiry=<e>&window=3`
  picks the ATM nearest spot, slices ATM±window, grades `ohMark`/`olMark` + `fallPctFromOpen`/`fallPctFromPrevClose`;
  decimals serialize as JSON strings; no data → 200 + empty items.

## 2. Automated proof — the faithful grading (strategy-signal)
```powershell
& $mvn -pl services/strategy-signal-service -am test -Dtest='OpenHighLowTest,OpenHighLowGateTest,MarketOiClientTest,ScalperGatesTest'
```
**Expect:** green. Proves:
- **Table 1** — Futures-OH + ≥3 ATM±3 Call-OH strikes + a Put-OL → **HIGH**; few strikes → **MILD**;
  both-sides OH footprint or no mark → **STAND_ASIDE** (mirror for PE/bearish).
- **Table 2 + LOW** — an OH strike whose premium fell on **≥50k** decline-volume → **LOW**; fell on <50k /
  flat → keeps the Table-1 tier; a **>50% fall vs prev close** → **LOW**.
- The gate fires **only on HIGH**; `MarketOiClient.openHighStats` degrades (null/empty → block, never a
  false HIGH); **RSI > 50** for #2 (the shared `rsiBand` 60–80 is unchanged for the other strategies); the
  ≤50% premium/OI spurt reject still blocks. The OI quadrant is **dropped** from the tier.

## 3. Parity — frozen golden vectors byte-identical
```powershell
& $mvn -pl services/strategy-signal-service,services/market-data-service -am verify
& $mvn -pl libs/strategy-engine,services/backtest-service -am test -Dtest='GoldenDeterminismTest,BacktestParityTest'
```
**Expect:** `BUILD SUCCESS`; JaCoCo + Modulith green; **GoldenDeterminismTest 5/5 + BacktestParityTest 5/5**
byte-identical (the grading is live-only / V009 side-channel).

## 4. Mock-stack walk (optional)
```powershell
.\ay.ps1 up
```
- `GET /api/v1/market/options/strike-session-stats?underlying=NIFTY 50&expiry=<e>&window=3` → per-strike
  `open/high/low/last/dayVolume/declineVolume/prevClose/ohMark/olMark/fallPct*` + `atmStrike`/`spot`.
- A scalper ENTRY from `scalp-open-high-low-nifty` carries the graded tier + reason in `scalperDetail`.

## 5. Caveats / still deferred
- **5-min snapshot resolution** — `declineVolume` (the Table-2 "volume candle") is at the capture cadence.
  For a native 3-min volume candle, set `artha.options.snapshot-interval-ms`=180000 (the endpoint's
  `interval` param serves both; you cannot bucket finer than the capture). Table-1 OH/OL is resolution-robust.
- **OiPulse ≥90% AI badge** — external Phase-4 OiPulse-parity model; not implemented, treated as an
  optional confirmation degraded around (never required).
- `volume` is the broker's **cumulative day-volume** → candle volume = consecutive-snapshot diff (verified
  against `OptionsSnapshotService`).
