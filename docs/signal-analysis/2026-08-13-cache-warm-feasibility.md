# Pre-entry cache warm — feasibility verdict (2026-08-13)

**Scope.** Whether `ManasGoverningStopCache` and `EquityMarkCache` can be warmed before the swing
ENTRY pass, subject to the four acceptance criteria that killed the withdrawn attempt (lever (D),
reverted in `15c507dc` on branch `fix/swing-entry-admission-equity-mark`).

**Verdict: CANNOT BE DONE as specified. No code was written.**

Two independent impossibility results, one per cache. Neither is an implementation gap — a fifth
attempt cannot close either. A third finding (§4) says the warm's *effect* is an order of magnitude
larger than the record claims, and a fourth (§5) says the direction of that effect is fail-open.
§6 gives the one constraint whose relaxation makes the whole thing trivially satisfiable.

---

## 1. The system as it actually is

`SwingBatchEngine.runDaily` runs both passes **in one invocation, sharing one `seriesCache`**
(`SwingBatchEngine.java:304, 316-324`) [sourced]:

```
seriesCache = new HashMap<>()
entryPass(..., seriesCache, ...)      // :316-319   — only when entriesEnabled
exitPass (..., seriesCache, ...)      // :321-324   — populates BOTH caches
```

`cacheGoverningStop` is called **only** from the exit pass's `else` branch — the no-exit branch
(`:876-879` → `:906-930`) [sourced]. Entries therefore read a cache the *previous* run populated.

Deployed schedule, from `strategy.swing_batch_runs` [computed, live DB]:

| Session | Ran (IST) | entries | exits |
|---|---|---|---|
| 2026-08-13 | **16:02:00** | 0 | **2** |
| 2026-08-12 | 2026-08-13 **08:35:35** | 0 | 0 |
| 2026-08-11 | 2026-08-12 **08:35:34** | 0 | 0 |
| 2026-08-10 | 2026-08-10 **20:05:30** | 0 | 0 |
| 2026-08-07 | 2026-08-07 20:05:31 | 1 | 0 |

Three regimes coexist, and this matters for §2:

- **08:35 catch-up** — `requiredBarDate` **PINNED** to the prior session. Entries then exits.
- **20:05 on-time** — `requiredBarDate` **null**. Entries then exits. **Not dead code: it ran 8 of
  the last 10 recorded sessions**, most recently 2026-08-10.
- **16:02 exit-only** — `entriesEnabled=false`, so no entry pass and no warm is needed.

⚠️ The brief and the ledger both describe the 08:35 catch-up as the standing regime. It is the
*current* one, but the unpinned 20:05 entry path is recent and live. A design that is safe only when
pinned is safe only in the degraded regime.

---

## 2. Criterion 3 is unsatisfiable by construction (governing-stop cache)

> *"The exit pass's sampling time and ordering must not move."*

The warm's precondition is that the governing stop exists **before** `entryPass`. The governing stop
is a pure function of the daily series (`ManasDoctrine.governingStop` → `trailLevel` →
`ExitEvaluator.trailStop`, `ManasDoctrine.java:246-258`) [sourced]. So **the series must be fetched
before `entryPass`**. The exit pass currently fetches **after** `entryPass`. There are exactly two
ways to reconcile that, and both move the exit's sample:

**(A) The warm fetches its own series.** This is what was built. The exit pass fetches again, later.
Costs: ≤180 s added latency ahead of the exit pass; `RunDeadline` consumption, and if the deadline
trips during the entry pass the exit pass is skipped **entirely** at `:321-324` returning
`exitSkipped = 0` — a silent total skip of the book's only stop evaluator; and, worst, **two samples
that can disagree**, so the risk rail can be governed by a series the exit rail never saw. Killed.

**(B) The warm populates the shared `seriesCache`.** Zero extra fetches, zero net delay, one sample
— the exit pass reuses the identical `List` via `computeIfAbsent` (`series()`, verified). But the
exit pass now consumes a series sampled at *warm* time rather than *exit* time. The sample moved.

There is no option (C): the warm needs the observation before entries, the exit needs it after, and
one fetch cannot occur at two instants. **Criterion 3 is the negation of the warm's precondition.**

The withdrawn attempt found this the hard way and its commit messages record the trap precisely
[sourced, `7a3d202a` / `6d585290`]: the warm used its own fetch *deliberately*, "since re-sampling a
still-forming daily bar at a different instant could change an exit" — and that separate fetch is
exactly what introduced the ≤180 s delay that moved the exit's sampling instant anyway. **Avoiding
the shared cache to protect the exit is what broke the exit.** The builder's own generalisation is
correct and is the whole answer: *"warm before entries" and "the exit samples at the instant it
always did" are in direct conflict within a single run.*

### The one honest qualifier

Option (B)'s violation is **immaterial in the pinned path only**. `truncateToSession` returns early
when `requiredBarDate == null` and otherwise **drops every bar after the pinned session**
(verified in source). So in the 08:35 catch-up the tail is a finalised past daily bar and the
builder's "still-forming bar" fear does not apply; the head (`now.minusDays(warmupDays)`) shifts by
minutes and cannot cross an IST midnight bucket boundary at 08:35.

So a warm gated on `requiredBarDate != null` **would** be sample-safe. It buys that safety by being
inert in the 20:05 regime that ran 8 of the last 10 sessions — a money rail that silently changes
behaviour with the machine's power schedule, with no signal when it stops working. That is the
"armed gate whose operand is structurally zero" shape (trap catalogue #13) with the trigger moved
outside the codebase. I do not recommend shipping it, and it is an owner call, not a builder call.

---

## 3. Criterion 4 contradicts equity-mark correctness (separate impossibility)

This half fails for an unrelated reason and no sampling fix touches it.

`PaperAccountService.unrealizedTotal` falls back to `avgEntryPrice` for an unmarked position
(verified in source), which **erases that position's unrealized in whichever direction it sits**:

| Unmarked position | True unrealized | Counted | Equity | `pct`-mode rails |
|---|---|---|---|---|
| Winner | > 0 | 0 | understated | bind tighter (safe) |
| **Loser** | **< 0** | **0** | **overstated** | **bind looser — fail-open** |

Now compose that with criterion 4: *the warm must be fail-soft as a whole* — a transient failure must
never prevent the exit pass. **Fail-soft ⇒ the warm proceeds with a partial mark set.** A partial mark
set is neither the cold number nor the true number, and its error is **not sign-bounded**. So:

> Criterion 4 *guarantees* the condition (partiality) that makes the equity mark fail-open.

The only correct semantics is all-or-nothing — mark every open position or none — which is precisely
**not** fail-soft. A fail-closed variant was already built and removed by owner ruling, because "the
cache is cold on every boot, so a gate keyed on it refuses entries hardest on precisely the degraded
days", and an interim version blocked **manual owner orders** [sourced, #1368 body].

This is unresolved on #1368 by its own admission — C2 records "the reviewer's objection stands and is
not resolved by this PR." Today's book masks it: 16 of 18 positions profitable, so the aggregate error
is currently conservative. That is **a property of today's book, not of the mechanism** (#1368's words,
and I confirm the direction from source).

Contrast with the stop cache, where every failure mode degrades **conservatively** — a cache miss falls
back to the persisted `stop_loss`, which is always **wider**, so unwarmed positions consume *more*
budget. Partial warming of the stop cache is monotonically safe; partial warming of the equity mark is
not. **The two caches do not belong in one item and must not be warmed by one mechanism.**

---

## 4. What a warm would actually do — an order of magnitude larger than recorded

The published manas `trailing_stop` carries **`breakeven_floor: true`** [computed, live
`strategy.strategy_versions`], and `ExitEvaluator.rollingAtrTrailLevel` applies it to every ATR
candidate: `c = c.max(entry)` (verified in source). **So an armed trail is never below the entry
price**, and `openRiskInr`'s `perUnit = avgEntryPrice − effectiveStop` collapses to the fill
slippage alone.

All four open manas positions are armed today — peak gain vs `arm_pct: 9` [computed, live DB]:

| id | symbol | entry | peak high | peak gain | armed | cold risk (₹) | warm risk ≤ (₹) |
|---|---|---|---|---|---|---|---|
| 33 | SANSERA | 3336.87 | 3938.00 | **18.01%** | yes | 1450.82 | 10.02 |
| 36 | KANORICHEM | 152.29 | 167.90 | **10.25%** | yes | 1469.23 | 8.00 |
| 46 | AVALON | 1760.38 | 2004.00 | **13.84%** | yes | 1366.82 | 7.04 |
| 53 | SCPL | 615.31 | 700.00 | **13.76%** | yes | 1421.63 | 7.13 |
| | | | | | **total** | **5708.50** | **≤ 32.19** |

**A full warm removes ~99.4% of the existing book's counted open risk**, not the ~24% the ledger
implies. Against a 6% cap of roughly ₹8,577 the existing book would occupy **0.4%**, leaving the cap
binding on *new* entries only — it would admit on the order of six further full-size positions.

Worked check on the tightest case, KANORICHEM: ATR(20) ≈ 9.7095 [computed], so the ATR candidate is
167.90 − 2(9.7095) = 148.48, **below** the 152.21 entry; `breakeven_floor` lifts it to 152.21 and the
contribution is (152.29 − 152.21) × 100 = **₹8.00**. The floor, not the ATR, is what zeroes these.

This is a materially different decision from the one in the record, and the owner should see this
number before ruling.

---

## 5. The warm swaps an enforceable stop for an unenforceable one

`stop_loss` is polled every 15 seconds by the paper bracket evaluator, **with no book filter**. The
governing-stop cache is explicitly **exit-neutral — no exit decision consults it**
(`ManasGoverningStopCache` javadoc `:11-14`; doc-of-record `2026-08-13-swing-exit-stickiness.md:210-213`)
[sourced]. The trail is executed only by the once-daily swing exit pass.

So warming tells the cap "this position can lose nothing", while the only mechanism that can enforce
that claim next runs at 16:00. Between 09:15 and 16:00 the position's genuinely enforced floor remains
the wide `stop_loss`. A gap through the trail is not caught until the daily settle, at whatever price
then obtains.

⚠️ **In fairness this is a property of the M40 cache design, not of the warm.** Whenever the cache is
warm today the cap already reads the unenforceable trail. What the warm changes is *when*: it moves
that approximation into the one moment it decides money — admission. That is a scope increase on an
accepted fail-open approximation, and it is an owner decision.

---

## 6. Alternatives, with costs

**(a) Persist what the exit pass already computed — the cheapest correct fix, and it is currently
ruled out.** The 16:00 run on session D and the 08:35 run on D+1 **pin to the same session and compute
the same governing stop from the same bar**. The warm is therefore not new information: it recomputes,
16.5 hours later, a value the exit pass already produced and lost to process shutdown. A side table of
`(position_id, session_date, governing_stop)` written at the existing `cacheGoverningStop` call site
satisfies all four criteria *trivially*: it is already inside the no-exit `else` (criterion 1); the
exit pass has already refused mixed pre/post-session lots before reaching it (criterion 2); it adds
**no fetch and no ordering change** (criterion 3); and it is inside the existing fail-soft try/catch
(criterion 4). Reads at entry take the accepted five-day bounded-freshness rule; a missing row falls
back to `stop_loss` — conservative.

I am **not relitigating** the settled rejection. I am reporting that the constraint set as given is
unsatisfiable, and that **this is the single constraint whose relaxation dissolves it.** Note the
ruling that was made was against `stop_loss` (which drives the 15-second disaster stop) and against a
*column on `paper_positions`* (round 2). A separate side table touches neither surface and preserves
M40's exit-neutrality guarantee intact. Whether that distinction is meaningful is the owner's call,
not mine.

**(b) Degraded-mode signal — recommended if (a) stays closed.** Do not make the rail see; make it say
that it is blind. An `effectiveStop` cached-vs-fallback counter is **already the named cheapest
follow-up from #1228 and is recorded as NOT BUILT** (ledger `:727`) [sourced]. Extend it so a refusal
at 99.91% of cap carries "computed on 4/4 fallback stops" in its own `risk_audit` row and on the
admission probe; `AccountDto.unmarkedPositions` already exists on the #1368 branch for the equity half.
Cost: small, no money-path change, no exit perturbation, no sampling question. It does not admit the
refused entry — it explains the refusal, which is what today's rail cannot do.

**(c) Compute from data already in hand — does not exist.** The entry pass fetches series for
*candidates*, never for held symbols, so the data is genuinely absent. Lazy computation at admission
is barred: the risk check runs inside `PaperService.openOrder`'s `@Transactional` fill, where blocking
HTTP is forbidden. And `openRiskInr` keys off `avgEntryPrice`, not current price, so there is no
price-only shortcut. Dead end — recorded because the brief offered it.

---

## 7. Corrections to the record

1. **The ledger is stale and still recommends the withdrawn design.**
   `docs/superpowers/plans/2026-07-02-remaining-items.md:2035-2039` still reads "A fourth lever WOULD
   work immediately … warming `ManasGoverningStopCache`". It was written before the 13:49 revert. The
   withdrawal and its four failure modes exist **only** in PR #1368's body, commit `15c507dc`, and the
   `EquityMarkCache` javadoc — in no `docs/` file [sourced].
2. **Two different percentages for one rupee figure.** The doc-of-record says ₹4,355.97 = **3.05%**;
   the ledger says ₹4,356 = **2.87%**. The brief inherited 2.87%. One of them is wrong.
3. **`EquityMarkCache`'s javadoc is half-corrected** — it still asserts the withdrawn one-directional
   claim ("equity is under-marked (never over-marked)") eleven lines above the corrected form. The PR
   body was fixed; the javadoc was not.
4. **The cache's own javadoc says cold is "the NORMAL operating regime"** on the strength of a
   2026-08-02 measurement where zero of six positions had armed. Today **4 of 4 are armed**. That
   sentence reads as a standing property and is a dated snapshot.
5. **Book state moved during the analysis.** The 16:02 run today closed 2 positions; the ledger's
   08:35 figures describe a 6-position book, mine a 4-position book. Both correct for their moment —
   re-measure at write time.

---

## 8. Open doubts

1. **[assumed] The warm-risk column in §4 is an upper bound, not the exact figure.** I proved
   `trail ≥ entry` from `breakeven_floor`, which bounds each contribution by the fill slippage
   (avg − signal entry). I did not replay `rollingAtrTrailLevel` bar-by-bar per position, so the true
   values are between ₹0 and ₹32.19. The conclusion (~99% of counted risk released) is insensitive to
   where in that range they fall; the exact figure is not established.
2. **[computed, single sample] Peak gains use `max(high)` since `opened_at − 1 day`** as a proxy for
   the engine's `entryIndex`-anchored running extreme. Off-by-one-bar at the entry boundary would not
   move any of the four below the 9% arm threshold (nearest is 10.25%), but the peaks themselves could
   differ slightly.
3. **[assumed] Option (B)'s sample-safety in the pinned path rests on candle data being stable across
   the entry pass's duration.** `marketdata.candles` is retro-mutable by doctrine. The window is
   minutes and pre-market, so the probability is low — but "low" is not "proven", and this is exactly
   the property the withdrawn attempt refused to assume. I did not attempt to measure re-fetch
   stability empirically.
4. **[assumed] I did not verify whether the 20:05 on-time regime will return.** If the machine's power
   schedule is permanent, the unpinned entry path is effectively dead and §2's qualifier is stronger
   than I have credited. That is owner knowledge, not code.
5. **[open] Whether the exit-neutrality objection in §5 should block the M40 cache design itself**, not
   merely the warm. I raise it and do not resolve it; it was accepted at M40 and re-opening it is out
   of scope for this item.
6. **[not investigated] The five other consumers of the equity number** (`PositionSizer`,
   `max_deployment_pct`, `mode: pct` limits, `currentHeatPct`, `dayPnl`) are listed in #1368's body. I
   reasoned about the risk-cap path only; the blast radius of a partial mark on the other four is
   asserted by #1368, not verified by me.
