# Strategy provenance manifest (Track-2 Siva Options Scalper)

The 12 sub-strategies are a **one-time manual port** of the human-readable specs in the sibling repo
`C:\Trading\ArthaYantra\StockMarketStrategyTraining`. There is **no automatic sync** — strategy logic
is parity-locked on the deterministic backtest replay path, so an auto-pull from a docs repo is a
parity hazard, and the 12-strategy registry is intentionally stable. When a spec doc changes, the Java
is **re-ported by hand**: read the changed spec → edit the affected strategy's Java + its one-signal
fixture → `./mvnw -pl services/strategy-signal-service -am verify` → commit. **Threshold/limit tuning
rides DB rows** (`risk_settings`, strategy params), never the Java, so tuning never needs a re-port.

This manifest is the **re-sync hook**: it maps each strategy → source doc + the commit it was last
ported from, so a later re-sync is a cheap `git diff` instead of a hunt. Each ported Java strategy
also carries a `@since` / source-tag Javadoc citing the same.

## Source authority

- Repo: `C:\Trading\ArthaYantra\StockMarketStrategyTraining` — **HEAD `5fe9d52` (2026-06-16)**.
- **Authority:** `Options_Scalper_Siva_Consolidated_Strategy.md` (`bb9febe`) — §3 narrative + §6
  machine-readable JSON (the parity spec, with per-strategy `uncertain[]` traps) + §7 open questions.
- **Execution:** `Options_Scalper_Siva_Cheat_Sheet.md` (`a0d0dd3`) — derived from the Consolidated doc.
- **Evolution:** `Options_Scalper_Siva_Changelog.md` (`61c68ff`).
- Raw decks/xlsx: `StrategySources/OptionsScalperSiva/<session>/...`. Sessions S20–S24; latest session's
  value is primary, older retained, no rule ever removed/reversed.

## Manifest

`last-ported`: the Consolidated-doc commit the Java was last reconciled to (`—` = not yet ported).

| # | Strategy | §6 key | Source root (`StrategySources/OptionsScalperSiva/...`) | Phase-3 scope | last-ported |
|---|---|---|---|---|---|
| 1 | Two-Candle Theory | `two_candle` | `20 Live Mentoring 2023/Day 5/2 Canlde Theory LMP23.pdf` | **core** | — |
| 2 | Open=High / Open=Low | `open_high_low` | `20.../Day 12/Open _ High Strategy - Index Options _ Futures.pdf` | derived | — |
| 3 | Market Movers | `market_movers` | `20.../Day 12/Market Movers Strategy.pdf` | **DEFER** (equity-fut screener) | — |
| 4 | Gap Theory | `gap` | `20.../Day 6/` (Gap) | derived | — |
| 5 | Trending-OI Crossover | `trending_oi_crossover` | `20.../Day 7/TRENDING OI CROSSOVER STRATEGY_LMP.pdf` | **core** | — |
| 6 | Golden Crossover | `golden_crossover` | `20.../Day 6/Golden Crossover Strategy.pdf` | **core** | — |
| 7 | Hero-Zero (expiry OI) | `hero_zero` | `20.../Day 8/Oi Expiry Strategy Mentoring.pdf` | **gate** (SPAN+manual) | — |
| 8 | BTST / STBT | `btst_stbt` | `20.../Day 8/BTST _ STBT Strategy Mentoring.pdf` | derived (BTST clock built) | — |
| 9 | Morning Trade | `morning_trade` | `20.../Day 8/Opening Trade Strategy Mentoring.pdf` | derived | — |
| 10 | Connect-the-Dots framework | `scalping_framework` | `20.../Day 2/Connect The Dots... Day 2.pdf` | **core (build first)** | — |
| 11 | Straddle (long/short) | `straddle` | `22 Live Mentoring Prog 2.0 2024/Day 11/Straddle Strategy...pdf` | **gate** (short = SPAN+hedge) | — |
| 12 | Trend Change | `trend_change` | `23 Sensex Scalping.../Day 10/Trend Change Strategy.pdf` | derived | — |

Registry order = Consolidated §7 canonical order. Two `.txt` mentoring transcripts directly under
`C:\Trading\` (`OSPL - Siva Sir.txt`, `OSPL-Modified with BB.txt`) are raw sources already folded into
the structured docs above — consult only to disambiguate, never as the primary spec.

## Column-misattribution traps (verified — do not propagate across strategies)

- "aim 1–2%" → **#3 Market Movers** only (not #2). · "30–50 pts / exit ~5 pts below OH" → **#2 O=H**
  only (not #5/#10). · "SL 50% / close 3:20pm" → **#7/#8** only (not #2/#9).
- No native numeric SL/target (size off structure + VWAP, never invent): **#5, #3, #11, #12**.
- **#2 O=H** premium bands: S22 (BN 250–550 / N 150–350) primary; S20 (BN 250–400 / N 100–250) default.

When porting a strategy, update its `last-ported` cell to the then-current Consolidated-doc commit and
confirm against that strategy's §6 `uncertain[]` block before hard-coding any threshold.
