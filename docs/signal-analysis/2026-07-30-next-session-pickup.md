# Next-session pickup — written 2026-07-29 evening

**Owner instruction: take ALL of these next session.** This is the single sheet; each item points at
its authority. Written after the 07-29 close, with the live stack deployed at `c24c7242` and the
repo clean.

⚠️ **Do NOT enumerate the queue from this file alone.** It is a convenience index for what 07-29
produced. The authoritative enumeration is the six-location recipe at the top of §0 of
`docs/superpowers/plans/2026-07-02-remaining-items.md`.

---

## Start here — the only clean, unblocked BUILD

**G12 — frozen-operand probe for `DotHealthCanary`.** `atmIv` carries exactly ONE distinct value per
session, four sessions running, so `iv_abs_band` is a per-day step function (0/180 on 07-28,
133/133 on 07-29). The canary called it alive and **was right by its own contract** — a frozen value
is not null. **Every alive/dead probe in the registry shares that blind spot**, which is why the
probe (`count(DISTINCT …)` over the session) is worth more than the individual dot fix.

Two deliverables, in this order:
1. **The probe** — clean tier, no owner gate, closes a class of blindness.
2. **The cause** — NOT established. No producer-side code has been read, and a deliberate
   session-open ATM-IV reference is a legitimate design. **If the freeze is intentional the fix
   belongs to the DOT, not the feed.** Read before changing.

## Then — investigation that is clean even though its fix is not

**G10 — relative volume floor, opening surge.** 43% of the session's `volume-floor` blocks land
before 11:00 because the median baseline is legitimately inflated by the opening surge (4 of the
first ten buckets ≥100,000 against a session median of 15,015). Structural: the surge recurs daily.

⚠️ **First step is a CODE READ of `priorVolumes`, not a number.** The window was NOT reconstructible
from SQL — two thresholds reconcile to a 10-bar window, a third matches nothing tried, and the engine
reads its in-memory `LiveSeriesStore`, which `PartialBucketCanary` proves diverges from the DB rollup.

⚠️ **Do NOT conflate with T1.** T1 is the MULTIPLIER and is REJECTED (2W/9L counterfactual). This row
is the WINDOW. The floor's **level is vindicated and its shape is not** — applying the T1 knob makes
this row worse.

## Unblocks the biggest finding

**G15 — chop-day observation trigger.** G11 (the `time_stop` cutting winners) is the session's largest
finding by P&L magnitude and is BLOCKED-DATA on a chop-day observation — **and nothing detects one**,
so it waits forever by default. Cheapest fix: the 15:47 agent stamps a regime label on its `rollup.md`
session row from numbers it already computes, and G11 is re-read the first time a chop day lands.

⚠️ Derive the classifier cut from the observed distribution across sessions already in the rollup —
do not pick a threshold. Same discipline that settled T22 and G13. And label the ~20 existing sessions
retrospectively; a forward-only label gives no back-sample.

## Measurement, not a fix

**G14 — capital-governors convergence bound.** #1086 refused nothing on day one, but
capacity-neutrality is **conditional on ≤3 strategies converging on one option key** (at ₹9,641/lot,
a 4th hits ₹38,564 > ₹30,000 and is refused where the book cap previously allowed it). Next step is to
instrument convergence depth, not to change the ceiling. `lockAnchorsBeforeBook` also remains unproven
— 07-29's two same-key opens were 1.1 s apart, sequential, never contended.

## Owner decisions — do not start, present

- **G13** — `iv_pair`'s operand cannot express the signal (put-call parity pins the two 6-strike
  averages within 0.0007 against a 0.02 threshold). **(a) DROP** from Σw lifts the composite cap
  0.9574 → 1.0000 and is the cheapest correct action; **(b) REDEFINE** onto something that actually
  skews. HOLD — changes which signals fire.
- **G11** — exit doctrine, money surface. Lands as ONE coordinated decision with the entry-gate track,
  never as a unilateral knob turn.
- **#1075** `budget_inr` — held to 2026-08-12. ⚠️ **Interacts with G14**: raising the budget raises the
  per-lot charge and LOWERS the convergence count before a sub-account refusal. Decide together.
- **T9 arming**, **INT I4** (~2026-08-09), **B8 host-clock resync** — unchanged.

## Carry-forward engineering (not from today's analysis)

- **D3 Map-return burn-down** — 47 left (market-data 26, strategy-signal 14, backtest 7). Next batch
  `RegistryController` (10; `list` + `versions` are already-typed-item envelopes). Per-PR recipe:
  classify the source before writing the claim (`LinkedHashMap` = order load-bearing · multi-key
  `Map.of` = order NORMALIZED, never claim byte-identical · single-key = trivially identical), diff
  generated-vs-committed spec, add the `Contract break: APPROVED` line, extend `contracts.bridge.ts`,
  ratchet down in `MapReturnRatchetTest`.
- **§9-03 trial-metrics catalog** — the only remaining startable in §9 (02/04/05/06 all closed).
- **task_7f57c0d5** — `OpeningSignal`'s three nullable `JsonNode` fields, same defect review caught in
  #1097.
- **task_d16e9d86** — CLOSED by #1104.

## Watch items, no action

- **T24 volume dot is WORKING** (rail-vs-dot disagreement 141 → 270 → **0**), but its **exit radius is
  UNEXERCISED, not proven safe** — the 8 dual-tag strategies opened nothing, and there have been zero
  `CONFLUENCE_FLIP` exits platform-wide since 07-01.
- **3 of 5 scalper sub-accounts froze on losses by 13:40 IST** on day one. A 6th entry would have found
  4/5 frozen. Not a #1086 issue; worth watching.
- **Entry-vs-exit emit latency (G8): the premise is gone.** 07-29's 20 signals show ~17 s as a UNIFORM
  emit cost, not the entry path's strike resolution. The 07-27 fast-exit reading is now the outlier.

---

## Two process facts from 07-29 worth not relearning

1. **Both scheduled verify tasks reached a verdict and neither wrote it down.** Recovered in
   [#1109](https://github.com/prashantm912/artha-yantra-2/pull/1109). A task whose output lives only in
   its session transcript is, a week later, indistinguishable from one that never ran. Either open a PR
   like the post-market routine does, or say in the prompt that findings return to the owner only.
2. **The champion shadow book's "+₹15,260.87 best session on record" is ~ONE BAR replicated ten times**
   by slug fan-out — effective independent sample ~6, not 24, and every cross-session league number
   inherits it. The post-market routine caught and filed this correction itself, against its own
   headline. Dedupe by `(bar_time, tradingsymbol)` before quoting any W/L or per-close figure.
