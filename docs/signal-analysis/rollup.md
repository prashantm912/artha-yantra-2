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

## Per-variant league (cumulative — refresh each rollup pass from the §6 league SQL)

Refreshed 2026-07-21 (through the 2026-07-21 session). Note the challenger roster changed: the
`composite-070` book was replaced by `composite-055`, so its all-time row is retired.

| variant | closed | net wins | total net ₹ | total pts | verdict-so-far |
|---|---|---|---|---|---|
| champion | 103 | 38 | **−41,260.30** | −580.6 | still deeply NEGATIVE all-time; 07-21 clawed back ₹2.9k but that is **one idea ×5** (deduped ≈ −₹2.2k). The would-have-fired class mostly loses ⇒ **the gate is correctly rejecting losers on balance**; 07-06 and 07-17 remain the two trend-day outliers |
| composite-055 | 8 | 2 | **−478.98** | +2.5 | unchanged — **took nothing on 07-21**. Across its logged sessions it buys one winner and three losers; loosening the composite buys noise |
| vol-12k5 | 13 | 2 | **−5,285.67** | −265.7 | **worst single session yet on 07-21** (3 entries, 0 wins, −₹4,671). Its 07-17 all-time lead is fully erased |
| vol-off | 21 | 4 | **−11,226.90** | −383.7 | fully-disabled floor stays the worst book all-time (4 entries, 0 wins, −₹5,112 on 07-21) — the floor is doing real work; `vol-12k5` (relax, don't remove) is still the better shape |
| composite-070 | 0 | — | — | — | RETIRED (never took a row; replaced by `composite-055`) |

⚠ **Every challenger is net-negative all-time and the gap widened again on 07-21.** On 07-17 the
loosened books were the ones that made money; on 07-20 and 07-21 they lost most per position, and
07-21 added the cleanest evidence yet — the six rows the floor **alone** vetoed were **6 losers out
of 6**. No loosening proposal (T1, T7) has surviving positive evidence.

## Structural-vs-regime watchlist

- **⚠⚠ THE `relative-volume-floor` TAG WAS SILENTLY DISARMED ON ALL 18 PE SCALPERS (NEW 2026-07-21,
  UNRESOLVED — now the highest-priority open item).** A single publish batch at **2026-07-20
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
- **`supertrend` is now a second free dot (NEW 2026-07-21)** — 1,070/1,070 support, first recorded
  100% session. Watch, not yet a candidate (today's population was 98% PE, and a one-sided tape
  flatters any directional dot). `vwap` by contrast has now held 100% across a CE-heavy (07-17), a
  mixed (07-20) and a PE-heavy (07-21) session — 2,177 rows, zero discrimination — which is the
  harder standard and keeps **T6** live.
- **`breadth` threshold sits one constituent outside the operand's realised range (NEW 2026-07-21)**
  — the dot needs `advances/declines > 32` of 50; on 07-21 declines peaked at **31** all session
  while advances reached 36, so the PE side missed by one. That is neither dead data (counts were
  live all day, 0 zero-pairs) nor an ordinary regime miss. One session only ⇒ **T18**, collect more.
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
  (narrow the support condition or cut the weight).
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
- **17 → 15 paper positions OPEN, brackets starved all session (CARRIED, 4th session, 2026-07-21)** —
  two more closed since 07-20, 15 remain (oldest 07-07, newest 07-20 20:05 IST). `PaperStaleTickAlerter`
  WARNed once per position per cycle all day, worst **~9,988 s (2 h 46 m) un-evaluated**. These are NSE
  cash equities on the `minervini`/`manas-arora` books and are **not on the live tick subscription**
  (`tickedTokens = 25` = indices/futures/option legs), so a live intraday stop on the swing books
  **would not fire**. Chronic, not a regression; owner call (T10): subscribe the open swing holdings or
  accept EOD-only exit evaluation and downgrade the alert.
- **`FINNIFTY26SEPFUT` bar-close canary noise (3 sessions running)** — 21 market-data ERROR lines on
  2026-07-21, all the same far-month contract (`ticks flowing but no 1m bar closed for 886–910 s`).
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
