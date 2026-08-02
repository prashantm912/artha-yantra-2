# Do T1 / T7 / G13 / G10 still stand? — the tune verdicts against the real `time_stop` spread

**Written 2026-08-02.** Chip `task_2735acfb`, follow-up to the G11 correction in
[`docs/audits/2026-08-02-ledger-claim-audit-shard-a.md`](../audits/2026-08-02-ledger-claim-audit-shard-a.md) §2.1.
**Measurement and documentation only. No production change, no config edit, nothing armed or un-armed.**

---

## Verdict

**None of the four is overturned, and no knob is reopened — but the G11 decision cell's promotion of them
from "stop-conditional" to "FINAL" is not supported, so all four revert to CONDITIONAL.** Three separate
reasons, each measured this session:

1. **The engine honours `max_bars` per strategy** (computed, code + live) — so the five-horizon spread is
   real running behaviour, not a cosmetic YAML difference. The escape hatch that would have made this
   whole question moot is closed.
2. **The counterfactuals' "30-minute time stop" was never the fleet's `time_stop` at all** (sourced). It is
   a harness parameter — `ExitKnobs.timeStopBars`, counted in **wall-minutes on the option's own 1m premium
   series** — applied uniformly because a COUNTERFACTUAL job carries *no strategy version*. The engine's
   `time_stop.max_bars` counts **3m bars on the index-future series**, per strategy, sixth of seven in exit
   precedence. Two different rules on two different instruments at two different resolutions, which happen
   to coincide numerically at `max_bars: 10`.
3. **The horizon is load-bearing, and the sign moves with it** (computed). On 247 would-be legs the
   after-cost result runs **−993.82 pts at 30 min → +190.18 at 90 min**; excluding the one NSE monthly
   expiry in the sample it runs **−79.07 → +2,358.13**.

**But the horizon question cannot be settled on the available data, and I am not claiming it is.** The 90-min
sign flip is carried by 5 legs of 247 (removing them returns −411.17), the priceable sample is 3 sessions,
and dropping the single expiry session moves the 30-minute number by 914.75 points. **"Insufficient to
separate" is the verdict on the horizon, and per-cohort P&L is not merely under-powered — it is ill-defined**
(§3).

**What this does NOT say.** It does not say the rejected loosenings were wrong. T1's own knob still loses on
every measurement of it, G10's concentration finding reproduces independently at *every* horizon I tested,
and nothing here weakens the standing prior that measured loosenings of this entry gate lose money.
The correction is to the *conditionality* of the four records, not to their direction.

---

## 1. Reproducing the spread (I did not adopt it)

Re-derived from the live `artha` DB against `published_version_id`, not from shard A's prose. Identical:

| `max_bars` | primary | wall-clock | published configs | of which enabled |
|---|---|---|---|---|
| 10 | `3m` | **30 min** | 18 | 12 |
| 12 | `3m` | 36 min | 12 | 8 |
| 16 | `3m` | 48 min | 3 | 2 |
| 20 | `3m` | 60 min | 18 | 12 |
| 30 | `3m` | 90 min | 9 | 4 |
| *(none — `max_holding_days: 1`)* | `3m` | BTST | 3 | 0 |
| | | | **63** | **38** |

`timeframes.primary` is `3m` on 63 of 63, so the bar→wall-clock conversion is exact. A `time_stop` *rule* is
present on 63/63 (the DONE verdict's "armed fleet-wide" half holds); the *horizon* is not uniform.

**#990's attribution, checked wider than shard A checked it.** Shard A tested one YAML. Across the whole
merge commit `64f9caaa`: `max_bars` appears **30 times, all of them context lines — 0 added, 0 removed**.
Those 30 context lines already show four distinct values (10, 12, 20, 30), so the heterogeneity is directly
visible *in #990's own diff*. The attribution correction holds and is now stronger than as filed.

---

## 2. The runtime answer — the engine honours `max_bars` PER STRATEGY

Settled from code and then confirmed against live rows. **It does not collapse to a single horizon.**

**Code.** `ExitEvaluator.timeStop`
(`libs/strategy-engine/src/main/java/in/arthayantra/strategyengine/eval/ExitEvaluator.java:692-700`) reads
`rule.params().get("max_bars")` off the rule instance it was handed, and `evaluate` (`:327-350`) iterates
`definition.exitRules()` — that strategy's own list, in the fixed precedence
`stop_loss → trailing_stop → take_profit → scaled_exit → square_off → time_stop → signal_exit`. The live
intraday sweep (`services/strategy-signal-service/.../signals/SignalEngine.java:1672-1684`) passes
`strategy.definition()` plus an `entryIndex` from `entryAnchorIndex(primary, interval, generatedAt)`
(`:3142-3148`), an index into **that strategy's own 3m primary series**. `held = index − entryIndex`, so the
count is per-strategy 3m bars. Nothing global is consulted.

**Live confirmation.** Every `TIME_STOP` exit the engine has emitted since 2026-07-01 (n=8) held for exactly
`max_bars × 3` minutes, 8 for 8:

| slug | `max_bars` | entry (IST) | exit | held | expected |
|---|---|---|---|---|---|
| `scalp-connect-the-dots-sensex-niftyoi` | 10 | 07-29 11:06 | 11:36 | 30 min | 30 |
| `scalp-connect-the-dots-sensex-niftyoi` | 10 | 07-29 13:09 | 13:39 | 30 min | 30 |
| `scalp-connect-the-dots-sensex-niftyoi` | 10 | 07-29 14:03 | 14:33 | 30 min | 30 |
| **`scalp-golden-crossover-nifty`** | **12** | **07-29 14:03** | **14:39** | **36 min** | 36 |
| `scalp-connect-the-dots-nifty` | 10 | 07-30 12:33 | 13:03 | 30 min | 30 |
| **`scalp-golden-crossover-nifty`** | **12** | **07-30 12:33** | **13:09** | **36 min** | 36 |
| `scalp-connect-the-dots-nifty` | 10 | 07-31 11:15 | 11:45 | 30 min | 30 |
| `scalp-connect-the-dots-nifty` | 10 | 07-31 12:15 | 12:45 | 30 min | 30 |

The two bolded pairs are a natural A/B the tape ran for us: on 07-29 14:03 and 07-30 12:33 a `max_bars: 10`
and a `max_bars: 12` strategy entered on the **same bar, same instrument** (`NFO NIFTY26AUGFUT`) and exited
**6 minutes apart**, in the predicted direction. That is per-strategy honouring observed live, not inferred.

**So the brief's "complete and successful answer, stop here" branch does not apply.** The YAML difference is
behaviour.

### 2.1 The counterfactual's "30-minute time stop" is a different object

This is the finding that matters more than the composition, and it was not visible from the docs.

`CounterfactualService` "pins a self-contained COUNTERFACTUAL job (entries + variants in the request JSONB,
**no strategy version**)" (`services/backtest-service/.../counterfactual/CounterfactualService.java:46-48`).
The variants carry `ExitKnobs`, applied **identically to every entry** (`:248-252`, `:265-266`) — the harness
has no per-strategy config to read, by construction. And the unit is not the engine's bar:

> "The index is minutes-from-entry, so a variant's bar-count time stop counts **wall-minutes**."
> — `CounterfactualService.java:288-292` (the premium grid is the option's own captured **1m** closes)

| | engine `time_stop` | counterfactual `timeStopBars` |
|---|---|---|
| series | index future (`NIFTY26AUGFUT`) | the **option's own** premium |
| resolution | 3m primary bars | 1m / wall-minutes |
| scope | per strategy, from its published config | uniform, from the request |
| precedence | 6th of 7 (four protective rules can pre-empt it) | 4th of 5 |

They coincide numerically only for the `max_bars: 10` cohort, and even there they are not the same
measurement. **"T1/T7/G13/G10 were measured under the 30-minute `time_stop`" is true of the harness and false
of the fleet** — the sentence silently upgrades a modelling choice into a statement about armed production
config, and that upgrade is what the G11 cell then used to call the four verdicts FINAL.

---

## 3. Cohort composition — with n, and why the per-cohort split is ill-defined

**Population** (`strategy.signal_rejections`, `blocking_rail = 'volume-floor'`, `diagnostic ? 'wouldBeLeg'`,
2026-07-15 → 07-29 IST): **5,035 rows** — matching G10's own stated 5,035 priceable blocks exactly, so this is
the same population it measured on.

| `max_bars` | wall | rows | deduped legs | distinct slugs |
|---|---|---|---|---|
| 10 | 30 min | 1,391 (**27.6%**) | 605 | 14 |
| 12 | 36 min | 1,075 | 542 | 9 |
| 16 | 48 min | 137 | 90 | 2 |
| 20 | 60 min | 1,649 | 522 | 14 |
| 30 | 90 min | 783 | 599 | 5 |
| | | **5,035** | | **44** |

**Answer to the brief's question (1): the legs span all five horizons; they did not concentrate in the
30-minute cohort.** Only 27.6% of rows come from `max_bars: 10` strategies. The result is robust to slicing —
in the opening window (09:15–10:15, where G10's mechanism lives) the 30-minute cohort is 219 of 707 rows, 31%.

**But the sharper finding is that the question cannot be answered per leg at all.** Deduping by
`(bar_time, tradingsymbol)` — the folder's own §3.24 slug-fan-out rule, and the key every one of these
counterfactuals used — collapses 5,035 rows to **695 legs**, and those legs are claimed simultaneously by
strategies at different horizons:

| distinct horizons claiming the leg | dedupe groups | share |
|---|---|---|
| 1 | 96 | 13.8% |
| 2 | 7 | 1.0% |
| 3 | 120 | 17.3% |
| **4** | **472** | **67.9%** |
| | **695** | |

**599 of 695 legs (86.2%) are claimed by strategies at two or more horizons; 67.9% at four**, spanning 30 min
to 90 min. Shard A's follow-up ("establish which horizon each sampled leg's strategy actually carries") has no
answer on the recorded unit of analysis: after the dedupe that every one of these measurements applied, a leg
does not *have* an owning horizon. **A per-cohort P&L split is therefore not reported — not because n is
small, but because the cell it would populate is not well defined.**

*(Deduping by the option symbol instead gives 928 groups over 74 distinct contracts; same conclusion.)*

---

## 4. Horizon sensitivity — the sign moves, and the measurement cannot decide

An independent probe, not a reproduction of G10 (see §4.2). Same rail, same population, same exit arithmetic;
what varies is only the time-stop horizon, swept across the fleet's five real values.

**Method.** Deduped `(bar_time, option)` legs from §3; entry premium = `wouldBeLeg.entryLtp`; premium path =
the option's captured **1m closes** from `marketdata.candles` (the source `CounterfactualService` itself
reads), bounded per leg by a PK-indexed `LATERAL` to `min(entry + 90 min, 15:12 IST)`. Levels
`round(entry × 1.35, 2)` / `round(entry × 0.75, 2)` — the pinned `PremiumLevels.paiseRounded` derivation —
SL winning ties, else exit at the last close inside the horizon. Cost = 1% of entry premium round-trip.

*Cost model verified against G10's published arithmetic:* `324.87 − 265 × (345.6 × 0.01) = −590.97` vs its
stated `−590.95` — reproducing to 0.02 pts on a rounded average premium, so the cost convention is confirmed
as 1% of entry premium per leg.

**Result (n = 247 legs, all sessions):**

| horizon | legs | TP | SL | wins | gross pts | **net after 1%** | median leg | net ex-top-5 |
|---|---|---|---|---|---|---|---|---|
| **30 min** | 247 | 0 | 0 | 111 | +151.45 | **−993.82** | −1.55 | −1,541.57 |
| 36 min | 247 | 0 | 0 | 124 | +474.35 | −670.92 | +0.20 | −1,249.77 |
| 48 min | 247 | 0 | 1 | 136 | +829.70 | −315.57 | +2.75 | −889.87 |
| 60 min | 247 | 0 | 3 | 140 | +578.35 | −566.92 | +2.45 | −1,212.92 |
| **90 min** | 247 | 0 | 3 | 152 | +1,335.45 | **+190.18** | +6.10 | −411.17 |

**Zero take-profit touches at every horizon**, and at most 3 stop touches — so in this sample the exit model
*is* the time stop and the horizon is the dominant term by construction. That matches the 41-, 22- and
13-leg counterfactuals ("every leg resolved at the time stop") and G13's 6; it is *stronger* than G10's own
6 TP / 12 SL of 265, which is expected given §4.2's different price source and shorter session span.

### 4.1 Three reasons this does not settle anything

1. **The 90-minute flip is 5 legs.** `net ex-top-5` is negative at **every** horizon including 90. G10's own
   central finding — "removing 5 legs of 265 flips the sign" — reproduces independently, and it survives
   lengthening the hold. A result that fragile is not an edge at 30 min and is not an edge at 90 min.
2. **The sample is three sessions, one of them an NSE monthly expiry.** 2026-07-28 is a **Tuesday** and the
   last Tuesday of July 2026 — the NSE monthly expiry, which this repo's standing rule says never to judge
   calibration from. Per-session gross (30 min → 90 min): 07-27 **−14.5 → +1,137.2** (77 legs), 07-28
   **−659.6 → −1,912.8** (38 legs), 07-29 **+825.5 → +2,111.1** (132 legs). The expiry session is the only
   one where a longer hold is catastrophic, and the two others are trending days — so the "longer is better"
   pattern is session direction, not exit-rule behaviour.
3. **Dropping that one session moves the 30-minute number by 914.75 points.** Excluding 07-28 (n=209):
   net after cost 30 → **−79.07**, 36 → +195.33, 48 → +929.48, 60 → +909.53, 90 → **+2,358.13**; ex-top-5
   still negative at 30/36 and positive from 48. **A verdict that flips on excluding one of three sessions is
   not a verdict**, which is exactly what the brief said to say out loud when it happens.

**Honest reading: the horizon is load-bearing enough that the four records must state their exposure, and the
data is nowhere near good enough to say which horizon is better.** The latter is an owner decision on new
forward evidence, not something to infer from 2–3 sessions of blocked-entry replay.

### 4.2 Why this is not a reproduction of G10

Only **261 of 928** deduped legs have a captured 1m premium path at all, and the coverage is not spread over
the window — it is **zero for 07-15 through 07-24** and partial for 07-27/28/29:

| session | legs | with 1m option candles |
|---|---|---|
| 07-15 … 07-24 (7 sessions) | 609 | **0** |
| 07-27 | 106 | 79 |
| 07-28 | 63 | 38 |
| 07-29 | 150 | 144 |

So G10's "265/265 priced" cannot have come from `marketdata.candles`; it came from
`options_chain_snapshots` (G13's write-up states the cadence: "15–16 ticks each over the 30-minute window").
My probe uses the finer source over fewer sessions; G10 used the coarser source over ten. **Different sample,
different price source — same population and same arithmetic.** Treat §4 as a sensitivity probe on G10's
data, not as a restatement of its number.

---

## 5. What each of the four actually says now

### T1 — `relativeVolumeMultiplier` k 1.5 → 1.2/1.0 · **REJECTED, now stop-conditional**
Direction unchanged and independently re-corroborated (the 07-31 chop tape put its alone-vetoed legs at
1W/2L, −2.40 pts — a sixth consecutive failure to pay). Two exposures to state: its own knob is **11 legs
(2W/9L, −121.95 pts) on a single session**, 2026-07-29 — the widely-quoted **41** is the six-rail *union*, not
T1 — and it was modelled under a uniform harness stop on a population where only ~28% of rows come from
30-minute strategies. Small, one-session, horizon-conditional. Still rejected.

### T7 — composite threshold 0.600 → 0.55 · **REJECTED, and NOT horizon-conditional**
**The "all four were measured under the 30-minute `time_stop`" claim is false for T7, in the opposite
direction from the others: T7 was measured under *no time stop at all*.** Its only forward evidence is the
`composite-055` **shadow** book, and the standing exit-fidelity caveat is explicit — the shadow book
"replicates brackets + structural stop + 15:12 square-off. It does **not** replicate indicator signal-exits
**or the YAML `time_stop`**" (`docs/signal-analysis/2026-07-29-session-findings.md:503-505`, restated at
`README.md:190`). So T7 is the one of the four this correction leaves untouched by horizon — and the standing
instruction "if G11 changes the exit, re-run every one of them" does not apply to it as written.

Two further exposures, both inherited rather than introduced here: the quoted **−₹321/close is an ALL-TIME
figure** (−4,494.36 over 14 closes), not the tested session's (−₹984/close over 3), and those 3 closes are
**un-deduped shadow rows** under the folder's own §3.24 fan-out rule, which was applied to the champion book
the same day but never to `composite-055`.

### G13 — `iv_pair` drop-or-redefine · **still undecidable; nothing to overturn**
It was never a verdict — "6 legs, the sign flips on ONE observation, six legs is not a result, it is an
anecdote". Unchanged. One caveat worth recording: **all six legs date 07-17 / 07-23 ×3 / 07-24 ×2 — every one
of them predates #990 (2026-07-25)**, which is what put the ±35% / −25% bands on the fleet. The modelled
brackets did not exist for any leg in the sample; only the modelled time stop ever bound, which is what the
write-up observed ("all six resolved at the time stop").

### G10 — relative-floor time-of-day profile · **DO NOT ARM stands; the framing was wrong**
The verdict rests on three legs of evidence and two of them are horizon-independent: the concentration
(5 legs of 265 flip the sign) and the cost band (+1.23/leg gross vs ~3.5/leg). **My probe reproduces the
concentration finding at every horizon from 30 to 90 minutes** — `net ex-top-5` never turns positive in the
all-sessions cut. So "keep it default-OFF" is not in question here.

What is wrong is the framing: ~93% of its legs resolved at the modelled stop (TP 6 / SL 12 of 265), so the
exit model was the dominant term in the number, and that model was a uniform harness parameter applied to a
population spanning five real horizons. The row's own standing instruction — "if G11 changes the exit, re-run
every one of them" — was therefore already owed, and G11 marking the four FINAL cancelled it on a false
premise.

---

## 6. Changes made by this chip

Documentation only. **No code, no config, no YAML, no arming, no deploy.**

- **G11** (`docs/superpowers/plans/2026-07-02-remaining-items.md`) — decision cell: the four are restored to
  CONDITIONAL, with the harness-vs-engine distinction recorded.
- **G10 / G1 (T1 + T7) / G13** — each row's "measured under the 30-minute `time_stop`" exposure restated;
  T7's row corrected to say it was measured under *no* time stop.
- **`docs/signal-analysis/README.md` §3.16** — the method doc every future counterfactual reads still
  asserted the uniform `max_bars: 10` fleet shape. Corrected; without this, the next counterfactual
  reintroduces the same error.
- **§4b ledger row** for `task_2735acfb`.

**Not done, deliberately:** no change to any `time_stop` horizon. If the spread now looks wrong, that is an
owner decision and a separate PR.

---

## Claim labels

- **computed** this session: the five-horizon spread re-derived from live `artha`; the 8-for-8 `TIME_STOP`
  hold-time table; the 5,035-row cohort composition and the 695-group horizon-overlap table; the entire §4
  horizon sweep including the per-session and ex-top-5 cuts; the −590.98 vs −590.95 cost-model check; the
  0-added / 0-removed / 30-context classification of `max_bars` in `64f9caaa`.
- **sourced** (read this session in this worktree at `39c4bc79`): every `file:line` code citation in §2 and
  §2.1; the T1 / T7 / G13 / G10 ledger cells and their docs-of-record; the shadow-book exit-fidelity caveat;
  G13's six leg dates; the `options_chain_snapshots` tick cadence.
- **assumed:** the 1% round-trip cost band (carried from G10 unchanged, so §4's horizons are comparable to
  its number — not re-derived against real fills); that `slug LIKE 'scalp%'` enumerates the scalper fleet
  (corroborated: returns 63, matching the 63 YAMLs); that a leg's exchange follows its symbol prefix
  (`SENSEX*` → BFO, else NFO).
- **recalled:** nothing load-bearing. Shard A's spread table was re-measured rather than adopted, and the
  brief's restatement of it was likewise treated as prose to check.

---

## Open doubts

1. **I did not reproduce G10's 265-leg selection.** Its counterfactual floor (median of the same IST bucket
   over the prior 3 sessions × 1.5) was not re-implemented; §4 sweeps the full priceable population instead.
   Same rail, same window, same arithmetic — but a superset, and the newly-passing subset could plausibly
   have a different session mix than the whole.
2. **Price source differs** (§4.2): 1m candles here vs chain snapshots there. A ~2-minute tick grid can miss
   a bracket touch a 1m close would catch. Both report ~zero TP touches, so the effect looks small, but it is
   unquantified.
3. **The 90-minute column is partly truncated.** I capped at the 15:12 square-off per README §3.16, while
   `CounterfactualService.SESSION_CLOSE` is 15:30; legs entered after ~13:42 never reach a true 90 minutes,
   so that column mixes full and truncated holds. Direction of the bias is not established.
4. **"Which strategy would have taken this leg" may be unanswerable in principle, not just in the record.**
   `signal_rejections` logs every strategy that evaluated the bar, and a fan-out entry pyramids into ONE
   paper position, so the counterfactual leg is a shared object. §3 shows 86% of legs are multi-horizon; I did
   not attempt a tie-break rule, and I do not think one would be meaningful.
5. **`paper_positions.opening_signal_id` is not a reliable cohort key under fan-out.** On 07-29 11:06 three
   strategies opened the same option leg; one position exists, its opening signal names an arbitrary one of
   them, and it was closed by whichever strategy's EXIT fired first — which is how a `max_bars: 12` position
   comes to show a 30-minute hold. I switched to `strategy.signals` for §2 and did not pursue it. This is
   consistent with the documented pyramiding behaviour, not a new defect, but it will mislead anyone who
   cohorts paper P&L that way.
6. **Whether a longer stop is actually better is wide open.** Two trending sessions say yes, the expiry
   session says emphatically no, and long-premium theta argues against it a priori. Nothing here should be
   read as evidence for lengthening any horizon.
7. **Only the `volume-floor` rail was swept.** The 41-leg and 22-leg counterfactuals draw on six and seven
   rails respectively; their non-`volume-floor` legs are not covered by §4 and could behave differently.
