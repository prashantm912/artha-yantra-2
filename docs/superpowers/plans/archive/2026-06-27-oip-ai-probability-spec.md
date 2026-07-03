# OIP-AI Probability — build spec (our deterministic Open=High success probability)

> **ARCHIVED (2026-07-03 doc sweep):** historical planning doc — the work here is delivered, superseded, or consciously parked. Anything still open lives in `../2026-07-02-remaining-items.md` (ledger) or `../2026-07-03-10x-value-roadmap.md`. Do not mine this file for TODOs.


**Status:** SPEC only (owner asked spec, not build, 2026-06-27). Forward-work plan.
**Owner decisions (2026-06-27):** tier→% = **HIGH 90 / MILD 60 / LOW 30 / AVOID 0**; scope = doc remap (done) + this spec; OSPL chart signal handled separately via `docs/oipulse-study/advance-chart/ospl-signal.md`.

## 1. Goal
Replace oipulse's proprietary "AI probability %" (the Open=High Probability column, >90% = badge) with our **own transparent, rule-based** Open=High success probability. No black-box model — a deterministic grade off data we already capture, surfaced as a **LOW / MILD / HIGH** tier + an assigned **%** + an **AVOID** veto.

Source of truth = the **Day-14 "Open & High Strategy — Index Options & Futures" deck** (`strategy-documents/options-scalper-siva/original-source-backup/24 Big 5 Anniversery .../Day 14/Open & High Strategy - Index Options & Futures (2).pdf`), tables on p11 + p14, application on p9/p12/p13/p20/p22.

## 2. The model (verbatim from the deck)

**Table A — FNO-data alignment (p11) → base tier**

| Futures | Options footprint | Interpretation |
|---|---|---|
| OPEN=HIGH | **all** CE OPEN=HIGH **&** PUT OPEN=LOW | **HIGH** (premiums reach back to high) |
| — | **few** CE OPEN=HIGH **&** few PUT OPEN=LOW | **MILD** |
| OPEN=LOW | **all** PUT OPEN=HIGH **&** CALL OPEN=LOW | **HIGH** (bearish mirror) |
| — | **few** PUT OPEN=HIGH **&** few CALL OPEN=LOW | **MILD** |
| — | CALL OPEN=HIGH **&** PUT OPEN=HIGH (conflict) | **MILD / both-sides** (stand aside) |

**Table B — subsequent price + volume (p14) → modifier**

| OH side | Subsequent price | Volume | Probability of success |
|---|---|---|---|
| CALL OH | falls | < 50K | INCREASES |
| CALL OH | falls | > 50K | **DECREASES** |
| CALL OH | flat | flat | INCREASES |
| PUT OH | rises | < 50K | INCREASES |
| PUT OH | rises | > 50K | **DECREASES** |
| PUT OH | flat | flat | INCREASES |

**Gates (p9/p12/p13/p20)**
- ≥3 strikes above & below ATM must match OH **+ futures** match (else MILD/none).
- Premium must **not** fall >50% from prev close (CE fall >50% → LOW).
- Adverse move **with >50K volume** → LOW; without volume → HIGH.
- **AVOID** (hard veto, p20): option premium ↓>50% **AND/OR** strike ΔOI ↑>50% (a bigger player took the opposite side). Deep OTM/ITM = skip (liquidity). 2nd-half = premium erosion, prefer 1st-half.
- p22: oipulse renders this as a %; **>90% + badge = high chance**.

**Owner tier → %:** HIGH **90** · MILD **60** · LOW **30** · AVOID/STAND_ASIDE **0** (no-trade). Badge at the HIGH/90 read.

## 3. What already exists (extend, don't rebuild)

| Component | Path | Does |
|---|---|---|
| `OpenHighLow.tier(marks, stats, side, props)` | `services/strategy-signal-service/.../scalper/OpenHighLow.java` | **Table-1 + Table-2 + >50%-prev-close-fall** → `Tier{HIGH, MILD, LOW, STAND_ASIDE}`. Pure + replay-safe. |
| `OpenHighLow.marks(future, index)` | same | front-future OH/OL marks (1-pt tolerance) |
| `OpenHighLowGate` | `.../scalper/OpenHighLowGate.java` | pre-gate: proceeds only on `Tier.HIGH` |
| `MarketOiClient.OpenHighStats` / `StrikeStat` | `.../scalper/MarketOiClient.java` | per-strike footprint: `strike, type, ohMark, olMark, last, open, high, declineVolume, fallPctFromPrevClose` (NO ΔOI field) |
| `GET /api/v1/market/options/strike-session-stats` | market-data | per-strike OHLC + volume + OH/OL marks (zero new capture) |
| `ScalperOiProps` | `.../scalper/ScalperOiProps.java` | thresholds: `openHighMinStrikes`, `openHighFallVolumeFloor` (~50K), `openHighMaxPrevCloseFallPct` (~50) |
| `OpenHighStrategyService` | `services/market-data-service/.../analytics/OpenHighStrategyService.java` | the oipulse PAGE's probability = **historical trigger frequency** (hits/prior-sessions) — a DIFFERENT number from this deterministic OIP probability; keep both, label clearly |

So Tables A/B + the LOW rules are **built**. Missing = the **%**, an explicit **AVOID** (incl. the ΔOI veto), and **surfacing** it as "our OIP probability".

## 4. Build deliverables (the gaps)

**G1 — Tier → % (pure).** Add `OpenHighLow.probabilityPct(Tier)` (or a `record OhProbability(Tier tier, int pct, boolean badge)`): HIGH→90, MILD→60, LOW→30, STAND_ASIDE/AVOID→0; `badge = tier==HIGH`. Pure, deterministic, unit-tested.

**G2 — explicit AVOID + ΔOI veto (p20).** Today `OpenHighLow` does the >50%-premium-fall → LOW but **not** the ΔOI ↑>50% veto (StrikeStat carries no ΔOI). Options:
  - (a) extend `strike-session-stats` + `StrikeStat` with per-strike `oiChangePct`, add an `AVOID` tier when `premiumFall>50% OR oiChangePct>50%` (distinct from STAND_ASIDE so a vetoed-HIGH reads AVOID, not sideways); **or**
  - (b) reuse the existing **#5 oi-cross-filter ≥50% ΔOI pre-gate** (`ScalperOiProps`/`ScalperConfluenceGate`) and keep AVOID as "STAND_ASIDE + that gate fired" — no new field.
  - **Open point — owner/eng to pick (a) vs (b).** (b) is cheaper + avoids double-gating; (a) gives a self-contained OIP score for the page. Recommend (b) for the gate path, (a)-lite (page-only oiChangePct) for the page column.

**G3 — surface the probability.**
  - **Scalper signal (parity-safe side-channel):** carry `{ohTier, ohPct, badge}` in the SignalEvent/Trade side-channel (the frozen golden writer ignores new fields → byte-identical golden, per the golden/parity side-channel convention). Compute at entry, deterministic.
  - **Open=High page (read-only, no parity impact):** add an "OIP Probability" column (tier chip + %) **alongside** the existing historical-frequency `Prob` column — they answer different questions (today's deterministic FNO read vs historical odds). Needs a futures OH/OL read at request time + the latest-session strike footprint (market-data already has the stats; add the futures mark). New RTL spec.
  - **Cockpit / scalp alerts:** show the tier badge (HIGH=badge); optional ntfy/telegram line.

**G4 — badge.** HIGH (90) renders the strong/"badge" state, mirroring oipulse's >90%.

## 5. Parity & safety
- `OpenHighLow` is already pure + replay-safe (deterministic recompute). **G1 (%)** and **G3 page column** are pure derivations / read-only → **parity-safe, no golden change**.
- **G2** *can* change gating (an AVOID newly blocks a HIGH the oi-cross-filter didn't) → **signal-affecting**. Gate it behind a NEW default-OFF tag (e.g. `open-high-oi-veto`) + a NEW golden variant; existing goldens stay byte-identical (GoldenDeterminismTest / BacktestParityTest). Only arm it after forward-paper validation.
- Signal side-channel fields (G3) ride the frozen `GoldenSignalsJson.write()` as non-serialized extras → golden byte-identical.

## 6. Data inputs (all already captured)
Per-strike O/H/L/last/volume/declineVolume/OH-mark/OL-mark/fallPctFromPrevClose (`strike-session-stats`); front-future OH/OL marks (`OpenHighLow.marks`); thresholds (`ScalperOiProps`: minStrikes, 50K fall-volume floor, 50% prev-close-fall). For G2(a): add per-strike `oiChangePct` to the endpoint.

## 7. Acceptance / verify
- Unit: `OpenHighLow.probabilityPct` (90/60/30/0) + AVOID veto truth table; extend `OpenHighLowTest`.
- Parity: Golden + Backtest parity byte-identical with the veto tag OFF; a new golden variant for the veto ON.
- Page: RTL spec for the new OIP-Probability column; e2e/axe on the Open=High page.
- Live-verify on forward paper with real captured OI (derived-history OI mutes the OI factors).

## 8. Open points (owner / eng)
1. **G2 veto:** path (a) extend endpoint with ΔOI vs (b) reuse #5 oi-cross-filter. (Recommend b for gating.)
2. **Open=High page:** add a 2nd column (recommended) vs replace the historical-freq %.
3. **% display:** point anchors (90/60/30/0) only, or also bands (HIGH 75–95 etc.) for a gradient chip?
4. **Where to surface** beyond the page: signal payload only, or also Cockpit badge + scalp alerts?
5. **Does this re-arm any gate?** Currently `OpenHighLowGate` already requires `Tier.HIGH`; the % is presentational unless we add a min-% gate — decide if the % becomes a tunable gate threshold (then optimize-block parameter).

## 9. Provenance
Deck tables extracted 2026-06-27 (rendered p11/p14 + text p1–p24). Related: the operative-doc OIP remap (§3.2 + §6.2 + the cross-cut anchor in `strategy-documents/options-scalper-siva-operative/Options_Scalper_Siva_Operative_Strategy.md`), and `docs/strategy-audit/RATIFICATION-PACK.md` U7/U8.
