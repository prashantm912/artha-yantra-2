# Session learnings — 2026-07-28/29 scalper sizing + capital governors

**Verdict first: six cross-vendor review rounds found six defects in one change; three of them were
created by the fixes for the previous round's findings, and none of the six was reachable by any test
in the suite.** The suite was green before, during, and after every one of them. That is the single
most useful fact this session produced, and it is the reason the review gate earns its cost.

Scope: PRs [#1067](https://github.com/prashantm912/artha-yantra-2/pull/1067),
[#1071](https://github.com/prashantm912/artha-yantra-2/pull/1071),
[#1084](https://github.com/prashantm912/artha-yantra-2/pull/1084),
[#1086](https://github.com/prashantm912/artha-yantra-2/pull/1086) (all shipped + deployed) and
[#1075](https://github.com/prashantm912/artha-yantra-2/pull/1075) (HELD to 2026-08-12).

---

## 1. The defect that repeated all night: one rule living in two places

Every single defect found in this work — by review, and by me — has the same shape: **a value or a
rule that exists in two places which can disagree.**

| Defect | The two places |
|---|---|
| `max_lots` inert (#1084) | `PositionSizer.size()` applied it; `heroZeroSuggestedQty` overrode it |
| `min_premium_inr` inert (#1084) | the same override, one param later |
| replay/live/schema disagreement | replay honoured the param, live ignored it, the schema forbade it |
| candidate valuation (self-caught) | `capitalUsed` used `usageFor` (margin); the projection used raw `price × qty` |
| sub-account attribution | `upsertPosition` charged the EXISTING row's account; the check read the REQUESTED one |
| lock order (#1086 r5) | `openOrder` took anchor→book; the new wrapper took book→anchor |

The fix was the same every time: **one definition, read from both sides.** `belowPremiumFloor` is a
shared predicate rather than a second check precisely because a second copy is how the gap opened
twice in the same file.

**Rule for next time:** when adding a bound, a rule, or a lock, the first question is not "is my code
correct" but **"where else does this concept already exist, and can the two disagree?"**

## 2. Adding enforcement converts previously-harmless gaps into live defects

This is the corollary of #1067's *"a dead code path hides every defect downstream of it"*, and it
fired three times in one night:

- Sub-account **routing** ignored open positions. Harmless for as long as nothing capped an account —
  the moment a ceiling existed, the second entry was *refused* at account 1 instead of routed to an
  idle account, and five sub-accounts behaved as one.
- Straddle legs opened in **independent transactions**. Harmless while nothing could refuse the
  second one — the moment a cap could, a refused leg 2 left leg 1 open: a delta-neutral straddle
  silently becoming a **naked directional position**, which is strictly worse than the cap breach the
  refusal prevented.
- The capital reads were **unlocked**. Harmless while nothing compared them against a hard limit.

**Rule:** when you make a rule binding, re-audit everything upstream of it. The new enforcement does
not just add a check — it changes what every existing gap *means*.

## 3. Green suites, and the three false greens

**Not one of the four Criticals/Majors from rounds 3–5 was reachable by the test suite.** The
reviewer said it plainly about the parity ladder: *"The supplied green goldens do not exercise the
Hero-Zero/floor interaction."* A green gate is evidence that known behaviours did not regress; it is
not evidence that the change is correct.

Three separate false greens appeared, each a signal that *looked* like success while measuring
something else:

1. **`-Dtest=A+B` ran ZERO tests and exited BUILD SUCCESS.** The separator is a comma; a filter
   matching nothing plus `-DfailIfNoSpecifiedTests=false` is a green build that ran nothing. Caught
   by counting: the file declared 9 `@Test` methods, the report claimed 6.
2. **A 2.5-hour-old surefire report stood in for a run that never happened.** `surefire-reports/` is
   never pruned — it is a graveyard, not a result.
3. **A "BUILD-DONE" monitor fired on file *existence*, not build completion,** leaving jars three
   hours stale. Deploying then would have **silently reverted the merge while reporting success.**

**Rule:** if a run reports nothing, it reported nothing — not success. Trust the RUN output, never a
report directory, and verify a deploy by fingerprinting the running artifact.

## 4. What splitting a PR twice bought

#1075 began as one branch carrying a money decision (`budget_inr` ₹15k→₹20k) *and* five protective
fixes. It shipped as **two** PRs and one still-open decision:

- **#1084** — cap + floor. Quantity strictly non-increasing, so it could ship while the raise waited.
- **#1086** — the governors. **Capacity-neutral by arithmetic**: at ₹15,000 each ₹30,000 sub-account
  holds exactly 2 and the book cap binds first at 8, so the new ceiling never fires. It also came out
  **byte-identical in YAML to main**, which removed the republish step entirely.
- **#1075** — one number, 60 lines. The decision, and nothing else.

**Rule:** when a branch mixes a decision with corrections, the corrections are hostages. Split on
"does this change what the system is allowed to do?" — and prove the split with arithmetic, not
assurance.

## 5. Process notes worth keeping

- **The review round and the audit are two gates.** Rounds 3, 4 and 5 each found something the
  previous round's fix introduced. Collapsing them would have shipped the deadlock.
- **Ask the reviewer to attack the load-bearing claim.** I asked whether
  `takenPathOpenOrderIsUngatedByDesign` really permitted validating at the writer; it did (*"it does
  not prohibit a pure writer invariant"*), and knowing that was worth more than a generic pass.
- **Surface your own gaps.** I flagged the untested straddle-through-listener seam myself; the
  verdict came back block-worthy, and the branch that parses `scalper_detail.legs[]` had genuinely
  never executed in a test.
- **Downgrade your own evidence when it does not prove what you claimed.** One test was
  characterization, not regression (it passed with or without the fix); another was sequential, so it
  proved capital-aware routing, not concurrent serialization. Both are now labelled as such.
- **Red-proof every new behaviour** by neutering the code under test and confirming *exactly* the
  predicted test fails. It caught a test that would have passed for the wrong reason.

## 6. Durable traps recorded to memory

- `test-filter-false-green` — the `-Dtest` separator + stale surefire reports.
- `advisory-lock-order-paper-path` — anchor (4801) **then** book (4802); any new entry wrapper must
  call `lockAnchorsBeforeBook` first, or two concurrent money-path opens deadlock. Invisible to
  tests: the suite was 38/38 green with the inversion in place.
- `deploy-verify-by-jar-fingerprint` — written earlier the same day, and it paid off twice.

## 6b. §9-06 — a refactor correctly declined

The ledger carried "collapse the two 1m→N rollup readers" as an architecture-deepening candidate.
Investigated 2026-07-29 and **declined**, because the two anchors are not drift — each is
load-bearing, and they are mutually exclusive:

- `ConnectingDotsService.bucketStart` floors from **IST midnight**, for **pg `time_bucket` parity**
  (its own comment says so). A read-time rollup that disagrees with DB-side bucketing disagrees with
  the caggs.
- `FuturesOiChartService.bucketStart` floors from **09:15 SESSION_OPEN**, so the OI series left-joins
  onto the candle grid it shares — its javadoc states both sides use the SAME function for exactly
  that reason.

Unifying satisfies one requirement and breaks the other. And the divergence is measurable, not
theoretical: 09:15 is **555 minutes** past midnight, so the grids are **identical for 1/3/5/15m**
(555 mod N = 0) and **diverge for 10/30/60m** (remainders 5, 15, 15). Both services expose all seven
`OiInterval` values, so three of them differ today.

**The lesson is about the shape of the finding, not the finding.** "Two functions that do the same
thing on different grids" reads like duplication — the tabulated defect in §1 of this document. It
is the opposite: two deliberate anchors serving two different correctness requirements. The
distinguishing question is *does one definition satisfy every consumer?* Here it provably cannot, and
the right deliverable was documentation that stops the next reader attempting it.

Earlier in the same session I had declared §9-06 moot from a single grep of the SQL fold shape, and a
reviewer produced file:line evidence that I was wrong. This time the verdict rests on both
implementations plus arithmetic.

## 8. The D3 Map-return burn-down — four review findings, all in the CLAIMS

Later the same session, ledger D3 slice 1 moved **68 -> 47** Map-returning handlers across three PRs
([#1097](https://github.com/prashantm912/artha-yantra-2/pull/1097) signals family 25->18,
[#1098](https://github.com/prashantm912/artha-yantra-2/pull/1098) paper + journal 18->14, both merged
and preceded by [#1094](https://github.com/prashantm912/artha-yantra-2/pull/1094)).

**Every defect cross-vendor review found was in what I SAID about the change, not in the change.**
The conversions themselves were correct all four times. This is a different failure mode from §1's
"one rule in two places", and it is worth naming separately because no test can reach it:

| Round | Finding | Class |
|---|---|---|
| #1097 r1 | `scalperDetail` missing its nullable union | acted on a STALE recalled fact |
| #1098 r1 | "the wire is byte-identical" | overclaim — disproved by running it |
| #1098 r1 | presence-assertions called a wire proof | overclaim — the instrument cannot see order |
| #1098 r2 | "the journal list had no wire coverage" | overclaim — it had some |

The suite was green before, during and after all four. A reviewer reading the *prose* against the
*code* is the only gate that catches this class.

### 8.1 `Map.of` iteration order VARIES BETWEEN JVM RUNS

The load-bearing technical find. I wrote that multi-key `Map.of` order is "unspecified, so no client
could have depended on it -- a strict improvement, **not a wire change**." That is having it both
ways, and the reviewer settled it empirically: on the project JDK 21.0.11 the equity point serialized
as both `{date,equity}` and `{equity,date}` **across separate JVMs**, because
`java.util.ImmutableCollections` randomizes its probe order per JVM (a per-process SALT).

So converting a multi-key `Map.of` body to a record **NORMALIZES a previously nondeterministic key
order** -- it does not preserve one. Safe (JSON members are unordered by spec; every client here binds
by key), but the honest claim is normalization.

**Rule for the remaining ~47 handlers:** classify each source before writing the claim.
`LinkedHashMap` -> order was fixed, is load-bearing, must be preserved component-for-component.
Multi-key `Map.of` -> order was random per JVM, is now normalized; say so. Single-key `Map.of` ->
trivially byte-identical.

(Checked, not assumed: this does **not** falsify #1097, whose only two `Map.of` uses were
single-key. The reviewer independently confirmed.)

### 8.2 Red-proof the guard you actually claim

Proving the new `/pnl` wire assertion could fail, I first renamed `PnlSummary.realizedTotal` in Java.
The build broke -- because `TelegramCommandBot` calls the accessor. That *felt* like a pass and was
the wrong proof: it never exercised the JSON assertion at all. A **wire-only** rename via
`@JsonProperty` (still compiles) produced the real thing: `No value at JSON path
"$.summary.realizedTotal"`, one test, nothing else.

**Rule:** a red-proof must fail through the mechanism you are claiming coverage from. If it fails
earlier -- at the compiler, at wiring -- you have proven a different guard.

### 8.3 A pipe masks Maven's exit code, exactly like the git trap

`./mvnw.cmd ... | grep -E 'Tests run|BUILD'; echo "rc=$?"` reports **grep's** exit status. Twice this
session that turned a `BUILD FAILURE` into a reported success -- once calling a broken `test-compile`
clean, once chaining a spec re-capture off `&&` so it ran against a failed build. CLAUDE.md already
warns "never pipe a git command whose failure must stop a chain"; it is the same trap with a
different binary. **Redirect to a file and read the command's own `$?`**, then grep the file.

### 8.4 Typing a Map surfaces consumers nobody listed

Retyping `PaperService.pnl` broke compilation in `TelegramCommandBot`, which was reading the response
through an unchecked cast plus string map keys. Three tests also had to migrate off map-key
assertions -- one had been stubbing `"0.75"` as a **String** where the field is a `BigDecimal`, which
the Map silently accepted. The burn-down's real payoff is not the spec entry; it is that the compiler
starts enforcing a contract that was previously convention.

### 8.5 Two structural traps recorded in the code

- **Duplicate simple names collapse to ONE springdoc schema.** `SignalRejectionRepository.RailCount`
  and `StrategyEvidenceReader.RailCount` are different records sharing a name. Byte-identical today,
  so no drift -- but diverging either silently rewrites the other's published schema. Both now
  cross-warn in javadoc.
- **`$ref` nullability is NO LONGER a carve-out.** `NullableRefCustomizer` (task_bd871971) already
  rewrites a nullable `$ref` sibling into `anyOf: [$ref, null]`. My briefing to the reviewer said
  otherwise from memory; the annotation was the supported path all along. A nullable `JsonNode`
  response component takes `@Schema(types = {"object", "null"})`.

### 8.6 Rebasing after a squash-merge needs `--onto`

A plain `git rebase origin/main` on a stacked branch tries to replay the already-merged commits --
squash-merge means they are not ancestors of what landed -- and conflicts. `git status -sb` showed
`## HEAD (no branch)`, the fingerprint CLAUDE.md names. Abort, then
`git rebase --onto origin/main <predecessor-tip> <branch>` replays only the new work. Same reason
`tools/git-prune-merged.sh` conservatively keeps squash-merged branches: match by commit SUBJECT (the
squash uses the PR title, i.e. the branch's FIRST commit, not its tip).

## 7. Open, with dates

| Item | State |
|---|---|
| `budget_inr` ₹15,000 → ₹20,000 (#1075) | **HELD to 2026-08-12** on owner decision; revisit task scheduled, measures real `ZERO_SIZE` rate + real peak concurrency |
| T24 volume dot first live session | verify task 2026-07-29 16:20 IST |
| Capital governors first live session | verify task 2026-07-29 16:35 IST — written to FALSIFY the capacity-neutral claim |
