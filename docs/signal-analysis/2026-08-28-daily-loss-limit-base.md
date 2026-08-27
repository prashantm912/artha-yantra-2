# `daily_loss_limit` — does the ratcheting base actually change anything?

`computed` 2026-08-28 against the live DB. Owner asked for a measurement before deciding
(2026-08-27), rather than a ruling on the mechanism alone.

## Verdict

**The base choice changed the outcome on exactly 1 of 15 trading days, and that day is 2026-08-27 —
the day the question was asked. On the other 14 it is inert.** The one firing it caused looks
correct: the suppressed leg's real-fill twin closed `STRUCTURAL_STOP` at **−₹588.10**, so the
suppression avoided a loss rather than costing a win.

**Recommendation: leave it as-is.** The evidence shows no harm and one small benefit. It is not
proof — n=1 firing — but there is nothing here arguing for a change, and loosening a live money gate
on no evidence is the move the standing prior warns against.

## What was measured

The limit resolves 3% against the book's **current equity**, not the ₹150,000 allocation. On
2026-08-27 that was 3% × ₹133,799 = **₹4,013.98**; against the allocation it would be **₹4,500**.

Daily realised P&L per session, `strategy.paper_positions` where `status='CLOSED'` and
`book='scalper'`, bucketed by `closed_at AT TIME ZONE 'Asia/Kolkata'`:

| | days |
|---|---|
| trading days with closes | **15** |
| would trip at the ratcheted ₹4,014 | **1** |
| would trip at the fixed ₹4,500 | **0** |

Worst six sessions:

| day | P&L | closes |
|---|---|---|
| **2026-08-27** | **−4,155.14** | 2 |
| 2026-08-13 | −3,073.00 | 2 |
| 2026-08-19 | −2,863.79 | 6 |
| 2026-08-05 | −2,583.73 | 1 |
| 2026-07-29 | −2,435.95 | 4 |
| 2026-08-24 | −1,950.26 | 3 |

## ⚠️ Why the result is robust despite an approximation

The query compares every day against **today's** ₹4,014, but the ratcheted limit is not constant —
it is 3% of equity *on that day*, and equity was HIGHER earlier, so past limits were higher. Using
today's figure therefore **overstates** how often the ratchet would have tripped historically.

That approximation cannot flip the answer: the **second-worst day is −3,073**, clear of both
thresholds by ₹941 and ₹1,427. Only one day is anywhere near either line, so no plausible
per-day limit changes the count. **Stated because an approximation that cannot change the verdict
still has to be shown not to.**

## What this does NOT establish

- **n=1 on firings.** One trip in the table's whole history. This measures how often the two bases
  DISAGREE, not whether the limit's level is right.
- **The profit-target twin ratchets too**, and nobody asked. `risk_audit` id 64 (08-20) fired at
  limit 2,098.57 and id 30 (08-12) at 2,201.69 — both below 1.5% × ₹150,000 = ₹2,250, so both
  used the same current-equity base. Neither outcome would have changed (3,582.29 and 3,675.58 both
  clear ₹2,250), so it is inert on the evidence too — but the asymmetry is worth knowing: **a
  ratcheting PROFIT target gets easier to hit as the book loses**, which is the opposite of the
  conservatism the loss side buys.
- Nothing about the ₹150,000 allocation itself, or whether 3% is the right number.
