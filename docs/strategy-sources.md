# Strategy provenance manifest (Track-2 Siva Options Scalper)

The 12 sub-strategies are a **one-time manual port** of the human-readable specs now vendored **in this
repo** under `strategy-documents/options-scalper-siva/` (relocated 2026-06-20 from the sibling
`StockMarketStrategyTraining` repo so future spec updates land here directly). There is **no automatic
sync** — strategy logic is parity-locked on the deterministic backtest replay path, so an auto-pull
from a docs repo is a parity hazard, and the 12-strategy registry is intentionally stable. When a spec
doc changes, the Java is **re-ported by hand**: read the changed spec → edit the affected strategy's
Java + its one-signal fixture → `./mvnw -pl services/strategy-signal-service -am verify` → commit.
**Threshold/limit tuning rides DB rows** (`risk_settings`, strategy params), never the Java, so tuning
never needs a re-port.

This manifest is the **re-sync hook**: it maps each strategy → source doc, so a later re-sync is a cheap
in-repo `git log -- strategy-documents/options-scalper-siva/...` instead of a hunt. Each ported Java
strategy also carries a `@since` / source-tag Javadoc citing the same.

## Source authority

In-repo base: `strategy-documents/options-scalper-siva/` (all paths below are relative to it).

- **Authority:** `Options_Scalper_Siva_Consolidated_Strategy.md` — §3 narrative + §6 machine-readable
  JSON (the parity spec, with per-strategy `uncertain[]` traps) + §7 open questions.
- **Execution:** `Options_Scalper_Siva_Cheat_Sheet.md` — concise human cheat sheet, derived from the
  Consolidated doc (secondary — the Consolidated doc wins on any conflict).
- **Evolution:** `Options_Scalper_Siva_Changelog.md` — session-by-session diff (S20 → S24).
- **Raw decks:** `original-source-backup/24 Big 5 Anniversery - Live Decoding 21 Days 2025/`
  (`Daywise Sessions/Day 1..21` + `Pre-Mentoring Documents`). The in-repo backup holds **only the
  current/latest session (S24)**; the older S20–S23 raw decks live only in the external
  `StockMarketStrategyTraining` archive — their rules are already folded into the three `.md` docs
  above. Latest session's value is primary, older retained, no rule ever removed/reversed.

## Manifest

`last-ported`: the Consolidated-doc commit the Java was last reconciled to (`—` = not yet ported).

In-repo S24 deck paths are relative to `original-source-backup/24 Big 5 Anniversery - Live Decoding 21
Days 2025/Daywise Sessions/`. `— (consolidated doc only)` = no dedicated deck in the S24 backup; the
rule lives in the three `.md` docs (its S20–S23 raw decks are external-archive only).

| # | Strategy | §6 key | In-repo S24 deck (`…/Daywise Sessions/…`) | Phase-3 scope | last-ported |
|---|---|---|---|---|---|
| 1 | Two-Candle Theory | `two_candle` | `Day 4/2 Candle Theory.pdf` | **core** | — |
| 2 | Open=High / Open=Low | `open_high_low` | `Day 14/Open & High Strategy - Index Options & Futures (2).pdf` | derived | — |
| 3 | Market Movers | `market_movers` | `Day 10/Market Movers Strategy.pdf` | **DEFER** (equity-fut screener) | — |
| 4 | Gap Theory | `gap` | `Day 6/Gap Theory.pdf` | derived | — |
| 5 | Trending-OI Crossover | `trending_oi_crossover` | — (consolidated doc only) | **core** | — |
| 6 | Golden Crossover | `golden_crossover` | `Day 6/How To Trade Using Golden Crossover.pdf` | **core** | — |
| 7 | Hero-Zero (expiry OI) | `hero_zero` | `Day 9/Oi Expiry Strategy 10th Mentoring.pdf` · `Day 10/How To Identify Hero Or Zero - Expiry Day (1).pdf` | **gate** (SPAN+manual) | — |
| 8 | BTST / STBT | `btst_stbt` | — (consolidated doc only) | derived (BTST clock built) | — |
| 9 | Morning Trade | `morning_trade` | — (consolidated doc only) | derived | — |
| 10 | Connect-the-Dots framework | `scalping_framework` | `Day 3/Connect The Dots - Become Successful Options Scalper.pdf` | **core (build first)** | — |
| 11 | Straddle (long/short) | `straddle` | — (consolidated doc only) | **gate** (short = SPAN+hedge) | — |
| 12 | Trend Change | `trend_change` | — (consolidated doc only) | derived | — |

Registry order = Consolidated §7 canonical order. Two shared S24 decks are framework inputs, not strategy
rows: `Day 4/How To Scalp Sensex Using Nifty Charts.pdf` (§4.16 Sensex-via-Nifty proxy) and `Day 5/Kingdom
Trading Strategy-1.pdf` (chess-metaphor re-framing of #1 + the indicator framework — a mnemonic, not a 13th
strategy). The two `.txt` mentoring transcripts (`OSPL - Siva Sir.txt`, `OSPL-Modified with BB.txt`) remain
external (not vendored) — already folded into the structured docs above; consult only to disambiguate.

## Column-misattribution traps (verified — do not propagate across strategies)

- "aim 1–2%" → **#3 Market Movers** only (not #2). · "30–50 pts / exit ~5 pts below OH" → **#2 O=H**
  only (not #5/#10). · "SL 50% / close 3:20pm" → **#7/#8** only (not #2/#9).
- No native numeric SL/target (size off structure + VWAP, never invent): **#5, #3, #11, #12**.
- **#2 O=H** premium bands: S22 (BN 250–550 / N 150–350) primary; S20 (BN 250–400 / N 100–250) default.

When porting a strategy, update its `last-ported` cell to the then-current Consolidated-doc commit and
confirm against that strategy's §6 `uncertain[]` block before hard-coding any threshold.
