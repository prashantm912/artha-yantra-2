# Next-session pickup — written 2026-07-30 (after the 07-29/30 overnight run)

Live stack deployed at **`f6ba4dab`**, repo clean (main + two owner-gated branches). Nine PRs merged
this run: #1111–#1119.

⚠️ **Do NOT enumerate the queue from this file alone.** It is a convenience index. The authoritative
enumeration is the six-location recipe at the top of §0 of
`docs/superpowers/plans/2026-07-02-remaining-items.md`.

---

## READ THIS FIRST — it changes what is worth picking up

**Every measured loosening of the scalper ENTRY gate has lost money: T1, T7, G13, G10 — four tests,
three knobs.** Details in `2026-07-30-session-learnings.md`. Treat it as the prior before proposing
any entry-knob change.

⚠️ **All four are conditional on the 30-minute `time_stop`**, which G11 says is the dominant term in
scalper P&L. **If G11's exit changes, re-run all four.** That makes G11 the highest-leverage row on
the board, not the entry track.

---

## Clean, unblocked, startable

- **§9-03 trial-metrics catalog** — the only remaining startable in §9 (02/04/05/06 closed).
- **D3 Map-return burn-down — 37 left** (market-data 26, strategy-signal 4, backtest 7). Recipe in
  the D3 ledger row; ⚠️ count the ratchet with `MapReturnRatchetTest`'s own `*Controller.java` regex
  and red-proof the floor (an over-stated one passes green — cost a review round this session).
- **`task_7f57c0d5`** — `OpeningSignal`'s three nullable `JsonNode` fields.
- **`task_fbc8e4bd`** — NEW: audit for other structural/ArchUnit tests that should also be
  rerun-exempt (#1115 fixed `ModularityTest` only).

## Data-gated, waiting on the market

- **G11 (exit doctrine)** — needs ONE post-07-27 chop day. G15's detector now stamps a regime label
  on each `rollup.md` session row, so the 15:47 agent will announce it. Base rate ~29% ⇒ expect one
  within ~3–4 sessions. **Re-read G11 the moment a `chop` row lands.**
- **G8 / G9** — open but re-characterised; see their rows.

## Owner decisions — present, do not start

| row | state |
|---|---|
| **G10 arming** | recommendation **NO** — 265 legs, +324.87 gross, **−590.95 after 1% cost**, sign flips on 5 legs. Build is in and default-OFF. |
| **G13** | measured. Arithmetic favours dropping `iv_pair` + redefining `iv_abs_band` (the intraday operand already exists as `(ceIvAvg6+peIvAvg6)/2`), but payoff ≈ 6 legs and the loosening prior argues against. |
| **#1075** `budget_inr` | held to 2026-08-12. **Decide with G14** — raising it lowers the convergence depth at which the sub-account ceiling refuses. |
| **T9 arming**, **INT I4** (~2026-08-09), **B8 clock** | unchanged |

## Watch items

- **G14 convergence** — 5–7 strategies converge on one option key on 6 of 7 sessions in the shadow
  book. Not binding today (fires reach depth 2) but sits directly in the path of any fire-rate rise.
- **`MIN_FROZEN_BARS` residual** — the G12 freeze probe still abstains on thin sessions (07-21 hit 7
  bars vs a floor of 8). Closing it needs `FETCH_DEPTH` 200→400, doubling a deliberately-bounded
  5-minutely read. Owner call, recorded not done.
- **T24 volume dot** working, but its exit radius is still **unexercised**, not proven safe.

---

## Process facts worth not relearning

1. **A green red-proof is a finding about the proof, not the code.** Twice this session a red-proof
   passed when it should have failed — once because it disabled the wrong thing, once because the
   fixture triggered the *safe* symptom rather than the dangerous one.
2. **Verify a claim on the population the CODE reads.** "200 context-bearing rows" vs the code's
   "200 raw rows" turned 7–25 into a claimed 18–34 and survived review.
3. **CI retries can mask a deterministic failure.** A real Modulith cycle sat on main for hours
   reported green as a "flake". Fixed for structural tests (#1115); `task_fbc8e4bd` covers the rest.
