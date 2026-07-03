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

## Per-variant league (cumulative — refresh each rollup pass from the §6 league SQL)

| variant | closed | net wins | total net ₹ | total pts | verdict-so-far |
|---|---|---|---|---|---|
| champion | 20 | 0 | n/a (pre-F8) | −513.1 | gate was RIGHT on 07-03's grind day |
| vol-off | 0 | — | — | — | starts 07-06 |
| vol-12k5 | 0 | — | — | — | starts 07-06 |
| composite-070 | 0 | — | — | — | starts 07-06 |

## Structural-vs-regime watchlist

- **volume-floor 125k** — unpassable BOTH sessions (operand p50 ~6–12k). Structural mis-calibration,
  but 07-03 shadow says the veto SAVED money that day → verdict needs variant data (vol-off /
  vol-12k5 net ₹) across regimes, not another loosening argument.
- **Dead dots capping composite at 0.765** — identical both sessions: iv_rank (honest-null until IV
  history floor), iv_pair (unit-gap suspicion, README §7), breadth (FIXED #486 — expect ALIVE from
  07-06; flag here if still 0/0), oi_spurt price-floor 50 (max observed 22.2), dow (by-design).
- **PE mirror silence** — 07-03 was an up-day (1 PE row); watch whether PE evaluates on down days
  or something structural suppresses it.

## Proposals (locked until ≥5 sessions — target 2026-07-09/10)

*(none yet — the rollup pass writes ranked config diffs here, each with evidence citations)*
