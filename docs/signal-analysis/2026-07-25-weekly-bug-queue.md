# Weekly bug queue — 2026-07-25 (from the 07-21…07-24 routine docs)

Source: the four post-market forensics files, four open-gates, three midday-gates, four
live-watches and `rollup.md` for the trading week 2026-07-21…24 (PRs #964–#978). This doc
freezes the FIX ORDER the owner approved on 2026-07-25 ("document all bugs in recommended
order and start fixing them one by one"). Per-bug evidence lives in the dated findings
files; this is the queue, not the forensics.

Tier legend: **clean** = ship on CI-green; **HOLD/owner** = build/present, owner decides;
**investigate** = read-only first, fix scope unknown until then.

⚠️ **Status authority:** the `status` column here is a local-readability convenience. The
**authoritative** status of every row is its mirror in
[`../superpowers/plans/2026-07-02-remaining-items.md`](../superpowers/plans/2026-07-02-remaining-items.md)
§0a — when the two disagree, the ledger wins and this file gets corrected in the same PR. (Rows
B1–B4 below read `IN PROGRESS`/`queued` for a day after all four had merged *and* deployed; corrected
2026-07-25.) **Items that are NOT in the table** — the blocked tunes (item 7) and the parked T12
limiter proposal (item 5) — now carry ledger rows **G1** and **G5**; add a ledger row for anything new
you park in a closing bullet, or the next session will not find it.

| # | id | bug (one line) | tier | status |
|---|---|---|---|---|
| B1 | **T16** | `relative-volume-floor` tag silently disarmed on all 18 PE scalpers by the 07-20 21:28 republish (fixed 125k floor > p99 of the operand; 73–78% of all rejections die there). Root cause: #605 armed the tag REGISTRY-side only — **0 of 63 YAMLs carry it**, so every seeder draft is tagless and any republish disarms. Fix = tag in the YAML source of truth + guard test + republish. Subsumes T11 (sensex family never armed). | clean (republish owner-directed 07-25) | **DONE [#980](https://github.com/prashantm912/artha-yantra-2/pull/980)** — 63/63 YAMLs tagged + guard test; deployed, 38 republished, DB-verified (subsumes T11) |
| B2 | **T23** | Live 3m signal series ≠ its own in-memory 1m series, in exact NIFTY-lot multiples (65…6,110), ± pairs on consecutive buckets — `NIFTY26JULFUT@3m` only, 37–48 canary WARNs/session. Volume rails + `volume` dot read that series; the 09:15 opener was off 4.7%. Boundary-tick attribution race (inferred — code read FIRST, then fix; do NOT raise canary tolerance before the mechanism is pinned). | clean, code-read first | **DONE [#981](https://github.com/prashantm912/artha-yantra-2/pull/981)** — mechanism CORRECTED by the code read (no aggregation race; see the §"B2 (T23) correction of record" below): rollover baseline excludes the pre-open auction, canary tolerance 650 abs AND <=10% rel |
| B3 | **T19** | Gap-backfill writes 1m candles on UNALIGNED buckets (`12:51:38`…): distinct phantom PK rows, repair never lands, every `time_bucket` rollup double-counts, volume operands inflate after any outage (887/403/308 rows on 07-15/20/22; only `source='BACKFILL'`). Floor the window to the minute + clean historical rows + guard. | clean | **DONE [#982](https://github.com/prashantm912/artha-yantra-2/pull/982)** — normalized at the `fetchAndStore` choke point (covers the public refresh endpoint); live cleanup 2026-07-25 deleted 1,682 July phantoms, caggs refreshed, 0 remain (26k 2015-era twinless rows LEFT — only copy) |
| B4 | **T17+T13** | `DotHealthCanary` false all-dead readings ×4 (samples newest-40 rejections, which at EOD/pre-open are context-less — 15:58 call said all six dead while breadth supported 426/1,100). Fix sampling to context-bearing current-session rows; add `futures_oi`/`underlying_oi` NEUTRAL-share probes so the 07-20 OI-dot outage class pages. | clean | **DONE [#983](https://github.com/prashantm912/artha-yantra-2/pull/983)** — context-bearing sampling + `rowsScanned`; `futures_oi`/`underlying_oi` REQUIRED by default; expiry-day S24 exemption |
| B5 | **T14** | `confluence-composite` rows recorded a composite that passes its own threshold as "blocked" — the verdict is decisive-legs AND scalar (`ConnectTheDotsScorer:222`), but the margin was `aggregate − threshold` unconditionally. | clean | **DONE [#985](https://github.com/prashantm912/artha-yantra-2/pull/985)** (2 rounds — round 2 also fixed the opening-tick path naming a SOFT VWAP miss "decisive") — margin now null on decisive-leg blocks, negative on scalar blocks, positive slack on passes |
| B6 | **T20** | `FINNIFTY26SEPFUT` thin-tape fired the bar-close divergence canary 5 sessions running. | clean | **DONE [#986](https://github.com/prashantm912/artha-yantra-2/pull/986)** (codex first-pass APPROVED) — tick-density guard: flag only when ≥`artha.canary.min-divergence-ticks` (30) ticks accrued since the last close; thin tapes silent, genuine holes + the fault drill still fire |
| B7 | **T15** | Engine boot line existed only in container logs; post-close deploys destroyed it 07-17 + 07-20. | clean (V046) | **DONE #987** (2 rounds — V047→V046 ordering corrected [the parked swing branch renumbers at rebase]; sync JDBC → BoundedAsyncWriter so a DB stall can never park the eval/kite-recovery threads) — `strategy.engine_reloads` append-only ledger |
| B8 | — | 07-24 "scheduler misfire". | investigate | **CLOSED — NOT a cron fault: the host Windows time service is DEAD** (`not synchronized`, source Local CMOS Clock, never synced). Drift hit ~17 min (07-23) then ~87 min (07-24): tasks fired at "09:35" HOST time = ~11:02 real; the midday session ran at real ~14:00+ (exists, activity to 15:28 IST, produced no PR). Drift is 0 today (a reboot resynced) but UNPROTECTED — recurrence guaranteed until the owner re-enables time sync (system-settings change, owner-only): `net start w32time` + `w32tm /config /syncfromflags:manual /manualpeerlist:"time.windows.com,0x9 pool.ntp.org,0x9" /update` + `w32tm /resync`. Chip task_a2ae20ed (in-JVM scheduler sweep) is a different scope — **and is now CLOSED**: the sweep below ran it and its successors S1–S3 shipped as #1016 |
| B9 | **T12** | `/options/spurt` 400 + futures-OI capture cadence ~200/375. | investigate | **CLOSED** — endpoint RESOLVED: 200 on the consumer's exact shape (`name`+`expiry`[+`date`], live-probed); the 07-20 probe omitted `expiry` (the 400 is correct validation) and the underlying `latestPair` planner bug was #957-fixed the same evening — the operand has flowed every session since. Cadence: gap structure is 161 single-minute skips / 48 consecutive / max 3 min — a systematic ~2-min effective cadence, leading mechanism the batched `quotes()` pass queueing behind the shared 1/s kite-quote limiter (chain captures) so the serialized cron skips alternate fires. Consumers tolerate (3m buckets covered, quadrants alive ×4 sessions) — severity LOW; a dedicated limiter slice is an owner-visible rate-budget change, parked as a proposal |
| B10 | **T22** | `oi_spurt` dead — ground-truth distribution then a floor proposal. | analysis → owner tune | **ANALYSIS DONE** (4,118 context rows, 07-21..24): \|spurtOiPct\| p50=11 / p80=20 / p90=33 / p95=50; \|spurtPricePct\| p50=2.4 / p90=10. **The #675 recalibration lowered only the PRICE floor — `DEFAULT_SPURT_OI_PCT` is still 50 = p95 of its own operand.** Joint pass rates (pre-quadrant): current (50,8) **1.26%**; (30,8) 2.4%; (20,5) 6.3%; **(15,3) 15.5%** — the selective-but-alive target. Owner picks; env `artha.scalper.oi.spurtOiPct`/`spurtPricePct` |
| B11a | **T21** | 30 of 38 live scalpers have NO premium exit (no TP, no premium stop) — measured both directions: −88.4% ridden to square-off 07-23 vs −10.4% on the same leg with a stop; +37.5% banked vs +25.1% without. Intentional indicator-exit design or unfinished config? | **OWNER** | pack delivered 2026-07-25 (below) |
| B11b | **T6** | `vwap` dot 100% support 6 consecutive sessions / 5,225 rows at the heaviest weight (2.5 = 12.8% Σw) — a free dot is an unlabelled threshold reduction. Narrow the support condition or cut the weight. | **OWNER** (gate number) | pack delivered 2026-07-25 (below) |
| B11c | **T10** | 17 stale OPEN paper positions (oldest 07-07), swing brackets starved all session every day (equities not on the live tick subscription). Draining (19→17, 0 new ×2 sessions) but chronic. Subscribe the holdings, or accept EOD-only exits + downgrade the alert. | **OWNER** | pack delivered 2026-07-25 (below) |

## Owner-decision pack — 2026-07-25 (B11; decide any subset, each lands as its own PR)

**DECIDED 2026-07-25 (owner picked the recommended option on all four) — ALL SHIPPED:**
- **T21 → (b)**: premium bands SL −25% / TP +35% on all 42 bracket-less YAMLs — **#990** (3 review
  rounds; round 2 caught + fixed a live Critical: `premium_pct` resolved against the INDEX entry
  price made a one-bar force-exit for held-PE; `levelFromRules` deleted, index-side levels now
  persist NULL for premium_pct-only strategies). Sibling defect fixed separately: **#993**
  (`indexPointStopLevel` keyed on `definition.direction()` — wrong side for every PE-side take
  across the 12 `index_points` YAMLs, 8 enabled live). **DEPLOYED + REPUBLISHED 2026-07-25 ~23:00
  IST** — 28 enabled scalpers republished, 38/38 now publish a config carrying the bands (and still
  carrying the armed `relative-volume-floor` tag); deployed `SignalEngine.class` carries
  `entryExposureIsShort`.
- **T6 → (b)**: vwap dot support requires ≥15 bps distance — **#991**. Deploy-effective, no republish.
  **LIVE** — deployed jar's `application.yml` carries `vwap-min-distance-bps: 15`.
- **T10 → (b)**: EOD-only exits accepted; `PaperStaleTickAlerter` downgraded for the
  `minervini`/`manas-arora` books — **#992**. Deploy-effective. **LIVE.**
- **T22 → (15,3)**: oi_spurt floors — **#991**. Deploy-effective; judge combined T22+T6 over 2
  forward sessions. **LIVE** — deployed `spurt-oi-pct: 15` / `spurt-price-pct: 3`.
- **S1–S3 scheduler isolation → #1016** (dedicated bar-flush + Telegram pools, IST cron zone,
  bounded registry-reload query). **DEPLOYED + LIVE-VERIFIED** — thread dumps show
  `bar-flush-sched-1` and `telegram-poll-sched-1`.
- Still open from this pack: **T12 accept** (no build), **B8 host clock = OWNER before Monday**,
  item 7's blocked tunes (wait for post-fix forward sessions).

1. **T21 — premium exits on the 30 bracket-less scalpers.** (a) declare indicator-exit-only
   intentional and accept square-off rides (document it, done); (b) add a `premium_pct` band to the
   6 bracket-less families (HOLD build — owner supplies the numbers, e.g. the E9-style SL −25% /
   TP +35%; backtest `PremiumExitEvaluator` picks the YAML up, exit-equivalence fixture untouched
   since the SEMANTICS don't change, only which strategies configure them); (c) structural-stop-only
   middle ground. **Recommendation: (b)** — the same-leg evidence is both-directional and large.
2. **T6 — `vwap` free dot** (w 2.5, 100% × 6 sessions / 5,225 rows). (a) cut the weight 2.5→1.0;
   (b) narrow the support condition to a real distance (e.g. \|close−vwap\|/close ≥ x bps — x from a
   §3.8 distribution pull on ask); (c) leave, understood as a −12.8% effective threshold cut.
   **Recommendation: (b)** — keeps the dot meaningful instead of demoting it.
3. **T10 — 17 stale paper positions + starved brackets.** (a) subscribe the open swing holdings to
   the live tick set; (b) accept EOD-only exit evaluation + downgrade `PaperStaleTickAlerter` for
   swing books (the batch's trailing stop is already draining them: 19→17); (c) square off the
   backlog manually. **Recommendation: (b)** — swing books are EOD-managed by design; the alert
   noise (31k WARNs/day) is the real cost today.
4. **T22 — oi_spurt floors** (from the B10 ground truth): pick (15,3) ≈ 15.5% joint pre-quadrant
   (recommended), (20,5) ≈ 6.3%, or leave dead at (50,8) = 1.26%. Judge on 2 forward sessions —
   the iv_pair lesson says verify the revival, never assume it.
5. **T12 — futures-OI capture cadence** (~2-min effective): accept (consumers tolerate; 3m buckets
   covered) or fund a dedicated kite-quote limiter slice for the 1-min pass (rate-budget change).
   **Recommendation: accept**, revisit only if a consumer ever needs true 1-min.
6. **B8 — host clock (the one urgent one):** re-enable Windows time sync before Monday's open, or
   the routine-drift recurs. Command in the B8 row above; system settings = owner-only.
7. **Blocked tunes stay blocked:** T1 (k) / T7 (composite) / T3 (iv_pair) / T5 (iv_abs_band) /
   T2 (iv_rank) wait for post-fix forward sessions — the week's PnL measured the regressions.
8. **T6 concrete number (measured 2026-07-25, 4,118 rows):** the dot is free BECAUSE the entry
   gate already enforces the VWAP side — the dot re-measures the same condition. Distance
   distribution \|close−vwap\|/close: p25=11.8 / p50=16.3 / p75=23.4 / p90=38.6 bps. **Proposal:
   support iff right side AND ≥15 bps** (median split ≈ 50% support, restores discrimination).

## Scheduler-binding sweep — 2026-07-25 (closes chip task_a2ae20ed; successor items S1–S3)

Full sweep: 69 `@Scheduled` methods across both services, every binding verified (default pool-1 /
monitorTaskScheduler / evalOutcomeTaskScheduler / maintenanceTaskScheduler). Three findings beyond
what BEJ-01 (#919) fenced — all NEW, owner picks which to build:

- **S1 (HIGH, live data path): `CandleHousekeeping.flushBars` (`CandlesConfig:79`, 1-second bar-close
  sweep) rides market-data's DEFAULT pool-1** alongside the options-snapshot pass, whose own javadoc
  sizes one pass at ~70 s through the 1/s kite-quote limiter. While that pass holds the thread no
  bar closes — every 1m bar can close up to ~70 s LATE (availability, not content), pushing the
  engine's bar-close eval + receipt heartbeats toward their thresholds. `flushBars` is also itself
  a hog (per-bar sync JDBC + Redis publish inline). **Fix shape: dedicated scheduler (or async sink)
  for flushBars.** This also REFINES B9: the futures-OI alternate-minute skip is the even-minute
  options pass (cron `0 */2`) eating the odd-minute futures tick on the same thread — 2:1 exactly
  matches the observed 161 skip-1 pattern; the limiter is the secondary axis. Verify Monday via the
  `ay_options_snapshot_duration_seconds` histogram before building.
- **S2 (HIGH, money-adjacent): `TelegramCommandBot.poll` (3 s cadence, outbound HTTPS to
  api.telegram.org, LIVE-ARMED) shares strategy-signal's pool-1 with `PaperScheduler.bracketEvaluation`
  (the 15 s live SL/TP sweep)** + the straddle exit monitor + the insight sweeps (30 s-read HTTP
  each). One Telegram stall delays ten SL/TP sweeps. **Fix shape: move the poller (and/or the exit
  paths) off the default pool.**
- **S3 (MED): `ExpiredBackfillAutoResume.selfHeal` cron carries no `zone`** (only cron in either
  service missing explicit IST — harmless today, a trap if the expression ever gains an hour field);
  `ShadowVariantRegistry.reload`'s 5-min JDBC read has no verified query timeout on the SL/TP pool.

Off-session jobs (the 19:xx EOD block, 02:30 prunes, swing batches) are pool-1 by design and fine.

**Deferred by construction (not bugs — tunes blocked on data):** T1 (k), T7 (composite
threshold), T3 (iv_pair), T5 (iv_abs_band), T2 (iv_rank sourcing) — the rollup's own
conclusion stands: every PnL number since 07-21 is confounded by T16 (floor disarmed), T21
(no exits) and T23 (wrong operand); resolve those first or the tunes measure artifacts.

**Doc-hygiene note:** the 07-21/22/23 midday gates still carry "Timescale `non-Var pathkey`
mitigation scheduled 15:40 IST" — stale boilerplate; #957 shipped 2026-07-20 and OI quadrants
ran alive all four sessions. No action beyond this note.

## B2 (T23) correction of record — 2026-07-25 code read + DB probe

The 07-24 §6.1 / rollup hypothesis ("boundary-tick attribution race between two in-memory
aggregation paths") is **wrong in one load-bearing way: the engine performs no 3m aggregation at
all.** The 3m series is a REST-pulled SQL rollup of DB 1m rows
(`CandleRepository.rangeRolledFromOneMinute`), and the 10-minute recency window authoritatively
REPLACES those DB rows with broker-official Kite bars at every 3m boundary
(`ensureCoverage` → `upsertAuthoritativeAll`; DB-probed: every 07-24 session minute is
`source='KITE'` with `fetched_at` marching in exact 3-minute steps). The store's in-memory 1m
stays tick-agg and is never revised — the canary compared unlike series, and **the tick-agg side
was the wrong one.** Consequences:

- **The rollup's "the volume operand is measurably wrong at the open" is OVERSTATED** — the rails
  read the broker-corrected 3m rollup; the 09:15 +6,110 error lived in the tick-agg mirror, not in
  the floor's operand. (Transient sub-3-minute revision noise on the newest bucket remains.)
- The exact-lot ± pairs are the sub-second boundary-straddle between ~1 Hz cumulative-volume
  snapshots and the broker's trade-timestamped attribution — structural residue, ≤8 lots on 35 of
  37 measured events.
- The unpaired opening +94-lot error was a REAL tick-agg defect: `CandleBuilder` zeroed its
  baseline on day rollover, folding the pre-open auction quantity into the first bar a WARM
  process built. Fixed in B2 (baseline = the rollover tick's own cumulative, cold-boot semantics)
  together with a principled canary default tolerance of 650 (10 NIFTY lots), far below the
  frozen-first-minute signature the canary exists to catch.
- Open residual (not built): no divergence canary covers the 5m/15m/1h cagg rollups, and the
  in-memory 1m series also feeds the intrabar exit-level pass — price-based, so unaffected by the
  volume skew.
