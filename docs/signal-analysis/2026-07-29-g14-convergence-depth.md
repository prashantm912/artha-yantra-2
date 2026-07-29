# G14 — capital-governor convergence depth: measured

**Written 2026-07-29.** Discharges the measurement step ledger row **G14** asked for.
**No code changed** — any change to the ceiling is HOLD, and none is proposed.

---

## Verdict

**Measured, and it inverts the row's assumption. Convergence deeper than the governor's bound is not
"plausible, not exotic" — it happens on 6 of the 7 sessions on record, and when it happens it lands
at 5–7 strategies on one option key, never at 3 or 4.** The distribution is **bimodal**: convergence
is either 1–2 or it is 5–7, with essentially nothing in between. So the ₹30,000 sub-account ceiling
is not approached gradually and then breached at the margin — the events that reach it overshoot it
by 60–125%.

**It is not binding today, and the reason is the fire rate, not the bound.** The live paper book
opens on FIRES, and fired signals reach at most **2**-way convergence. The 5–7 figures come from the
champion **shadow** book, which opens on composite-passing REJECTIONS — i.e. it models *what would
open if the gates passed*. **That is precisely the state the entry-gate tuning track is trying to
reach**, so the bound sits directly in the path of the work in progress.

---

## 1. Method

One row per `(3m bar, exchange, tradingsymbol, side)`, counting DISTINCT `strategy_version_id` — the
governor's key is `(book, exchange, tradingsymbol, side)` and the add path pins a correlated add to
the same sub-account, so same-bar same-key opens are what accumulate against one ₹30,000 account.

Source `strategy.shadow_positions` (variant `champion`, the one that mirrors the real fleet) and
`strategy.signals` (`tradeable_*` = the resolved option leg), 2026-07-20 → 2026-07-29, live `artha`.

## 2. Fired signals — max convergence 2

| session | max same bar | max same session | keys ≥3 | keys ≥4 |
|---|---|---|---|---|
| 2026-07-20 | 1 | 1 | 0 | 0 |
| 2026-07-27 | 2 | 2 | 0 | 0 |
| 2026-07-29 | 2 | 2 | 0 | 0 |

**Under today's fire rate the governor cannot refuse anything** — 2 converging is ₹19,282, well
inside ₹30,000. That, not the ceiling's design, is why #1086 refused nothing on its first live day.

## 3. Shadow book — the depth histogram

Champion variant, 7 sessions, per `(bar, key)`:

| strategies on one key | occurrences | sessions seen on |
|---|---|---|
| 1 | 48 | 7 |
| 2 | 14 | 5 |
| 3 | **0** | — |
| 4 | **0** | — |
| 5 | 3 | 2 |
| 6 | 9 | 6 |
| 7 | 3 | 2 |

**The gap at 3 and 4 is the finding.** Convergence is produced by slug fan-out, not by independent
coincidence: every scalper shares one 3m NIFTY-future signal series and one `StrikePicker`, so when
a bar produces a signal a whole family of slugs resolves to the *same* ATM leg at once. The family
is ~6 wide. There is no mechanism that produces exactly 3.

Per-session maxima: 6, 5, 6, 7, 7, 6, 6 (07-20 … 07-29). **Every session reaches ≥5.**

## 4. What that does to the row's arithmetic

The row modelled, at the sizer's ₹9,641/lot:

- 3 converging = ₹28,923 → passes
- 4 converging = ₹38,564 → **refused**, where the pre-#1086 ₹120,000 book cap allowed it

Against the measured distribution, the 3-vs-4 boundary is in the empty part of the histogram. The
real cases are:

| depth | charge | vs ₹30,000 |
|---|---|---|
| 5 | ₹48,205 | 1.6× |
| 6 | ₹57,846 | 1.9× |
| 7 | ₹67,487 | 2.2× |

So **15 of the 77 measured (bar, key) events would hit the ceiling**, each refusing the 4th and
subsequent strategy. The claim under audit — *"refuses nothing which previously opened"* — is
**false whenever fires reach shadow-book density**, and true today only because they do not.

⚠️ **This does NOT say the governor is wrong.** Bounding per-account exposure is its purpose, and
refusing the 5th and 6th strategy piling into one ATM leg is very plausibly the correct behaviour —
six slugs on one key is six times the same bet, not diversification. The finding is that the
behaviour is a **capacity change**, not the capacity-neutral change it was recorded as.

⚠️ **`lockAnchorsBeforeBook` remains UNPROVEN**, unchanged from the row. 07-29's two same-key opens
were 1.1 s apart and sequential. But note the shadow data says genuine simultaneity is the normal
shape of a converging bar, so the contention this lock guards is not hypothetical either.

## 5. Interaction with #1075 — decide together

Raising `budget_inr` raises the per-lot charge and therefore **lowers** the depth at which a refusal
occurs. At the ₹20,000 budget under consideration the per-lot charge rises and the ceiling is
breached at a *shallower* convergence — into the 1–2 region that fires actually reach today. **The
two rows must be decided together**, as the row already says; this measurement is the number that
decision needs.

---

## Claim labels

- §2, §3 tables: **computed** this session from live `artha` (`strategy.signals`,
  `strategy.shadow_positions`), 2026-07-20 → 2026-07-29.
- §4's ₹ figures: **computed** from the row's own ₹9,641/lot. That per-lot charge is **recalled**
  from the G14 row, not re-derived here — if it is wrong, every ₹ figure in §4 moves with it.
- "The family is ~6 wide because of shared signal series + StrikePicker": **sourced** as a mechanism
  (ADR-0003 three-way decoupling, one 3m NIFTY-future series per fleet), **computed** as a width.
- The shadow book models "what would open if gates passed": **sourced** — it opens on
  composite-passing rejections by construction, per the G11 row.
