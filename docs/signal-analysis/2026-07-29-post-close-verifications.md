# Post-close verifications — 2026-07-29 (T24 volume dot · #1086 capital governors)

**Both scheduled verifications ran, both produced a verdict, and NEITHER committed anything.** Their
findings existed only in their session transcripts until this document. Recovered and recorded here
2026-07-29 evening; the analysis is theirs, the cross-reading in §3 is new.

Companion to `2026-07-29-post-market-session-forensics` ([#1106](https://github.com/prashantm912/artha-yantra-2/pull/1106))
and the ledger G-rows opened in [#1107](https://github.com/prashantm912/artha-yantra-2/pull/1107) /
[#1108](https://github.com/prashantm912/artha-yantra-2/pull/1108), which DID commit.

---

## 1. T24 volume dot, first live session — **WORKING, no revert**

The dot now tests the floor the RAIL tests. Decisive measure is rail-vs-dot agreement on the same
rejection rows:

| IST day | rail passed | volume dot supported | disagreements |
|---|---|---|---|
| 07-27 | 141 | 0 | **141** |
| 07-28 (expiry) | 308 | 38 | **270** |
| **07-29** | **227** | **227** | **0** |

Today's rail threshold was observed **banded at 25,155**, not the static 125,000 — which is why the
pre-T24 dot could never agree with it.

**Composite lift matches arithmetic.** Avg composite +0.019 vs 07-27; expectation from the dot alone
is 0.23 × (1.0/~20) ≈ **+0.012**, same order — the remainder is tape. 11 rejection rows crossed
threshold *only* because of the volume dot; all 11 were still blocked by another rail. **None became
a fire.**

**The overfire flag is numeric, not causal.** 12 scalper ENTRY rows vs 3 on 07-27 trips the ~3×
revert heuristic. But recomputing each fire's composite with the volume dot removed
(`(score·Σw − 1.0)/Σw`) leaves **0 of 12 dependent on it** — lowest counterfactual 0.7245 against a
0.600 threshold. The dot is the only place T24 enters, so the extra fires are regime, not T24. The
12 rows are 4 distinct bar events × 3 slugs firing together, where 07-27 was 2 bars firing singly.

**Unexercised, NOT proven safe:** the 8 dual-tag strategies (`scalp-trend-change-*`,
`scalp-trending-oi-*`) were alive and evaluating but opened nothing, so the `CONFLUENCE_FLIP` exit
rail had nothing to close. There have been **zero CONFLUENCE_FLIP exits platform-wide since 07-01**.
Today's 8 exits were 4 TIME_STOP + 4 STRUCTURAL_STOP.

## 2. #1086 capital governors, first live session — **refused nothing; two supporting claims were wrong**

**Outcome held: zero `DEPLOYMENT_BLOCKED`, zero deadlocks, zero `RISK_ENTRY_BLOCKED`, 0 ERROR/0 WARN
in 14 h.** Headroom never approached a cap — scalper book peaked at ₹20,722 of ₹120,000, sub-account
peak ₹19,282 of ₹30,000.

**The add-path fix was exercised live on day one.** All four opens are *two* 20-qty opens averaged:
`scalp-golden-crossover-sensex-niftyoi` and `scalp-connect-the-dots-sensex-niftyoi` emitted the same
ATM CE strike ~1.1 s apart, four times. The second open resolved `effectiveSubAccount` from the
existing row and charged the **same** account — 9,641 + 9,641 = ₹19,282 ≤ ₹30,000. That is precisely
the defect #1086 fixed.

**Routing 1→2→3→4 is correct, not a bug:** `ScalperAccountModel` picks the non-frozen account with
fewest trades today, and 41/42/43 each closed at a loss, freezing accounts 1/2/3 in turn.

### Where the capacity-neutral claim was wrong

- **(a) Arithmetic WRONG, conclusion survives.** The claim said "at ₹15,000 each ₹30,000 sub-account
  holds exactly 2". Live, an open charges **₹9,641** (the sizer's rounded-down lot, not the budget),
  so an account holds **three**. The error was in the safe direction.
- **(c) EXPIRED WITHIN A DAY.** "No live paper position carries a `subaccount_idx`" was true when
  written and false 11 hours later — all four of today's opens carried one, so the sub-account
  ceiling was **not inert**; it evaluated on all 8 open calls.
- **(b) held.** Both swing books still gate at emission.

### Residual risk the claim does not cover

Routing balances by **trade count, not capital**, and the add path deliberately pins a correlated add
to the *same* account. At ₹9,641/lot against ₹30,000: 3 converging strategies = ₹28,923 **passes**,
**4 converging = ₹38,564 → REFUSED**, where pre-#1086 the ₹120,000 book cap would have allowed it.
19 SENSEX + 19 NIFTY scalpers all pick ATM off the same spot, so 4-way convergence is plausible.
**Capacity-neutrality is conditional on ≤3 strategies converging on one key; today never tested it.**

Also unproven: the `lockAnchorsBeforeBook` inversion fix. The two same-key opens were 1.1 s apart —
sequential, never contended. No deadlock ≠ deadlock impossible.

**Side observation, not a #1086 issue:** 3 of 5 sub-accounts froze on losses by 13:40 IST on day one.
Account 4 closed +197.50, below its ₹300 profit-lock, so it stayed open; account 5 unused. A sixth
entry today would have found 4/5 frozen.

## 3. What neither session could see alone — and it bears on the HELD #1075 decision

T24's session flagged, as an out-of-scope curiosity, that *"12 entry signals produced only 4 paper
positions, all SENSEX; the 4 NIFTY entries opened none"*, and guessed the #1086 crossing-order cap.
The governors session independently held the other half. Together they close it, and the cause is
neither T24 nor #1086:

```
ZERO_SIZE ×4  strategy=scalp-golden-crossover-nifty
              premium=285.25; lot=65; budget_inr=15000; computed_lots=0
```

**One NIFTY lot cost ₹18,541 against a ₹15,000 budget.** Every NIFTY entry was refused by the #1084
sizer. The 4 SENSEX opens survived because a SENSEX lot is 20, not 65 — ₹9,641.

### Is that systematic? **No — today was an outlier, and the honest reading matters for 08-12.**

At `budget_inr = 15000` and NIFTY lot 65, the refusal threshold is an ATM premium of **₹230.77**.
Measured across every NIFTY scalper ENTRY since 07-15:

| IST day | NIFTY entries | ATM premium range | would refuse @ ₹15k | @ ₹20k |
|---|---|---|---|---|
| 07-15 | 2 | 146.35 – 161.75 | 0 | 0 |
| 07-17 | 2 | 134.55 – 136.60 | 0 | 0 |
| 07-20 | 1 | 93.90 | 0 | 0 |
| 07-27 | 1 | 152.65 | 0 | 0 |
| **07-29** | **4** | **285.25 – 305.75** | **4** | **0** |

So the ₹15,000 budget did not silently make NIFTY untradeable in general — it did on **one session
out of five**, when ATM premium ran ~2× its prior range. At ₹20,000 the threshold is ₹307.69 and
today's maximum (305.75) would have **just** cleared.

**This is a genuine input to the 2026-08-12 `budget_inr` decision and it is n=1.** It is the first
real evidence that ₹15,000 has a live refusal mode at all, but four of five sessions were unaffected.
Do not treat one high-premium day as the general case; the revisit task should re-run this table over
the full two weeks.

---

## Process note

Both verifications reached a defensible verdict and neither wrote it down. A scheduled task whose
output lives only in its session transcript is indistinguishable, a week later, from a task that
never ran. The two verify tasks should either open a PR like the post-market forensics routine does,
or say explicitly in their prompt that findings return to the owner only.
