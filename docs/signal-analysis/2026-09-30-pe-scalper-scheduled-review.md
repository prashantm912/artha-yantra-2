# 2026-09-30 — PE scalper scheduled review (owner stopping rule, set 2026-08-03)

**Run:** scheduled task `pe-scalper-review-20260930`, executed 2026-09-30 ~22:55 IST by the
Architect session (Fable 5). Read-only analysis; no code, no config, no DB writes.

---

## Verdict

**The question this stopping rule was set to force is DEAD — the PE scalpers are not inert
and have not been since 2026-08-11.** The bearish regime arrived 8 sessions after the owner's
wait-call, the `biasAligned` veto opened on its own, and 5 PE slugs fired **117 signals over
16 sessions** (`computed`). #959 moved from unfalsified to **validated** (ledger H2, closed
2026-08-17). What the regime then measured is the new fact this review adds on top of the
interim record: **on its native bearish tape (NIFTY −4.82% over the window), the PE side
lost ₹24,259.45 net across 46 closed paper trades** (`computed`, blended attribution;
−₹15,366.66 excluding the two 08-28 incident closes). One strategy was already cut on this
evidence (H45, 2026-08-31, owner-approved). **This review's recommendation: present
`scalp-connect-the-dots-sensex-niftyoi-pe` as the SECOND cut candidate** — its solo loss now
survives leave-3-worst-out, the same bar the first cut cleared — **and otherwise change
nothing.** Owner-gated; no action taken.

A separate, larger finding surfaced in passing: **the platform has not captured a single
market bar since 2026-09-11 15:29 IST** — ~13 straight trading sessions dark, which freezes
all further accrual (§6).

---

## 1. Re-measurement (task step 1)

All `computed` live this session from DB `artha` unless labeled.

| Question | 2026-08-03 value | 2026-09-30 value |
|---|---|---|
| PE signals since 07-21 | 0 | **117** (5 slugs, 16 sessions, first 08-11 11:06 IST, last 09-10 14:39) |
| PE signals since 08-03 | — | **117** (identical — nothing fired 08-04→08-08) |
| PE signals since 09-01 | — | **39** |
| Live `-pe` strategies (`enabled AND published_version_id IS NOT NULL`) | 18 | **17** — `scalp-golden-crossover-sensex-niftyoi-pe` cut 08-31 18:48 IST (H45, owner-approved) |
| Published `-pe` | 27/27 | 27/27 (10 disabled: the 9 `sensex-sensexoi` + the H45 cut) |

Per-slug signal counts since 07-21: connect-the-dots-sensex-niftyoi-pe 39,
golden-crossover-sensex-niftyoi-pe 28 (cut 08-31; 0 since — the cut held),
golden-crossover-nifty-pe 25, connect-the-dots-nifty-pe 24,
gap-theory-sensex-niftyoi-pe 1. **12 of the 17 live PE slugs have still never fired —
still unmeasurable**, same posture as H2: not a defect claim.

## 2. Did a bearish 60m regime occur? (task step 2)

**Yes — and it is now read directly, not inferred.** The task asked for a `SUPERTREND(7, 3.0)`
recomputation because on 08-03 the regime was inferred from complementary veto rates of two
disjoint bar populations. That recomputation is no longer the best available instrument and
was not run, for the reason H2's close recorded: `biasAligned` is a **decisive leg**
(`ConnectTheDotsScorer.java:428/:440`, `sourced` from the 08-03 diagnosis) — a PE signal can
only exist on a bar where the 60m SuperTrend bias was bearish. **Every PE fire IS a direct
observation of the bearish regime.** By that instrument (`computed`): **16 distinct sessions
between 08-11 and 09-10 carried a bearish 60m bias** (08-11, 08-12, 08-13, 08-14, 08-17,
08-18, 08-19, 08-24, 08-27, 08-28, 09-02, 09-03, 09-07, 09-08, 09-09, 09-10). Corroborating
tape (`computed` from `candles`@1d): NIFTY 50 closed 24,583.80 on 08-08 → 23,398.10 on 09-11,
**−4.82%**. The window the owner waited for arrived and persisted for a month.

## 3. PE fired — what was the P&L? (task step 3)

Legs first, then money, n everywhere. Two attributions, stated per number:
**blended** = position → `opening_signal_id` → strategy (over-credits the opener on averaged
positions); **solo** = positions whose `paper_position_lots` all trace to ONE slug (the
H45-validated method; `paper_positions.strategy_id` is NULL on all closed rows).

**Book level (blended), scalper book, window 08-11→09-10:** PE **46 closed / 13 wins (28.3%)
/ −₹24,259.45**; CE **4 closed / 2 wins / +₹3,582.29**. PE was essentially the book's entire
activity in the window — the regime that opened the PE gate closed the CE one, exactly
mirroring July. Book scale: ₹1.5L starting capital (`paper_account`), so the PE loss is
~16% of book.

**₹8,892.79 of the PE loss (37%) is the two 08-28 stuck-open incident closes** (positions
99/100, closed `MANUAL` 19:56 IST — the §6.1 incident in the 08-28 session doc). Ex-MANUAL
PE: **−₹15,366.66, n=44**.

**Per slug (solo, all-time), worst first:**

| Slug | n solo | wins | P&L all | P&L ex-MANUAL | Sept (n) | leave-3-worst-out (ex-MANUAL) |
|---|---|---|---|---|---|---|
| connect-the-dots-sensex-niftyoi-pe | 15 | 4 | −13,766.10 | **−8,748.13** (14) | −6,245.66 (11) | **−2,230.02 — still negative** |
| golden-crossover-sensex-niftyoi-pe | 11 | 2 | −12,182.37 | −8,307.55 (10) | 0 (cut) | −2,324.51 (H45 basis) |
| golden-crossover-nifty-pe | 6 | **0** | −4,026.66 | −4,026.66 | −517.27 (2) | −1,054.08, but n=3 remainder |
| connect-the-dots-nifty-pe | 7 | 4 | −1,547.16 | −1,547.16 | **+597.39** (3) | flips positive |

Close-reason economics (PE, blended, all-time): `TIME_STOP` +₹3,108.97/22 is the only
positive exit; `STRUCTURAL_STOP` −₹11,364.54/15, `TRAILING_STOP` −₹5,414.13/6 — consistent
with E1's T29 observation (confounded by construction; unchanged).

## 4. The volume-floor anomaly (task constraint check)

**No recurrence** (`computed`): 0 PE `volume-floor` rejections at the 125,000 static-fallback
threshold since 2026-08-03. Probe validated against its own history: the same filter returns
**3,264** rows all-time, last 2026-07-24 — exactly the known anomaly window — so the zero is
a real zero, not a filter artifact.

## 5. Recommendation (task step 5)

**KEEP the PE program; CUT one more strategy, pending owner approval; loosen nothing.**

1. **Present `scalp-connect-the-dots-sensex-niftyoi-pe` as cut candidate #2** —
   **owner-gated**, not actioned. It now matches the H45 cut profile on every axis: solo
   ex-MANUAL −₹8,748.13 (n=14) vs the cut strategy's −₹8,307.55 (n=10); negative sign
   survives leave-3-worst-out (−₹2,230.02 vs H45's −₹2,324.51); losses spread over 8 sessions,
   not concentrated. September alone added −₹6,245.66 over 11 closes — the E1 (08-31) HOLD
   verdict ("flips on leave-3-out") no longer holds on the grown sample.
2. **`golden-crossover-nifty-pe`: WATCH, not cut.** 0 wins in 6 solo closes is stark, but the
   leave-3-out remainder is n=3 — below any honest robustness bar. Keep accruing.
3. **`connect-the-dots-nifty-pe`: HOLD** — mildest loss, 4/7 wins, September positive.
4. **The 12 never-fired PE slugs: KEEP.** Carrying cost is zero (no trades, no capital);
   cutting them buys nothing and destroys future measurability. The original fear — "18 inert
   strategies forever" — did not materialize.
5. **Do NOT touch `biasAligned` or any entry gate.** The wait-call is now double-vindicated:
   the gate opened itself within 8 sessions, AND the trades it then admitted lost money — a
   looser gate would only have admitted more of them, exactly as the standing prior
   (T1/T7/G13/G10: every measured entry-gate loosening lost money) predicts. Any "more bars
   would pass" argument remains a shape test, not an outcome test.

Tier: this doc is read-only analysis. Item 1 is **owner-gated** (changes which trades the
book takes); the H45 precedent defines the execution path (registry `POST /{id}/disable`,
audit row, engine-reload verify) if approved.

## 6. Platform finding (out of scope, too large to omit)

**The live stack has captured nothing since 2026-09-11 15:29 IST** (`computed`: max 1m
candle bucket; 0 signals from ANY strategy since 09-11; TICK_AGG coverage normal through
09-11, absent after). The host itself was booted 08:48–10:38 IST daily through late September
(`computed` from System event log 6005/6006) — including a shutdown mid-session at ~10:21–10:38
on 09-28/29/30 — but Docker was not running at those times (no bars were written). Whether
this is a deliberate owner pause or a broken Docker autostart that nobody noticed is
**unknown and undetermined here**. Every "keep accruing" verdict above is frozen until the
stack trades again. Flagged separately to the owner.

## 7. Open doubts

- **Attribution:** blended vs solo figures differ (−24,259 vs −31,522 summed solo); neither
  is "the" number. Solo is used for per-strategy verdicts (H45 precedent); blended for book
  totals. The twin-averaging that motivated lots (e.g. position 98, two strategies) means
  blended per-slug rows in §1 over-credit openers.
- **Costs:** `realized_pnl` is net of ~0.571% of notional per options trade (`sourced`, E1
  08-31). With 46–50 trades, friction is a material share of the loss; gross-vs-net was not
  decomposed here.
- **SENSEX execution confound:** both deep losers execute SENSEX options off NIFTY OI
  (`sensex-niftyoi`); both NIFTY-executing slugs are mild. n is far too small to attribute
  (2 vs 2 slugs), but if a third sensex-niftyoi PE slug ever accrues, check this axis before
  cutting it.
- **n is small everywhere** (6–15 solo per slug). Leave-3-out is the strongest robustness
  check the sample supports; it is not large-sample evidence.
- **MANUAL contamination:** the two 08-28 incident closes sit on the two SENSEX slugs; all
  cut-relevant verdicts are stated ex-MANUAL, but the incident trades would likely have lost
  something under normal exits too (direction, not size, is what the exclusion protects).
- **Stack state at measurement:** Docker Desktop was not running at 22:54 IST; this session
  started it to run the mandated queries (the one deviation from "change nothing" — disclosed;
  auto-login boot window 08:00–15:30 was closed, no crons fire at this hour) and stopped it
  again after. `ay-wiremock` was present among the resumed containers; queries targeted DB
  `artha` directly, so profile state does not affect the historical reads.
- `assumed`: that `biasAligned` gates all five firing PE slug families identically (the 08-03
  diagnosis measured the veto across 16 slugs, so this is well-grounded, but the
  per-family gate wiring was not re-read this session).
