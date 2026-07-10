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
| *(2026-07-08, 2026-07-09)* | 0 | — | — | — | — | — | — | **OUTAGE — zero rejection rows both days (confirmed capture hole through market hours); no session analysis** |
| 2026-07-10 | 701 (09:19–**14:52** — 3rd EVAL STALL, feed alive to 15:29) | 35 | **9** (3 ENTRY + exits/re-entries) | volume-floor 424/701 (RELATIVE #605) | **219 (all CE — richest yet)** | 19, 9W/19, −24.3 pts, **−₹3,027.30** | 4 (breadth 86.7% = REGIME up-day) | **up-trend +110 pts. FIRST DIRECTIONAL FIRES: `golden-crossover` + `connect-the-dots` CE (comp 0.688) + straddle re-fire — relative floor passed volume-expansion bars. All small LOSERS (afternoon whipsaw, −2% to −9%), advisory-only. ALARM: 3rd eval stall 14:52; #634 canary in image but 0 logs + no recovery; findings `2026-07-10-session-findings.md` |

## Per-variant league (cumulative — refresh each rollup pass from the §6 league SQL)

| variant | closed | net wins | total net ₹ | total pts | verdict-so-far |
|---|---|---|---|---|---|
| champion | 54 | 19 | **−18,560.46** (07-06 +₹19.3k reversed by 07-10 −₹3.0k + prior grind losses) | −225.3 | net NEGATIVE all-time → the would-have-fired class mostly loses = **the gate is correctly rejecting losers** on balance; the +₹19.3k 07-06 trend day is the outlier |
| vol-off | 6 | 2 | **+2,231.07** | +41.2 | loosest config marginally profitable (small n=6); best variant |
| vol-12k5 | 2 | 0 | −247.33 | −1.6 | 2 marginal-loss trades (small n) |
| composite-070 | 0 | — | — | — | still ZERO rows all-time (unfalsified; cap 0.816 has not opened it — watch) |

## Structural-vs-regime watchlist

- **volume-floor — RELATIVE floor ARMED #605 (from 2026-07-07).** Fixed 125k was unpassable all 3 prior
  sessions and regime-flipped in effect (07-03 grind veto SAVED 513 pts / 07-06 trend veto COST +₹19,274).
  Now `k×median(prior-20)`, k=1.5. **1st live session (07-07): behaved correctly** — on a no-expansion
  expiry morning it still (rightly) blocked directional scalpers (bar vol < 1.5× median = no impulse), and
  the single-rail would-have-fired/shadow class **dissolved** (0 shadow opens). **Evidence source shifts
  from shadow book → real fires.** Owner tuning question is no longer "fixed vs relative" (settled) but
  "is k=1.5 right" — judge on real paper fills over ~1 month. SENSEX scalpers still un-armed (fixed 125k).
  **2026-07-10 update: FIRST DIRECTIONAL FIRES** — on an up-trend day with real volume expansion the floor
  PASSED `golden-crossover` + `connect-the-dots` CE (comp 0.688). Both lost small to afternoon whipsaw
  (−6.5% / −9.1%) — entries at 13:39 landed at a local top (24,229) before the future dipped then recovered.
  One session ≠ a k verdict; **watch entry-timing quality**, not just win/loss, over the tuning month.
- **Dead dots capping composite** — cap **0.816 (4 structural-dead, all 5 sessions)**: iv_rank
  (honest-null), iv_pair (unit-gap), oi_spurt (price-floor 50), volume (relative floor / no-expansion days).
  These are now the **§Proposals P1–P3** (iv_pair, oi_spurt, iv_rank). breadth is LIVE (#486) — regime, not
  dead (0% on 07-07 down day, 44.9% on 07-06, 86.7% on 07-10 up day). dow by-design.
- **PE mirror silence — REGIME (confirmed).** 07-02/03/06 were 0–1 PE rows (up-ish days); 07-07 (down-biased
  expiry) produced 189 PE rows; 07-10 (up day) 63 PE rows capped at the 0.5 bucket (never passed). Pattern is
  consistent: PE evaluates but only scores well on down days. Still awaiting a clean trend-DOWN day to see
  whether a PE composite can pass threshold.
- **`context.macro.vix` NULL while vix dot works** (07-06/07) — macro snapshot mirror blind though the dot
  path is fine (60.9% support on 07-07); candidate for a data-health flag, not a gate defect.
- **shadow entry latency p95 ~105s** (07-06 F8 measure) — no new shadows on 07-07 (0 opens); carry.
- **strategy-signal EVAL STALL — RECURRING, HIGH (07-07, 07-10).** Silent Redis `candles.1m.*` subscriber
  drop → `signal-eval` executor starves → nothing logs; **market-data feed healthy throughout** (candles to
  15:29). 07-07 stalled 14:22 (+ a self-recovered 12:18–13:20 gap); 07-10 stalled 14:52, no recovery before
  close. **`SubscriberHealthCanary` (#634) was DEPLOYED to catch exactly this** and IS in the 07-10 running
  image — but it logged nothing and eval did not auto-recover. **Priority: verify #634 actually pages +
  re-subscribes on a mid-session GREEN-feed-no-bar gap; its stall window or market-hours guard likely needs
  tightening** (both stalls were in the last ~40 min of the session — a window that clears at 15:30 close
  would miss them). Thread-dump on the next occurrence.
- **`scalp-straddle-nifty` fires 0DTE/short-dated ATM straddles (07-07, 07-10)** — 07-07 both LOST (−19%,
  −6.8%), 07-10 re-fired (13:09, 14:42) for a small loss. None auto-papered. Owner: confirm straddle-path
  threshold + whether these should route to a paper book.
- **Directional scalpers now fire (NEW 07-10)** — `golden-crossover` + `connect-the-dots` CE, first
  directional fires in analysis history, enabled by the relative floor. All small losers (whipsaw). The
  scalper family is now live-firing directionally, not just via the straddle path.

## Proposals (UNLOCKED — 5 sessions logged: 07-02, 07-03, 07-06, 07-07, 07-10; 07-08/09 outage skipped)

Ranked structural tune candidates. **STRUCTURAL** = the operand is dead every session because the threshold
sits outside its physical range or the feed is null — not day-dependent. All three below are dead-DOT
calibrations that raise the composite dead-weight cap above 0.816; each is owner-gated and lands as its own
PR (README §1 cadence). None changes a RAIL — they change how much composite headroom the dead dots waste.
Judge impact carefully: these help borderline 0.55–0.60 rows cross threshold, they do not change the fires
already passing at 0.68–0.89.

**P1 — `iv_pair` min-gap: `artha.scalper.oi.ivPairMinGap` 0.10 → 0.02.**
- **Diff:** `application.yml` / env `ARTHA_SCALPER_OI_IV_PAIR_MIN_GAP: 0.02` (verify exact passthrough name
  in `ScalperOiProps.java` before landing — CLAUDE.md `${ENV_NAME}` mismatch trap).
- **Evidence:** iv_pair dot support **0% on all 5 sessions** (2026-07-02/03/06/07/10). The rail asks for a
  ≥0.10 IV gap between the two 6-strike averages; that gap never occurs on NIFTY weeklies → the 0.8-weight
  dot is permanently dead, contributing to the 0.816 cap.
- **Risk:** loosening to 0.02 makes the dot *reachable*, adding up to 0.8/19.6 ≈ 0.041 of composite headroom
  on rows where the small IV skew genuinely supports the side. Low risk (it can only *activate* a currently
  never-firing dot); measure whether it then over-supports.

**P2 — `oi_spurt` price floor: `artha.scalper.oi.spurtPricePct` 50 → 5–10.**
- **Diff:** env `ARTHA_SCALPER_OI_SPURT_PRICE_PCT: 8` (verify passthrough name in `ScalperOiProps.java`).
- **Evidence:** oi_spurt dot support **0% on all 5 sessions**. The 50(%) price-move floor is unreachable on
  a 3m scalper bar (`macro.spurtPricePct` is populated non-zero but never near 50). 1.0-weight dead dot.
- **Risk:** 8% is still a real spurt filter on a 3m bar; setting it too low frees the dot (≈100% support).
  Pick the value from the observed `spurtPricePct` distribution (a rollup ground-truth query) before landing.

**P3 — `iv_rank` null semantics: null scores AGAINST → null = NEUTRAL/excluded (code).**
- **Diff:** in `ConnectTheDotsScorer` (or the iv_rank dot evaluator) treat `ivRank == null` as
  withhold-from-denominator (exclude the 0.8 weight from Σw) rather than supports=false. Parity-safe (live
  gate only; no golden vectors). Ties into README §7 backlog item 6 (dot-null unification).
- **Evidence:** `macro.ivRank` is **NULL every row, all 5 sessions** (honest-null — insufficient IV history).
  Today it silently scores against every CE, costing 0.8 weight of headroom. This is a DATA gap punished as
  bearish evidence, the exact mis-read README backlog #6 calls out.
- **Risk:** excluding it raises the cap and every composite slightly; do it together with a decision on
  iv_pair/fii null semantics so all dead-data dots are handled uniformly (one code change, one review).

**Not proposed (settled or regime):** volume-floor (ARMED #605, in k-tuning window — not a fixed-number
tune); composite threshold 0.6 (DECIDED-KEEP, cap 0.816 gives headroom); breadth (LIVE #486); all
day-dependent dots (rsi/vix/breadth/oi-divergence swing hard across sessions — regime, hold). **Exit-band
tunes are a SEPARATE track** (`docs/superpowers/plans/2026-06-30-live-signal-analysis-runbook.md`) and land
with these entry-gate tunes as ONE coordinated owner decision. **No auto-apply** — each proposal is an
owner-approved PR.
