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

## Per-variant league (cumulative — refresh each rollup pass from the §6 league SQL)

| variant | closed | net wins | total net ₹ | total pts | verdict-so-far |
|---|---|---|---|---|---|
| champion | 35 | 10 | **+19,274.61** (07-06 only; 07-03 pre-F8 null) | −201.0 | regime-split: RIGHT on 07-03 grind, WRONG on 07-06 trend (vetoed +₹19.3k winners) → **relative-floor case, not a fixed number** |
| vol-off | 2 | 2 | +4,051.27 | +64.5 | loosest config profitable on 07-06 trend (small n) |
| vol-12k5 | 1 | 0 | −160.15 | −1.4 | 1 marginal-loss trade (small n) |
| composite-070 | 0 | — | — | — | still ZERO rows all-time (unfalsified; cap now 0.816 may let it open — watch) |

## Structural-vs-regime watchlist

- **volume-floor 125k** — unpassable ALL THREE sessions (operand p50 ~6–12k). **Both regimes now sampled:**
  07-03 grind (veto SAVED 513 pts, 20/20 losers) vs 07-06 trend (veto COST +₹19,274, 10/15 winners). The
  effect is regime-flipped ⇒ the fix is a **relative `k×rolling-median` floor** (filters chop, admits
  impulse), NOT a lower fixed number. vol-off shadow (+₹4,051 on 07-06) agrees. This is now the #1 proposal
  candidate for the ≥5-session pass — evidence is complete, just needs 1–2 more variant sessions to size `k`.
- **Dead dots capping composite** — cap was 0.765 (5 dead), now **0.816 (4 dead)** after breadth revival.
  Remaining dead: iv_rank (honest-null until IV history floor), iv_pair (unit-gap suspicion, README §7),
  oi_spurt price-floor 50 (0% support all 3 sessions), volume (the 125k floor). breadth **CLEARED — #486
  live, 44.9% support on 07-06** (was 0/0). dow stays by-design.
- **PE mirror silence** — 07-02/03/06 all up-ish; still 0–1 PE rows. Watch whether PE evaluates on a down
  day or something structural suppresses the PE side.
- **`context.macro.vix` NULL while vix dot works** (NEW 07-06) — macro snapshot mirror blind though the dot
  path is fine; candidate for a data-health flag, not a gate defect.
- **shadow entry latency p95 ~105s** (NEW 07-06, F8 first measure) — likely inherent to the 3m-bar →
  next-tick-eval cadence; watch on a fast-reversal day where 90s drift could flip a fill.

## Proposals (locked until ≥5 sessions — target 2026-07-09/10; now 3 of ~5 sessions logged)

*(none yet — the rollup pass writes ranked config diffs here, each with evidence citations)*
