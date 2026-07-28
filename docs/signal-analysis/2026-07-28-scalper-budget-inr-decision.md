# Scalper `budget_inr` — measured decision pack (task_79b20900)

**Headline (computed 2026-07-28 from `marketdata.options_chain_snapshots`): the premise this task
was filed on is WRONG.** SENSEX is *not* systematically excluded by the ₹15,000 budget — it fits
**30 of 32** measured legs (94%), while **NIFTY fits only 24 of 32** (75%). The family the current
budget silently drops most often is **NIFTY**, not SENSEX.

**Recommendation: raise `budget_inr` to ₹20,000** — that admits **32/32 of both families**, with
the worst observed leg at ₹16,549 leaving ~21% headroom. Separately, and arguably more important,
**cap the lot count** (see §4) — the current sizing has no maximum and can buy 36 lots of a cheap
expiry-day option.

## 1. Measurement basis (this is where the earlier numbers went wrong)

The naive basis — ATM premium — understates cost, because the armed `delta-s24-floor` tag pins
strike selection to a **0.7–0.8 delta band, target 0.75** (the slightly-ITM strike). The two live
signals of 2026-07-27 confirm it: deltas 0.726 and 0.776.

But measuring the whole chain at 0.75 delta *over*-states it, because the universe also pins
`strikes: {selector: atm_window, width: 3}` — the picker can only reach **ATM ± 3 strikes**. On the
unconstrained basis SENSEX shows a p90 of ₹52,841 and a worst of ₹135,925; those legs sit 6,000+
points from spot and **the picker cannot select them**. Any recommendation built on that basis
would be wrong.

Correct basis, used below: **front expiry · inside ATM ± 3 strikes · closest to 0.75 delta · clean
greeks only (`iv_reason='OK'`) · 32 legs per family across ~16 July 2026 sessions.**

## 2. The numbers

| | NIFTY 50 (lot 65) | SENSEX (lot 20) |
|---|---|---|
| cheapest lot | ₹549 | ₹4,395 |
| median lot | ₹12,857 | ₹10,282 |
| p90 lot | ₹15,856 | ₹14,108 |
| worst lot | ₹16,549 | ₹16,366 |
| **fits ₹15,000** | **24/32 (75%)** | **30/32 (94%)** |
| fits ₹20,000 | 32/32 (100%) | 32/32 (100%) |
| fits ₹25,000 | 32/32 (100%) | 32/32 (100%) |

The 2026-07-27 SENSEX leg that prompted this task (₹776 × 20 = ₹15,520) was an unlucky draw near
that family's own worst case — not a structural exclusion.

**Book context:** scalper equity ₹150,000, split into 5 sub-accounts of ₹30,000 (E10 model).
Governors all enabled: `max_deployment_pct` 80% (₹120,000), `max_open_paper_positions` 20,
`heat_cap_pct` 60%.

## 3. Options

**(a) Raise `budget_inr` to ₹20,000 — RECOMMENDED.** Admits every measured leg of both families.
Five concurrent positions at ₹20,000 = ₹100,000 = 67% of book equity, inside the 80% deployment
cap. Cost: each trade deploys more, so a losing streak draws down faster.

**(b) Per-family budgets — DECLINE.** The data removes the reason for it: both families need the
same number, and SENSEX is *cheaper* on the median. It would add config surface and two more
strategies to keep in sync, for no measured gain.

**(c) Floor-at-one-lot semantics — DECLINE.** It does not fix the observed failures (those are
"one lot costs more than the whole budget" cases, where a floor forces a deliberately
over-budget trade), and it changes deployment for **every** strategy using `premium_budget`,
including the swing books. Wrong blast radius for the problem.

**(d) Leave SENSEX dormant — DECLINE, wrong premise.** There is nothing to leave dormant; SENSEX
already fits 94% of the time. Adopting (d) would have entrenched a belief the data contradicts.

## 4. The bigger issue this surfaced: unbounded lot count

`premium_budget` computes `FLOOR(budget / (premium × lot))` with **no maximum**. Measured
consequence at a ₹20,000 budget:

| | max lots | average lots |
|---|---|---|
| NIFTY 50 | **36** (2,340 units) | 2.7 |
| SENSEX | 4 | 1.6 |

On an expiry-day session where the front-expiry premium collapses to ₹549/lot, the same budget buys
**36 lots instead of 1**. Rupee deployment stays capped, so the governors do not trip — but the
trade profile changes completely, from a directional scalp into a near-worthless-option lottery
ticket that either expires at zero or multiplies. That is a different strategy than the one being
evaluated, taken automatically, on expiry day.

This wants a `max_lots` (or minimum-premium) cap alongside the budget change. It is a `PositionSizer`
code change affecting every `premium_budget` consumer, so it is a **separate owner decision** — but
I would rate it higher priority than the budget number itself.

## 5. Caveats on this data

- **One month, one volatility regime.** 32 legs per family across ~16 sessions of July 2026.
  Premiums scale with IV; a high-vol regime shifts every number up. Worth re-measuring after a
  quarter of forward data.
- Deltas come from the platform's own Black-76 recompute, not the broker. Rows with unclean IV
  (`BELOW_INTRINSIC`, `ZERO_QUOTE`) were excluded; including them did not change the percentiles.
- Snapshot capture began 2026-06-15, so no pre-June history exists to widen the window.

## 6. If you take the recommendation

A YAML change here is a **silent no-op until republished**. The sequence is: change `budget_inr` in
every affected scalper YAML → normal PR → merge → deploy →
`POST /api/v1/strategies/{id}/publish` for **each** affected strategy → verify the published config
carries the new number before trusting it. Diff what each republish GAINS vs LOSES first; a stale
leftover draft can carry unrelated changes in either direction.
