# Weekly bug queue — 2026-07-25 (from the 07-21…07-24 routine docs)

Source: the four post-market forensics files, four open-gates, three midday-gates, four
live-watches and `rollup.md` for the trading week 2026-07-21…24 (PRs #964–#978). This doc
freezes the FIX ORDER the owner approved on 2026-07-25 ("document all bugs in recommended
order and start fixing them one by one"). Per-bug evidence lives in the dated findings
files; this is the queue, not the forensics.

Tier legend: **clean** = ship on CI-green; **HOLD/owner** = build/present, owner decides;
**investigate** = read-only first, fix scope unknown until then.

| # | id | bug (one line) | tier | status |
|---|---|---|---|---|
| B1 | **T16** | `relative-volume-floor` tag silently disarmed on all 18 PE scalpers by the 07-20 21:28 republish (fixed 125k floor > p99 of the operand; 73–78% of all rejections die there). Root cause: #605 armed the tag REGISTRY-side only — **0 of 63 YAMLs carry it**, so every seeder draft is tagless and any republish disarms. Fix = tag in the YAML source of truth + guard test + republish. Subsumes T11 (sensex family never armed). | clean (republish owner-directed 07-25) | **IN PROGRESS** |
| B2 | **T23** | Live 3m signal series ≠ its own in-memory 1m series, in exact NIFTY-lot multiples (65…6,110), ± pairs on consecutive buckets — `NIFTY26JULFUT@3m` only, 37–48 canary WARNs/session. Volume rails + `volume` dot read that series; the 09:15 opener was off 4.7%. Boundary-tick attribution race (inferred — code read FIRST, then fix; do NOT raise canary tolerance before the mechanism is pinned). | clean, code-read first | queued |
| B3 | **T19** | Gap-backfill writes 1m candles on UNALIGNED buckets (`12:51:38`…): distinct phantom PK rows, repair never lands, every `time_bucket` rollup double-counts, volume operands inflate after any outage (887/403/308 rows on 07-15/20/22; only `source='BACKFILL'`). Floor the window to the minute + clean historical rows + guard. | clean | queued |
| B4 | **T17+T13** | `DotHealthCanary` false all-dead readings ×4 (samples newest-40 rejections, which at EOD/pre-open are context-less — 15:58 call said all six dead while breadth supported 426/1,100). Fix sampling to context-bearing current-session rows; add `futures_oi`/`underlying_oi` NEUTRAL-share probes so the 07-20 OI-dot outage class pages. | clean | queued |
| B5 | **T14** | Rejection diagnostics self-contradict: `confluence-composite` rows record a composite that PASSES its own threshold as blocked (optional-gate mechanic stores the FULL composite while the REQUIRED-ONLY sub-composite failed) — 3 rows 07-24, 1 on 07-23. Make the margin invariant sign-aware per rail direction (the 40 `vwap-distance` positive margins are CORRECT — ceiling rail) and record the operand that actually failed. | clean | queued |
| B6 | **T20** | `FINNIFTY26SEPFUT` thin-tape fired the bar-close divergence canary 5 sessions running (35 bars/day vs 375 front) — the only recurring ERROR-channel noise. Exclude far-month contracts where thin-tape is the cause / liquidity-scale the threshold; keep genuine holes (AUGFUT 07-23, 341/375) alarming. | clean | queued |
| B7 | **T15** | Engine boot line (`loaded/unresolved/dropped`) exists only in container logs; post-close deploys destroyed it 07-17 + 07-20 and the week's readable boot lines were luck (`RestartCount=0`). Persist per-reload rows to a strategy-schema table. | clean (new migration) | queued |
| B8 | — | Routine scheduler misfired 07-24: open gate fired 11:02 (usual ~09:35), live watch ~11:05, **midday gate never ran** — no doc explains it; 07-23 also logged a ~17-min host-vs-container clock lag. Audit the scheduled-task definitions + host clock. Folds open chip task_a2ae20ed. | investigate | queued |
| B9 | **T12** | `/options/spurt` 400s on every parameter shape (post-close probe 07-20); `futures_oi_snapshots` cadence 208/192/187/198/211 of ~375 min across the week. Quadrants alive since #957, so severity is degraded-input, not outage — root-cause both. | investigate | queued |
| B10 | **T22** | `oi_spurt` dot decayed to fully dead (3.0% → 0.2% → 0.0% → 0.0%) with input data present — the #675/#676 recalibration did not hold. Next step is the §3.8 ground-truth `spurtOiPct` distribution, THEN a floor proposal from the operand, never a guessed number. | analysis → owner tune | queued |
| B11a | **T21** | 30 of 38 live scalpers have NO premium exit (no TP, no premium stop) — measured both directions: −88.4% ridden to square-off 07-23 vs −10.4% on the same leg with a stop; +37.5% banked vs +25.1% without. Intentional indicator-exit design or unfinished config? | **OWNER** | options pack queued |
| B11b | **T6** | `vwap` dot 100% support 6 consecutive sessions / 5,225 rows at the heaviest weight (2.5 = 12.8% Σw) — a free dot is an unlabelled threshold reduction. Narrow the support condition or cut the weight. | **OWNER** (gate number) | options pack queued |
| B11c | **T10** | 17 stale OPEN paper positions (oldest 07-07), swing brackets starved all session every day (equities not on the live tick subscription). Draining (19→17, 0 new ×2 sessions) but chronic. Subscribe the holdings, or accept EOD-only exits + downgrade the alert. | **OWNER** | options pack queued |

**Deferred by construction (not bugs — tunes blocked on data):** T1 (k), T7 (composite
threshold), T3 (iv_pair), T5 (iv_abs_band), T2 (iv_rank sourcing) — the rollup's own
conclusion stands: every PnL number since 07-21 is confounded by T16 (floor disarmed), T21
(no exits) and T23 (wrong operand); resolve those first or the tunes measure artifacts.

**Doc-hygiene note:** the 07-21/22/23 midday gates still carry "Timescale `non-Var pathkey`
mitigation scheduled 15:40 IST" — stale boilerplate; #957 shipped 2026-07-20 and OI quadrants
ran alive all four sessions. No action beyond this note.
