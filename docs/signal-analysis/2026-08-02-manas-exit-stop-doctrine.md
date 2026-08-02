# Manas exit-stop doctrine — should the Chandelier trail drive intraday exits?

**Date:** 2026-08-02 · **Chip:** `task_3e4bae86` (split out of PR #1221 / M40) · **Type:** measurement +
recommendation, **no production behaviour changed** · **HEAD measured:** `85c56004`

---

## 1. Verdict

**Stay EOD-managed. Do not persist the Chandelier trail into `paper_positions.stop_loss`, and do not add
a second column for it either — not yet.** Confidence **HIGH** on the recommendation, **LOW** on the P&L
estimate behind it; those differ because the burden of proof sits on the change, and the measurement
fails to discharge it (n = 5 armed legs, and 63% of the measured benefit is three coin flips that all
happened to land the same way).

Three independent reasons, in descending order of strength:

1. **The intraday path is not merely undesirable — it is structurally non-functional for this book, and
   the fork as posed understates what "go intraday" costs.** No NSE equity is subscribed to the live tick
   feed. `PaperBracketEvaluator` does iterate all six open `manas-arora` rows every 15 s and does read
   their `stop_loss`, but `LastTickReader` returns empty for every one of them, so it skips on every pass
   and has done since the book opened. "Go intraday" is therefore not a doctrine flip; it is a feature
   request — subscribe ~14+ small-cap equities to the live feed, add a ratchet writer, add a second
   column, and decide what three UI surfaces display. None of that is priced by the measurement below.
2. **The measurement cannot support a verdict either way.** Full-intraday counterfactual over the book's
   entire life: 9 of 14 legs differ, net **+₹1,145** favouring intraday — but t = +0.88 (needs |t| > 2.31),
   sign-test p = 0.090, the sign flips on dropping 3 of 9 legs, and one of the 9 is an unrealised mark.
3. **The residual is real but belongs elsewhere.** Exactly one consumer is served wrongly by the stale
   column — `PaperEmissionGuard.openRiskInr` — and it is a *risk-sizing* read, not an exit read. M40's
   in-memory fix is the correct shape. The column itself is correct for exits; only its **name** misleads.

**What I am not saying:** the point estimate does lean positive, and under the narrow trail-only policy it
leans positive on 5 of 5 legs. That is suggestive and worth re-testing later. It is not decidable now.

---

## 2. Premise verification (Step 0)

Every premise in the brief was re-checked against `85c56004`. **Four corrections.**

| # | Premise as briefed | Verdict |
|---|---|---|
| 1 | `PaperBracketEvaluator.evaluate()` iterates `listOpen()` with **no book filter**, breach at `:95` | ✅ **CONFIRMED** verbatim — `positions.listOpen()` at `:48`, `breach(pos.side(), pos.stopLoss(), pos.takeProfit(), ltp)` at `:95` |
| 2 | `PaperScheduler.bracketEvaluation()` runs **every 15 s** | ✅ **CONFIRMED** — `@Scheduled(cron = "*/15 * 9-15 * * MON-FRI", zone="Asia/Kolkata")` |
| 3 | `manas-arora` has 6 OPEN rows, **all 6** with non-null `stop_loss`, 0 with `take_profit` | ✅ **CONFIRMED** exactly |
| 4 | The only production caller of `updateBrackets` is the manual bracket-edit path (`PaperService:1202`) | ✅ **CONFIRMED** — `paper_positions` has exactly 4 UPDATE statements; only `:169` touches `stop_loss`, sole caller `:1202`. **The column is write-once-at-entry unless a human PATCHes it.** |
| 5 | "…so they are intraday-evaluated on that column today" | ⚠️ **CORRECTION.** They are *iterated*, never *evaluated*. `LastTickReader` returns empty for every equity → `ltp == null` → `continue`. **Zero of the book's 9 closes came from the 15 s poll.** See §3. |
| 6 | "`manas-arora` is listed in `eod-managed-books` … that is what it exists for" | ⚠️ **CORRECTION.** Listed: yes (`PaperStaleTickAlerter:78`, default `minervini,manas-arora`). But the setting is referenced in exactly **one** place — `:118` — and it suppresses a *starvation alert*, not an *exit*. It does not exempt Manas from intraday evaluation. Tick starvation does. The config is a symptom of the real mechanism, not the mechanism. |
| 7 | "`manas-arora` has a known **open** SELL row (`id=28`, owner-parked)" | ❌ **FALSE at HEAD.** `id=28` is **CLOSED** — 2026-07-17 13:04:53 IST, `close_reason=MANUAL`. It also carried a **NULL** `stop_loss` for its whole life. There is no open SELL row on the book today. The short-ratchet direction trap remains a valid design caution (§6) but has no live instance. |
| 8 | "M40 now computes the governing stop in memory … leaving exits byte-identical" | ⚠️ **CORRECTION.** True of the **PR branch**; PR #1221 is still **OPEN** and unmerged. On `main` today there is no in-memory correction at all — `openRiskInr` reads the raw stale column. |

---

## 3. Why the intraday evaluator has never fired on this book

Measured, not inferred.

**Every automated close is the EOD batch.** All 9 `manas-arora` closes, by IST wall clock:

| close_reason | n | close times (IST) |
|---|---|---|
| `TRAILING_STOP` | 5 | all at **20:05** |
| `STOP_LOSS` | 2 | **20:05:06** (07-08), **20:05:06** (07-31) |
| `MANUAL` | 2 | 13:04:53, 13:52:43 — owner actions |

Not one close at a 15-second poll boundary. Both `STOP_LOSS` closes — the reason the bracket evaluator
*would* write — landed at 20:05, i.e. from the swing EOD pass, not the poll.

**The cause is tick starvation, and it is structural.** Redis `ticks:last` holds **181** fields:

```
88 BFO  ·  86 NFO  ·  5 NSE  ·  2 BSE
NSE/BSE keys in full: NSE:NIFTY 50, NSE:NIFTY BANK, NSE:NIFTY MID SELECT,
                      NSE:NIFTY FIN SERVICE, NSE:INDIA VIX, BSE:SENSEX, BSE:BANKEX
```

Indices and index derivatives only. **Zero equities.** Probing all 20 distinct symbols ever held by
`manas-arora` and `minervini` returns no tick for any of them. This is not a weekend artefact — Friday
2026-07-31 ticks are still resident (`"timestamp":"2026-07-31T08:58:09.424+05:30"`), so the hash retains
the last session; the equities were simply never there.

So `PaperBracketEvaluator` reaches `:52`, gets `Optional.empty()`, sets `ltp = null`, computes
`reason = null`, calls `staleTicks.observeBracket(...)` and `continue`s — every position, every pass,
every session. **`paper_positions.stop_loss` is read every 15 seconds and discarded every 15 seconds.**

> ⚠️ `ay_paper_bracket_starved_total` currently reads **0.0** and that proves nothing — today is Sunday
> 2026-08-02 and the container restarted 03:58 IST, so the `MON-FRI 9-15` cron has not run since boot.
> I nearly reported that counter as evidence; it is not.

**Consequence for the discarded M40 attempt.** Persisting the ratchet into that column would have been
**inert today**, not the immediate one-bar force-exit the audit feared — the mechanism differs from the
one assumed. That is *worse*, not better: it would have sat dormant and silently armed itself the first
day an equity entered the tick universe, with no test and no alert marking the transition. The audit
reached the right decision on a wrong mechanism.

---

## 4. The measurement

### 4.1 Harness validation (done first — the sample is too small to survive a harness bug)

Only **daily** bars exist for these equities (63 bars/symbol from 2026-05-04; no 1 m data — consistent
with them not being tick-subscribed). So the test is `daily low ≤ trail` (intraday touch) vs
`daily close ≤ trail` (what fires today, per `ExitEvaluator:610-611`).

Reconstructed `ExitEvaluator.rollingAtrTrailLevel` exactly: ratcheted `max(highest-high − 2×Wilder-ATR(20), entry)`,
armed at +9%, ta4j MMA semantics, `unstable = period`. Three independent checks:

1. **Cap-bound initial stops match to the paisa** — `SCPL 615.00×0.9 = 553.50` = persisted `553.5000`;
   `SOTL 607.45×0.9 = 546.705` = persisted; `TIRUPATIFL 76.95×0.9 = 69.255` = persisted.
2. **`sell_decisions.stop_level` matches** — OMAXAUTO `229.95529340` vs persisted `stop_loss 229.9553`.
3. **The EOD model reproduces 7 of 7 automated exits — date *and* reason** (SENORES 07-20 TRAIL,
   SBCL 07-08 STOP, ATHERENERG 07-23 TRAIL, THANGAMAYL 07-29 TRAIL, SOTL 07-30 TRAIL,
   TIRUPATIFL 07-24 TRAIL, SATIN 07-31 STOP).

**A surprising result that was a harness bug, per the rule.** TIRUPATIFL closed `TRAILING_STOP` in
production but my first model said its trail never armed — peak gain +8.98% against a 9% threshold. Cause:
the engine's `Position.entryPrice()` is the **entry bar's close** (76.95), not the paper fill (76.99).
On the correct basis `76.95 × 1.09 = 83.8755 ≤ 83.90` → armed. Fixing the entry basis is what produced
the 7/7 above; on the wrong basis it was 6/7. Reported because it changed a headline number.

### 4.2 Result — full-intraday policy (governing stop = trail if armed, else initial stop)

9 of 14 BUY legs differ. Net **+₹1,141.74** (costs netted) against a book that has realised **−₹7,278.75**.

| robustness test | result |
|---|---|
| per-leg mean / sd | +₹127.21 / ₹435.02 → **t = +0.88** (needs \|t\| > 2.31 at 5%, df=8) |
| sign test | 7 positive / 2 negative → one-sided binomial **p = 0.090** |
| drop top-1 / top-2 / top-3 | +₹514 / +₹49 / **−₹410 — sign flips** |
| slippage 25 / 50 / 100 bp | +₹869 / +₹593 / **+₹40** |
| exclude the unrealised leg (KANORICHEM still OPEN — a mark, not money) | +₹1,684 over 8 legs |

### 4.3 Result — trail-only policy (the exact fork as posed: trail intraday, initial stop stays EOD)

The trail armed on **5 of 14** legs. All 5 differ, and all 5 favour intraday: net **+₹1,977** gross.
Uniform sign is why this variant deserved a second look — and why it then had to be decomposed.

**Touched-but-recovered events — the specific quantity asked for: 3, on 5 armed legs.**
SENORES 07-17 (trail 1396.18, low 1392.80, close 1405.10, +0.64% recovery) · SOTL 07-29 (607.45 / 602.05 /
609.30, +0.26%) · TIRUPATIFL 07-23 (76.95 / 73.40 / 77.48, +0.69%). (Four further touched-but-recovered
events occur against the *initial* stop, not the trail, and are out of scope for this variant.)

**The decomposition that decides it.** Split the +₹1,977 by *why* each leg benefits:

| component | legs | benefit | survives a pessimistic fill? | sign established? |
|---|---|---|---|---|
| **Path-independent** — same-day, exits earlier into a decline | ATHERENERG, THANGAMAYL | +₹736 | ❌ **→ +₹0** when the fill degrades to that bar's close | n/a — collapses |
| **Path-dependent** — touched-but-recovered, then rolled over anyway | SENORES, SOTL, TIRUPATIFL | +₹1,240 | ✅ unaffected (exit is on an earlier bar) | ❌ **no — 3 coin flips, all heads** |

The path-independent half is purely a **fill-quality bet I cannot verify**: with no 1 m bars I cannot
confirm the price traded through the level in a way a 15 s poll would catch, rather than gapping past it.
At `f = 1` (fill degrades to the close) it contributes exactly zero.

The path-dependent half — **63% of the total** — is the brief's own question turned against the result.
Each of those three is a day when the intraday policy exits and EOD holds. In this sample all three were
followed by a *lower* eventual exit, so early exit looked good. Had any recovery continued upward instead,
the early exit would have cut a winner, and the loss on that side is unbounded above. Three observations
all landing the same way is p = 0.125 on a fair coin. **That is not evidence; it is a small sample.**

This is also the shape the repo's prior predicts. Every measured *loosening* of a gate here has lost money
(T1/T7/G13/G10). A tightening is not the automatic inverse — but the mechanism by which this tightening
would pay is "exit earlier and be right about it", and the sample contains no instance of being wrong.

### 4.4 Power — what would actually settle this

Current rate: 5 armed legs and 3 touch events in ~4 weeks (≈0.75 touch events/week). Resolving the sign of
the path-dependent component at even modest power needs ~30 events → **roughly 9–10 months of forward
paper at the present book size**. The path-independent component cannot be settled by more daily bars at
all: it needs **intraday bars for these symbols**, which only exist if the equities are tick-subscribed —
which is the change itself. **Insufficient to decide, and not close.**

---

## 5. The residual — who actually reads `paper_positions.stop_loss`

Enumerated across backend, API, frontend, notifiers, reconcilers and metrics; every claim below re-verified
against source. Excluded as different sources (not this column): `backtest_trades.stop_loss`,
`strategy.signals.stop_loss` (this is what `SignalEngine:1587-1595` reads — *not* the paper column),
`shadow_positions.stop_loss`, and the YAML `exit_rules[type=stop_loss]` family.

**Writers:** the entry INSERT (`PaperPositionRepository:251`; value from premium brackets, the signal's
stop, or a manual ticket) and the manual `PATCH .../brackets`. **Nothing ratchets it. Ever.**

| consumer | file:line | kind | wants | verdict |
|---|---|---|---|---|
| `PaperEmissionGuard.openRiskInr` → `ManasPyramidPolicy:100` | `PaperEmissionGuard.java:80-91` | decision (6% pyramid risk cap) | **ratcheted** | ❌ **SERVED WRONGLY** |
| `PaperBracketEvaluator.breach` | `PaperBracketEvaluator.java:104-111` | decision (auto-exit) | the *current* stop | ✅ self-consistent — and inert here (§3) |
| `toPositionDto` / `positionDetail` → 3 UI sites | `PaperService.java:1372`, `:1137`; `PaperBookPanel.tsx:177`, `ScalperCockpitPage.tsx:173`, `PositionDetailDrawer.tsx:115-127` | display | current live-effective | ✅ honest **only because** nothing ratchets |
| `PaperService.editBrackets` | `PaperService.java:1206` | audit "previous value" | prior value | ✅ correct |
| `PortfolioReader.staleTickScan` | `PortfolioReader.java:135,137` | `IS NOT NULL` only | indifferent | ✅ correct |
| ~14 repository/service pass-throughs | incl. `intradayOpen():355`, `openForSignal():373` | select-and-discard | — | ✅ never read the value |

**So the brief's expected outcome is about 80% right, and I want to be precise about the 20%.** "The
intraday bracket wants exactly the initial stop" — nearly: it wants whatever is *currently* live, which
happens to be the initial stop because nothing ratchets. "Nothing else reads it" — **not true.** The
pyramid risk cap reads it and is served wrongly.

The evidence that this is a genuine defect and not my invention is in the repo's own test:

```java
// PaperEmissionGuardTest.java:34
// Position B: 50 qty, entry 300, stop 320 (trailed ABOVE entry) -> open risk 0 (§3.5.B).
```

The production branch that case exercises — `if (perUnit.signum() > 0)` at `PaperEmissionGuard.java:87` —
**is dead**, because no code path ever moves the column after insert. The guard therefore charges every
winner its full *initial* risk for the life of the position and **over-refuses** pyramid adds.

**But that does not argue for changing the column.** It is a risk-sizing read, and the right fix computes
the governing stop at the point of use — which is exactly what M40 (PR #1221) does. **Land M40; leave the
column alone.** For exits the column is correct as-is and the fix is a comment naming what it holds
("the INITIAL entry stop; never ratcheted — the trail lives in the EOD pass"), not code.

---

## 6. If this is ever revisited

Preconditions, both required: (a) these equities are tick-subscribed for some independent reason, and
(b) ~30 armed-trail touch events have accumulated and the path-dependent sign has been re-measured.

Then, and only then: **two stops mean two fields.** Do not overload one column — that overloading is the
entire root cause here. And the ratchet guard must be direction-aware: `? > stop_loss` is correct for a
LONG and **backwards for a SHORT**. No open SELL row exists today (§2 row 7), but `id=28` proves the book
takes them, and it carried a NULL stop — so a short-side ratchet would also need to handle "no stop yet".
A second column additionally forces a display decision at the three UI sites in §5, including
`PositionDetailDrawer`, which re-seeds the owner's manual-edit draft from whichever column it reads.

---

## 7. Side-finding — a missed EOD session (separate from this fork)

**2026-07-17 (Friday) was a trading session with zero swing equity evaluation.** `sell_decisions` for
`manas-arora` runs 07-13…07-16, skips 07-17, resumes 07-20; `strategy.signals` shows the only EXIT that
day was `NIFTY26JULFUT` (a scalper) — no equity exits. Daily bars for 07-17 exist, so it was a real session.

That session mattered: OMAXAUTO closed at **228.38** against its stop of **229.9553** — the EOD stop should
have fired. Nothing evaluated it, and the owner manually closed the position three days later on 07-20 at
≈227.59. This is the only leg where my EOD model disagrees with production, and the disagreement is
production's, not the model's.

**This is the strongest argument on the "go intraday" side and I want to state it fairly — and then
reject it.** An always-on 15 s poll does not miss a session. But the correct remedy for a missed batch is
the batch-liveness/catch-up machinery that already exists for exactly this (detector, catch-up, heartbeat
dead-man's-switch), not a change of exit doctrine. Converting how exits are *decided* in order to fix a
*scheduler-reliability* problem would be solving the wrong problem, and would do so by adopting a policy
whose own P&L sign is unestablished. **Worth its own chip; not a reason to move this fork.**

---

## 8. Claims and evidence

| # | Claim | Label | Evidence |
|---|---|---|---|
| 1 | Bracket evaluator has no book filter; breach at `:95`; 15 s cron | **sourced** | `PaperBracketEvaluator.java:48,95`; `PaperScheduler.java:31-33` |
| 2 | 6 OPEN manas rows, all with `stop_loss`, none with `take_profit` | **computed** | `SELECT book,status,count(*),count(stop_loss),count(take_profit) … GROUP BY` |
| 3 | All 7 automated closes at ~20:05 IST; 0 from the 15 s poll | **computed** | `to_char(closed_at AT TIME ZONE 'Asia/Kolkata', …)` over the 9 closed rows |
| 4 | `ticks:last` = 181 fields, indices + index derivatives only; 0 equities | **computed** | `HLEN` = 181; `HKEYS` prefix tally 88 BFO/86 NFO/5 NSE/2 BSE; `HGET` empty for all 20 swing symbols |
| 5 | Friday ticks retained ⇒ absence is structural, not a weekend artefact | **computed** | sample value carries `"timestamp":"2026-07-31T08:58:09.424+05:30"` |
| 6 | `eod-managed-books` is alert-suppression only, referenced once | **sourced** | repo-wide grep → `PaperStaleTickAlerter.java:53,78,84-87,118` + `application.yml:166` only; `:118` returns before the page |
| 7 | `stop_loss` is write-once-at-entry unless manually PATCHed | **sourced** | 4 `UPDATE paper_positions` statements; only `:169` writes it; sole caller `PaperService:1202` |
| 8 | Trail reconstruction reproduces 7/7 automated exits, date + reason | **computed** | `trail2.py` validation block |
| 9 | Engine entry basis = entry-bar CLOSE (3 cap-bound stops match to the paisa) | **computed** | `615.00×0.9=553.50`, `607.45×0.9=546.705`, `76.95×0.9=69.255` vs persisted |
| 10 | Full-intraday: +₹1,141.74; t=+0.88; p=0.090; sign flips at top-3 | **computed** | `trail2.py` + robustness script |
| 11 | Trail-only: 5/5 legs positive, +₹1,977; 3 touched-but-recovered | **computed** | trail-only variant script |
| 12 | 63% of that benefit is path-dependent; the rest → ₹0 at worst fill | **computed** | fill-sensitivity table, f ∈ {0,…,1} |
| 13 | `openRiskInr` wants ratcheted, gets initial; its test's branch is dead | **sourced** | `PaperEmissionGuard.java:80-91`; `PaperEmissionGuardTest.java:34`; `ManasPyramidPolicy.java:100` |
| 14 | PR #1221 OPEN — no in-memory governing stop on `main` | **computed** | `gh pr view 1221` → `state: OPEN, mergeCommit: null`; grep finds no such calc at HEAD |
| 15 | 2026-07-17 had no swing equity evaluation | **computed** | `sell_decisions` gap; `signals` EXIT that day = `NIFTY26JULFUT` only; daily bars exist |
| 16 | ~9–10 months to power the path-dependent sign | **assumed** | extrapolation of 3 events / 4 weeks at constant book size — arithmetic, not a model |

All DB access read-only: `SET statement_timeout` on every statement, per-symbol `IN` lists, explicit
`+05:30` bounds, `AT TIME ZONE 'Asia/Kolkata'` for rendering only. No DDL, no writes, no cagg refresh.

---

## 9. Open doubts

1. **The daily-low proxy over-counts touches.** A 15 s poll can miss a sub-15 s wick, and a stop that
   triggers need not fill at the level. I flagged this as a fill-quality bet and priced it (§4.3), but I
   cannot bound it without 1 m bars for these symbols, which do not exist.
2. **My counterfactual is partial-equilibrium.** Exiting SENORES on 07-17 frees capital and changes the
   pyramid risk-cap headroom, which could alter later entries. The book is held fixed. With 14 legs and
   `max_positions` pressure this could matter, and I did not model it.
3. **ATR reconstruction is not byte-exact.** My series starts 2026-05-04; production's may start earlier,
   and Wilder retains ~12% of seed influence after 42 bars. ATR-bound stops reproduce within ±0.78%
   (cap-bound ones are exact). The 7/7 exit-date match suggests this is immaterial at daily resolution,
   but a leg sitting within ~0.8% of its trail could flip.
4. **`square_off` (fast_pct/parabolic) is not modelled.** It never fired in this sample, so it did not
   affect any measured leg — but a longer window would need it.
5. **I did not establish *why* 2026-07-17 was missed**, only that it was. Missed-batch vs recorder gap vs
   data condition is unresolved; `sell_decisions` itself only begins 07-13, though the 07-13…07-16 /
   07-20 contiguity makes a genuine gap the most likely reading.
6. **n = 5 armed legs is small enough that one mis-modelled leg moves the headline.** THANGAMAYL alone is
   36% of the trail-only total at best fill. I have not independently verified its 07-29 intraday path
   (a −10.7% session) beyond the daily bar.
7. **Whether equity tick subscription is even feasible** (feed cost, Kite subscription limits against the
   181 already in use) is unexamined — it is the gating precondition for the whole intraday option and
   I did not price it.
