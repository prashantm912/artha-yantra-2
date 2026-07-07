# Multi-session rollup — accruing ledger (roadmap F2)

**Status:** ACCRUING. The 15:47 post-market agent appends ONE session row here after writing the
dated findings file (method: README §3; per-session detail stays in the findings files — this doc
is the cross-session view). At **≥5 sessions**, the rollup pass fills §Proposals with ranked tune
proposals as literal config diffs, each citing its evidence rows (roadmap F2 acceptance: every
proposal reproducible from the cited findings files alone). Structural patterns (dead across ALL
sessions) → tune now; day-dependent (regime) → keep collecting.

**Reading rules:** judge shadow/variant PnL on **NET ₹** (F8, V018 `pnl_net` — statutory costs
through the engine fill model), points only for scale-free comparison. Variant books
(vol-off / vol-12k5 / composite-070) start 2026-07-06; `pnl_net` starts 2026-07-06 (older closes
carry null). Champion book = would-have-fired class (composite passed, some rail blocked).

## Session log (append-only — one row per session)

| date | rejections | strategies | fired | first-block #1 | composite ≥ thr rows | shadow champion (n, W/L, pts, net ₹) | dead dots | notes |
|---|---|---|---|---|---|---|---|---|
| 2026-07-02 | 524 | 16 | 0 | volume-floor 350/524 (67%) | 118 | — (book shipped EOD) | volume, iv_rank, iv_pair, breadth, oi_spurt (composite capped 0.765) | first forensics pass; findings `2026-07-02-session-findings.md` |
| 2026-07-03 | 438 (09:19–12:40 only — CandleBuilder stall #482) | 12 | 0 | volume-floor 357/438 (82%) | 208 (all CE, 1 PE) | 20, 0W/20L, −513.1 pts, net n/a (pre-F8) | same 5 | theta-bleed grind day: volume floor vetoed only losers; stall RCA + fix #482; findings `2026-07-03-session-findings.md` |
| 2026-07-06 | 643 (09:19–15:19 FULL, no stall) | 17 | 0 | volume-floor 525/643 (82%) | 254 (all CE, 0 PE) | 15, 10W/15, +312.1 pts, **+₹19,274.61** | **4** (breadth REVIVED #486) | **trend-up day (+97 pts): volume floor vetoed WINNERS** (mirror of 07-03); breadth live → cap 0.765→0.816; findings `2026-07-06-session-findings.md` |
| 2026-07-07 | 638 (09:19–**14:22** — strategy-signal EVAL STALL, feed alive) | 31 | **3** (straddle) | volume-floor 458/638 (RELATIVE #605) | 184 (all CE; **189 PE rows appeared**) | **0 opens** (would-have-fired class dissolved) | 4 (breadth 0% today = REGIME, data alive) | **expiry Tue, −94 pts rangebound. FIRST FIRES: 2× `scalp-straddle-nifty` 0DTE straddles, BOTH LOST (−19%, −6.8%), advisory-only. Relative floor's 1st live session — correctly blocked no-expansion morning. NEW: eval stall 14:22; findings `2026-07-07-session-findings.md` |

## Per-variant league (cumulative — refresh each rollup pass from the §6 league SQL)

| variant | closed | net wins | total net ₹ | total pts | verdict-so-far |
|---|---|---|---|---|---|
| champion | 35 | 10 | **+19,274.61** (07-06 only; 07-03 pre-F8 null) | −201.0 | regime-split: RIGHT on 07-03 grind, WRONG on 07-06 trend (vetoed +₹19.3k winners) → **relative-floor case, not a fixed number** |
| vol-off | 2 | 2 | +4,051.27 | +64.5 | loosest config profitable on 07-06 trend (small n) |
| vol-12k5 | 1 | 0 | −160.15 | −1.4 | 1 marginal-loss trade (small n) |
| composite-070 | 0 | — | — | — | still ZERO rows all-time (unfalsified; cap now 0.816 may let it open — watch) |

## Structural-vs-regime watchlist

- **volume-floor — RELATIVE floor ARMED #605 (from 2026-07-07).** Fixed 125k was unpassable all 3 prior
  sessions and regime-flipped in effect (07-03 grind veto SAVED 513 pts / 07-06 trend veto COST +₹19,274).
  Now `k×median(prior-20)`, k=1.5. **1st live session (07-07): behaved correctly** — on a no-expansion
  expiry morning it still (rightly) blocked directional scalpers (bar vol < 1.5× median = no impulse), and
  the single-rail would-have-fired/shadow class **dissolved** (0 shadow opens). **Evidence source shifts
  from shadow book → real fires.** Owner tuning question is no longer "fixed vs relative" (settled) but
  "is k=1.5 right" — judge on real paper fills over ~1 month. SENSEX scalpers still un-armed (fixed 125k).
- **Dead dots capping composite** — cap **0.816 (4 structural-dead)**: iv_rank (honest-null), iv_pair
  (unit-gap), oi_spurt (price-floor 50), volume (relative floor, no-expansion days). breadth is LIVE (#486)
  — its 0% support on 07-07 is REGIME (A/D non-zero, breadth against CE on a down day), NOT dead; it was
  44.9% on 07-06. dow by-design.
- **PE mirror silence — LOOSENING toward REGIME.** 07-02/03/06 were 0–1 PE rows (all up-ish days); **07-07
  (down-biased expiry) produced 189 PE rows** — PE evaluates on a down day (scored low, none passed). Watch
  a clean trend-down day for whether PE composites can pass threshold.
- **`context.macro.vix` NULL while vix dot works** (07-06/07) — macro snapshot mirror blind though the dot
  path is fine (60.9% support on 07-07); candidate for a data-health flag, not a gate defect.
- **shadow entry latency p95 ~105s** (07-06 F8 measure) — no new shadows on 07-07 (0 opens); carry.
- **strategy-signal EVAL STALL (NEW 07-07)** — `signal-eval` hung 14:22:45 IST → close, NO exception,
  **market-data feed healthy the whole session** (candles/chain to 15:29–15:31). NEW signature vs 07-03's
  market-data tick/bar divergence — this is a consumer-side hang. Watch for recurrence; thread-dump on next
  occurrence; verify DataHealthCanary covers a strategy-signal-side hang (it watched market-data feed
  health, may not catch a live-feed eval hang).
- **`scalp-straddle-nifty` fires 0DTE ATM straddles (NEW 07-07)** — first fires ever, both LOST (−19%,
  −6.8%), one fired at composite 0.5 (below the 0.6 directional threshold), none auto-papered. Owner:
  confirm straddle-path threshold + whether 0DTE straddles should route to a paper book.

## Proposals (locked until ≥5 sessions — target 2026-07-09/10; now 4 of ~5 sessions logged)

*(none yet — the rollup pass writes ranked config diffs here, each with evidence citations)*
