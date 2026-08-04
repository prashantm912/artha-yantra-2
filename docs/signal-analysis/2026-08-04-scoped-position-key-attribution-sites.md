# Strategy-scoped position key (#1275) — how many mis-attribution sites, and can the set be closed?

**Date:** 2026-08-04 · **Status:** investigation only, no production code changed · **Subject:** PR #1275
(`feat/scalper-strategy-scoped-position-key`), migration `V058`, flag
`artha.paper.strategy-scoped-books` / `ARTHA_PAPER_STRATEGY_SCOPED_BOOKS`.

---

## 0. Verdict first

**Twelve dangerous locations. This is a BOUND, not an absolute proof — but it is a bound with a
closure argument behind it, and two of the three premises that argument rests on are measured
closed rather than assumed.**

| | count |
|---|---|
| Dangerous locations, inside `strategy-signal-service` | **11** |
| Dangerous locations, outside it (`optimizer-service`, Python) | **1** |
| — of which #1275's diff already scopes | **9** |
| — of which #1275 knowingly leaves open (documented + pinned) | **1** |
| — of which are book-gated out of the scalper arming scope | **1** |
| — **of which no round has enumerated at all** | **1** |
| Benign by construction (attributed value cannot differ between twins) | 7 |
| Already discriminating (keys on `id` or `opening_signal_id`) | ~20 |
| Count-granularity only (no wrong row selected; row count changes) | 7 |

Three corrections to the standing account, in descending order of consequence:

1. **The "eight sites" figure is inconsistent with the branch's own diff, which scopes TEN.**
   `PaperPositionRepository.notifyTargetFor` and
   `PaperReconciliationRepository.deadAnchorOrphanPositions` both receive a strategy predicate in the
   diff and appear in **neither** the round-2 table nor the round-3 "eighth site" note. The builder
   fixed more than the prose claims. `computed` — ten `strategy_id` predicates counted in
   `git diff origin/main...origin/feat/scalper-strategy-scoped-position-key`.
2. **A twelfth site exists outside the Java service entirely** —
   `services/optimizer-service/app/reconciliation.py:424`. Every round searched Java; this is Python,
   in a different service, reached through a repo function. `sourced`.
3. **The count kept moving partly because "site" was never defined.** Rounds 2, 3 and the round-3
   review each counted a different kind of thing (SQL statements touching one table; queries that
   attribute rows; then an ad-hoc inspection find). §2 fixes the unit before counting.

**PREMISE CORRECTION (STEP 0).** The brief says #1275 is *merged*. **It is not — PR #1275 is OPEN.**
`computed`: `gh pr view 1275 --json state` → `"OPEN"`, `mergedAt: null`. The live database has no
`strategy_id` column (`computed`: `information_schema.columns` → 0 rows) and the live index is still
the pre-V058 four-tuple (`computed`: `pg_indexes` → `USING btree (book, exchange, tradingsymbol,
side) WHERE status = 'OPEN'`). Nothing here is a live incident, and the flag cannot be armed today
because the code that reads it is not deployed.

One status item the PR body predates: **its stated hard block is cleared.** #1259 (V057
`paper_position_lots`) merged at `e929d15d` and V057 is on `main`. `computed`.

---

## 1. What makes a site dangerous — the defect, stated exactly

The whole feature turns on one index. Before V058 (`V021__paper_books.sql:14-16`, `sourced`):

```sql
CREATE UNIQUE INDEX uq_paper_positions_open
  ON paper_positions (book, exchange, tradingsymbol, side) WHERE status = 'OPEN';
```

That index is a **cardinality guarantee**, and it is the thing the codebase has silently been built
on top of for the life of the paper module:

> **INV.** For any `(book, exchange, tradingsymbol, side)`, at most ONE row of `paper_positions`
> has `status='OPEN'`.

V058 (`sourced`, branch) rewrites it to include `strategy_id` with `NULLS NOT DISTINCT`. Armed, INV
weakens to *at most one open row per (key, strategy)*. Disarmed, every row stamps `strategy_id =
NULL` and `NULLS NOT DISTINCT` makes all NULLs collide as one key — which is precisely what keeps
INV intact on every unscoped book.

**A site is dangerous iff it depends on INV.** Concretely, all three of:

- **(a) Non-discriminating predicate.** It selects rows under a predicate `P` that does not
  functionally determine `strategy_id` — i.e. `P` is implied by the four-tuple, or is a subset of it.
- **(b) Cardinality-1 consumption.** The result is consumed as though at most one row could match:
  a `findFirst()` / `LIMIT 1`, a bind-and-close, or a count used as a per-instrument proxy.
- **(c) The value can differ.** The attributed value is *not* functionally determined by the columns
  already in `P`.

Clause (c) is the one that earns its keep, and it is why `PortfolioReader.expiryScan` was correctly
classified as benign even while being scoped: expiry is a function of `tradingsymbol`, and
`tradingsymbol` is *in* the join key, so two twins cannot disagree on it. No amount of wrong-row
selection can produce a wrong answer. Same test, applied in §4, clears six other sites.

---

## 2. The unit, fixed before counting

> **Resolution site** = one code location at which a position — or a per-position value — is
> obtained from a descriptor that does **not** functionally determine `strategy_id`.

Two consequences, both of which the earlier counts fell foul of:

- A site is **a location, not a table**. `PaperOrderRepository.legsForPosition` never names
  `paper_positions`; it is still a resolution site, because it attributes orders *to a position*.
  That is the round-2→3 lesson, and it generalises further than round 3 applied it.
- A site need not be **SQL**. `RiskService.java:328-336` resolves a position with a Java
  `stream().filter(...).findFirst()` over rows already fetched. §3 shows this is a whole class, not
  a one-off.

---

## 3. Deriving the search space (the closure argument)

**Claim.** Every mis-attribution must, at some point, map a **non-`id` descriptor** to a position row
or a position-derived value.

**Why.** If a code path holds `paper_positions.id`, the row it names is unique by primary key and
correct by construction; every downstream use of that row is attributed to the right position. So the
defect cannot originate in an id-keyed path — it can only be *inherited* from whatever produced the
id. Push that back far enough and you reach a non-`id` descriptor or a genuine primary-key read.

So the search space is exactly the set of places a non-`id` descriptor is resolved. Enumerating what
those descriptors can be:

| Descriptor | Reachable how | Enumerable? |
|---|---|---|
| Columns of `paper_positions` (the natural key, or subsets) | SQL naming the table; **or a Java filter over an already-fetched row collection** | yes — §4 A, B, D |
| Columns of `paper_orders` joined on the natural key | SQL naming `paper_orders` | yes — §4 A |
| `position_id` in satellite tables (`journal_entries`, `paper_position_lots`, `paper_events`, `swing_paper_effects.target_position_ids`) | FK / array column | **these ARE the id** — they inherit correctness from their producer, which is already in the set |
| DB-side objects (views, matviews, functions, triggers) | server-side | **measured: none exist** |
| Anything resolved at runtime (reflection, dynamic SQL, `@Query`, classpath SQL) | — | **measured: none exist** (§3 (iii)) |

The satellite row is the load-bearing one: it is what makes the enumeration *finite*. Because every
satellite table references positions by `id`, no satellite read can introduce a new mis-attribution —
it can only propagate one. That collapses what looks like an open-ended graph into four statically
enumerable buckets.

### The two premises I closed by measurement, not assertion

**(i) No DB-side objects.** `computed`, against live `artha`:

```sql
SELECT c.relkind, n.nspname, c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE c.relkind IN ('v','m') AND pg_get_viewdef(c.oid) ~* 'paper_(positions|orders|position_lots)';
-- (0 rows)
SELECT n.nspname, p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
WHERE n.nspname NOT IN ('pg_catalog','information_schema','_timescaledb_internal','_timescaledb_functions')
  AND p.prosrc ~* 'paper_(positions|orders|position_lots)';
-- (0 rows)
SELECT c.relname, t.tgname FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
WHERE NOT t.tgisinternal AND c.relname LIKE 'paper%';
-- (0 rows)
```

No view, no matview, no function, no trigger anywhere in the live database references any paper
table. A whole class of invisible reader is ruled out by evidence rather than by hoping.

**(ii) Every satellite reference is by `id`.** `computed`, same session:

```
child                        | col               | parent
strategy.journal_entries     | paper_position_id | strategy.paper_positions
strategy.paper_position_lots | order_id          | strategy.paper_orders
strategy.paper_position_lots | position_id       | strategy.paper_positions
```

plus `paper_events.position_id` (bigint, no FK) and `swing_paper_effects.target_position_ids`
(bigint array). Both are written from an already-resolved id and read back by it —
`PaperEventRepository.query:87` filters `position_id = ?`, `SwingPaperEffectRepository
.exitConfirmedByPaper:517` reads `WHERE id=?` per element. Neither introduces a descriptor.

**(iii) Every SQL statement in the service is a Java string literal.** `computed`, over
`services/strategy-signal-service/src/main`:

```
grep -rn "extends CrudRepository\|extends JpaRepository\|extends Repository\|@Query" --include=*.java  → 0
grep -rn "Class.forName\|getDeclaredMethod\|ScriptEngine"                --include=*.java  → 0
find resources -name "*.sql"                                                               → 0
```

No Spring Data repository interface, no `@Query` annotation, no derived query methods, no reflection,
no scripting engine, and no SQL loaded from the classpath. Everything is `JdbcTemplate` against a
literal string, which is what makes a static enumeration meaningful at all — a repository whose
queries are generated from method names would have made this whole exercise unsound.

**(iv) The premise I did NOT close.** Whether each *benign-by-construction* argument in §4 B is
actually sound is a semantic judgement about the domain, not something a query can settle. If one of
those seven arguments is wrong, a benign site is really a dangerous one. I give the reasoning for
each so it can be attacked individually; that is the best available, and it is not proof.

---

## 4. Classification — every candidate

Line numbers are the **branch** tree (`feat/scalper-strategy-scoped-position-key`) unless noted.
All file paths relative to `services/strategy-signal-service/src/main/java/in/arthayantra/strategysignal/`.

### A. DANGEROUS — can attribute, bind or close the wrong twin

| # | Site | Consumer / consequence | Status |
|---|---|---|---|
| A1 | `paper/PaperPositionRepository.java:544` `openForSignal` | `PaperService.closeForSignal:1685` settles every returned row → **wrong close** | scoped ✅ |
| A2 | `paper/PaperPositionRepository.java:508` `intradayOpen` | `markToCloseIntraday:1726` settles → a BTST twin force-closed at 15:45 | scoped ✅ (C1) |
| A3 | `paper/PaperPositionRepository.java:584` `openStraddleLegs` | `StraddleExitMonitor.java:68` closes every returned id | scoped ✅ (C2) |
| A4 | `paper/PaperPositionRepository.java:304` `findLatestForKey` | `PaperService.replayFor:639` returns a sibling's id/qty/brackets on an idempotent retry — a silently wrong **200** | scoped ✅ (M4) |
| A5 | `paper/PaperReconciliationRepository.java:60` entry LATERAL | doubles `entry_qty` → nightly false `qtyMismatch` | scoped ✅ (M5) |
| A6 | `signals/SwingPaperEffectRepository.java:206` `openPositionIdsForSignals` | ids bound at `SwingBatchEngine:1032`, closed via `closeForPosition` → **wrong close** | scoped ✅ (#6) |
| A7 | `paper/PaperOrderRepository.java:286` `legsForPosition` | `GET /paper/positions/{id}` renders a sibling's entry fills | scoped ✅ **PARTIAL** — settle legs carry `signal_id = NULL` and remain shared |
| A8 | `paper/PaperPositionRepository.java:612` `notifyTargetFor` | one twin's expiry pages the other twin's channel | scoped ✅ — **not in any round's enumeration** |
| A9 | `paper/PaperReconciliationRepository.java:352` `deadAnchorOrphanPositions` | a sibling's live ENTRY counted as a healthy anchor → false negative in an orphan detector | scoped ✅ — **not in any round's enumeration** |
| A10 | `paper/PaperReconciliationRepository.java:96` exit LATERAL | twin A's settle order satisfies the lateral for twin B → **missing-exit detection becomes a false negative**; a genuinely unsettled position stops being reported | **UNFIXED** — known, documented in-line, pinned by `PaperScopedResolutionPathsIntegrationTest` |
| A11 | `paper/RiskService.java:328-336` | `stream().filter(exchange/tradingsymbol/side).findFirst()` picks one twin; `projectedTotal` then subtracts that twin's risk and adds a projection built on **its** qty/avg/stop while the fill belongs to the other → risk cap computed against the wrong row | **UNFIXED** — book-gated, see §5 |
| A12 | `services/optimizer-service/app/reconciliation.py:424` | `key = (_norm_symbol(tradingsymbol), _ist_date(openedAt))` then `live_by_key.setdefault(key, price)` — **first-wins**, so `entryPriceDelta` silently takes one twin's entry price and discards the other. No secondary `ORDER BY` on identical `opened_at`, so which twin wins is arbitrary | **UNFIXED — never enumerated by any round** |

A12 is the interesting one methodologically: it is not merely in a different file, it is in a
different **service**, a different **language**, and its key is a *proper subset* of the four-tuple
(it drops `book`, `exchange` and `side` entirely). No sweep scoped to "SQL in strategy-signal-service"
could have reached it, and the corrected round-3 unit ("queries that attribute rows to a position")
still would not have — it is not a query. Its blast radius is genuinely small: `paired_count` is a
set cardinality and is unaffected, and the damage is confined to `entryPriceDelta` in the
rollback-proposal path (`app/proposals.py:993`). It is a wrong *number*, not a wrong *close*.

### B. BENIGN BY CONSTRUCTION — clause (c) fails, twins cannot disagree

| Site | Why the value cannot differ |
|---|---|
| `insights/PortfolioReader.java:79` `expiryScan` | expiry = f(`tradingsymbol`), and `tradingsymbol` is in the join key. Scoped anyway — correctly, since one unscoped instance of a pattern teaches the next reader it is optional |
| `paper/PaperPositionRepository.java:840` `openSubAccountIdx` | **deliberately** unscoped. A sibling *inherits* the key's existing sub-account, so all twins on a key carry the same `subaccount_idx` by construction. Scoping it would route the sibling to a second account and halve the capital each pair charges — a governor change disguised as a lookup fix |
| `paper/PaperPositionRepository.java:562` `signalIdsFor` | returns a **superset** (both twins' signal ids), but `TakenSignalResolver.java:43-46` composes it with the now-scoped `openForSignal`. **Verified, not accepted**: twin A closes → for `sigA`, `openForSignal(sigA)` is empty (A is CLOSED) → A's anchor expires; for `sigB`, it returns B's still-open row → B's anchor survives. Correct. Note the dependency is real — unscoped, `openForSignal(sigA)` would reach B's open row and A's anchor would stick in `TAKEN` forever |
| `paper/PaperAccountService.java:95` `unrealizedTotal` | algebraically invariant. Merged: `Q·(mark − A)` where `A = Σqᵢaᵢ / Q`. Split: `Σ qᵢ·(mark − aᵢ) = Q·mark − Σqᵢaᵢ`. Identical |
| `paper/PaperPositionRepository.java:868` `openCapitalBySubAccount` | same identity: `Σ qᵢaᵢ = Q·A`. Deployed capital per sub-account is unchanged by the split |
| `paper/PaperAccountService.java:150` deployment usage | additive per row; twins share an instrument and therefore an `instrumentClass`, so both land in the same bucket |
| `insights/PortfolioReader.java:143` `staleTickScan` | selects by `id`, maps `exchange:tradingsymbol` only to look up a stale-key set. Each twin genuinely has its own brackets, so flagging both is correct |

### C. ALREADY DISCRIMINATING — keys on `id` or on `opening_signal_id`

`find` · `findDetail` · `findBook` · `updateBrackets` · `close` · `updateOpen` ·
`updateMarginSnapshot` · `strategyIdOf` (all `WHERE id=?`) · `findOpenIdIfOpenedBy:170` ·
`GraduationService.closedPnls:118` · `strandedCarryPositions` · `autoPaperPositionsWithoutSignal:164`
(all anchor on `opening_signal_id`) · `PaperPositionLotRepository:202,247` (`l.position_id = p.id`) ·
`JournalRepository.paperPositionExists:64` · `PaperEventRepository.query:87` ·
`SwingPaperEffectRepository.exitConfirmedByPaper:517` / `entryConfirmedByPaper:503` ·
`PaperBracketEvaluator.java:48` and `PaperExpiryService.java:86,119` (iterate `listOpen()` but act
**per row by id**, which is exactly right — each twin evaluates its own brackets) ·
`PaperService.closeForPosition:1705` · every `/paper/positions/{id}` REST endpoint.

Worth stating because it inverts the usual direction: **arming makes `GraduationService.closedPnls`
and `PaperPositionLotRepository`'s attribution more correct, not less.** Today a merged twin's entire
realised P&L is credited to whichever strategy's signal happened to open the row first. Split rows
each carry their own `opening_signal_id`, so promotion evidence stops being cross-attributed.

`optimizer-service/app/repos.py:1181` belongs here too: it joins
`signals.id = p.opening_signal_id` and filters on `strategy_version_id`, so it is already
strategy-scoped and gets *more* accurate under arming.

### D. COUNT-GRANULARITY — no wrong row, but the row count changes

The PR discloses two (`max_open_paper_positions` = 20, `ScalperAccountModel.MAX_WINS_PER_DAY` = 5)
and states both "bind **SOONER, never later**". Five more exist:

| Site | Effect | Disclosed? |
|---|---|---|
| `RiskService.java:488` `open.size() > MAX_LEGS` (20) | heat-cap governor returns `null` = "cannot assess" = **never blocks** | no — see the falsification below |
| `paper/PaperMarginController.java:93` | `GET /margin-heat` refuses loud over 20 legs | no |
| `insights/BookHeatReader.java:47` `GROUP BY book, tradingsymbol, side, count(*)` | `maxConcentration` reads 2 for a co-fired pair and `concentrationLeg` names a concentration that does not exist. Feeds `RiskHeatGenerator` (an **insight**, not a governor) | no |
| `insights/PortfolioReader.java:57` un-journaled count | doubles for a co-fired pair (nag metric) | no |
| `PaperService.java:1769` `ay_paper_mtm_blind_positions` gauge | doubles (metric only) | no |

**A hypothesis I formed and then falsified — recorded because the falsification is the useful part.**
The first row above looks like a governor that gets *looser*, contradicting the PR's "binds sooner,
never later". If twins double the row count, `open.size()` crosses `MAX_LEGS = 20` sooner, and
`currentHeatPct` then returns `null`, which the caller treats as fail-soft and **never blocks**
(`RiskService.java:455-470`, `sourced`). That would be a live money-path control switching itself off.

It does not hold. `computed`: live `risk_settings` gives the scalper book
`max_open_paper_positions = {"value": 20, "enabled": true}` and `heat_cap_pct = {"value": 60.0,
"enabled": true}`. `RiskService.entryVeto:412-416` blocks when `openCount(book) >= 20`, and
`PaperService.java:521` consults `entryVeto` on **every** open path (`computed`: the only call sites
are `PaperService:521` and `RiskService:136`). So the row count can reach exactly 20 and never
exceed it, and `open.size() > 20` is unreachable. The two constants being equal is what saves it.

**The residual, narrower version is real, and it is new.** `entryVeto` runs *outside* the fill
transaction (`PaperService.java:434`, `sourced`), so two co-firing entries at count 19 both pass.
Pre-arming the second one hits `uq_paper_positions_open` and **averages** into the first — the count
stays 20 and the race self-corrects. Armed, the second one **INSERTs its own row** → 21 > `MAX_LEGS`
→ the heat cap goes inert. Arming therefore converts a governor race that the unique index used to
absorb into one that adds a row. Narrow (needs a co-fire at exactly count 19) and it does not make
the *cap value* wrong, but it is a reachable path that the "binds sooner, never later" claim does not
cover, and it exists only because splitting the rows removed the index that was silently repairing it.

### E. OUT OF SCOPE

`RiskService.manasAggregateRiskCheck` — see §5.

### Negative evidence (searched, nothing found)

`market-data-service` contains **no SQL against any paper table at all** — the
`SubscriptionRegistry.java` hits are a Javadoc line and an exception-message string; the service's
datasource is `currentSchema=marketdata`. (This corrects a premise I carried into the sweep myself.)
`backtest-service`, `edge-gateway` (no datasource; it path-proxies `/api/v1/paper/**`), `libs/`,
`tools/`, `e2e/`, `contracts/`, `load/`, `config/` and non-migration `deploy/` are all clean.
The frontend calls REST only and every mutating call is `/{id}`-keyed; `QueryConsolePage.tsx` does
build raw SQL, but it runs as `ay_console`, which is granted `SELECT` on schema `marketdata` **only**
(`deploy/flyway/admin/V002__console_readonly_role.sql:34-40`), so `strategy.paper_positions` is
refused by Postgres. Thirteen further Java files mention the paper tables in **comments only**.

---

## 5. Does the `RiskService` containment hold?

**It holds today — but it is a property-VALUE containment, not a structural one, and that distinction
is the finding.**

`RiskService.java:295-297` (`sourced`):

```java
if (!BookResolver.MANAS_ARORA.equals(book) || fillPrice == null || qty <= 0) {
  return ManasRiskOutcome.ADMIT;
}
```

The method is inert unless `book` is exactly `manas-arora`. A scalper signal stamps `book='scalper'`
(`BookResolver.bookForSignal:42-52`, reading `signals.book` frozen at emission), so **no scalper path
reaches line 328.** Containment confirmed. Not an urgent finding.

**But nothing structural stops it being reached.** `artha.paper.strategy-scoped-books` is a free-form
comma-separated list of book names parsed at `PaperService.java:623-628`; `manas-arora` is a valid
book name and the property would accept it without complaint. The moment it appears there:

- `manas-arora` is a **multi-strategy family** (five strategies), so twins are constructible;
- `RiskService.java:310` `listOpen(book)` returns both twins;
- `existingTotal` correctly sums both, but `existing = ...findFirst()` picks one arbitrarily, and
  `projectedTotal = existingTotal − oldSymbolRisk + projectedSymbolRisk` then subtracts *that* twin's
  risk and adds a projection computed from *its* qty, avg and stop — for a fill that belongs to the
  other. The aggregate risk cap is evaluated against the wrong row;
- `manas-arora` currently holds **6 OPEN positions** (`computed`, live), so `PaperStrategyScopeGuard`
  would refuse to boot on the arming direction — which catches this, loudly, by accident rather than
  by design. It would not catch it on a flat book.

So the honest statement is: *the containment is one line in a `.env` file wide.* §7 turns that into a
concrete precondition.

---

## 6. What my method cannot see

Stated plainly, because this is what makes the bound honest rather than decorative.

1. **The semantic half of "benign by construction."** §4 B rests on seven arguments about whether a
   value *can* differ between twins. Two are algebraic identities and I regard those as solid; the
   other five are domain judgements. If one is wrong, a benign site is a dangerous one, and no query
   I can run will tell me which.
2. **Runtime-resolved SQL.** §3 (iii) rules out the mechanised forms by measurement — no Spring Data,
   no `@Query`, no reflection, no classpath SQL. What remains is hand-rolled string concatenation,
   and two such sites do exist: `PaperEventRepository.java:87` and
   `SwingPaperEffectRepository.java:368`. I read both — they compose **filters** onto a fixed `FROM`,
   never a key reconstruction. That last step is inspection, not proof, and it is the residue.
3. **Anything outside the repository.** Hand-run `psql`, an operator following a runbook, a future
   service, a scheduled job someone adds. Paper-table SQL *does* live in `docs/` runbooks
   (`docs/signal-analysis/README.md:508,579` and four others) — all aggregate/display, none
   resolving a single row by the four-tuple, but they are copy-paste sources for future scripts.
4. **A representational limit no site-hunt can close.** The `A10` exit-lateral gap is not a bad
   query — it is that `doSettle` writes settle orders with `signal_id = NULL`, so **nothing in
   today's schema can tie an exit fill to one of two sibling positions.** No enumeration finds a
   site to fix, because the fix is a new column, not a new predicate. V057 does not close it: lots
   are entry-only by design, and adding SELL-side lot rows would break the `sum(lots.qty) =
   position.qty` invariant #1259 relies on.
5. **Time.** This is a snapshot of one branch. Nothing prevents an eleventh in-service site being
   written next week. §7's last recommendation is the only durable answer to that.

---

## 7. What must be true before the flag is armed

1. **Arm `scalper` and nothing else, and make that structural rather than conventional.** §5 shows
   the `RiskService` containment is a property value. The cheapest hardening is to have
   `PaperStrategyScopeGuard` refuse any book other than `scalper` (or refuse any book whose family
   reaches `manasAggregateRiskCheck`), so the containment is code, not care.
2. **Arm only onto a FLAT scalper book.** `computed` (live, today): scalper is **0 OPEN / 10 CLOSED**;
   `minervini` holds 12 OPEN and `manas-arora` 6 OPEN, both NULL-strategy. Arming `scalper` alone is
   clean right now, and `PaperStrategyScopeGuard` enforces it in both directions. That window closes
   the moment a scalper position opens.
3. **Accept A10 explicitly, in writing, as a known blind spot.** It is the sharpest residual: on an
   armed book, a genuinely unsettled twin can stop being reported by the nightly reconciler. It
   *deletes an alarm* rather than adding a false one, which is the worse failure direction, and it is
   not fixable without a `position_id ↔ exit_order_id` linkage. This is an owner decision, not a
   builder one.
4. **Decide A12** (`optimizer-service`). Lowest severity in the set — it perturbs `entryPriceDelta`
   in the rollback-proposal path, not a close — but it should be a recorded decision rather than an
   omission, since no round has seen it.
5. **Reconcile the PR prose with its own diff** before merge: the body says eight sites, the diff
   scopes ten. A reader auditing this later will otherwise conclude two predicates were added without
   a reason.
6. **Add a ratchet, and treat this as the real deliverable.** The count moved four times across three
   rounds because *nothing enforces the invariant* — every round was a fresh act of diligence, and
   diligence does not compose. A test that freezes the set of statements joining
   `paper_positions`/`paper_orders` on the natural key without a strategy predicate (the
   `MapReturnRatchetTest` pattern, which already works in this repo) converts "we swept carefully"
   into "an eleventh site fails CI". That is the only form in which the answer to *"is the set
   complete?"* stays true after today.

---

## 8. Claim ledger

| Claim | Label |
|---|---|
| PR #1275 is OPEN, not merged; live DB has no `strategy_id`; live index is the 4-tuple | computed (`gh pr view`, `information_schema`, `pg_indexes`) |
| No views / matviews / functions / triggers reference any paper table | computed (three `pg_catalog` queries, 0 rows each) |
| Satellite tables reference positions by `id` only | computed (`pg_constraint` + `information_schema.columns`) |
| No Spring Data / `@Query` / reflection / classpath SQL in strategy-signal-service | computed (three greps + `find`, 0 hits each) |
| The branch diff carries TEN strategy predicates, not eight | computed (diff read end-to-end) |
| `notifyTargetFor` + `deadAnchorOrphanPositions` appear in no round's enumeration | sourced (PR #1275 body, read in full) |
| A12 (`optimizer-service/app/reconciliation.py:424`) exists and is first-wins | sourced (sub-sweep read the file) |
| `RiskService` is gated on `book == manas-arora` at :295-296 | sourced |
| The arming property is a free-form book list; `manas-arora` would be accepted | sourced (`PaperService:623-628`, `application.yml:199`, `docker-compose.yml:592`) |
| Live: scalper 0 OPEN / 10 CLOSED; minervini 12 OPEN; manas-arora 6 OPEN | computed |
| Live `risk_settings`: scalper `max_open`=20 enabled, `heat_cap_pct`=60.0 enabled | computed |
| `entryVeto` is consulted on every open path ⇒ `open.size() > 20` unreachable | computed (call-site grep) + sourced (`PaperService:521`) |
| The `entryVeto` TOCTOU widens under arming (index no longer absorbs the race) | **assumed** — reasoned from the code, NOT reproduced |
| `signalIdsFor` composes safely with the scoped `openForSignal` | computed (traced both branches of `TakenSignalResolver:43-46`) |
| V057 is on main (#1259 @ `e929d15d`) — the hard block is cleared | computed |
| Twelve dangerous locations is a bound, not a proof | computed, subject to §6 |

---

## 9. Open doubts

1. **"Twelve" is a bound and could still be an undercount, in one specific direction.** The closure
   argument in §3 is sound for *code that queries the database*. It is weakest at clause (c) — the
   benign/dangerous judgement — because that is where I substituted reasoning for measurement seven
   times. I would attack `openSubAccountIdx` first: it is the one benign classification that is a
   deliberate *design decision* rather than a functional dependency, so unlike the others it could be
   revisited and become wrong without any code changing.
2. **I did not run the test suite.** Every statement about behaviour here is read from source and
   from live DB state. I did not execute `PaperScopedResolutionPathsIntegrationTest` or attempt to
   construct twins in a scratch database, so the claim that the nine scoped sites are *correctly*
   scoped rests on reading their predicates, not on observing them discriminate. A reviewer who wants
   that should ask for it explicitly — it is a different, larger job than enumeration.
3. **The TOCTOU claim in §4 D is reasoned, not reproduced.** I did not build the race. It is labelled
   `assumed` and should be treated as a hypothesis worth one integration test, not as a measured
   defect.
4. **A12's blast radius is my inference.** I read that `paired_count` is a set cardinality and
   therefore insensitive to the duplicate, and that only `entryPriceDelta` takes the first-wins
   value. I did not trace every consumer of the reconciliation vector, so "small" is a judgement.
5. **§6.3 is genuinely open and I cannot close it.** If anyone has ever run a paper-table query from
   outside this repository — a saved psql snippet, a notebook, a scheduled task — it is outside every
   method available to me, and no amount of further sweeping changes that.
6. **This document decays.** It describes one branch on one day. The ratchet in §7.6 is the only
   recommendation here that survives the next commit; everything else is a snapshot.
