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
| *(2026-07-11 – 2026-07-13)* | — | — | — | — | — | — | — | no findings files run (skipped; weekend + owner-paused days per ledger) |
| 2026-07-14 | — (starvation incident, see [[live-mode-findings]]) | — | 0 | — | — | — | — | **ZERO SIGNALS ALL SESSION — the eval loop parked on an unbounded market-data candle fetch (different root cause than the 07-07/07-10 subscriber-drop class). Fixed #866 (bounded fetch + dedup), deployed post-market. No dedicated findings file (incident tracked in `live-mode-findings` memory + ledger).** |
| 2026-07-15 | 396 (09:50–15:21, **4th EVAL STALL 11:49–14:10, self-recovered, thread-level PROVEN, #634/#679 logged nothing**) | 33 | **3** (straddle only) | volume-floor 241/396 | 144 (all CE, max 0.7 — no 0.8 bucket) | 12, 0W/12, −54.7 avg pts, −656.3 pts total | 4 (breadth 72.0% = REGIME) | **rangy/flat day (24,073.5→24,072.0, ±212pt range). 4th confirmed eval stall, DIRECT thread-log proof this time (exactly 1 `signal-eval` line in 2h21m) — capture + an unrelated scheduled canary stayed healthy throughout, masking it from casual checks. SEPARATE finding: 16/17 paper positions had zero live ticks all session (`PaperStaleTickAlerter`). oi_spurt dot showed first-ever life (1.6%) post-#675/#676 recalibration; iv_pair still 0% despite its recalibration. findings `2026-07-15-session-findings.md`** |
| 2026-07-16 | **0** (NULL SESSION — engine never evaluated a bar) | **0** of 39 published | 0 | — | — | — | — (dot-health: `rowsInspected 0`, every dot `alive:false`) | **F10 COLD-START STARVATION, 2nd occurrence (1st was 07-15, filed not fixed → recurred in a day). Stack cold-booted 08:57:04; engine `reload()` at 08:57:19–31 hit term-structure 403/DATA_STALE/breaker-open because market-data's IN-MEMORY `lastGood` cache is empty on a cold boot (a WARM container survives the same expired-token window off yesterday's cache — why only cold starts break); all 39 dropped → `loaded 0 published strategies` 08:57:31. Owner's Kite login landed 08:58:22 — **missed by 51s**. No reload path could recover (08:40 cron passed; 20s reconcile compares registry-vs-last-reload-SNAPSHOT so total failure ≡ steady state; no `kite.status` listener existed). CAPTURE WAS PERFECT throughout (375/375 1m bars, 373 OI snaps) — every health signal green while the engine did nothing. 18 paper positions unmanaged (28,441 `PaperStaleTickAlerter` WARNs) — supersedes 07-15's §6.2 "16/17 tickless" finding, SAME cause. **FIXED SAME NIGHT: [#874](https://github.com/prashantm912/artha-yantra-2/pull/874) @ d9f30a8f deployed + drill-proven live** (self-healed 0→39 from a `CONNECTED` alone). findings `2026-07-16-session-findings.md`** |
| 2026-07-17 | 523 (09:24–15:18 — **FULL session, no eval stall**, first clean coverage since 07-06) | **17 of 63 published** ⚠ | **3** (straddle only; **0 paper positions opened**) | volume-floor 292/523 (55.8%); time-window 149 (28.5%) | 210 (all CE, 0 PE; **202 parked exactly in the 0.6 bucket**) | 11, 7W/11, +381.9 pts, **+₹23,946.92** (best session ever — but only **4 distinct entry events**, 6 rows are the same 24000CE @267.05 09:45 leg across 6 strategies; deduped **2 of 4 events won**) | **5**: iv_rank (ivRank NULL 523/523), iv_pair (gap 0.0004 ≪ 0.02 — recalibration #675/#676 did NOT revive it), vix (data alive, 0% = up-day direction), basis (**NEW — no operand visible in `context.macro`**), iv_abs_band (ATM IV 12.49% just outside the 10–12 band). Cap unchanged at 0.816; effective live-dot bar 0.735. **`vwap` supported 359/359 = the heaviest dot (w 2.5) discriminated NOTHING — new watch item** | **trend-up CE day, 24000CE 267→371.** volume-floor blocked the day's money (+₹22,345 / 6W-4L attributed); `vol-off`≡`vol-12k5` delta +₹4,475 2W/2, `composite-055` delta +₹2,063 2W/2L (bought the same winner **plus two losers**). ⚠ **NEW ALARM §6.1: coverage collapsed 35→33→17 of 63; 38 sensex CE variants emitted ZERO rows** while SENSEX chain (361 snaps, expiry rolled 07-16→07-23) + `SENSEX26JULFUT` (375/375 bars) + eval loop were all healthy — **cause UNVERIFIED, 07-17 container logs destroyed by the 07-20 restart** → promoted to README **§3.10** (log the boot line the SAME day). ⚠ **CARRIED: 18 paper positions still OPEN since 07-16 20:00 IST**, two sessions unmanaged. Shadow entry latency structural at p50 76s / p95 95s. findings `2026-07-17-session-findings.md` |

| 2026-07-20 | **1,013** (10:19–15:19 endpoints, but **two interior holes** — nothing before 10:19, nothing 11:45–12:45, capture healthy through both) | **49 of 63 loaded** ✅ (07-17's collapse largely resolved; 31 sensex slugs vs 4) | **1** (straddle, comp 0.500; **0 paper positions**) | volume-floor 706/1013 (69.7%); time-window 201 (19.8%) | 230 (546 CE / **202 PE** — first real PE population, max 0.452, still never passes) | 17, 6W/17, −258.4 pts, **−₹5,881.86** (only **6 distinct entry events**, deduped **1 of 6 won**) | **7**: futures_oi (**NEW**, w1.5), underlying_oi (**NEW**), oi_spurt, volume, iv_rank (withheld from Σw), iv_pair, iv_abs_band. **basis REVIVED to 67.5% and vix to 54% — both 07-17 "dead" calls were REGIME.** Cap fell **0.816 → 0.7181** (reconciled exactly: 13.5/18.8 with iv_rank withheld; **30 rows sat on the ceiling**); effective live-dot bar 0.836 | **flat/rangebound day (+3.4 pts, 169-pt range, volume HALF of 07-17).** ⚠⚠ **TOP FINDING §6.2: all three OI dots dead session-wide — `futuresQuadrant`=`underlyingQuadrant`=NEUTRAL on 748/748, spurt pcts null 1013/1013. CODE-PROVEN NOT REGIME** (`OiInterpretation.classify` is total over 4 states, no dead zone; NEUTRAL means "data missing" only). Monthly-expiry suppression ruled out (Monday; weekly expiry is Tue). Chain capture clean (0 null OI over 34k/64k snaps) but **futures_oi_snapshots cadence −43% (208 of ~375 minutes)**; post-close probe: `/options/spurt` **400s**, `/futures/banks?name=NIFTY` **422s** while `name=NIFTY 50` 200s. Root cause UNPINNED — logs destroyed (§6.4). ✅ **§6.1a FIRST-EVER canary telemetry for the stall class** (#634 eval-stall 09:58 + #679 receive-stall/resubscribe 13:23, both recovered) — answers the rollup's "prove #634 CAN log a row"; but the largest hole (11:45–12:45) passed **unalarmed**. ⚠ **§6.1 CORRECTS the 07-17 file**: bucketing the interior shows 07-17 also had two holes — its "FULL session, no eval stall" was drawn from min/max only → new README **§3.11**. ⚠ **§6.3: 7 rows log a block with a POSITIVE margin** (composite 0.613–0.718 vs thr 0.600) — diagnostic self-contradiction. ⚠ **§2.1: SENSEX family still on the fixed 125k floor** (419 of 706 blocks) which is **above p95 (101,920)** of the signal series' own 3m distribution → new T11. volume-floor **SAVED money** today (11 blocked, 0 winners, −₹6,723) — T1 now **2-for/2-against**. New dimensions **§3.11 + §3.12**. findings `2026-07-20-session-findings.md` |

| 2026-07-21 | **1,372** (09:21–14:57, **interior fully covered — no hole, 0 `subscriber_health_events`**; tail after 14:57 empty, trade windows closing) | **22 of 38 loaded** (16 silent slugs are CE — tape, not load: `chart-gate-failed` counter 2,020) | **0** (0 paper positions) | volume-floor **1,069/1,372 (77.9%)**, every row at a flat **125,000**; time-window 256 (18.7%) | 218 (**216 PE** — max PE **0.7447**, max CE 0.6915) | 9, 7W/9, +177.5 pts, **+₹2,872.77** (only **5 distinct entry events**; deduped **2W/2L/1 flat ≈ −₹2,232**) | **4 structural**: iv_rank (withheld), iv_pair, iv_abs_band, volume (mechanically dead behind the 125k floor). **OI dots ALL RECOVERED** (futures_oi 57.6%, underlying_oi 63.9%, oi_spurt 3.0%); breadth 0.2% + vix 0.2% = REGIME. Cap recovered **0.7181 → 0.9043**; effective live-dot bar 0.664 | **mild-down NIFTY weekly expiry Tue (−35 pts, 145-pt range); PE-dominated tape (1,068 of 1,070 scored rows PE).** ⚠⚠ **TOP FINDING §2.1 — CONFIG REGRESSION: all 18 PE scalpers were re-published 2026-07-20 21:28:5x IST from seeder drafts missing the `relative-volume-floor` tag**, so every PE strategy reverted to the fixed 125,000 floor (`ScalperConfluenceGate.java:422` is `cfg.has(...)`-gated). Registry-proven: `scalp-connect-the-dots-nifty-pe` v1.0.1 (07-06) carries the tag, v1.0.3 (07-20 21:28:56, live today) does not; the same slug ran banded 12,188–32,370 **yesterday**. 125,000 sits above **p99 (117,455)** of the session's own 3m distribution — **1 passable bar in 125** → T16, highest priority. ✅ **§6.2 the 07-20 OI outage did NOT repeat** (0 NEUTRAL, 13 quadrant combos) **although `futures_oi_snapshots` cadence got WORSE (192/375 vs 208)** — refutes 07-20's cadence mechanism, leaves the `/options/spurt` 400 as the suspect. ✅ **§3 the PE-ceiling question is half-answered**: PE reached **0.7447 with 216 rows over threshold** — a PE composite passes fine; the volume rail, not the score, stopped them. ✅ **§6.1 first fully-covered interior since §3.11 was written**; ✅ **§6.4 first session whose boot line + eval counters survived** (`RestartCount=0`, no post-close deploy). ⚠ **§6.3 `supertrend` joined `vwap` at 100%** (vwap now 3 consecutive sessions / 2,177 rows). ⚠ **§4 `dot-health` reported ALL SIX dots dead at 16:58 including one it called alive at 09:44** — it samples the newest 40 rejections, which at EOD are `time-window` rows carrying no context at all (the 302 context-less rows = 256 time-window + 44 time-of-day + 2 option-side, exactly) → T17, a canary false-negative. ⚠ **§3 `breadth` is a one-constituent near-miss**, not dead: threshold `>32`, session max declines **31** → T18. §5.1: **6 of 6 would-have-fired rows WOULD-LOSE**; both loosened books 0-for-7 (−₹9,784) → **T1 now 2-for/3-against**. New dimensions **§3.13 + §3.14**. findings `2026-07-21-session-findings.md` |

| 2026-07-22 | **1,042** (09:18–15:18, **interior fully covered — 2nd session running**; the 12:45/13:00 dip is the §6.1 outage, directly attributed) | **36 of 38 loaded** ✅ best on record (the 2 silent slugs are CE `golden-crossover` on a −0.60% day; `chart-gate-failed` 2,028) | **0** (0 paper positions) | volume-floor **816/1,042 (78.3%)**, every PE row at a flat **125,000**; time-window 160 (15.4%) | **568 (all PE)** — max **0.8511**, the highest ever recorded here | 27, 18W/9L, +1,712.8 pts, **+₹42,240.91** (only **14 distinct entry events**; deduped **8W/6L ≈ +₹18,080**) | **4 structural**: iv_rank (withheld), iv_pair, iv_abs_band, volume (mechanically dead behind the 125k floor). breadth **96.6%** and vix 96.6% (both were 0.2% on 07-21 — pure regime); OI dots alive 2nd session. Cap **0.9043**; observed max = **94.1% of cap**, closest yet | **clean trend-DOWN day (−144.3 pts, −0.60%, 192-pt range).** ⚠⚠ **T16 STILL UNRESOLVED, 2nd session** — the registry is unchanged since the 07-20 21:28 republish, so all 18 PE scalpers (+ the 10 never-armed sensex CE) still run the fixed 125,000 floor while the armed nifty CE slugs ran **7,069–17,940** the same day; 125,000 clears **2 of 125** 3m bars. **And this time it cost money:** the 86 rows the floor **alone** vetoed resolve **56 WOULD-WIN / 30 WOULD-LOSE, +2,547.6 pts** — the exact inverse of 07-21's 6-of-6 losers (morning 39W/4L, midday 17W/26L). Both loosened books went **4-for-4** and `vol-12k5` turned **net-positive all-time**. ⚠⚠ **NEW DEFECT §6.2 (T19): the gap backfill writes 1m candles on an UNALIGNED bucket** (`12:51:38`, …) — distinct PK rows, so the repair never lands and every `time_bucket` rollup **double-counts**; 308 rows / 22 instruments today, 403 on 07-20, 887 on 07-15. It inflates the **live** `volume-floor` operand (3m median 13,520 → 14,885) → new README **§3.15**. ✅ **§6.1 a ~14-min Kite connectivity outage 12:51–13:05 IST was detected and self-recovered end-to-end** (ws+REST+niftyindices all failing = host/DNS level; circuit opened, feed watchdog restarted ×2, subscriber watchdog logged a 743 s receive-stall + resubscribe, recovery at 13:06:20) — the first time the whole stall-detection stack is recorded working on a real event. ✅ **§3 the PE-ceiling question is CLOSED**: 0.8511 with **568 rows over threshold**; the volume rail, not the score, stopped every one. ✅ **T18 CLOSED as regime** — `breadth` supported 96.6% today (declines 36–45) after 0.2% on 07-21. ⚠ **`vwap` 100% for a 4th session (3,005 rows)** → T6 is the best-evidenced row in the ledger. ⚠ **T17 widened** — the false `breadth DEAD` ERROR fired at **08:56 pre-open**, off *yesterday's* context-less tail. ⚠ **T10 escalating: 19 OPEN paper positions (was 15), 4 NEW from the 07-21 batch, 31,730 starvation WARNs, worst ~25,184 s (7 h).** ⚠ `futures_oi_snapshots` cadence fell a 3rd time (**187**/375 after 192, 208). New dimension **§3.15**; new candidates **T19**, **T20**. findings `2026-07-22-session-findings.md` |

| 2026-07-23 | **1,430** (09:19–15:19, **interior fully covered — 3rd session running; `subscriber_health_events` EMPTY**) | **38 of 38 loaded** ✅✅ **full coverage, a first** (set-difference against the registry returns the empty set) | **0** (0 paper positions; 6th consecutive fire-less session) | volume-floor **1,065/1,430 (74.5%)**, every PE/sensex-CE row at a flat **125,000**; time-window 268 (18.7%). **15 distinct first-block rails — widest tail recorded** | **634** — **416 PE / 218 CE, the first two-sided passing population**; max **0.8511** | 26, 8W/18L, −1,693.8 pts, **−₹30,946.11** (only **8 distinct entry events**; deduped **3W/5L**) | **5 structural**: iv_rank (withheld), iv_pair, iv_abs_band, volume (mechanically dead behind the 125k floor), **`oi_spurt` NEWLY DEAD** (3.0% → 0.2% → **0.0%**, input data present). breadth 22.1% = regime. Cap **0.8511** and **the session max IS the cap** — 48 rows sat on the ceiling | **flat/choppy day (+6.0 pts, +0.03%, 187-pt range) on a 2.7× thicker tape (3m median 36,595 vs 13,520).** ⚠⚠ **TOP FINDING §6.1 (T21): 30 of the 38 live scalpers have NO premium exit rule.** Only **21 of 63** YAMLs carry a `premium_pct` block (gap-theory / market-movers / hero-zero / btst-stbt / straddle); `golden-crossover`, `connect-the-dots`, `two-candle`, `trending-oi`, `trend-change`, `open-high-low` have **no take-profit and no premium stop** — their shadow rows carry `take_profit IS NULL` and **all 8 all-time `TAKE_PROFIT` closes belong to the 2 configured families**. This **invalidates the "+35% TP" assumption in every prior §4.2 counterfactual here**, 07-22's +2,547.6-pt headline included → new README **§3.16** + a rewrite of §4.2 step 4. Live exposure is real too: §5's SENSEX 76300CE rode **−88.4%** to the 15:12 square-off while the 2 positions on the identical leg that carried a structural stop lost 10.4%. ⚠⚠ **T16 UNRESOLVED, 3rd session** — registry byte-unchanged; **125,000 cleared 0 of 125 3m bars, above the session max (112,645)**, while armed CE slugs ran 45,971–65,032 on the same tape. **But today it SAVED money:** the 22 rows it alone vetoed are **6W/16L, −451.2 pts** (square-off-only model) — **3rd sign alternation in 3 sessions** ⇒ single-session PnL cannot decide T16; argue it as correctness. ✅ **T19 negative control: 0 misaligned 1m rows session-wide** (no outage ⇒ no backfill) — confirms the trigger. ✅ OI quadrants live a **3rd** session (0 NEUTRAL) while cadence plateaued at **198**/375 (after 187, 192, 208). ⚠ **T14 REFUTED AS SPECIFIED**: all 10 `vwap-distance` positive margins are semantically CORRECT (ceiling rail) — the invariant must be sign-aware; the one true self-contradiction is `id 7794` (composite 0.6373 ≥ thr 0.600, blocked by the composite rail). ⚠ **`vwap` 100% for a 5th session — 4,125 rows**. ⚠ **T17 3rd false `breadth DEAD`** ("across 10 rejections" while breadth supported 248/1,120). ⚠ **§6.4 `FINNIFTY26AUGFUT` is a GENUINE 34-minute bar hole** (341/375 bars), unlike the SEP thin-tape artifact → T20 respecified. ✅ §6.3 the 09:31–09:34 breaker burst cost exactly **one** chain capture cycle (09:30 bucket 1,290 vs 2,580–3,870), recovered. ✅ §6.5 the ~17-min host clock lag did not persist. T10 stable at 19 OPEN (0 new). New dimension **§3.16**; new candidates **T21**, **T22**. findings `2026-07-23-session-findings.md` |

| 2026-07-24 | **1,366** (bar times 09:18–**14:57**; interior fully covered 09:15→14:45, **4th session running; `subscriber_health_events` EMPTY**; the 15:00/15:15 buckets are empty — a chart-gate effect, ruled out as a stall by 375/375 eval cycles) | **36 of 38 loaded** (the 2 silent slugs are the `hero-zero` pair on a non-expiry Friday) | **0** (0 paper positions; 7th consecutive fire-less session) | volume-floor **998/1,366 (73.1%)**, every PE/sensex-CE row at a flat **125,000**; time-window 210 (15.4%); **14 distinct first-block rails** | **418** — 218 CE / 200 PE, two-sided a 2nd session; max **0.7447**, **no 0.8 bucket at all** | 22, 8W/14L, −191.3 pts, **−₹14,195.21** (only **11 distinct entry events**; deduped **3W/8L**) | **5 structural**: iv_rank (withheld), iv_pair, iv_abs_band, volume (mechanically dead behind the 125k floor), **`oi_spurt` dead a 2nd session** (0.0%). breadth 38.7% = regime; `trending_cross` collapsed 45.0% → **9.5%** (regime, watch). Cap **0.8511** but the session max is only **87.5% of it** — a market reading, not a ceiling | **quiet up-day (+130.0 pts, +0.55%, 214-pt range) on a THINNER tape (3m median 25,935 vs 36,595).** ⚠⚠ **TOP FINDING §6.1 (T23): the live engine's 3m signal series disagrees with its own in-memory 1m series — 37 `PartialBucketCanary` WARNs, ALL on `NFO:NIFTY26JULFUT@3m`, and EVERY shortfall is an exact multiple of the NIFTY lot size 65** (65…**6,110**), arriving in near-perfect **± pairs on consecutive buckets**. Both sides are in-memory (`LiveSeriesStore`), and the 3m side matches the DB rollup exactly (09:15: engine 131,300 = Σ aligned 1m) ⇒ a **boundary-tick attribution race**, NOT the frozen-first-minute partial the canary was built for (that signature is a persistent ~⅔ one-directional shortfall). Not cosmetic: `volume-floor`/`volume-pump`/`rising-volume`/`volume` all read that series, and **the 09:15 error was 4.7% of the bar — on the ONLY bar all session that cleared the 125,000 floor**. It also fired **48×** on 07-23, unreported (that file enumerated ERROR lines only) → new README **§3.17**. ⚠⚠ **T16 UNRESOLVED, 4th session** — registry byte-unchanged; **125,000 cleared 1 of 125 3m bars and that one is the 09:15 opener**, while armed CE slugs ran 29,396–53,284. **Protective again:** the 15 rows it alone vetoed are **0 economic winners in 15, −1,020.1 pts** (the 2 "wins" are +0.30 and +0.65 pts — same-bar structural stops) ⇒ **3 protective sessions in 4**; T1 now **3-for / 5-against**. ⚠ **T21 reproduces in BOTH directions on a 2nd session**: on the 09:48 PE leg the 5 bracket-less positions rode to −36.0% while the one with a structural stop lost −7.3%; on the 11:24 CE leg the one with a take-profit booked +37.5% vs +25.1% at square-off. ⚠ **T22 ESCALATED** — `oi_spurt` 0.0% a 2nd session with input data present; next step is the §3.8 `spurtOiPct` distribution, not a guessed number. ⚠ **T14 composite half confirmed 2nd time** (3 rows record a composite that passes its own threshold as blocked by the composite rail; 40 `vwap-distance` positive margins remain semantically CORRECT). ⚠ **T17 4th false reading** — at 15:58 all six dots called dead, `breadth` included, while breadth supported 426/1,100. ⚠ **`vwap` 100% for a 6th session — 5,225 rows**; `supertrend` hit 100% for the 2nd time. ✅ **T19 quiet a 2nd session** (0 misaligned rows, 0 kite-rest circuit-opens vs 93 on 07-23) — two clean negative controls. ✅ OI quadrants live a **4th** session while cadence **improved to 211**/375 (after 198, 187, 192, 208). ✅ **T20 respecification holds**: `FINNIFTY26AUGFUT` recovered to 375/375 (07-23's 34-min hole did not recur) while `FINNIFTY26SEPFUT` stayed thin-tape (35 bars, 14 of 14 ERRORs). ✅ **T10 IMPROVED for the first time — 19 → 17 OPEN** (the 07-23 20:00 swing batch closed CARYSIL −₹744 and ATHERENERG +₹429 on TRAILING_STOP; 0 new opens a 2nd session). ⚠ **`composite-055` had its first profitable session (+₹2,956) and is now the LEAST-BAD book all-time (−₹1,542 / 11 closes)** — T7's "reaffirmed by the challenger book" justification no longer holds. `strike-pick` fails rose 390 → **550**, 3rd-largest failing rail 2 sessions running, still un-bucketed. New dimension **§3.17**; new candidate **T23**. findings `2026-07-24-session-findings.md` |

| 2026-07-27 | **1,253** (09:18–15:18, **interior fully covered — 5th session running; `subscriber_health_events` EMPTY, 0 strategy-signal ERRORs**) | **38 of 38 loaded** ✅✅ best on record — the set-difference against the registry is EMPTY, so the 07-23/07-24 "which slugs go silent" question is answered: today, none | **3 ENTRY + 3 EXIT** (`golden-crossover-sensex`, `connect-the-dots-sensex` @14:03; `golden-crossover-nifty` @14:27, composite **1.0000**) — **first fires since 07-20**, and **0 paper positions**: all six went `EXPIRED` | volume-floor **768/1,253 (61.3%)**, every slug **BANDED 25,935–49,140**; time-window 282 (22.5%); **16 distinct first-block rails — widest tail recorded** | **253 (all CE, 0 PE)** — max **0.8511** | 41, 19W/22L, +506.4 pts, **+₹9,007.08** (only **21 distinct entry events**; deduped **9W/12L**) | **4 structural**: iv_rank (withheld), iv_pair, iv_abs_band, **volume (still 0% — the "dead behind the 125k floor" explanation is now FALSIFIED → T24)**. `oi_spurt` **REVIVED to 9.9%**, `vwap` fell to **17.5%**, breadth 79.5% = regime. Cap **ROSE 0.8511 → 0.9043**; observed max = 94.1% of cap | ⚠ **THE SIGNAL SERIES ROLLED to `NIFTY26AUGFUT`** (July monthly expiry 07-28) — every threshold and volume percentile here is a different series from all prior rows → new README **§3.18**. **Gap-up trending day (`NIFTY 50` +215.4 pts / +0.91% vs the 07-24 close; AUGFUT +119.0, 154.8-pt range, 3m median 22,620).** ⚠⚠ **THE HEADLINE: this is the first live session measuring the whole 2026-07-25 D-wave, and FIVE carried rows CLOSED.** ✅ **T16 RESOLVED** ([#980](https://github.com/prashantm912/artha-yantra-2/pull/980)) — all **38/38** enabled scalpers carry `relative-volume-floor`, published 07-25 21:44; **zero slugs on the flat 125,000**; the floor now sits between p55 and p92 of its own operand (was clearing 1 bar in 125). ✅ **T21 RESOLVED** ([#990](https://github.com/prashantm912/artha-yantra-2/pull/990)) — **63/63** YAMLs carry `premium_pct` (was 21/63); 16 of the 18 trading slugs had TP ×1.35 **and** SL ×0.75 set, and the book recorded its **first-ever `STOP_LOSS` closes (3, −₹8,017)**. ⚠ **But the new stop COST money on one leg**: on 09:48 `NIFTY26JUL23850CE` three positions stopped at −25.4% where the same leg/bar rode to **+17.1%** at square-off (`id 263`) — logged so T21 is not recorded as costless. ✅ **T6 RESOLVED** ([#991](https://github.com/prashantm912/artha-yantra-2/pull/991)) — `vwap` fell from **100% on six sessions / 5,225 rows to 17.5%** under the ≥15 bps condition (CE 22.0% / PE 0.0%); row-level check confirms the arithmetic (`id 11083` = 14.83 bps, correctly just under). **This changes what a 0.600 threshold MEANS — all pre-07-27 composite and challenger evidence is stale, T7 included.** ✅ **T22 RESOLVED** (#991) — `oi_spurt` **0.0% ×2 → 9.9%** on floors (15, 3); the §3.8 ground truth T22 had been demanding: `\|spurtOiPct\|` p50 −5.96 / **p90 14.44**, so a floor of 15 predicts ~10% and delivered 9.9%. ✅ **T17+T13 RESOLVED** ([#983](https://github.com/prashantm912/artha-yantra-2/pull/983)) — `dot-health` read CORRECTLY at 16:56 (`rowsScanned 200 / rowsInspected 40`, breadth+both OI probes alive and required), ending four straight false all-dead readings. ✅ **T12 cadence CONFIRMED FIXED** ([#1031](https://github.com/prashantm912/artha-yantra-2/pull/1031)) — `futures_oi_snapshots` **372 of 375 minutes (99.2%)** after 211/198/187/192/208; quadrants live a 5th session. ✅ **T19 quiet a 3rd session** (0 misaligned rows) and ✅ **T20 CLOSED** ([#986](https://github.com/prashantm912/artha-yantra-2/pull/986), 0 canary REDs). ⚠ **T23 is 92% quiet but NOT closed**: `PartialBucketCanary` fell 37 → **3**, two a benign ±845 pair — **but the 09:15 opening bucket shows the same unpaired shortfall #981 targeted, at +3,185 = 49 lots (was 94)** on a warm process that crossed the day rollover. Impact now low (that bar clears every floor by 2.4×); narrowed to the opening bucket, needs a code read. ⚠⚠ **§5.1: on a correctly-calibrated floor the rail was EXPENSIVE** — the 18 rows it alone vetoed resolve **8W/10L, +235.1 pts**, of which +162.5 comes from the 4 `connect-the-dots` rows (no structural stop, rode to square-off) while 10 of 12 `golden-crossover` rows died on their own stop. **T1 is now 4-for/5-against but the evidence base effectively RESTARTS today** — the four prior sessions measured a welded 125,000. ⚠ **NEW T24 (highest priority): the `volume` DOT is still 0/909 with the floor fixed** — dot and rail do not share a threshold, 1.0 of weight dead for an unexplained reason. ⚠ **NEW T25**: no scalper paper book exists (`paper_positions` = `minervini`/`manas-arora` only), so the 3 fires lapsed — owner arming question, not a proven defect. ⚠ **NEW T26**: ENTRY-path emit latency **~17 s** vs ~0.3 s on exits (`ay_signal_bar_to_emit_seconds`), first measurable because fires are rare. ⚠ **Points are NOT commensurable across NIFTY/SENSEX** (lot 75 vs 20) — the 09:48 pair flips sign between points and ₹; judge on net. ✅ The 11:08–11:16 Kite blip left the 1m stream **complete** and only a 3-minute OI hole. ⚠ T10 reversed: 17 → **18 OPEN** (2 new on the 07-24 batch). `composite-055` took **0 rows** and correctly so (0 eligible). `strike-pick` fails FELL 550 → 264. New dimension **§3.18**; new candidates **T24, T25, T26**. findings `2026-07-27-session-findings.md` |
| 2026-07-28 | **1,350** (09:18–**14:57**; two interior holes, BOTH ruled out as stalls against the eval counters: 09:24–09:42 is the pre-09:45 trade-window ramp — 4 evals/bucket stepping to 36 at 09:45 — and 15:00–15:30 is window narrowing at 2 evals/bucket with all **375/375** eval cycles completing; `subscriber_health_events` EMPTY, **0 ERRORs in BOTH services**) | **36 of 38 loaded** — the 2 silent slugs are the `hero-zero` pair, and **their own YAML explains it** (`scalp-hero-zero-nifty.yaml:6-15`: on a MONTHLY expiry the inert OI snapshot **degrades to a block** by design). The one family built for expiry day is silenced by the expiry-day suppression | **0** (0 paper positions; 0 shadow positions) | volume-floor **760/1,350 (56.3%)**, all slugs BANDED 25,057–68,835, **zero on a flat floor**; time-window 248 (18.4%); **16 distinct first-block rails**, incl. a first sighting of **`flat-oi-stand-aside`** (32 rows, all 4 `connect-the-dots` variants — the direct NEUTRAL-quadrant consequence) | **0** — max composite **0.4521**, and **max ACHIEVABLE 0.5479**, i.e. *below* the 0.600 threshold | **0 closes, no book traded** (league unchanged: champion 219 / −₹35,153.63) | **10 at 0%**: the seven S24-inert OI dots (`futures_oi` w1.5, `underlying_oi`, `oi_spurt`, `drastic_oi`, `sentiment`, `sentiment_slope`, `trending_cross`), `iv_rank` (withheld), `iv_abs_band` (8th session) and **`breadth` on a one-constituent regime near-miss**. `vwap` 1.5%, **`iv_pair` 1.7% = FIRST LIFE EVER**, **`volume` 3.6% = first non-zero**, `basis` 50.4% (the S24 positive control), `supertrend` 100%. Cap **0.9043 → 0.5479** | ⚠️⚠️ **NSE MONTHLY INDEX-EXPIRY DAY — this row is REGIME and contributes NO entry-gate calibration evidence.** Flat, narrow tape (`NIFTY 50` −14.5 pts / −0.06%, 86.3-pt range) on a THICKER contract (`NIFTY26AUGFUT`, derived per §3.18 from `context.chart.close` ∈ 24,050–24,150; 3m median **30,420** vs 07-27's 22,620, max 184,275). ⚠️⚠️ **TOP FINDING §3.1 — A FIRE WAS ARITHMETICALLY IMPOSSIBLE, and the number is exact.** The seven S24-inert OI dots (7.5 weight) plus `breadth` (1.0) score zero **while staying in the denominator** (§3.12), so the cap was **10.3/18.8 = 0.5479 against a 0.600 threshold**. Reconciled against the session's top row (`id 11467`: 8.5 supporting weight / 0.4521 = 18.80 denominator) — **even with every live dot supporting, no row could clear the gate.** Without `breadth` the cap would have been 0.6011, a 0.0011 margin. **Zero fires is mechanical, not a market or calibration reading.** ⚠️⚠️ **T24 ROOT-CAUSED IN CODE — a one-line call-site divergence, not a data problem.** `ConnectTheDotsScorer.java:141` calls the **2-arg** `ScalperGates.volume(underlying, volume)` overload, which resolves via `volumeFloorFor(underlying, null)` = the **static per-index default (NIFTY 125,000)**; the `relative-volume-floor` tag substitutes only at the RAIL call site (`ScalperConfluenceGate.java:422`). **The dot never sees the banded floor.** Arithmetic confirms it: 6 of 125 3m bars cleared 125,000 today (4.8%) → dot supported **38/1,068 (3.6%)**, its first non-zero in nine sessions; on 07-27 the series max was 117,000 so **zero** bars could clear it → 0/909. 1.0 of weight has been gated at ~p95 of its own operand on EVERY strategy, EVERY session. **Now a BUILD row, not a knob turn** → README §5 ledger rule. ✅ **§3.19 CONFIRMED AT 67× THE SAMPLE that wrote it** — this morning's live run derived the S24 suppression from 16 rows; the full session gives 1,068/1,068 on all four probes (both quadrants NEUTRAL, both spurt pcts NULL) with **`futuresBasis` LIVE on 1,068/1,068** (discriminator 1) and `futures_oi_snapshots` at **374/375 minutes = 99.7%** (discriminator 2). Capture healthy; the gate chose not to read it. Root = `NIFTY 50` on **all 1,068** rows (BSE monthly is Thu the 30th — the roots do NOT coincide). ✅ **§4 `fii` REVIVED and held ALL SESSION** ([#1050](https://github.com/prashantm912/artha-yantra-2/pull/1050)) — `fiiLongPct` = 8.78 on 1,068/1,068 after NULL 100% since 07-02. **The standing dead set is now the PAIR `ivRank` + `dowUp`, not the trio.** ⚠ **§6.3 [#1073](https://github.com/prashantm912/artha-yantra-2/pull/1073) was NOT deployed at 16:07 IST** (`RestartCount 0`, `StartedAt` 03:10 IST) **and its defect reproduced LIVE at EOD** — `oi_spurt_price` reported `input dead` while both quadrant dots correctly reported `NEUTRAL by design`. **The last direct observation of the pre-fix behaviour until the next monthly expiry.** The worse half (blanket `nse || bse` keying vs the row's own OI root) was *reachable* today — NSE expiring, BSE not — but not realised, since every row was NIFTY-rooted. ⚠ **T23 CHANGED SHAPE: 10 WARNs (was 3), but the 09:15 opening signature #981 targeted did NOT recur at all** on a process warm across the day rollover — a genuine negative control. The 2 unpaired events moved to the session's two THICKEST buckets (09:18 **+2,730** / 15:15 **−2,600** = 42 and 40 lots, 2.5% and 2.4% of bar), with 8 of 10 as ± pairs on consecutive buckets and **every shortfall an exact ×65 multiple**. Re-narrowed from 'the opening bucket' to 'the thickest buckets, either end'. ⚠ **§2.2 the would-have-fired set is EMPTY for EVERY rail** (nothing passed composite), so **T1 gains nothing in either direction** and no §4.2 counterfactual exists — stated explicitly so a later rollup does not read the absence as an oversight. G1's 'second clean forward session' is **NOT** today; earliest 2026-07-29. ⚠ **§3.2 `breadth` is a one-constituent near-miss AGAIN** — 0/1,068 with session-max advances **exactly 32** against a strictly-greater `>32`, the same shape as 07-21 (max 31). Input fully live (0 zero-pairs, 0 nulls, 10 distinct values) and the canary correctly calls it `alive`. **T18 stays CLOSED** — logged as a 2nd near-miss data point, not reopened. ⚠ **§3.3 `iv_pair` showed life for the first time ever (18/1,068 = 1.7%)** after nine sessions at 0.0% — **but on a monthly expiry, exactly where front-chain IV skew opens up. Explicitly EXCLUDED from T3's evidence base.** ⚠ **`strike-pick` fails REVERSED: 550 → 264 → 534**, 3rd-largest failing rail, **still un-bucketed for a 4th session**. ⚠ `max-oi-sr-gate` fails 1 → **48**, every one at operand 24,000 (expiry max-OI pinning). ⚠ T10 reversed again: 18 → **19 OPEN** paper (`manas-arora` 6 → 7). ✅ **T19 quiet a 4th session** (0 misaligned rows), **T12 holds** (374/375 minutes), **T16 holds** (38/38 armed, 0 flat floors), eval grid **375/375** (one better than 07-27), clock drift **<0.3 s**. New dimension **§3.20**; **T24 root-caused**, T23 re-narrowed. findings `2026-07-28-session-findings.md` |
| 2026-07-29 | **1,293** (09:18–15:12; **interior FULLY covered, 24/24 buckets — the first clean interior since 07-27**; `subscriber_health_events` EMPTY, **0 ERRORs in BOTH services**) | **34 of 38 loaded** — the 4 silent slugs are ALL `-pe` variants (`golden-crossover-nifty-pe`, `golden-crossover-sensex-niftyoi-pe`, `open-high-low-nifty-pe`, `open-high-low-sensex-niftyoi-pe`) on a **+1.06% one-directional up tape** with 971 of 983 scored rows CE. Boot line `loaded 38 (0 dropped, 0 failed)` ⇒ **not a T9 shortfall**; the block is chart-stage (upstream of `recordRejection`) but was NOT traced, so the directional attribution is consistent-with, not proven | **12 ENTRY + 8 EXIT** (counter `fired`=12 reconciles exactly); **4 paper positions — the scalper book's first fires ever**, all closed, net **−₹2,435.95**; **24 shadow positions**, all closed | volume-floor **756/1,293 (58.5%)**, all slugs BANDED 13,211–133,185, **zero on a flat floor**; time-window 252 (19.5%); **18 distinct first-block rails — the widest spread in the folder**; `confluence-composite` as a first-blocker collapsed 20 → **6** at avg margin −0.058 (near-misses, not starvation) | **311 of 983 (31.6%)** — max composite **0.9118**, cap **0.9574**, 50 rows in the 0.9 bucket | **24 closes, 14W/10L, +688.85 pts, +₹15,260.87 NET — the champion book's BEST session on record** (all-time −39,440 → **−19,892.76**). Split: **16 SQUARE_OFF +₹19,547.61** vs 8 STRUCTURAL_STOP −₹4,286.74 | **2 at 0%**: `iv_rank` (withheld from Σw, 11th session) and **`iv_pair`** (in Σw — the only dead weight). Everything else alive: `trending_cross` 5.8%, `oi_spurt` 8.5%, `vwap` 16.3%, `volume` **23.1%** (highest ever), `futures_oi` 49.9%, `underlying_oi` 52.2%, `breadth`/`vix`/`basis` 98.8%, `supertrend` 100%, **`iv_abs_band` 100% (a FREE dot on a FROZEN input — T28)**. First-ever sighting of **`premium_skew`** (16/30) | ✅ **THE SECOND CLEAN FORWARD SESSION G1 WAS WAITING FOR** — ordinary Wednesday, no expiry, whole OI bloc live. Trend-up day: `NIFTY 50` 24,176.65 → **24,241.00** (+253.40 / **+1.06%** vs 07-28's close). Contract **`NFO:NIFTY26AUGFUT@3m` confirmed DIRECTLY from the engine log** (1,308 rail-line occurrences; ⚠ the §3.18 range test alone was NOT decisive today — `chart.close` 24,258.60–24,278.00 falls inside BOTH AUGFUT and `NIFTY 50` day ranges). ⚠️⚠️ **TOP FINDING (T29) IS AN EXIT FINDING, AND IT IS BIGGER THAN ANY ENTRY KNOB ON THE TABLE.** Same tape, three exit models, opposite signs: champion shadow (no `time_stop`, holds to 15:12) **+₹15,260.87**; live paper (YAML `time_stop: 10 bars` armed) **−₹2,435.95** with **3 of 4 losing exits on `SENSEX26JUL77200CE`, a leg that ran 441.40 (11:00) → 580.25 (14:30)**; §4.2's 41-leg counterfactual under the 30-min stop **5W/36L, −538.50 pts with ZERO TP and ZERO SL touches**. ⚠ ONE trend day, and the two books do not trade the same entries — needs a chop-day counter-observation. **EXIT-BAND track, owner-coordinated.** ⚠️ **NEW T27 — the relative floor is mis-calibrated for the first 90 minutes and the OPENING SURGE is why.** `relativeVolumeFloor` is `1.5 × MEDIAN(prior N)` (code-read), robust to one outlier — but 4 of the first ten 3m bars are ≥100,000 (476,840 / 153,075 / 124,410 / 105,560) against a session median of **15,015**, so the median itself is high. Thresholds ran **133,185 at 09:45–09:57 (= p98.4 of the session's own distribution)** decaying to ~26,000 by 11:21 while the operand sat at 11,000–34,000; **326 of 756 blocks (43%) are pre-11:00**, a 1h15m slice of a 5h45m session. ⚠ The exact window was **NOT reconstructible from SQL** (two thresholds reconcile to a 10-bar DB median, a third does not; the engine reads its diverging in-memory series) — read `priorVolumes` before proposing a fix. ✅✅ **T1 IS ANSWERED AND REJECTED — the first real counterfactual for the knob.** The `volume-floor` would-have-fired set is **2W/9L, −121.95 pts**; **every one of the six rails' would-have-fired sets loses** (union of 41 distinct legs: 5W/36L, −538.50 pts). Loosening k 1.5 → 1.2 would have admitted losers. ⚠ T27 and T1 are DIFFERENT fixes — the floor's SHAPE is wrong, its LEVEL is vindicated; do not conflate. ✅✅ **T3 IS DEAD — the ground-truth query outstanding since 07-15 finally ran on a NON-expiry session.** CE-vs-PE 6-strike IV gap over 983 rows: p50 **0.00010**, p90 0.00050, **max 0.00070**. Live threshold 0.02 = **28× the session max**; the proposed 0.005 is still **7×** it. Put-call parity pins the two ATM-band averages together — **the operand cannot express the signal at any usable threshold. RE-SCOPED from a knob turn to drop-or-redefine** (dropping frees 0.8 and lifts the cap 0.9574 → 1.0000). ⚠️ **NEW T28 — `iv_abs_band`'s "revival" is a FROZEN operand.** `macro.atmIv` has **exactly ONE distinct value per session**, four running: 0.130859 / 0.135577 / 0.121736 / **0.118781** — so the 10–12 band is a per-day step function (07-28 just outside 0.12 → 0/180; today just inside → 133/133). Narrow: `ceIvAvg6` (41 distinct), `peIvAvg6` (44), `vixLevel` (27), `premiumSkewPct` (100) all move normally. **`DotHealthCanary` is BLIND to it** — a frozen value is non-null, so it reports `alive`. Cause NOT established (no producer-side read). **T5 superseded.** ✅ **T7 REJECTED WITH FORWARD EVIDENCE for the first time** — `composite-055` finally took rows (3) after two silent sessions and lost **−₹2,952.21**, the worst per-close book of the four. ✅ **T25 CLOSED by observation** — a scalper paper book now exists and traded (`subaccount_idx` 1–4, 0 left OPEN). ⚠ **T26 RE-CHARACTERISED: the 07-27 "entries 17 s / exits 0.3 s" split does NOT hold** — 20 emissions, mean **17.0 s**, with exits at 11.9–21.9 s too (exactly one fast exit at 606 ms). Uniform emit cost, not entry-specific. ✅ **T23 at its quietest ever: 6 WARNs, ALL exact ± pairs on consecutive buckets, ALL ×65 multiples, ZERO unpaired** — the 07-28 thickest-bucket signature did not recur. The largest shortfall (16,835 = **259 lots**) is **3.5%** of the 476,840 opening bar: provably benign by shape and it still alarmed ⇒ **the strongest argument yet that the 650 ABSOLUTE tolerance should scale with bar size**. ✅✅ **T24 (ledger G6) VERIFIED ON ITS FIRST LIVE SESSION — the fix shipped 2026-07-28 (#1082) and this is the session that proves it on data.** The `volume` dot supported **227/983 (23.1%)** on bars spanning **14,040–139,360**, only **5** of them ≥125,000 — so **222 of the 227 supports are reachable ONLY with the banded relative floor**, impossible under the pre-fix static NIFTY 125,000. Dot and rail are now the same test: the 756 non-supporting rows are exactly the 756 `volume-floor` first-blocks. Trajectory 0/909 (07-27, pre-fix) → 38/1,068 (07-28, pre-fix, expiry churn) → **227/983 (07-29, post-fix)**. ⚠ **The findings file's §2.3 first called this an unexplained anomaly and filed an open sub-question — WRONG, corrected by a dated addendum**: it reasoned forward from pre-fix source on a branch without checking what was deployed, while G6 already read DONE. Promoted to README **§3.23** (fingerprint the jar before attributing live behaviour to a code path). ✅ **§3.19's S24 reading now has a MATCHED CONTROL on the very next session** — quadrants NEUTRAL **0/983** and `spurtPricePct` NULL **0/983** where 07-28 was 1,068/1,068 on both. ✅ **`strike-pick` fails 534 → ZERO** — absent from the failing-rail table entirely; the 4-session "un-bucketed" carry closes as no-longer-observable (expiry-chain cause consistent, untested). ✅ **T12 BEST EVER at 375/375 OI minutes (100%)**, **T19 quiet a 5th session** (0 misaligned), **T16 holds** (38/38 armed, 0 flat floors), **T18 `breadth` back to 98.8%**, **T22 `oi_spurt` 8.5%** vs 07-27's 9.9%, eval grid **375/375**, clock drift **~0.17 s**, `RestartCount 0`. ⚠ **#1075 evidence, full session: 4 of 12 fires (all NIFTY-rooted) were UNFUNDABLE** — `NIFTY2680424000CE` at ~285–307 × lot 65 = ₹18,541–19,955 > ₹15,000, while all 8 SENSEX legs funded at 1 lot. **Reported, not acted on** (owner, 2026-08-12); ⚠ the funded legs LOST money, so "more of them" is not self-evidently better. New dimensions **§3.21** (a partial-session dot rate is not a finding — the midday run's `trending_cross` 0/722 was 57/983 by EOD) and **§3.22** (frozen operands); **§3.16 AMENDED — 63/63 YAMLs now carry `premium_pct`, the 21-of-63 text is historical**; §4.1 amended. findings `2026-07-29-session-findings.md` |

## Per-variant league (cumulative — refresh each rollup pass from the §6 league SQL)

Refreshed **2026-07-29** (through the 2026-07-29 session). Note the challenger roster changed: the
`composite-070` book was replaced by `composite-055`, so its all-time row is retired.

⚠ **2026-07-28 added NOTHING to this table — no book took a single row** (monthly-expiry composite cap
0.5479 below the 0.600 threshold, so no eligibility set existed for any book). **2026-07-29 moved every
row**, and it moved the champion book more than any session in the series.

| variant | closed | net wins | total net ₹ | total pts | verdict-so-far |
|---|---|---|---|---|---|
| champion | **243** | **106** | **−19,892.76** | +442.4 | ⚠️⚠️ **2026-07-29 is the best session on record by a wide margin: 24 closes, 14W/10L, +688.85 pts, +₹15,260.87**, taking the book −35,154 → **−19,893** and flipping cumulative POINTS positive (−246.5 → +442.4) for the first time. **The whole gain is in the exit model, not the entries:** 16 SQUARE_OFF rows (held to 15:12) carried **+₹19,547.61** while 8 STRUCTURAL_STOP rows lost −₹4,286.74. ⚠️⚠️ **BUT DEDUPE BEFORE QUOTING THIS: the 24 closes are NOT 24 independent observations.** They collapse to **6 bars / 12 distinct `(bar, leg, entry)` events**, and the **09:48 cluster alone carries +₹15,444.70 of the square-off gain (79%) and +₹14,625.93 of the session net (95.8%)** — one bar, replicated ten times because every scalper shares one 3m signal series and one StrikePicker. **The best session on record is essentially ONE BAR.** Effective independent sample ~6. ⚠️ **This fan-out is STRUCTURAL to the shadow book, so every cumulative number in this table inherits it** — dedupe by `(bar_time, tradingsymbol)` before quoting any W/L or per-close figure. The standing shape holds — the gate **rejects losers on chop/flat/quiet days and winners on trend days** — and 07-29 was a +1.06% trend day |
| composite-055 | **14** | **4** | **−4,494.36** | −86.6 | ⚠ **broke a two-session silence on 07-29 and lost on it: 3 closes, 1W, −₹2,952.21** — its first live outing since #991 changed what the composite means. On a per-close basis it is now the **WORST** of the four books (−₹321/close vs champion's −₹82). **First forward evidence AGAINST lowering the composite threshold (T7)**, where before there was only a re-baseline note |
| vol-12k5 | **41** | **13** | **−9,144.03** | −175.8 | won again on 07-29 (3 closes, +₹187.28) after 07-27's +₹4,059. Still the **least-bad of the two volume books** — the `vol-12k5 > vol-off` ordering has now survived **every** session where both traded |
| vol-off | **53** | **15** | **−17,574.05** | −361.5 | **lost on 07-29** (5 closes, −₹559.95) on a session where the tighter volume book won: took MORE entries for LESS money again, which is the ordering in miniature |
| composite-070 | 0 | — | — | — | RETIRED (never took a row; replaced by `composite-055`) |

⚠ **Every book is still net-negative all-time, but 2026-07-27 was the first session where all three
trading books made money simultaneously.** The reading remains **regime-driven and not converged**: on
trend days (07-06, 07-17, 07-22, **07-27**) the loosened books make money; on chop/flat/quiet days
(07-20, 07-21, 07-23, 07-24) they lose it.

⚠⚠ **All three confounders named here on 2026-07-24 were FIXED and deployed on 2026-07-25, and verified
live on 2026-07-27** — T16 (the disarmed relative floor, #980), T21 (30 of 38 slugs with no premium exit,
#990) and T23 (the opening-bucket operand error, #981, now 92% quiet). **The practical consequence is that
this entire league is measuring a pre-fix world.** Every cumulative number above except the 07-27 column
was earned under a welded 125,000 PE floor, exit-less positions, and a free `vwap` dot at weight 2.5.
**Treat 2026-07-27 as the start of the comparable series and do not decide T1 or T7 on the pre-07-27
cumulative.** Two clean forward sessions are needed (ledger G1; the second is earliest 2026-07-28).

⚠ **UPDATE 2026-07-28: today was NOT the second clean forward session.** It is an NSE monthly index
expiry, and the S24 OI suppression put the composite cap (0.5479) *below* the 0.600 threshold, so no rail's
block is falsifiable and no book traded. **G1 stays BLOCKED-DATA; the second clean session is now earliest
Wednesday 2026-07-29.** Standing rule for this table: **a monthly-expiry session is REGIME by
construction** (README §3.19) and must never be counted toward a forward-evidence quota.

✅✅ **UPDATE 2026-07-29: G1'S QUOTA IS MET AND EVERY BLOCKED TUNE RESOLVED — none of them by being
applied.** 07-27 was the first clean forward session and **07-29 is the second**. The forward evidence
resolves them as: **T1 REJECTED** (the `volume-floor` would-have-fired set is 2W/9L, −121.95 pts; all six
rails' sets lose), **T7 REJECTED** (`composite-055` lost −₹2,952.21 on its first post-#991 outing),
**T3 RE-SCOPED to a build** (the IV-pair gap maxes at 0.00070 against a 0.02 threshold — put-call parity),
**T5 SUPERSEDED by T28** (the operand is frozen, so the band was never the question), **T2 carried**
(still null). **G1 is CLOSED** (2026-07-29).

⚠️⚠️ **AND THE QUOTA ANSWERED A QUESTION IT WAS NOT ASKED.** Two clean forward sessions were meant to
decide entry-gate knobs. What they actually surfaced is that on 2026-07-29 **the exit model dominated the
P&L by an order of magnitude over any entry knob under discussion** — champion (no `time_stop`)
+₹15,260.87 vs live paper (`time_stop: 10 bars`) −₹2,435.95 on the same tape. **Any future forward-evidence
quota on this track should budget for a chop-day observation as well as a trend-day one**, because T29 is
exactly the row a trend-day-only sample cannot settle.

## Structural-vs-regime watchlist

- **⚠️⚠️ T29 — THE EXIT MODEL, NOT THE ENTRY GATE, WAS THE DOMINANT P&L TERM ON 2026-07-29 (NEW).** Three
  models on one tape: champion shadow (no `time_stop`, holds to 15:12) **+₹15,260.87**; live paper (YAML
  `time_stop: 10 bars` armed) **−₹2,435.95**, with 3 of 4 losing exits on `SENSEX26JUL77200CE` — a leg that
  ran 441.40 at 11:00 to 580.25 at 14:30; §4.2's 41-leg counterfactual under the 30-minute stop **5W/36L,
  −538.50 pts, with ZERO take-profit and ZERO stop touches**. **Ledger row G11** (OWNER, exit doctrine). **Classification: UNDETERMINED, pending a
  chop-day observation** — the time stop's whole purpose is to cut losers on chop days, and a trend-day
  sample structurally cannot see that. It is REGIME if the chop day reverses it and STRUCTURAL if it does
  not. Do not act on the trend day alone. Coordinate with the exit-band runbook. ✅ **EVIDENCE UPGRADED the same day — a CONTROLLED comparison exists inside the champion book itself.** The 09:48 cluster opened 12 rows on the SAME bar, SAME two legs, SAME entry LTP (six slugs x two roots — one shared signal series, one StrikePicker). **`market-movers-*` alone carries a structural stop; it fired at 09:52 while the other five held to square-off:** `NIFTY2680423950CE` @318.60 -> **−3.80 vs +16.85 x5**; `SENSEX26JUL77000CE` @613.90 -> **−20.85 vs +107.70 x5**. Entry constant, exit config the only variable, opposite sign — the confound the shadow-vs-paper comparison carries is absent. ⚠ Still ONE trend bar, and it is a STRUCTURAL stop not the `time_stop`, so it evidences *exit config dominated* rather than the `max_bars: 10` knob. Chop-day gate unchanged.
- **⚠ T27 — the relative volume floor's SHAPE is wrong even though its LEVEL is vindicated (NEW,
  STRUCTURAL, ledger row G10).** `1.5 × MEDIAN(prior N)` is robust to one outlier, but the opening surge is not one bar:
  4 of the first ten 3m buckets on 07-29 were ≥100,000 against a session median of 15,015, holding the
  floor at **133,185 (p98.4 of the operand's own distribution)** through 09:57 and above 40,000 until
  10:39. **43% of the session's `volume-floor` blocks (326 of 756) landed before 11:00** — a 1h15m slice of
  a 5h45m session, and the slice in which most of the fleet first comes in-window. **This is STRUCTURAL by
  construction** (the opening surge recurs every session) and is a BUILD, not a knob. ⚠ Do not conflate
  with T1: T1 (the multiplier) is REJECTED on the same session's counterfactual.
- **⚠ T28 — a THIRD dot state exists: frozen (NEW, STRUCTURAL, ledger row G12).** `macro.atmIv` carries exactly one
  distinct value per session across four sessions (0.130859 / 0.135577 / 0.121736 / 0.118781), making
  `iv_abs_band` a per-day coin flip on the 0.10–0.12 band — 0/180 on 07-28, **133/133 on 07-29**.
  `DotHealthCanary` cannot see it, because a frozen value is non-null and reports `alive`. **Generalise the
  lesson, not just the field:** every dot sitting at 0% or ~100% for a whole session now gets a
  DISTINCT-count on its operand before a threshold explanation is entertained (README §3.22).
- **✅ T3 CLOSED AS A KNOB — the ten-session-old ground-truth query finally ran and killed it.** The
  CE-vs-PE 6-strike IV gap maxes at **0.00070** over 983 non-expiry rows; the live threshold is 0.02 (28×)
  and the long-proposed 0.005 is still 7×. Put-call parity pins the two ATM-band averages together, so no
  threshold revives the dot. **The replacement is drop-or-redefine** (dropping frees 0.8 of denominator and
  lifts the cap 0.9574 → 1.0000) — filed as **ledger row G13**. ⚠ 07-28's "first life ever, 1.7%" was an expiry-day artefact and was
  correctly excluded from this evidence base at the time.
- **✅ The S24 by-design reading (README §3.19) now has a MATCHED CONTROL.** 07-28 (NSE monthly expiry):
  quadrants NEUTRAL 1,068/1,068, spurt NULL 1,068/1,068, seven OI dots at 0%. 07-29 (next session, same
  code, same contract, no expiry): quadrants NEUTRAL **0/983**, spurt NULL **0/983**, the same seven dots
  at 5.8–86.6%. The suppression hypothesis is now confirmed by a controlled before/after rather than by
  its own fingerprint.

- **✅✅ THE 2026-07-25 D-WAVE IS VERIFIED LIVE (2026-07-27) — five carried rows closed in one session.**
  `2026-07-27-session-findings.md` is the first forward measurement of every fix that landed on 07-25:
  **T16** all 38/38 scalpers back on the banded floor (thresholds 25,935–49,140; the flat 125,000 is
  gone), **T21** 63/63 YAMLs carrying `premium_pct` with the book's first-ever `STOP_LOSS` closes,
  **T6** `vwap` 100% → 17.5% under the ≥15 bps condition, **T22** `oi_spurt` 0.0% → 9.9% under floors
  (15, 3) with the ground-truth p90 (14.44) confirming the number, **T17+T13** `dot-health` reading
  correctly after four false all-dead sessions, and **T12** OI cadence 211 → 372 of 375 minutes.
  **Two things follow for this rollup.** (1) **The comparable series starts 2026-07-27** — every earlier
  session measured a welded PE floor, exit-less positions and a free 2.5-weight `vwap` dot, so
  cross-session composite, cap and league comparisons spanning 07-25 are invalid. (2) **Three NEW rows
  opened**: **T24** the `volume` DOT is still 0/909 with the floor fixed, which *falsifies* the standing
  "mechanically dead behind the 125k floor" explanation and leaves 1.0 of weight dead for an unexplained
  reason (highest new priority); **T25** no scalper paper book exists, so the session's 3 fires lapsed
  `EXPIRED` (owner arming question, not a proven defect); **T26** ENTRY-path emit latency ~17 s vs ~0.3 s
  on exits. **And one counter-example worth keeping**: T21's new stop COST money on the 09:48
  `NIFTY26JUL23850CE` leg (three positions stopped at −25.4% where the same leg/bar rode to +17.1% at
  square-off) — T21 is resolved, not costless.
- **⚠ SIGNAL-CONTRACT ROLL (NEW 2026-07-27 → README §3.18).** 2026-07-27 evaluated
  `NFO:NIFTY26AUGFUT@3m`; every prior row here measured `NIFTY26JULFUT`. Nothing in `signal_rejections`
  names the contract — the roll is only identifiable by matching `context.chart.close` against candidate
  contracts' ranges. The two series' 3m volume distributions differ materially (AUGFUT p90 47,320 / max
  117,000 vs JULFUT p90 57,785 / max 222,560), so a §3.8 ground-truth query run against the wrong
  contract silently mis-places every threshold. **Name the signal contract in every session file, derived
  from the data.** Next roll ~2026-08-25.
- **⚠⚠ THE LIVE 3m SIGNAL SERIES DISAGREES WITH ITS OWN 1m SERIES, IN EXACT LOT MULTIPLES (NEW
  2026-07-24, LARGELY FIXED #981 — T23 NARROWED, NOT CLOSED, as of 2026-07-27: WARNs fell 37 → **3**,
  but the **same unpaired opening-bucket shortfall recurs at +3,185 = 49 lots** (was 94) on a warm
  process that crossed the day rollover — the exact condition #981 addressed. Impact is now low (the
  09:15 bar clears every floor by 2.4×). Next step is a code read of the post-#981 rollover baseline;
  either the fix is partial or a second contributor exists at the open.).** `PartialBucketCanary` WARNed **37×** on 2026-07-24 and **48×** on
  2026-07-23, **exclusively on `NFO:NIFTY26JULFUT@3m`** — the series every live scalper signals off.
  Every shortfall is an exact multiple of the NIFTY lot size **65** (65, 130, 195, 260, 325, 390, 455,
  520, 845, 1,560, 1,820, **6,110** = 1…94 lots) and they arrive in near-perfect **± pairs on
  consecutive buckets**. Both sides of the comparison are **in-memory** (`LiveSeriesStore` 3m vs 1m —
  the canary does no DB read and no REST call), and the 3m side agrees with the database exactly
  (09:15 bucket: engine 131,300 = 72,995 + 28,340 + 29,965 from the store's own aligned 1m bars), so
  it is the **in-memory 1m sum** that diverges ⇒ a **boundary-tick attribution race between the two
  aggregation paths**, and specifically **NOT** the frozen-first-minute partial the canary exists to
  detect (that signature is a persistent ~⅔ one-directional shortfall). **Impact is real, not
  cosmetic:** `volume-floor`, `volume-pump`, `rising-volume` and the `volume` dot all read the 3m
  series; 35 of the 37 errors are ≤ 8 lots (≤ 0.1% of a median 3m bar) but the **09:15 opening bar was
  off by 94 lots = 4.7%** — and 09:15 was the **only** bar all session that cleared the fixed 125,000
  floor. Two separable actions: **(a)** fix the boundary attribution (the write path was NOT read —
  T23 needs a code read before a fix is designed), **(b)** only then raise
  `artha.signals.partial-bucket-canary.volume-tolerance` above 0, which the canary's own javadoc
  anticipates. **Do not do (b) first** — muting the benign residue before the mechanism is understood
  also mutes the regression the canary exists to catch. Promoted to README **§3.17**. Sessions before
  07-23 are unmeasured (logs gone). **CORRECTED + FIXED 2026-07-25 (bug-queue B2, PR #981):** the
  code read + DB probe overturned the two-in-memory-aggregations reading — the 3m side is a
  REST-pulled rollup of DB 1m rows that the recency window replaces with broker-official Kite bars
  (so the RAILS read corrected data and "the operand is measurably wrong at the open" is retracted);
  the tick-agg 1m mirror is the diverging side. The real defects fixed: warm-process day-rollover
  baselined at zero (pre-open auction folded into the 09:15 bar — the +94-lot outlier) and the
  canary's zero tolerance against structurally noisy tick-agg (now 650 absolute AND ≤10% relative,
  thin frozen bars still fire). See README §3.17 (rewritten) and
  `2026-07-25-weekly-bug-queue.md` §B2.
- **✅ PAPER-POSITION BACKLOG IS DRAINING (updated 2026-07-24; T10 CLOSED 2026-07-25 — owner
  decision (b): EOD-only exits accepted + alert downgraded, #992).** Swing-book bracket-starvation
  WARNs/pages are **suppressed BY DESIGN since #992** for the `minervini`/`manas-arora` books
  (`artha.paper.eod-managed-books`) — the `ay_paper_bracket_starved_total` metric still counts, and
  the settle-side alerts (settleRefused / staleSettleUsed) are NOT exempted. Do not re-flag the
  absent WARNs as a regression in future session forensics. 19 → **17**
  OPEN: the 07-23 20:00/20:05 swing batch closed `CARYSIL` (−₹744) and `ATHERENERG` (+₹429) on
  `TRAILING_STOP`, and **no new positions were opened for a second consecutive session**. The
  mechanism is unchanged — these exited on the EOD batch's trailing stop, not on a live intraday
  bracket, and `PaperStaleTickAlerter` still WARNs continuously because the equities are not on the
  live tick subscription — so the owner question (subscribe the holdings, or accept EOD-only exit
  evaluation and downgrade the alert) stands. But the population is no longer accumulating: **chronic,
  draining.**

- **~~30 OF 38 LIVE SCALPERS HAVE NO PREMIUM EXIT RULE~~ (NEW 2026-07-23, RESOLVED 2026-07-25 — T21).**
  **Owner decision (b): premium bands SL −25% / TP +35% added to all 42 bracket-less YAMLs (#990).**
  The build surfaced and fixed a live one-bar force-exit defect on the way: the engine resolved
  `premium_pct` rules against the INDEX entry price (a below-entry "stop" that
  `structuralStopHit(SHORT)` trips immediately for held-PE) — `levelFromRules` is deleted, the
  engine persists NULL index-side levels for premium_pct-only strategies, and the band is enforced
  on the option leg by the paper bracket path. A sibling defect of the same class
  (`indexPointStopLevel` keyed on `definition.direction()`, wrong side for every PE-side take across
  the 12 `index_points` YAMLs) shipped separately as #993. **Takes effect only after deploy +
  republish** of the affected strategies; judge on forward sessions.
  Only **21 of the 63** scalper YAMLs carry a `premium_pct` block (`gap-theory`, `market-movers`,
  `hero-zero`, `btst-stbt`, `straddle`). The `golden-crossover`, `connect-the-dots`, `two-candle`,
  `trending-oi`, `trend-change` and `open-high-low` families have **no take-profit and no premium
  stop** — they can exit only on an indicator signal-exit (which the shadow book does not
  replicate), a structural stop where configured, or the 15:12 square-off. DB-confirmed: those
  shadow rows carry `take_profit IS NULL` / `stop_loss IS NULL`, and **all 8 `TAKE_PROFIT` closes in
  the book's history belong to `gap-theory` / `market-movers`**. Two consequences: **(1) analytical
  —** every prior §4.2 counterfactual in this folder that applied "+35% (E9 default)" to a
  bracket-less slug overstated the win side, 07-22 §5.1's +2,547.6 pts included; promoted to README
  **§3.16** with §4.2 step 4 rewritten. **(2) live —** a long-premium position with neither bracket
  rides to 15:12: on 07-23 the SENSEX 76300CE went 330.85 → 38.45 (**−88.4%**) while the two
  positions on the identical leg that carried a structural stop lost 10.4%. Whether this is
  intentional (indicator-exit-only design) or unfinished config is an **owner** question.
- **⚠⚠ GAP-BACKFILL WRITES 1m CANDLES ON AN UNALIGNED BUCKET (NEW 2026-07-22, UNRESOLVED — T19).**
  After the 12:51–13:05 outage the backfill stored bars at the tick-gap's **second offset**
  (`12:51:38`, `12:52:38`, …). `marketdata.candles` is keyed
  `(exchange, tradingsymbol, interval, bucket)`, so these are **distinct phantom rows**: the
  backfill never replaces the bars it was meant to repair, and every `time_bucket` rollup sums the
  phantom *and* the real bar. **308 rows across 22 instruments** on 07-22, **403** on 07-20, **887**
  on 07-15 — only `source='BACKFILL'` is ever misaligned. This is not cosmetic: 3m reads are a
  read-time rollup over the 1m base, so the live `volume-floor` operand, `volume-pump`,
  `rising-volume` and the `volume` dot all **inflate after any feed outage** (session 3m median
  13,520 → 14,885 on 07-22; far larger inside the window). Root cause inferred from the call path
  (`GapBackfillService.backfill` hands `CandleQueryService.backfillRange` the raw tick-gap instant)
  — the write site was not read. Every §3.8-class query now needs
  `EXTRACT(second FROM bucket) = 0` ⇒ promoted to README **§3.15**; earlier findings files in this
  folder carry an unquantified upward bias on post-outage sessions. **Negative control 2026-07-23:
  zero misaligned rows session-wide on a day with no outage and therefore no backfill** — the
  trigger is confirmed to be the gap-backfill path and nothing else.
- **~~`oi_spurt` HAS DECAYED TO FULLY DEAD~~ (NEW 2026-07-23, RESOLVED 2026-07-25 — T22).**
  **Owner decision: floors recalibrated (50,8) → (15,3) (#991), ≈15.5% joint pre-quadrant pass on
  the B10 ground truth (4,118 rows).** Spring props, live at the next deploy with NO republish.
  Per the iv_pair lesson: verify the revival over 2 forward sessions, never assume it — and judge
  the COMBINED effect with the T6 vwap change below (they push composites in opposite directions). Support ran 3.0%
  (07-21) → 0.2% (07-22) → **0.0% of 1,120 rows** (07-23), a monotone decline, while the input data
  stayed present (`spurtOiPct` non-null on every context-bearing row). So this is a
  threshold-vs-operand question, not a feed outage — the same shape as `iv_pair` (T3), which the
  #675/#676 recalibration failed to revive. One more session before proposing a number.
- **✅ THE FULL STALL-DETECTION STACK WORKED ON A REAL EVENT (NEW 2026-07-22).** A ~14-minute
  host-level connectivity outage (12:51–13:05 IST: `ws.kite.trade` connect failures, `api.kite.trade`
  I/O errors **and** `liveindexsa.niftyindices.com` — so DNS/host, not Kite-specific) opened the
  kite-rest circuit, and every guard fired in order: chain snapshots degraded to cached, feed
  watchdog restarted the feed at 212 s and 815 s of tick age, the subscriber watchdog logged a
  **743 s receive-stall + resubscribe**, the data canary went RED feed-wide, and
  `candle receipt recovered (20s)` at 13:06:20 — three `subscriber_health_events` rows, no human
  action, full recovery inside 15 minutes. Together with 07-20 this closes the rollup's standing
  question about #634/#679 for good. **Residual:** the recovery path is what produced T19.
- **✅ THE PE-CEILING QUESTION IS CLOSED (2026-07-22).** On a clean trend-down day (−0.60%) the PE
  composite reached **0.8511 with 568 rows over the 0.600 threshold** — 54.5% of all rejections,
  and **94.1% of the dead-weight cap**, the closest any session has run to its ceiling. PE does not
  merely pass; on the right tape it passes in bulk. Every one was stopped by the §2.1 volume rail.
  Remaining open: whether a PE fire is *profitable* — no PE signal has ever fired.
- **⚠⚠ THE `relative-volume-floor` TAG WAS SILENTLY DISARMED ON ALL 18 PE SCALPERS (NEW 2026-07-21,
  STILL UNRESOLVED after 2026-07-22 — the highest-priority open item).** 07-22 re-ran the §3.14
  registry query: **unchanged**, and 816 of 1,042 rejections (78.3%) died on the flat 125,000 floor
  while the armed nifty CE slugs ran **7,069–17,940** on the same tape. **07-22 attaches money to
  it, in the opposite direction from 07-21:** the 86 rows the floor *alone* vetoed resolve
  **56 WOULD-WIN / 30 WOULD-LOSE, +2,547.6 pts** (morning 39W/4L, midday 17W/26L), and both
  loosened books went 4-for-4. Read the two sessions together: **the rail's P&L sign is regime, the
  regression is not.** Argue T16 as *restoring an armed knob*, never as a tuning bet. A single publish batch at **2026-07-20
  21:28:55–21:28:56 IST** promoted seeder drafts whose tag list omits `relative-volume-floor`, and
  the gate is tag-gated (`cfg.has("relative-volume-floor") ? relativeVolumeFloor(...) : fixed`,
  `ScalperConfluenceGate.java:422-426`), so from the 07-21 open every PE strategy ran the **fixed
  125,000** floor. Registry proof — `scalp-connect-the-dots-nifty-pe`: v1.0.1 (07-06) **has** the
  tag, v1.0.2 (07-07 00:21, seeder `resyncConfig` draft) **lost** it, v1.0.3 (07-20 21:28:56,
  published, live today) **lacks** it. Row proof — the same slug's `volume-floor` thresholds ran
  12,187.5–32,370.0 on 07-20 and **min = max = 125,000.0** on 07-21. Impact: **1,069 of 1,372
  rejections (77.9%)** died there, at a threshold above **p99 (117,455)** of the signal series' own
  3m distribution (1 passable bar in 125). **This is a regression, not a tuning question** —
  restoring the tag is a config action, but re-publishing changes live behaviour so it stays
  owner-gated (**T16**, and it subsumes T11 since the 10 sensex CE slugs published 06-29/06-30 never
  carried the tag either). **Standing method fix:** verify the armed knob in the *published config*
  every session, not only in the rejection rows — promoted to README **§3.14** with the SQL.
- **⚠ `dot-health` canary reports false deaths at end of day (NEW 2026-07-21).** At 16:58 it called
  all six probed dots dead — including `oi_spurt_price`, which it had called **alive** at 09:44 the
  same day — and emitted the session's only strategy-signal ERROR line (`required dot 'breadth'
  input DEAD`). Cause: it inspects the **newest 40 rejections**, which after ~14:45 are all
  `time-window` rows, and rows blocked at an early rail carry **no macro/OI context at all**. 07-21
  quantified this exactly: 302 context-less rows = 256 `time-window` + 44
  `time-of-day-preference` + 2 `option-side-constraint`. Any dot-health reading taken after the
  trade windows close is meaningless as written (**T17**). Note this also explains the 07-20 file's
  unexplained "265 null advances".
- **⚠ 07-20's OI-outage mechanism is PARTLY REFUTED (2026-07-21).** The outage did not repeat — 0
  NEUTRAL quadrants across 1,070 context-bearing rows, 13 distinct combinations, spurt pcts
  populated — **while `futures_oi_snapshots` cadence got worse (192 of ~375 minutes vs 208 on
  07-20)**. Since the cadence degraded and the quadrants recovered, "gappy 1-minute capture ⇒
  `latestPair` has no prior bucket ⇒ NEUTRAL" cannot be the sole mechanism. The `/options/spurt`
  HTTP 400 remains the better-supported suspect, and `oi_spurt` reviving to 3.0% is consistent with
  that read having been transiently broken. T12 stays open with the diagnosis rewritten around the
  endpoint; the cadence regression is a separate, real, lower-severity item.
- **PE CAN score — the standing question is half-answered (2026-07-21).** On a mildly-down expiry
  Tuesday PE reached a composite of **0.7447 with 216 rows over the 0.600 threshold** (07-20's PE
  population capped at 0.452). The PE book was stopped by the §2.1 volume rail, not by the
  confluence score. Still outstanding: the ceiling on a **clean trend-down** day, and whether a PE
  fire is profitable.
- **`supertrend` is a near-free dot; `vwap` is a free one (updated 2026-07-22)** — `supertrend`
  read 100% on 07-21 and **95.9%** on 07-22, so it discriminates a little; keep it a watch.
  `vwap` has now held **100% across four consecutive sessions — 3,005 rows** (CE-heavy 07-17,
  mixed 07-20, mild-down 07-21, clean-down 07-22) at the heaviest weight in the scorer (2.5 =
  12.8% of Σw). Four tape characters, zero discrimination: **T6 is the best-evidenced row in the
  ledger.**
- **~~`breadth` threshold sits one constituent outside the operand's realised range~~ — RESOLVED as
  REGIME 2026-07-22, T18 CLOSED.** 07-21 read 0.2% support with declines peaking at 31 against a
  `> 32`-of-50 threshold and filed it as a near-miss threshold problem. On 07-22, a real down day,
  **declines ran 36–45, advances 5–14, and the dot supported 96.6%**. The threshold is reachable;
  07-21 was a mild-tape miss. Same lesson as `basis` and `vix`: **a near-zero dot on one directional
  session is regime until a session with the opposite character disagrees.**
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
  **2026-07-20 update — the k evidence is now genuinely SPLIT (2-for / 2-against).** On a thin flat day the
  floor blocked 11 shadow positions with **0 winners (−₹6,723)** and every challenger book lost too
  (`vol-off` 0/5, `vol-12k5` 0/4) — the 07-03 signature, exactly inverting 07-17's +₹22,345. Both
  loosening books are now net-negative all-time. **T1 should not be applied on current evidence.**
  **2026-07-21 update — 2-for / 3-against.** The cleanest single-knob evidence yet: the six rows the
  floor *alone* vetoed resolved **6 WOULD-LOSE / 0 WOULD-WIN**, and `vol-off` + `vol-12k5` went
  0-for-7 for −₹9,784 between them. Read alongside the method fix in README §3.13 — the champion
  book's per-rail bucket credited `volume-floor` with **+₹2,872.77** the same day, because that
  bucket includes rows other rails also vetoed. **Use the would-have-fired set for knob decisions.**
  **2026-07-22 update — 3-for / 3-against, and the ledger is now openly regime-split.** On a clean
  trend-down day the same single-knob method returned **56 WOULD-WIN / 30 WOULD-LOSE (+2,547.6
  pts)**, both loosened books went **4-for-4** (+₹8,332 each) and `vol-12k5` turned net-positive
  all-time. But **all** the winning rows are pre-11:00; the same rows after 12:00 are net-negative,
  and the manual model carries no structural stop (the shadow book shows stops firing at −1.1% to
  −10.1% on those legs), so +2,547.6 is an **upper bound**. ⚠ **Every session in this ledger since
  07-21 measured the PE book with its relative floor wrongly DISARMED (T16)** — the challenger books
  are partly measuring the regression, not `k`. **T16 must be resolved before T1 is measurable.**
  ⚠ **SEPARATE, NEWLY-MEASURABLE ITEM — the SENSEX family is still on the un-armed FIXED 125,000 floor**
  (T11). 07-20 was the first session with enough sensex slugs emitting (31) to quantify it: **419 of 706**
  volume-floor blocks carried threshold 125,000 flat, while the nifty family's relative floor ranged
  12,188–53,138 against a session 3m median of 13,260. Per ADR-0003 the sensex variants signal on
  `NIFTY26JULFUT`, so the right yardstick is that same distribution — where **125k sits above p95
  (101,920)**, i.e. a README §3.8 *near-never*. This is a STRUCTURAL gap (the threshold is outside the
  operand's practical range), distinct from the regime-split k question above.
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
- **strategy-signal EVAL STALL — RECURRING, HIGHEST PRIORITY (07-07, 07-10, 07-15; 07-14 was a DIFFERENT
  root cause, see below).** Silent Redis `candles.1m.*` subscriber drop → `signal-eval` executor starves →
  nothing logs; **market-data feed healthy throughout** every time. 07-07 stalled 14:22 (+ a self-recovered
  12:18–13:20 gap); 07-10 stalled 14:52, no recovery before close; **07-15 stalled 11:49–14:10 (2h21m,
  mid-session, self-recovered) — this time with DIRECT thread-level proof** (`docker logs` filtered to the
  `signal-eval` thread shows exactly one line in the whole gap) confirming this is the eval-dispatch thread
  itself going silent, not a sampling artifact, while a DIFFERENT unrelated scheduled thread
  (`PartialBucketCanary`) kept ticking the whole time — proving the JVM was alive and candle capture was
  healthy, which is exactly why casual health checks miss this class. **`SubscriberHealthCanary` (#634) +
  the #679 eval-vs-receipt heartbeat were DEPLOYED to catch exactly this and are in every running image
  since — 0 telemetry rows across all 3 confirmed occurrences.** Working theory (07-15 analysis): if the
  Redis SUBSCRIBER drops, `lastBarReceivedAtMs` and `lastBarEvaluatedAtMs` freeze TOGETHER, so #679's
  receipt-relative heartbeat (deliberately quiet-market-safe) never sees a growing lag and never alarms —
  a structural blind spot, not a tuning issue. **Priority is no longer "tune #634's window" but "prove #634
  CAN log a row at all"** — recommend a forced fault-drill (`artha.canary.drill-suppress-key`, already
  built) before arming anything new on top of it. **The dormant `SignalStarvationCanary` (#868, shipped
  2026-07-14) explicitly does NOT cover this class either** (its predicate defers to the subscriber
  watchdog on any receive/eval-gap signature) — its arming timeline is orthogonal to this item.
- **strategy-signal ZERO-SIGNALS incident, 2026-07-14 — DIFFERENT root cause, FIXED.** The eval loop parked
  on an UNBOUNDED market-data candle fetch (no subscriber drop — a blocking-call-with-no-timeout bug).
  Fixed #866 (bounded fetch + dedup), deployed post-market 07-14. Do not conflate with the recurring
  subscriber-drop class above; the fixes are independent and both needed.
- **F10 COLD-START STARVATION, 2026-07-15 + 2026-07-16 — a THIRD, distinct class. Part A FIXED, Part B OPEN.**
  Do not conflate with either class above: there is no subscriber drop and no parked fetch — **the engine
  never loads a strategy at all**, because it resolves universes ONCE at boot and a cold boot lands before
  the daily Kite login. market-data's `lastGood` term-structure cache is **in-memory**, so a cold container
  has nothing to fall back on and 503s; a WARM container silently survives the identical expired-token
  window off yesterday's cache — which is why this hid for months and why "it works most mornings" was never
  evidence of correctness. Signature: `loaded 0 published strategies` / `subscribed 0 candle channels` at
  boot, zero rejections all session, **capture and every canary green**. Occurred 07-15 (manual restart to
  recover; filed as ledger F10, NOT built) and recurred **07-16 within one day**. **Part A SHIPPED
  [#874](https://github.com/prashantm912/artha-yantra-2/pull/874) @ d9f30a8f** — `kite.status` listener +
  bounded 3×~35s retry converging on `unresolved == 0`, plus a level-triggered `kite:session:status` key read
  at boot (the channel is edge-only and fire-and-forget, so a `CONNECTED` published before the engine
  subscribes is lost FOREVER and none ever follows). Drill-proven live 07-16 21:49–21:52 (0→39 from a
  `CONNECTED` alone, no restart). **Three standing cautions:** (1) the drill recovered on **attempt 3 of 3**
  with ~10s of margin — widen the bound (chip); (2) a cold boot produces a **PARTIAL** load (observed 32/39),
  so the honest health signal is **`unresolved == 0`, never `loaded > 0`** — a "something loaded" predicate
  reads a degraded session as success; (3) **Part B (detection) is still OPEN** — an exhausted retry only
  LOGS `DEGRADED`, nothing pages, and `SignalStarvationCanary` (#868) cannot cover this class (it
  early-returns on `!hasOneMinuteSubscriptions()` and `outputAtMs==0` — exactly this signature).
  See `2026-07-16-session-findings.md` §6.1/§6.3/§7.
- **`scalp-straddle-nifty` fires 0DTE/short-dated ATM straddles (07-07, 07-10)** — 07-07 both LOST (−19%,
  −6.8%), 07-10 re-fired (13:09, 14:42) for a small loss. None auto-papered. Owner: confirm straddle-path
  threshold + whether these should route to a paper book.
- **Directional scalpers now fire (NEW 07-10)** — `golden-crossover` + `connect-the-dots` CE, first
  directional fires in analysis history, enabled by the relative floor. All small losers (whipsaw). The
  scalper family is now live-firing directionally, not just via the straddle path.
- **STRATEGY-COVERAGE COLLAPSE — LARGELY RESOLVED 2026-07-20** (was the 07-17 highest-priority item).
  Distinct emitting slugs recovered **17 → 49**, and the sensex family came back from 4 slugs to **31**.
  The engine loaded **63 / 0 unresolved** at 08:36 IST after an F10-style cold boot self-healed (#874
  working as designed). The 07-17 cause remains permanently unverifiable (logs destroyed), so this stays
  on the watchlist one more session rather than being closed — but the alarm is eased and T9 is downgraded
  from "highest priority". **Caveat:** today's `published+enabled` snapshot read 44 *after* a post-close
  deploy, below the 49 that emitted — the denominator itself moved, so ratio-based alerting (T9) has to
  read the registry at the same instant as the numerator.
- **⚠ STRATEGY-COVERAGE COLLAPSE (2026-07-17 — superseded by the row above; retained for history).** Distinct
  slugs emitting rejections fell **35 (07-10) → 33 (07-15) → 17 (07-17)** against a registry that GREW to
  63 published+enabled. The 38 `%sensex%` CE variants emitted **zero rows all session**; the 4 surviving
  sensex slugs are all `-pe` and emitted exactly 2 rows each at the same two bars (10:48, 12:42 IST).
  Capture excluded as the cause (SENSEX chain 361 snaps with a correct 07-16→07-23 weekly roll;
  `SENSEX26JULFUT` 375/375 1m bars); instrument split excluded (per ADR-0003 the sensex variants signal on
  `NIFTY26JULFUT`/NFO and only execute on BFO — all 523 rows are NFO); eval stall excluded (rows ran to
  15:18). **Cause is UNVERIFIED because the 07-17 container logs were destroyed by the 07-20 restart** —
  read the boot line the SAME day from now on (README §3.10). Leading hypothesis is a partial load, per the
  07-16 §6.3 standing check (`unresolved == 0`, never `loaded > 0`). Watch for two more sessions before
  treating as structural; if the ratio stays low, this is a bigger finding than any gate tune in this file.
- **`vwap` dot supports 100% — CONFIRMED ACROSS 2 SESSIONS (07-17, 07-20).** 359/359 then 748/748 at the
  heaviest weight (2.5 = 12.8% of Σw): **1,107 consecutive rows with zero discrimination.** A
  permanently-supporting dot is an unlabelled threshold reduction, the mirror image of a dead dot. The
  2-session confirmation this item asked for is now met ⇒ **T6 is promoted from watch to a live proposal**
  (narrow the support condition or cut the weight). **RESOLVED 2026-07-25 — owner decision (b):
  support now requires the right side AND ≥15 bps |close−vwap|/close distance (#991, the measured
  median split ≈ 50% support). Spring prop, live at the next deploy, no republish; judge the
  combined T22+T6 composite effect over 2 forward sessions.**
- **`basis` dot — RESOLVED, was REGIME not dead-data.** 07-17 read 0/359 and it was filed as suspected
  dead-data; 07-20 read **505/748 = 67.5%**. Same story as `vix` (0% on 07-17, 54% on 07-20). **Carry the
  lesson, not the item: a 0% dot on a single directional session is regime until a second session with the
  opposite character disagrees.** T4 closed, no action.
- **⚠⚠ OI QUADRANT COLLAPSE (NEW 2026-07-20, UNRESOLVED — now the highest-priority open item).**
  `futuresQuadrant` = `underlyingQuadrant` = **NEUTRAL on 748/748** scored rows and `spurtOiPct`/
  `spurtPricePct` NULL on 1,013/1,013, killing `futures_oi` (w 1.5), `underlying_oi` (1.0) and `oi_spurt`
  (1.0) for the entire session and dropping the composite cap **0.816 → 0.7181**. **This is code-proven a
  data-absence, not a flat-day artifact:** `OiInterpretation.classify` (`OiInterpretation.java:16-23`) is a
  **total** function over four states with no dead zone — zeros included — so it can never emit NEUTRAL;
  NEUTRAL exists only in the strategy-side mirror (`OiQuadrant.java:10-25`) as the documented "data
  missing" sentinel and the declared fallback on read failure. Excluded: monthly-expiry suppression
  (`MarketCalendar.java:259-263` needs a weekly-expiry day; 07-20 was a Monday), chain-capture failure
  (0 null OI across 34k NIFTY / 64k SENSEX snapshots), and dead futures OI values (300 distinct values
  14.37M–14.73M). Live post-close probes: `/options/spurt` **400s on every parameter shape**;
  `/futures/banks?name=NIFTY%2050` **200s with real interpretations** while `name=NIFTY` **422s**.
  Capture regression alongside: `futures_oi_snapshots` logged **208 of ~375 minutes** (vs 365 on 07-17),
  and a gappy 1-minute capture leaves a 3-minute `latestPair` read with no prior bucket →
  `interpretation=null` → NEUTRAL (`FuturesMoversService.java:142-151`). **Root cause NOT pinned — the
  session's engine logs were destroyed by the 17:31 post-close deploy before the `scalper OI read …
  unavailable` lines could be read.** ⚠ **Severity note:** NEUTRAL dots are added with `absent=false`
  (`ConnectTheDotsScorer.java:207-214`) so they stay in the denominator and score zero — a dead OI
  endpoint is strictly worse than a dead IV feed, which is withheld. Promoted to README **§3.12**.
- **⚠ ENGINE-LOG DESTRUCTION IS NOW A RECURRING METHOD FAILURE (07-17, 07-20).** Both sessions lost their
  `signal engine loaded N published` boot line — and with it the root cause of that session's headline
  finding — because a container recreate landed before the analysis ran. The agent runs post-close, which
  is exactly the window deploys land in, so it will keep losing this race. README §3.10's "read it the same
  day" instruction is not sufficient on its own ⇒ **T15 proposes persisting the reload line to a table.**
  **2026-07-21 broke the streak — by luck, not design:** `RestartCount=0`, no post-close deploy
  landed before the run, so the boot line, the full day's logs and the eval counters (Σ 3,570,
  `failures_total = 0`) were all readable. That is exactly the outcome T15 asks to make reliable.
  Secondary trap from the same event: `published+enabled` read **44** post-deploy while the engine had
  loaded **63** and **49** slugs had emitted — **a post-deploy registry count is not the session's
  denominator.**
- **17 paper positions OPEN since 2026-07-16 20:00 IST (CARRIED, 3rd session)** — created by the 07-16 F10
  incident, still unsquared. One of the original 18 closed between 07-17 and 07-20; the rest remain. No
  longer an incident artefact; an open-position hygiene problem in its own right. Owner decision (T10).
- **Interior coverage holes on BOTH logged sessions (NEW 2026-07-20)** — 07-20 held a 64-minute hole after
  the open and a full hour empty 11:45–12:45; re-bucketing 07-17 exposed two holes there too, which
  **invalidates that file's "FULL session, no eval stall" claim** (it was drawn from min/max bar_time
  only). Capture was healthy through all of them. ✅ **The canaries finally logged**: #634's `eval-stall`
  (09:58, 213 s) and #679's `receive-stall` + `resubscribe` (13:23, 778 s) both fired and both recovered
  — the rollup's standing question "prove #634 CAN log a row at all" is **answered YES**. **But not
  sufficient:** the day's largest hole (11:45–12:45) triggered nothing, and the 13:23 receive-stall sits in
  a window that *was* producing rows. Watch whether the alarms ever coincide with the holes. New README
  dimension **§3.11**; note its honesty limit — an empty bucket alone is not proof of a dead engine
  (`recordRejection` sits downstream of the chart-gate early return), so confirm against
  `ay_signal_eval_outcome_total` **before** a post-close deploy resets the counters.
  **2026-07-21 was the first fully-covered interior** — every 15-minute bucket 09:15→14:57 non-empty,
  **0 `subscriber_health_events`**, `failures_total = 0`, counters advancing at 09:47 / 13:17 / 16:54.
  One residual: the last row is bar **14:57** where 07-17 and 07-20 both ran to ~15:18, and the
  14:45–14:57 rows are 4 slugs all blocked by `time-window` (windows closing normally). Cumulative
  counters cannot be time-sliced, so the empty tail is **flagged, not diagnosed** — watch it.
- **15 → 19 paper positions OPEN — the population is now GROWING (CARRIED, 5th session, 2026-07-22).**
  The 07-21 20:00/20:05 swing batch opened **4 new** (`minervini`: KANORICHEM, MENONBE;
  `manas-arora`: KANORICHEM, TIRUPATIFL) on top of the 15 carried, and `PaperStaleTickAlerter`
  WARNed **31,730 times** on 07-22, worst **~25,184 s (7 h)** un-evaluated. The swing batch keeps
  opening positions whose intraday stops cannot fire, so T10 stops being hygiene and becomes an
  accumulating exposure. Owner call.
- **17 → 15 paper positions OPEN, brackets starved all session (CARRIED, 4th session, 2026-07-21)** —
  two more closed since 07-20, 15 remain (oldest 07-07, newest 07-20 20:05 IST). `PaperStaleTickAlerter`
  WARNed once per position per cycle all day, worst **~9,988 s (2 h 46 m) un-evaluated**. These are NSE
  cash equities on the `minervini`/`manas-arora` books and are **not on the live tick subscription**
  (`tickedTokens = 25` = indices/futures/option legs), so a live intraday stop on the swing books
  **would not fire**. Chronic, not a regression; owner call (T10): subscribe the open swing holdings or
  accept EOD-only exit evaluation and downgrade the alert.
- **`FINNIFTY26SEPFUT` bar-close canary noise (4 sessions running — T20 on 2026-07-22)** — 18 of the
  25 market-data ERROR lines on 2026-07-22 and 21 on 2026-07-21, all the same far-month contract (`ticks flowing but no 1m bar closed for 886–910 s`).
  Not a scalper signal series, absent from the health endpoint's `problems`, `status=GREEN` throughout.
  It is now the only recurring noise in the ERROR channel ⇒ either exclude far-month FINNIFTY from the
  divergence probe or scale its threshold by liquidity.
- **`confluence-composite` rows logged with a POSITIVE blocking margin (NEW 2026-07-20)** — 7 rows record
  "blocked" with composite 0.613–0.718 against a 0.600 threshold. Probably the optional-gate mechanic
  (the row stores the FULL composite while the REQUIRED-ONLY sub-composite is what failed), which would
  make the gate decision right and the diagnostic wrong — **unverified in code**. It also means every
  §3.5 would-have-fired query in this folder mis-attributes these rows. Cheap fix proposed as T14 (assert
  `blocking_margin < 0` on persist).
- **Shadow entry latency is STRUCTURAL, not a flake** — p50 73–87 s / p95 85–170 s on every session logged
  (07-03, 07-06, 07-10, 07-15, 07-17). README flags p95 > ~5 s. Every shadow PnL in this file is computed
  off a fill stamped ~76 s after `bar_time`; bias direction unmeasured. Data-model fix belongs in README §7.

## Proposals (UNLOCKED — 5 sessions logged: 07-02, 07-03, 07-06, 07-07, 07-10; 07-08/09 outage skipped)

Ranked structural tune candidates. **STRUCTURAL** = the operand is dead every session because the threshold
sits outside its physical range or the feed is null — not day-dependent. All three below are dead-DOT
calibrations that raise the composite dead-weight cap above 0.816; each is owner-gated and lands as its own
PR (README §1 cadence). None changes a RAIL — they change how much composite headroom the dead dots waste.
Judge impact carefully: these help borderline 0.55–0.60 rows cross threshold, they do not change the fires
already passing at 0.68–0.89.

**P1 — `iv_pair` min-gap: `artha.scalper.oi.ivPairMinGap` 0.10 → 0.02.**
- **LANDED #675** (2026-07-10, owner-approved) — env-wired (`ARTHA_SCALPER_OI_IV_PAIR_MIN_GAP`, application.yml + compose passthrough) + default retuned 0.10 → 0.02. NOT merged/deployed.
- **Diff:** `application.yml` / env `ARTHA_SCALPER_OI_IV_PAIR_MIN_GAP: 0.02` (verify exact passthrough name
  in `ScalperOiProps.java` before landing — CLAUDE.md `${ENV_NAME}` mismatch trap).
- **Evidence:** iv_pair dot support **0% on all 6 sessions including 07-15** (2026-07-02/03/06/07/10/15) —
  **still 0% even after this recalibration landed and is live in the running config** (`application.yml`
  default 0.02, confirmed 07-15). The rail asks for a ≥0.02 IV gap between the two 6-strike averages; that
  gap apparently still never occurs on NIFTY weeklies → **escalate to a ground-truth check of the real
  IV-pair-gap distribution** (README §3.8-class query) before assuming 0.02 is the fix — the recalibration
  may not have been large enough, or the dot's formula itself may need review.
- **Risk:** loosening to 0.02 makes the dot *reachable*, adding up to 0.8/19.6 ≈ 0.041 of composite headroom
  on rows where the small IV skew genuinely supports the side. Low risk (it can only *activate* a currently
  never-firing dot); measure whether it then over-supports.

**P2 — `oi_spurt` price floor: `artha.scalper.oi.spurtPricePct` 50 → 5–10.**
- **LANDED #675** (2026-07-10, owner-approved) — env-wired (`ARTHA_SCALPER_OI_SPURT_PRICE_PCT`, application.yml + compose passthrough) + default retuned 50 → **8** (chosen from the 2,104-bar distribution: ≥8 on 17%). NOT merged/deployed.
- **Diff:** env `ARTHA_SCALPER_OI_SPURT_PRICE_PCT: 8` (verify passthrough name in `ScalperOiProps.java`).
- **Evidence:** oi_spurt dot support **0% on 5 sessions, then FIRST LIFE on 07-15 (1.6%, 4/246 rows)** —
  the floor-8 recalibration is confirmed live in config and producing its first measurable effect. Still
  tiny; keep watching, do not re-tune yet (need more sessions to judge if 8 is the right floor or just
  barely reachable).
- **Risk:** 8% is still a real spurt filter on a 3m bar; setting it too low frees the dot (≈100% support).
  Pick the value from the observed `spurtPricePct` distribution (a rollup ground-truth query) before landing.

**P3 — `iv_rank` null semantics: null scores AGAINST → null = NEUTRAL/excluded (code).**
- **LANDED #676** (2026-07-10, owner-approved) — a null-input dot is now `absent` (withheld from BOTH the numerator and the denominator) in `ConnectTheDotsScorer`; live-only, no golden/parity surface. NOT merged/deployed.
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
