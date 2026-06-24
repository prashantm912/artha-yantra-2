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
| 2 | Open=High / Open=Low | `open_high_low` | `Day 14/Open & High Strategy - Index Options & Futures (2).pdf` | derived | `5cf157b` (→ §3.2; per-strike faithful) |
| 3 | Market Movers | `market_movers` | `Day 10/Market Movers Strategy.pdf` | derived (v1 momentum CORE; equity screener DEFERRED) | `57a1866` (→ §3.3) |
| 4 | Gap Theory | `gap` | `Day 6/Gap Theory.pdf` | derived | `61292e2` (→ §3.4) |
| 5 | Trending-OI Crossover | `trending_oi_crossover` | — (consolidated doc only) | **core** | — |
| 6 | Golden Crossover | `golden_crossover` | `Day 6/How To Trade Using Golden Crossover.pdf` | **core** | — |
| 7 | Hero-Zero (expiry OI) | `hero_zero` | `Day 9/Oi Expiry Strategy 10th Mentoring.pdf` · `Day 10/How To Identify Hero Or Zero - Expiry Day (1).pdf` | **gate** (SPAN+manual) | — |
| 8 | BTST / STBT | `btst_stbt` | — (consolidated doc only) | derived (overnight carry on the btst clock; short-premium leg DEFERRED) | `57a1866` (→ §3.8) |
| 9 | Morning Trade | `morning_trade` | — (consolidated doc only) | derived | `b8f9cdb` (→ §3.9) |
| 10 | Connect-the-Dots framework | `scalping_framework` | `Day 3/Connect The Dots - Become Successful Options Scalper.pdf` | **core (build first)** | — |
| 11 | Straddle (long/short) | `straddle` | — (consolidated doc only) | **gate** (short = SPAN+hedge) | — |
| 12 | Trend Change | `trend_change` | — (consolidated doc only) | derived | `754c44a` (→ §3.12) |

Registry order = Consolidated §7 canonical order. Two shared S24 decks are framework inputs, not strategy
rows: `Day 4/How To Scalp Sensex Using Nifty Charts.pdf` (§4.16 Sensex-via-Nifty proxy) and `Day 5/Kingdom
Trading Strategy-1.pdf` (chess-metaphor re-framing of #1 + the indicator framework — a mnemonic, not a 13th
strategy). The two `.txt` mentoring transcripts (`OSPL - Siva Sir.txt`, `OSPL-Modified with BB.txt`) remain
external (not vendored) — already folded into the structured docs above; consult only to disambiguate.

## Derived-strategy v1 scope notes (#4 / #12 / #2 / #9 / #3 / #8)

The derived scalpers seeded as registry drafts (`scalper-strategies/*.yaml`) port a SUBSET of their
spec; the deferred legs are recorded here so a later re-port re-opens the right gap.

- **#2 Open=High / Open=Low (§3.2, `5cf157b`):** NOW PER-STRIKE FAITHFUL. The OI-quadrant proxy was
  dropped; the tier is the source's Table-1 (Futures-OH + ≥3 ATM±3 Call strikes Open=High + Put Open=Low
  → HIGH; few → MILD; both-sides OH → stand aside) refined by Table-2 (a CE-OH premium falling on ≥50k
  volume, or a >50% fall vs prev close → LOW), from `/options/strike-session-stats` (per-strike session
  OHLC+volume derived from `options_chain_snapshots`). RSI>50 entry. Caveat: 5-min snapshot resolution
  for the volume-candle test (native 3-min needs 3-min capture — `snapshot-interval-ms`). STILL DEFERRED:
  only the OiPulse ≥90% AI badge (a Phase-4 OiPulse-parity model; OPTIONAL, degraded around, never required).
- **#9 Morning Trade (§3.9, `b8f9cdb`):** an opening-tick scalp — the session window runs from 09:16
  (the general "after 09:45" cross-strategy rule does NOT apply); VWAP is degraded (soft, not the hard
  gate) before 10:30 IST; SL = the first session candle's low (CE) / high (PE); profits-only small size
  (a discipline note, not enforced).
- **#4 Gap Theory (§3.4, `61292e2`):** v1 automates the with-trend-after-fill variant — wait for the gap
  to fill, then trade WITH the prevailing trend; pre-gap-candle SL. DEFERRED: the counter-trend
  "trade toward the gap" gap-fill scalp (target = the gap level, SL = day-high/low), labelled
  risky/scalping-only in §3.4, is manual-only and not wired.
- **#12 Trend Change (§3.12, `754c44a`):** consumes the Tier-1 ≥50% call-put dOI momentum shift (read
  off the signed CE/PE dOI imbalance) plus a price swing-structure break + the §3.1 2-candle confirm;
  broken-swing-pivot SL. The ≥50% shift is enforced INSIDE the TrendChangeGate, so #12 does NOT also
  carry the `oi-cross-filter` tag (that would stack a redundant second ≥50% pre-gate).
- **#3 Market Movers (§3.3 / §6.3):** v1 ports the deterministic trend-continuation CORE only, on the
  NIFTY 50 front future (Model A) — trade WITH the trend after price reclaims VWAP (the source's literal
  "long entry after price moves above VWAP"), after 09:45 (the Market Movers matrix floor), ~1-2% target,
  SL = the entry-candle low (the source's "1st candle low" reference; stocks have no rigid native SL,
  reusing the `entry-candle-stop` tag). NO new Java gate (the engine-expressible chart core is enough).
  DEFERRED (no equity-screener feed in strategy-signal-service): the F&O EQUITY universe + the Top-
  Gainers/Top-Losers Market-Movers screener, the 8/9-day HIGH/LOW breakout (Min. B.O. Days), the per-
  stock OH/OL flag, the per-stock OI interpretation (LB/SC long, SB/LU short), the RSI(Daily) screen,
  and the SHORT side. The published draft is LONG-only (the bullish 8/9-day-high lean) — the index option
  is a faithful momentum surrogate for the deferred stock-futures leg.
- **#8 BTST / STBT (§3.8 / §6.8):** an OVERNIGHT carry on the existing engine btst clock (`style: btst`
  → fill AT_CLOSE, the A9 pre-close clock evaluates once/day at `pre_close_at` 15:20). `direction: both`
  — the side is resolved LIVE at the seam (closing toward the day HIGH ⇒ CE/BTST bullish carry, toward
  the day LOW ⇒ PE/STBT bearish carry), matching the §3.8 BTST-vs-STBT close-location split; SL = 50% of
  premium (`stop_loss premium_pct 50`) + the next-morning exit (`time_stop max_holding_days 1`). v1 seeds
  the DEFINED-RISK long-premium buy leg (Buy CE / Buy PE). The SHORT-PREMIUM sell legs — **BTST: Sell PE,
  STBT: Sell CE** (correctly SELL side) — are the documented SIZING FOLLOW-UP, gated on the dormant
  margin-service SPAN appliance (#47) and deliberately NOT coupled here. Also DEFERRED: the explicit EOD
  OI-quadrant gate (BTST SC=Q3/LB=Q1, STBT SB=Q2/LU=Q4), the 3:15pm Futures+Option OI check, the global-
  cue (Dow/Dollar/Asia/Oil) alignment + the avoid-Friday rail (ride the LIVE confluence seam), and the
  STOCK BTST/STBT variant (needs the deferred equity universe).

## Column-misattribution traps (verified — do not propagate across strategies)

- "aim 1–2%" → **#3 Market Movers** only (not #2). · "30–50 pts / exit ~5 pts below OH" → **#2 O=H**
  only (not #5/#10). · "SL 50% / close 3:20pm" → **#7/#8** only (not #2/#9).
- No native numeric SL/target (size off structure + VWAP, never invent): **#5, #3, #11, #12**.
- **#2 O=H** premium bands: S22 (BN 250–550 / N 150–350) primary; S20 (BN 250–400 / N 100–250) default.

When porting a strategy, update its `last-ported` cell to the then-current Consolidated-doc commit and
confirm against that strategy's §6 `uncertain[]` block before hard-coding any threshold.
