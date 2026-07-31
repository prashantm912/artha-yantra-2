# Which gate rails may suppress the confluence-flip exit — audit, 2026-07-27

**Verdict: no code change warranted. The chip's premise was wrong, two of its numbers were wrong,
and the hazard it describes is currently unreachable.** The real defect class is narrower than
"entry rails leak into the exit path", both known instances are already fixed, and the evidence base
is far too thin to convict any remaining rail. What this audit *did* find is an observability gap
that must be closed before the question can ever be settled empirically.

Audited against `main` @ `280d1bd9` and the live DB, market closed.

---

## 1. The chip's framing was wrong

`task_8ca040cd` (and ledger §4b) says *"32 of 33 side-dependent gate rails can hide a confluence
flip"*, implying 32 latent defects. That reading does not survive contact with the flip exit's own
doctrine.

`SignalEngine.java:1326-1329`:

> E9 D4 OI-confluence-flip exit (scalper, live-only, tag `oi-confluence-exit`): re-read the OI
> confluence at this bar; **if it now STRONGLY confirms the OPPOSITE side** to the one held, the read
> has flipped against the position — exit. **Reuses the entry gate.**

Reusing the entry gate is **deliberate**, and "strongly confirms" is the requirement. The rails are
therefore *supposed* to participate in the flip decision — a rail declining to confirm the opposite
side is the mechanism working, not leaking. A blanket entry-scoping of 32 rails would have weakened
every flip exit to a bare VWAP crossover.

## 2. The real defect class, and why `fii-bias` was still right to fix

The genuine failure is narrower:

> A rail that can **never** pass the opposite side turns the flip exit off **permanently**, rather
> than declining to confirm it on a particular bar.

Two instances are known, and **both are already fixed**:

| rail | why it could never pass the opposite side | fixed in |
|---|---|---|
| `option-side-constraint` | a strategy declaring `option_types: [PE]` structurally never passes CE — it is a declaration, not a market read | pre-existing (`enforceOptionSide`) |
| `fii-bias` | operand (FII index-future long share) runs 8.1–15.7%; the rail needs ≥50 for CE, so CE failed on essentially every bar | #1050 |

No third instance was identified. The distinguishing test is **not** "is this an entry rail" but
**"can this rail realistically pass on both sides?"** — an empirical question, not a doctrinal one.

## 3. Corrected numbers

The chip's counts were produced by a proximity regex (`side` appearing within N lines of
`cfg.has(...)`) and are inflated. Ground truth is the `ScalperGates` method signatures — which
methods actually take an `OptionType`:

| measure | chip claimed | actual |
|---|---|---|
| tags calling a **side-taking** gate | 33 | **27** |
| entry-scoped among them | 1 | 1 (`fii-bias`) |
| armed on the 8 flip-exit strategies | 8 | **7** |

`divergence-vol-gate` is **not** side-taking — it calls `divergenceVolume(BigDecimal volume)`, a
volume floor with no side parameter. It can still block (any block empties the oracle), but it cannot
be systematically one-sided, which is the class that matters.

The 7 genuinely side-taking armed rails: `rsi-cooloff`, `constituent-gate`, `directional-vix-gate`,
`oi-cross-required`, `oi-slope-agree`, `oi-divergence-magnitude`, `oi-interval-and-60m-trend`.

## 4. The hazard is currently unreachable

| fact | value |
|---|---|
| paper positions ever opened | 30 — **all** on `minervini` (18) / `manas-arora` (12) |
| paper positions on any scalper book | **0** |
| the 8 `oi-confluence-exit` strategies | all scalpers |

**The flip exit has never had a position to exit from.** Zero `CONFLUENCE_FLIP` events are fully
explained by that, not by rail suppression. Nothing is being suppressed today because nothing is held.

This is why no live change is warranted now — and why the doctrine should be settled **before**
scalpers begin taking paper positions rather than after.

## 5. Why the remaining 7 cannot be convicted on current evidence

Recorded blocks in `strategy.signal_rejections`, by side:

| rail | CE blocks | PE blocks |
|---|---|---|
| `oi-cross-required` | 36 | 0 |
| `directional-vix-gate` | 1 | 0 |
| `rsi-cooloff` | 0 | 0 |
| `constituent-gate` | 0 | 0 |
| `oi-slope-agree` | 0 | 0 |
| `oi-divergence-magnitude` | 0 | 0 |
| `oi-interval-and-60m-trend` | 0 | 0 |

Five of seven have **never been the blocking rail**. `oi-cross-required`'s 36-0 split is suggestive
of one-sidedness but is confounded: `signal_rejections` records only the `confluence-blocked` outcome
(both `recordRejection` call sites sit downstream of the chart-gate early return), it records the
**first** blocking rail only, and the sample is dominated by CE-deriving bars. 36 observations cannot
distinguish "structurally one-sided" from "the tape was bullish".

Convicting a rail on this evidence would mean changing live exit behaviour on a guess.

## 6. What this audit actually found: the exit reason is not queryable

`SignalEngine.emit(...)` passes `exitReason` to three places — a log line, a `SignalExited` event,
and the paper close — but **never persists it on the signal row** (`strategy.signals` has no reason
column; `scalper_detail` does not carry it).

Consequence: scalpers have emitted **10 EXIT signals** and hold **zero** paper positions, so those
reasons reached only the log and are now gone with container restarts. *"Why did this scalper exit?"*
is currently unanswerable from the database.

That is the instrument this question needs. Until an exit carries its reason durably, no amount of
reasoning settles whether the flip exit is firing when it should.

## 7. Recommendation

1. **No rail changes now.** The premise was wrong, the class is narrower than stated, both known
   instances are fixed, and the hazard is unreachable while no scalper holds a position.
2. **Persist the exit reason** on the signal row (or a dedicated column) — clean tier, observability
   only. Without it §5 can never be resolved.
3. **Re-run this audit once scalpers hold paper positions**, with the reason persisted. Then the test
   is empirical: does any armed rail block the opposite side on ~every bar?
4. **Keep the principle written down** so a future arming is checked against it: *a rail whose operand
   cannot realistically satisfy the opposite side must be entry-scoped.* That is what `fii-bias` taught,
   and it is cheaper to apply at arming time than to discover in production.
