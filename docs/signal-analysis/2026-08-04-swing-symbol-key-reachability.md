# Swing `openLotsBySymbol` symbol-only keying — is the collapse reachable?

**Date:** 2026-08-04 · **Status:** investigation only, no production code changed ·
**Subject:** `SwingBatchEngine.openLotsBySymbol` (`services/strategy-signal-service/src/main/java/in/arthayantra/strategysignal/swing/SwingBatchEngine.java:562-570`),
its five consumption sites, and whether the state it mis-keys can be reached at all.

---

## 0. Verdict first

**The keying weakness is real and the premise is accurate, but the collapse it enables is
UNREACHABLE for Minervini at compile time and gated behind one `.env` line for Manas — and it has
never occurred in live data. It does not bite today, and it is not independent of a flag flip.**

| family | strategies | can two hold one symbol? | what stops it |
|---|---|---|---|
| `minervini_funnel` | 4 | **No — structurally** | `MinerviniDoctrine.pyramid()` returns the `PyramidPolicy.NONE` **literal**; `NONE.hasRoom()` is `return false;`. No config path reaches it. |
| `manas_arora_funnel` | 2 | **Not today; one `.env` line away** | `ManasPyramidPolicy.hasRoom()` = `enabled && lots.size() < maxLots`, and `ARTHA_MANAS_ARORA_PYRAMID_ENABLED=false` in the live container. |
| cross-family | — | **No — structurally** | `universeMode` filters both the published load and the adoption path. |

Live: **zero** same-symbol multi-strategy anchors, now or ever, across the entire 43-signal swing
history. `computed`.

**The single most useful number in this report:** if Manas pyramiding is ever armed, the collapse is
not a rare corner — **965 of 2305 symbol-days (41.9%) in `manas_arora_setups` carry BOTH a valid
breakout pivot and a valid VCP pivot**, and `ManasDoctrine.toCandidate` makes exactly those
candidates eligible for both strategies. `computed`. The precondition that looks exotic is in fact
the common case; only the flag is holding it.

**But the blast radius, if it were reached, is much smaller than the sweep implies** — and this is
the finding that should govern whether anyone spends money-path risk on a fix:

- **Position sizing is NOT affected.** `computed` — `lots.size()` reaches `emitEntry` only as
  `lotNumber`, whose sole effect is `detail.put("pyramidLot", lotNumber)` when `> 1`
  (`SwingBatchEngine.java:688-690`). `suggestedQty` is computed from
  `(definition.sizing(), EX, symbol, entryPrice, stopDistance, book)` (`:696-703`) and never reads the
  lot list. The sweep's "wrong position size" is not a consequence of this defect.
- **The combined lot count is arguably CORRECT, not wrong.** All strategies in a family share ONE
  paper book (`MinerviniDoctrine.book()` → `Books.MINERVINI`, `ManasDoctrine.book()` →
  `Books.MANAS_ARORA`, both unconditional), and `uq_paper_positions_open` keys on
  `(book, exchange, tradingsymbol, side)` — so two strategies entering one symbol **average into a
  single paper position** (the documented `PaperService.openPosition` pyramiding behaviour). A cap
  counting lots on that shared position *should* count both. Symbol-only keying is aligned with the
  position key, not misaligned with it.
- **The real exposure is the exit pass**, and it is inert by data today (§6).

---

## 1. STEP 0 — premise verification

Every load-bearing clause of the brief re-checked against the code at `origin/main` (`d3a9bb8b`).

| brief claim | verdict | evidence |
|---|---|---|
| `openLotsBySymbol` at `:562-569` keys on `tradingsymbol` alone | **CONFIRMED** (`:562-570`) | `bySymbol.computeIfAbsent(anchor.tradingsymbol(), …)` — no exchange, side, book, or strategy. `sourced` |
| consumed at `:302, 427, 439, 607, 766` | **CONFIRMED**, all five | `grep -n openLotsBySymbol` → 302, 427, 607, 766 (definition 562); `:439` is the `openLots.getOrDefault` read. `computed` |
| it is the authority for "held" and for the pyramid lot count | **CONFIRMED** | `:439` → `:458 isAdd` → `:459 hasRoom` → `:467 qualifiesForAdd`. `sourced` |
| `firedThisRun` permits one entry per symbol per run | **CONFIRMED** (`:428, 436, 542`) | `sourced` |
| it keys on **signal anchors**, not `paper_positions` | **CONFIRMED** | `signals.activeEntries()` = `SELECT * FROM signals WHERE signal_type='ENTRY' AND status IN ('ACTIVE','TAKEN')` (`SignalRepository.java:177-182`). `sourced` |
| two strategies collapse into one list, `lots.size()` is the combined count | **CONFIRMED as a code property**, but the state is unreachable (§4) | `computed` |
| "one strategy's entry blocks the other's for that run" | **TRUE but mis-framed** | It blocks across ALL runs, not one — and that is the correct behaviour for a shared book (§0). `computed` |

**One correction to the standing account.** The predecessor doc
(`2026-08-04-scoped-position-key-attribution-sites.md` §5) states `manas-arora` is "a multi-strategy
family (**five** strategies)". **It has two.** `computed` — `strategy.strategies` joined to its
published/latest version, filtered `universe.mode='manas_arora_funnel'`, returns exactly
`manas-arora-breakout, manas-arora-vcp`, with no disabled or unpublished siblings. The §5 argument
("twins are constructible") survives — two is enough for twins — but the number is wrong and it
feeds a risk narrative, so it should not be re-quoted.

---

## 2. What actually populates the map

```java
private Map<String, List<SignalRepository.SignalRow>> openLotsBySymbol(AnchorResolution resolution) {
  Map<String, List<SignalRepository.SignalRow>> bySymbol = new HashMap<>();
  for (SignalRepository.SignalRow anchor : signals.activeEntries()) {
    if (resolution.resolve(anchor.strategyVersionId()).isPresent()) {
      bySymbol.computeIfAbsent(anchor.tradingsymbol(), k -> new ArrayList<>()).add(anchor);
    }
  }
  return bySymbol;
}
```

`activeEntries()` is a **global, version-less, family-less read** — every live ENTRY anchor in the
database, including all 38 scalpers'. The *entire* scoping is the `resolution.resolve(...)` filter.
So the uniqueness the rows carry is not in the query; it is in `AnchorResolution`.

**Two missing key dimensions are moot inside this engine, structurally:**

- **exchange** — `SwingBatchEngine.java:70` is `private static final String EX = "NSE";`, and every
  `signals.insert` on both the entry (`:713`) and exit (`:1045`) paths passes `EX`. Every anchor this
  engine can ever write is NSE. `sourced`
- **side** — both inserts hard-code `"BUY"` / `"SELL"`. Every swing ENTRY is BUY. `sourced`, and
  matches `PaperReconciliationRepository.java:154-165`'s independent statement that `x.side <> s.side`
  is "currently a tautology".

Live confirmation: all 21 live swing anchors are one exchange, side `BUY`. `computed`.

So the only key dimension whose absence can matter is **strategy**.

---

## 3. Cross-family isolation — structural

Both paths into `AnchorResolution` filter on `universeMode`:

- `loadPublishedSwingStrategies` (`:1147-1150`) — skips any strategy whose
  `config.universe.mode` ≠ `doctrine.universeMode()`, so `published` is family-pure.
- `adoptVersion` (`:374-376`) — same test before adopting a superseded version.

`MinerviniDoctrine.universeMode()` returns the literal `"minervini_funnel"`;
`ManasDoctrine.universeMode()` returns `"manas_arora_funnel"` — both Java constants, not config.
`sourced`.

That also excludes every scalper (`options_of_underlying`, 38 strategies live) and, redundantly, the
`"swing".equals(definition.session().style())` test at `:1142` and `:379`.

**So the brief's twins scenario cannot arise cross-family here.** This is the "say so plainly"
answer for that half: the `artha.paper.strategy-scoped-books` mechanism governs paper **books**, and
even if a swing book were listed in it, it could not merge two families into one
`openLotsBySymbol` list — that map never sees `paper_positions` at all. The two mechanisms are
orthogonal.

> **Status note, 2026-08-04 (added at round-3 rebase).** #1275 **merged** while this doc was in
> review — `main` now carries V058, `PaperStrategyScopeGuard` and the `artha.paper.strategy-scoped-books`
> flag, where the predecessor doc recorded it as OPEN and unarmable. That changes nothing above: the
> orthogonality argument is structural (this map reads signal anchors, never `paper_positions`), so it
> holds whether the flag ships or not. Flagged only so a reader does not carry the predecessor's
> "cannot be armed today" forward — that clause has expired.

---

## 4. Within one family — the decisive gate

This is where the brief's sharper question lands: **can two swing strategies independently signal the
same symbol today, without any flag?**

**Minervini makes this maximally easy on every axis but one.** `MinerviniDoctrine.setupToken()`
returns `null` — "no setup routing — every Minervini strategy scores every candidate" (`:101-103`,
`sourced`). So all four strategies are eligible for every candidate on every run. Nothing in the
routing separates them.

The one axis that stops it is the held-skip, `entryPass:457-461`:

```java
List<SignalRepository.SignalRow> lots = allLots;
boolean isAdd = !lots.isEmpty();
if (isAdd && !pyramid.hasRoom(lots)) {
  continue; // held with no room → skip BEFORE any fetch (the single-lot held-skip)
}
```

`continue` exits the **candidate** loop, before the per-strategy loop is ever entered. So if *any*
strategy in the family holds the symbol and the policy has no room, the symbol is skipped for *all*
of them — permanently, not just for this run.

- **Minervini:** `pyramid()` is `return PyramidPolicy.NONE;` — unconditional, no field, no
  `@Value` (`MinerviniDoctrine.java:190-192`). `PyramidPolicy.NONE.hasRoom()` is `return false;`
  (`PyramidPolicy.java:63-65`). **Compile-time constant.** A second concurrent Minervini anchor on a
  held symbol cannot be produced by any configuration. `sourced`
- **Manas:** `hasRoom()` is `enabled && lots.size() < maxLots` (`ManasPyramidPolicy.java:49-51`),
  `enabled` ← `@Value("${artha.manas-arora.pyramid.enabled:false}")`. **Configuration.** `sourced`

**Deployed value, re-queried rather than recalled** (`docker inspect ay-strategy-signal-service`):
`ARTHA_MANAS_ARORA_PYRAMID_ENABLED=false`. `computed`. The compose passthrough exists
(`deploy/docker-compose.yml:569`) and `.env:79` sets it `false`. Both the env var and the YAML
default agree, so the conclusion holds regardless of how relaxed-binding resolves the hyphen.

### 4.1 Is `emitEntry` really the only writer?

The held-skip is only a guarantee if nothing else can insert a swing ENTRY anchor. Checked, and it
holds — `SignalRepository.insert` has four call sites, and the two in the tick engine are excluded at
load: `SignalEngine.java:831-835` skips `session.style=swing` outright ("driven by the daily
[batch]"). The other two are `SwingBatchEngine:712` (entry) and `:1045` (exit). No controller or
REST path inserts a signal. `computed`.

Status never revives, either — so the dead-anchor case (an EXPIRED anchor over a still-OPEN position,
already acknowledged at `SwingBatchEngine.java:505-508`) produces **one** live anchor after
re-entry, not two. It is a different defect, not this one.

### 4.2 The armed-Manas path, traced end to end

If `ARTHA_MANAS_ARORA_PYRAMID_ENABLED=true`, every remaining gate is passable:

1. Symbol held by (say) `manas-arora-vcp`, 1 lot.
2. `hasRoom([1])` → `true && 1 < 3` → **passes**.
3. `qualifiesForAdd` → close ≥ +6% since the most-recent lot (`ARTHA_MANAS_ARORA_PYRAMID_MIN_GAIN_PCT=6.0`,
   `computed` from the container env) — a normal event for a winner.
4. Per-strategy loop: `if (c.eligibleSetups() != null && !c.eligibleSetups().contains(strat.setupToken())) continue;`
   `ManasDoctrine.toCandidate:170-177` sets eligibility as "a candidate is eligible for a setup **iff
   it carries that setup's pivot**", and `ManasFunnelService`'s SQL LEFT-JOINs both pivots
   independently. **A dual-pivot candidate is eligible for both strategies.**
5. `manas-arora-breakout` fires first → emits the ADD → **two strategies, one symbol, one list.**

Step 4 is the one that looked speculative, so it was measured rather than assumed:

```
both_pivots | breakout_only | vcp_only | symbol_days
        965 |          1183 |       69 |        2305
```

`computed` — `marketdata.manas_arora_setups`, grouped per `(screen_date, symbol)`, counting valid
non-null pivots per setup type. **41.9% dual-eligible.**

**Which strategy wins step 5 is non-deterministic across deploys.** `registry.listAll()` is
`ORDER BY updated_at DESC` (`StrategyRepository.java:144-147`, `sourced`), and the boot seeder
re-touches strategies on any YAML change — so the iteration order, and therefore which strategy
gets first crack at a dual-eligible held symbol, flips on unrelated republishes. The held lot's own
strategy is not privileged.

---

## 5. Has it happened live?

No — on both the direct and the historical test.

| query | result |
|---|---|
| Live anchors per family (`ENTRY`, `status IN ('ACTIVE','TAKEN')`, joined to `universe.mode`) | minervini 15 anchors / **15 distinct symbols** / 6 versions; manas 6 anchors / **6 distinct symbols** / 2 versions. One anchor per symbol exactly. |
| Live: any `(family, symbol)` with >1 distinct **strategy_id** | **0 rows** |
| Ever: any `(family, symbol)` entered by >1 distinct **strategy_id**, any status, any date | **0 rows** |

`computed`, all three. Population is small and fully enumerated: 43 swing ENTRY signals total
(minervini 14 EXPIRED + 15 TAKEN, manas 8 EXPIRED + 6 TAKEN), spanning 2026-07-03 → 2026-08-03 IST.

Note the 15 live Minervini anchors resolve through **6** version ids against **4** published
strategies — the audit-H2 adoption path is live and working; superseded versions are being
exit-managed as designed. That is the mechanism working, not a symptom.

**A false-negative I generated and caught.** My first historical test joined anchor lifetimes on
`generated_at < expires_at`. That is wrong: `activeEntries()` filters on **status**, not expiry, and
swing `ttlMinutes` defaults to 1440 — so a held anchor routinely outlives its own `expires_at` by
weeks and the overlap test could almost never fire. It returned 0 rows for a reason that had nothing
to do with the question. The replacement test (distinct `strategy_id` per `(family, symbol)`, no time
predicate at all) is strictly weaker in assumptions and also returns 0. Recording this because the
first query's 0 looked like an answer.

**One measurement I could not make from the signal tables.** `manas_arora_detail` persists only
`{setup, setupType, footprint, pivot}` (`ManasDoctrine.toCandidate:178-188`) — the individual
`breakoutPivot`/`vcpPivot` are **not** persisted. An early query of mine reported
`has_breakout_pivot=f, has_vcp_pivot=f` on all 14 rows; that is the keys being absent by design, not
evidence of single-eligibility. The 41.9% figure in §4.2 comes from the screen table instead, which
is the actual input to eligibility.

Incidentally: **all 14 Manas entries ever emitted came from `manas-arora-vcp`; `manas-arora-breakout`
has never fired an entry.** `computed`. That is a live asymmetry worth someone's attention on its own
terms, unrelated to this defect.

---

## 6. Blast radius, if it were reached

Ordered by what actually changes.

1. **Exit pass — the real one, and it is inert by DATA, not by structure.** `exitPass:766-800` groups
   by symbol, picks `oldestLot(lots)` across the whole group, resolves **that** lot's strategy, and
   evaluates `ExitEvaluator` with **its** definition — then closes the position and expires *every*
   lot (`:1052-1054`), firing `SignalExited` per lot. So a younger lot from strategy B would be exited
   by strategy A's rules. **Today that is a no-op**: both Manas strategies carry **byte-identical**
   `exit_rules`, and all four Minervini strategies share a single distinct `exit_rules` value
   (`computed`, `count(DISTINCT config->'exit_rules') = 1`). Entry rules differ; exit rules do not.
   This is a configuration coincidence that a single YAML edit removes — it is not a guarantee.

   ⚠️ **And that measurement is across STRATEGIES at ONE INSTANT, which is weaker than it reads.**
   The collision keys on `strategy_version_id` (§8.3), so the pair that actually matters is
   lot-1's version vs lot-2's version — which may be two versions of the SAME strategy. The
   `count(DISTINCT …) = 1` above is over `published_version_id` only; a superseded version still
   anchoring an open lot is NOT in that population, and `adoptVersion` runs it from its own frozen
   config. So "exit rules agree" is established for today's published set, not for the set of
   versions currently holding lots.

   **So I measured that population directly** — the versions actually anchoring a live lot:

   | family | anchoring versions | distinct strategies | distinct `exit_rules` sets |
   |---|---|---|---|
   | `minervini_funnel` | 6 | 4 | **1** |
   | `manas_arora_funnel` | 2 | **1** | **1** |

   `computed`. Two readings, both worth having. The reassuring one: every version currently holding a
   swing lot agrees on exit rules, so the exposure is latent on this axis too, not live. The sharper
   one: **`manas_arora_funnel` is already running two versions of a SINGLE strategy against open
   lots** — the exact shape §8.3 describes. The multi-version collision is not hypothetical; only its
   *divergence* is absent, and only pyramiding being off keeps the two versions from sharing a symbol.
2. **A known-documented reconciler false negative.** `emitExit` writes ONE EXIT row under the oldest
   lot's `strategy_version_id`, so `strandedCarryPositions` looks for an EXIT under the *other*
   version and finds none. Already written down at `PaperReconciliationRepository.java:167-174`,
   which states the assumption exactly: *"This predicate assumes one version per (symbol, family)."*
   That comment is the same defect, already scoped and already called dormant.
3. **Pyramid cap counting.** `hasRoom` would count both strategies' lots against `maxLots=3`. As §0
   argues, that is **correct** for a shared averaged position, not a bug.
4. **Position sizing.** Unaffected — see §0. `computed`.
5. **A blocked entry.** The second strategy is skipped for that symbol. Correct for a shared book.

The `heldBefore`/`heldAfter` sites (`:302`, `:607`) feed the F3 admission probe only, which its own
javadoc pins as "measurement-only … it NEVER changes admission". No decision rides them.

---

## 7. Structural vs. configuration — stated precisely

The brief asks for this distinction explicitly, and the answer differs per family.

- **Minervini: structural.** `PyramidPolicy.NONE` is a compile-time literal reached through an
  unconditional `return`. Changing it requires editing and redeploying Java. There is no `.env` line,
  no YAML tag, no database row that reaches it.
- **Cross-family: structural.** `universeMode` is a Java constant on each doctrine, tested on both
  the load and the adoption path.
- **Manas: configuration — one line, and the line already exists.** `ARTHA_MANAS_ARORA_PYRAMID_ENABLED`
  is present in `.env:79`, wired through `deploy/docker-compose.yml:569`, and set `false`. This is the
  same shape as the predecessor doc's "one line in a `.env` file wide" finding about `RiskService` —
  and it is worth noting that **arming Manas pyramiding is a documented, owner-reversible operation**
  (memory: F2 #612, armed 2026-07-07, later disarmed under H4 #628 on Sharpe grounds). It is not a
  hypothetical flag nobody would touch; it is a flag that has been ON before and whose re-arming is
  described as "a `.env` flip".

**That is the sentence that should drive the decision.** The condition is unreachable today, but the
specific `.env` flip that makes it reachable is one the owner has already performed once and may
perform again — and if re-armed, 41.9% of screen days offer a qualifying candidate.

---

## 8. Recommendation (for the owner's decision, not a fix)

**Do not touch swing entry or pyramid sizing now.** The defect cannot fire, the count it would
corrupt is arguably correct anyway, and sizing is provably unaffected — so a money-path change buys
nothing today.

**Do attach a precondition to the flag instead.** The cheap, non-money-path options, in ascending
cost:

1. **Record the coupling** where the flag is armed (`.env:78-79` already carries an F2 comment) and in
   the `arm-flag` runbook: *re-arming Manas pyramiding makes two strategies able to hold one symbol;
   verify both strategies' `exit_rules` are still identical before arming.*
2. **A test, not a fix** — pin the §6.1 data coincidence: assert that all strategies within a swing
   family share one `exit_rules` value. That converts the silent dependency into a red build if
   someone edits one family member's exit rules, which is the actual trigger for the exit-pass
   exposure.

   ✅ **SHIPPED in this PR** — `SwingFamilyExitDoctrineTest` (owner-approved 2026-08-04).
   Two refinements emerged while building it, both material:

   - **Comparing `exit_rules` alone would have been insufficient — and there are TWO indirections,
     not one.** The second was found by cross-vendor review, one level below my own finding:
     1. `params.alias` — Minervini's trail is `{trailing_stop, basis: indicator, alias: sma50}`,
        byte-identical across all four strategies while the exit LEVEL is whatever each declares
        `sma50` to be.
     2. **Operands named inside a `signal_exit` rule STRING** — `ExitEvaluator.signalExit:715-732`
        compiles `params.rule` with `StrategyCompiler.compileLeafText` and evaluates it against the
        bank, so `crossunder(ema20, ema50)` resolves `ema50` exactly as an alias field would. Two
        strategies with that identical rule string and different `ema50` declarations produce equal
        fingerprints and different exits.

     The fingerprint now folds both, extracting rule operands with the **engine's own parser**
     (walking the sealed `GateNode`) so it cannot drift from what the evaluator resolves. Only the
     fields `IndicatorBank` computes a value from are included (`name`, `timeframe`, `params`,
     `instrument`) — `weight`/`normalize` feed entry scoring, never an exit level. Each indirection
     is red-proved independently (§8.2 receipt below).
   - **The family population is DISCOVERED, not listed.** A hardcoded family list is precisely how
     this invariant would decay, so the test scans the classpath for every bundled
     `*-strategies/*.yaml`, keeps the `swing`-session docs, and groups by `universe.mode`. A second
     assertion cross-checks the discovered family count against the number of concrete
     `@Component SwingDoctrine` implementations, so a new swing family cannot appear silently exempt.

   **Red-proof receipt** — five runs, each through the `test` PHASE with `-am`, `compile-errors: 0`:

   | # | perturbation | expected | result |
   |---|---|---|---|
   | A | `exit_rules` value 8% → 7% in one strategy | red | ✅ exit-doctrine assertion |
   | B | **only** `sma50` SMA(50)→SMA(30), `exit_rules` untouched | red | ✅ diff showed `period 50` vs `30` |
   | C | scan glob narrowed so a family vanishes, doctrine remains | red | ✅ doctrine cross-check |
   | D-control | identical `signal_exit` rule added to BOTH Manas strategies | **green** | ✅ the addition alone is neutral |
   | D | `vol` lookback 20→10 in one, `exit_rules` byte-identical | red | ✅ operand extracted from the RULE STRING |
   | E | family `universe.mode` renamed, family COUNT unchanged | red | ✅ membership check; the earlier count-only form passed this |

   D pairs with its control deliberately: the green control is what makes D's red attributable to the
   operand divergence rather than to the rule addition. `vol` is referenced by no `alias` field, so
   only the rule-string extraction can reach it — the pre-review fingerprint passed this case.

   The `CanonicalJson` fix carries its own counterfactual: reordering param keys in one YAML
   (semantically identical) stays green, and reverting to `toPrettyString()` reddens that same
   reordering — so the key-order sensitivity was real, not hypothetical.
3. **The residual gap a unit test cannot close — the VERSION axis.** Found in review round 3, and it
   is sharper than the cross-strategy case §8.2 pins. The collision keys on
   **`strategy_version_id` per lot, not on strategy**: `SwingBatchEngine:713` stamps
   `strat.versionId()`, and `AnchorResolution.resolve:351-357` → `adoptVersion:365-408` exit-manages
   a superseded anchor **with that version's own frozen config**. So the two colliding lots need not
   belong to two strategies — **two versions of ONE strategy collide identically**. And the swing
   seeders **auto-publish** on any bundled-YAML change (`ManasAroraStrategySeeder:104-118`, literally
   `"manas seeder auto-publish"`; the scalper seeder only drafts). `sourced`.

   The reachable sequence, with pyramiding armed:

   ```
   lot 1 opens under manas-arora-vcp v1.0.0
   owner tunes arm_pct 9 → 6 in BOTH Manas YAMLs   → §8.2's guard stays GREEN (they still agree)
   deploy; seeder auto-publishes v1.0.1
   lot 2 adds under v1.0.1
   oldestLot = lot 1 @ v1.0.0 → BOTH lots exit on the OLD 9% arm, both expired
   ```

   **Editing every family member together — the well-behaved thing to do — is exactly what keeps the
   test green while creating the divergence.** And §5 already measured that multi-version anchors are
   the normal state, not an edge: 15 live Minervini anchors resolving through **6 version ids**
   against 4 published strategies. `computed`.

   This needs a check over `strategy_versions` rows (or over the live anchors' resolved configs), not
   a classpath unit test. **Not built here** — it is a different artifact with a DB dependency, and
   the owner's approval was scoped to the test. Recorded in `SwingFamilyExitDoctrineTest`'s javadoc as
   an explicit non-coverage so a green run is not over-read.

4. Only if the flag is armed: revisit the keying. At that point `openLotsBySymbol` and the
   `strandedCarryPositions` predicate (§6.2) should be fixed **together** — they encode the same
   one-strategy-per-symbol assumption, and fixing one without the other leaves the reconciler blind.
   The version axis in (3) is the same defect and should be fixed in that same pass.

---

## 9. Open doubts

1. **`ExitEvaluator` is not a pure function of `exit_rules` alone — CONFIRMED, and now partly
   covered.** This doubt was right, and building the §8.2 test resolved half of it. Minervini's trail
   *does* resolve through an `alias: sma50`, so identical `exit_rules` genuinely does not imply an
   identical exit level. `computed` — all four Minervini strategies declare `sma50` as `SMA(period:
   50)`, so they agree today, and `SwingFamilyExitDoctrineTest` now pins that. **Still uncovered:** an
   exit rule whose behaviour depends on something outside `exit_rules` and outside an aliased
   indicator declaration — e.g. a future `basis` that reads a series the fingerprint does not include.
   The guard covers the two indirections that exist today, not a proof that no third one can arise.
2. **I did not test the armed path.** The whole §4.2 trace is read from source with the flag off. I
   did not flip it in mock and observe a dual-strategy add. The trace is `computed` from code, not
   `computed` from execution, and a real run could hit a gate I did not model — the funnel's
   buyable/on-deck bucketing, or the `entryAttempted` idempotency claim, could suppress step 5 in
   practice.
3. **41.9% is dual-*pivot*, not dual-*fires*.** Eligibility is necessary, not sufficient — each
   strategy's `EntryEvaluator` must also fire. The live record that `manas-arora-breakout` has never
   emitted an entry in a month suggests its gate is materially stricter, which would make the real
   collapse rate far below 41.9%. I did not quantify the firing rate, so 41.9% is an **upper bound on
   the precondition**, not an estimate of the event.
4. **`manas_arora_setups` history vs. the arming window.** The 2305 symbol-days are whatever the table
   currently holds; I did not bound them by `screen_date` against the period the flag was armed, and
   per the standing trap both equity source tables are retro-mutable. The ratio is stable enough for
   an order-of-magnitude claim but should not be quoted to a decimal in a decision document.
5. **Relaxed binding of the hyphenated property.** `artha.manas-arora.pyramid.enabled` ←
   `ARTHA_MANAS_ARORA_PYRAMID_ENABLED` — I did not verify Spring resolves the hyphen this way. It does
   not affect this report's conclusion (env value and YAML default are both `false`), but **it would
   matter on the arming direction**: if the binding does not resolve, setting the env var `true` would
   silently leave pyramiding OFF. Someone arming this should verify via the actuator, not the `.env`.
6. **Scope.** I examined the swing engine's use of this map. `SwingSellDecisionService.java:119` also
   calls `signals.activeEntries()` and applies its own `universeMode` filter (`:201`, `:217`); I
   confirmed the filter exists but did not audit that consumer's keying.
