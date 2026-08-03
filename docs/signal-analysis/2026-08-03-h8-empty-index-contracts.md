# H8 / chip `task_f624fca7` — should an empty `contracts` array on an INDEX ladder be a FAULT?

**Date:** 2026-08-03 · **Type:** read-only investigation, no code change · **Tier:** clean
**Ledger row:** H8 (`docs/superpowers/plans/2026-07-02-remaining-items.md:798`) · **Origin:** #877 residue

---

## Verdict

**Yes — conditional on the resolution path: an empty `contracts` array MUST be a FAULT on the INDEX
path, and stay a legitimate skip on the screener path** — i.e. exactly the asymmetry #877 already
built and documented for the 404 case, applied to the one branch it left behind. The deciding
evidence is that it costs nothing and closes a silent-outage class: the case is **structurally
unreachable from the current producer** (`computed` — every path in
`FuturesTermStructureService.termStructure` either throws or returns a non-empty list), it has
**never been observed live** (`computed` — 0 occurrences of the log line that fires at the branch;
0 of 108 persisted engine reloads show its signature), and yet if it ever did fire it would
reproduce the **2026-07-15/16 outage byte-for-byte** — 38 of 38 live strategies dropped, `unresolved`
counted as 0, the retry chain reporting success, and the ARMED T9 coverage watchdog **explicitly
declining to page** because `RESOLVED_EMPTY.abnormal()` returns `false`.

Because it is unreachable, this is **defence-in-depth, not a live bug fix** — and that framing is
load-bearing for how it should be sized and reviewed. No live behaviour changes.

---

## 1. Provenance — why this chip exists and why it has no ledger row

`sourced`. #877 (`fix(strategy-signal): split universe-resolution FAILURE from legitimately-EMPTY`,
merged 2026-07-16, `e2f9f8c4`) split a **4-way** conflation in `FuturesUniverseResolver`. Its own PR
body enumerates the four:

1. the catch block (genuine failure swallow)
2. `!contracts.isArray()` **fused with** `contracts.isEmpty()` in one predicate
3. `resolveScreener`'s loop flattening its inner `resolve()` failure
4. the `default` not-live-resolvable mode

#877 fixed (1), (3) and (4), and it **un-fused** (2) — but having un-fused it, it then assigned the
two halves *different* meanings: non-array ⇒ `Optional.empty()` (UNRESOLVED), empty-array ⇒
`Optional.of(List.of())` (legitimately empty). **The empty half was a deliberate choice, not an
oversight** — which is precisely why it was chipped rather than fixed, and why the chip's question is
"was that choice right?", not "was something missed?".

The same PR body states the premise the choice rests on:

> Market-data never returns 200-with-empty-contracts (`FuturesTermStructureService:101-103` throws
> `NOT_FOUND_INSTRUMENT`).

That premise is **still true today** (§3), but nothing anywhere pins it (§3.3).

A design doc six days later routed around the question rather than answering it —
`docs/superpowers/plans/2026-07-26-t9-strategy-coverage-watchdog-design.md:161-163`:

> `RESOLVED_EMPTY` never alarms — it is the correct stand-aside […] (Whether an empty `contracts`
> array on an INDEX ladder should be a FAULT is chip `task_f624fca7`'s question, owned by the
> resolver, untouched here.)

That doc's design is now **BUILT and ARMED LIVE** (ledger row G2, #1035, armed 2026-08-01), so the
"never alarms" clause is live behaviour, not an intention (§4.3).

---

## 2. Where it is produced and consumed

### 2.1 The single producer

`computed`. One endpoint, one controller, one service:

| role | file:line |
|---|---|
| endpoint | `services/market-data-service/src/main/java/in/arthayantra/marketdata/futures/FuturesController.java:25-30` |
| producer | `services/market-data-service/src/main/java/in/arthayantra/marketdata/futures/FuturesTermStructureService.java:94-149` |
| ladder query | `services/market-data-service/src/main/java/in/arthayantra/marketdata/instruments/InstrumentRepository.java:291-301` |

`computed`. `TermStructure.contracts` is the **only** array-of-legs named `contracts` in the
market-data OpenAPI spec — verified by enumerating every component schema carrying a `contracts`
property in `contracts/market-data-service.openapi.json`; the four other hits
(`ContinuousBackfillResponse`, `BuzzMatrix`, `ExpiredBackfillStatus`, `CoverageRow`) are scalars or a
string array, not a contract ladder. So "the INDEX ladder" is unambiguous.

### 2.2 The five consumers

`computed`. Only **two** of the five can tell an empty array apart from an upstream failure at all —
and only one of those is on a live path.

| # | consumer | file:line | behaviour on empty `contracts` | distinguishes empty from failure? |
|---|---|---|---|---|
| **1** | **`FuturesUniverseResolver.resolve`** (live universe resolution) | `services/strategy-signal-service/.../signals/FuturesUniverseResolver.java:93-96` | `log.warn` + `Optional.of(List.of())` — a legitimate empty | **YES — this is the chip's question** |
| 2 | `SignalEngine.resolveUniverse` / `fromResolver` / `resolvedUniverse` | `.../signals/SignalEngine.java:866-875`, `:1216-1232` | `RESOLVED_EMPTY` ⇒ INFO log, strategy dropped, **not counted** | downstream of #1 |
| **3** | **`UniverseResolver.resolveFutures`** (backtest submission-time pinning) | `.../registry/UniverseResolver.java:171-181` | `legs.size() > leg` is false ⇒ pins an **EMPTY universe** silently. A 404/503 instead **throws** `ApiException(503)` (`:249-259`) — loud | **YES** (declared out of scope by #877) |
| 4 | `MarketOiClient.frontContractBasis` (live scalper `basis` dot) | `.../scalper/MarketOiClient.java:464-474`, `:626-629` | `null` basis ⇒ `ScalperGates.futuresBasis` returns `GateOutcome.pass(null, "basis unavailable")` | **NO** — `get()` (`:970-1001`) catches everything and returns the same `null` fallback |
| 5 | `FuturesDigestService.termStructure` (market-data, in-process) | `services/market-data-service/.../context/FuturesDigestService.java:371-380` | `nearBasis = null`; **already guards `ts.contracts().isEmpty()` defensively** | **NO** — `catch (RuntimeException)` ⇒ null + a reason |

Two observations worth keeping:

- **Consumer 4 is NOT a new exposure.** Because `MarketOiClient.get` maps *every* outcome — 404, 503,
  I/O error, empty body, empty array — to the same `null` fallback, the `basis` dot already degrades
  to a soft PASS on any upstream problem. An empty array adds nothing there. **So the whole question
  is consequential only on the universe-resolution path.** That materially narrows the blast radius
  from "a live money-path gate" to "the live load path" (which is worse, but different).
- **Consumer 5 already assumes empty is possible.** A file inside market-data itself codes
  defensively for `ts.contracts().isEmpty()`. The invariant is not universally believed even within
  the producing service.

---

## 3. Reachability — the producer cannot emit it

### 3.1 Every return path traced

`computed`. `FuturesTermStructureService.termStructure(String)` has exactly four exits, and **none**
can yield a non-throwing result with an empty `contracts`:

| # | file:line | condition | result |
|---|---|---|---|
| 1 | `:102-105` | `ladder.isEmpty()` (no active, non-expired FUT row) | **throws** `NotFoundException(NOT_FOUND_INSTRUMENT)` ⇒ HTTP 404 |
| 2 | `:117-119`, `:121-123` | quote call throws, or no spot quote | `staleFallback` |
| 3 | `:135-137` | `legs.isEmpty()` (ladder present but no leg got a quote) | `staleFallback` |
| 4 | `:138-148` | otherwise | `legs` is **non-empty by the `:135` guard**, and only this path reaches `lastGood.put` (`:147`) |

And `staleFallback` (`:183-191`) either **throws** `ApiException(503, DATA_STALE)` when
`lastGood` has no entry, or returns `cached.contracts()` — where `cached` can only have been written
at `:147`, i.e. downstream of the `:135` non-empty guard. **The cache cannot hold an empty
structure.** `withFreshness` (`:70-72`) and the controller (`:29`) copy the list unchanged.

Therefore `contracts: []` is **not producible**, and `FuturesUniverseResolver.java:93-96` is
**dead code with respect to the real producer**.

`computed`. **And it has never been producible.** Exactly five commits have ever touched
`FuturesTermStructureService.java` (`git log --follow`), and **all three load-bearing guards are
present in every one of them**, starting with the original Phase 15A implementation:

| commit | `ladder.isEmpty()` ⇒ 404 | `legs.isEmpty()` ⇒ `staleFallback` | `lastGood.put` below both |
|---|---|---|---|
| `8104bcaa` (Phase 15A, original) | `:81-82` | `:114` | `:126` |
| `50a6c7f7` (stage-B audit gaps) | `:82-83` | `:115` | `:127` |
| `5d8afd78` (#777 freshness envelope) | `:101-102` | `:134` | `:146` |
| `4077fc33` (#1001 nullable sweep) | `:102-103` | `:135` | `:147` |
| `76339112` (#1210 decimal-as-string) | `:102-103` | `:135` | `:147` |

So the chip is **hypothetical residue, not residue of a past occurrence** — nobody has ever seen this
happen, because at no point in the repo's history could it have.

### 3.2 Live probe

`computed`. Both live INDEX roots return a full 3-leg ladder, `stale: false`:

```
docker exec ay-market-data-service sh -c 'wget -qO- "http://127.0.0.1:8081/api/v1/market/futures/term-structure?underlying=NIFTY%2050"'
→ {"underlying":"NIFTY 50","spot":"24583.65","state":"CONTANGO","calendarSpread":"120","stale":false,
   "asOf":"2026-08-03T13:21:03.33+05:30","contracts":[
     {"tradingsymbol":"NIFTY26AUGFUT","expiry":"2026-08-25",...},
     {"tradingsymbol":"NIFTY26SEPFUT","expiry":"2026-09-29",...},
     {"tradingsymbol":"NIFTY26OCTFUT","expiry":"2026-10-27",...}], ...}

… underlying=SENSEX
→ {"underlying":"SENSEX","spot":"78703.45","state":"CONTANGO","stale":false, "contracts":[
     SENSEX26AUGFUT (2026-08-27), SENSEX26SEPFUT (2026-09-24), SENSEX26OCTFUT (2026-10-29)], ...}
```

### 3.3 Nothing pins the invariant — on either side

`computed`. This is the finding that gives the chip its remaining force.

- **Producer side:** there is **no `FuturesTermStructureServiceTest`**. The only endpoint test is
  `FuturesSliceIntegrationTest:113-127`, which asserts `$.contracts.length()` **is 3** for a healthy
  mock ladder. Nothing asserts "never empty" as a contract, and nothing exercises the
  ladder-empty ⇒ 404 rule at the HTTP boundary.
- **Consumer side:** `FuturesUniverseResolverTest` covers 503 ⇒ unresolved (`:47`), index 404 ⇒
  unresolved (`:58`), screener 404 ⇒ skip (`:76`), and non-array `contracts` ⇒ unresolved (`:104`).
  **No test drives `{"contracts":[]}` at all.** The branch's behaviour is pure assumption on both
  sides of the wire.

So the premise in #877's body ("market-data never returns 200-with-empty-contracts") is true, load-bearing,
cross-service, and **unenforced**. The plausible future change that breaks it is not exotic: making the
term-structure read fail-soft instead of throwing is the pattern the *same repo* already uses one
directory over (`FuturesDigestService:370` — "fail-soft to null + a reason (never a 5xx)").

---

## 4. What happens live today if it did fire

### 4.1 Blast radius: 38 of 38

`computed`. Every enabled+published live strategy resolves its signal series through the branch in
question.

```sql
SELECT v.config->'universe'->>'mode' AS mode, count(*)
FROM strategy.strategies s JOIN strategy.strategy_versions v ON v.id = s.published_version_id
WHERE s.enabled AND s.published_version_id IS NOT NULL GROUP BY 1 ORDER BY 2 DESC;
```
```
         mode          | count
-----------------------+-------
 options_of_underlying |    38
 minervini_funnel      |     4
 manas_arora_funnel    |     2
```

```sql
SELECT DISTINCT
  coalesce(v.config->'universe'->'signal_underlying'->>'tradingsymbol',
           v.config->'universe'->'underlying'->>'tradingsymbol') AS resolved_index,
  coalesce(v.config->'universe'->'signal_underlying'->>'exchange',
           v.config->'universe'->'underlying'->>'exchange') AS exch, count(*) AS strategies
FROM strategy.strategies s JOIN strategy.strategy_versions v ON v.id = s.published_version_id
WHERE s.enabled AND v.config->'universe'->>'mode' = 'options_of_underlying' GROUP BY 1,2 ORDER BY 3 DESC;
```
```
 resolved_index | exch | strategies
----------------+------+------------
 NIFTY-FUT-CONT | NFO  |         38
```

`computed`. `ScalperConfig.signalIndex` (`.../scalper/ScalperConfig.java:315-323`) maps that
continuous symbol through `FUT_CONT_INDEX` (`:304-307`) to the **INDEX ref** `NSE / NIFTY 50`, and
`SignalEngine:1181-1192` passes it to `futuresResolver.resolve(...)` — the 4-arg overload, i.e.
`missingInstrumentIsEmpty = false` (`FuturesUniverseResolver:64-67`). Confirmed against live logs,
which name the resolved underlying: `futures universe resolution failed for **NIFTY 50**`.

The remaining 6 (4 `minervini_funnel` + 2 `manas_arora_funnel`) never touch this path —
`NOT_APPLICABLE_SWING`. 44 enabled+published − 6 swing = **38**, which is exactly the `loaded` count
the engine reports (§4.2). No live strategy uses `futures_screener`, so the screener half of the
branch has zero live exposure.

### 4.2 The outcome would be silent

`computed`, tracing `SignalEngine`:

- `FuturesUniverseResolver:93-96` ⇒ `Optional.of(List.of())`
- `SignalEngine.fromResolver:1216-1223` ⇒ present ⇒ `resolvedUniverse`
- `SignalEngine.resolvedUniverse:1226-1232` ⇒ `instruments.isEmpty()` ⇒ **`RESOLVED_EMPTY`**
- `SignalEngine:866-875` ⇒ `log.info("… resolves to an empty universe — standing aside today")`,
  classification recorded, **`continue`** — so the strategy never enters `fresh`, is never
  subscribed, and emits nothing for the session.

The counters miss it by construction. `unresolvedDrops` is incremented **only** on `UNRESOLVED`
(`:847-853`); `load_errors` only on `LOAD_ERROR`. The ledger writes `fresh.size()` as `loaded`
(`EngineReloadLedger`, called at `SignalEngine:961`), so the *only* trace an empty INDEX ladder
leaves in the durable record is a **shrunken `loaded` with `unresolved = 0` and `load_errors = 0`** —
the same signature the 2026-07-15/16 outage had (`docs/signal-analysis/2026-07-16-session-findings.md`
§6.1: *"`signal engine loaded 0 published strategies` … engine inert for the day"*, with all three
recovery paths structurally blind).

Sharpening this: the ledger's **own documented health rule**, written into the V046 migration header,
is *"Health is unresolved = 0, NEVER loaded > 0"*. An empty INDEX ladder satisfies that rule exactly
— `unresolved` stays 0 — while the entire book is dark. The one durable health signal the repo
defined for this table is blind to this case by construction.

### 4.3 The armed watchdog is designed not to page on it

`computed`. `StrategyCoverageSnapshot.Classification.abnormal()`
(`.../signals/StrategyCoverageSnapshot.java:37-47`) returns **`false`** for
`RESOLVED, RESOLVED_EMPTY, NOT_APPLICABLE_SWING`. `StrategyCoverageWatchdog:118-120` skips every
non-abnormal classification before it can become a `STRATEGY_DARK` page, and a snapshot of nothing
but non-abnormal entries stays `HEALTHY` rather than `DEGRADED_TERMINAL` (`SignalEngine:1058-1068`).

`sourced`. The watchdog has been **ARMED live since 2026-08-01**
(`ARTHA_SIGNALS_STRATEGY_COVERAGE_WATCHDOG_MODE=ARMED`, ledger row G2 / #1035). So the exclusion is
in force today, exactly as its design doc §3.7 intends — with the chip's question still unanswered
underneath it.

**Net: an empty INDEX ladder is the one remaining way for the entire live book to go dark with every
counter reading healthy and the dedicated dark-strategy pager holding its fire.**

---

## 5. Has it ever happened live? No.

Two independent lines of evidence. Keep the history/live distinction in mind: this is a **live-only**
path (backtest replay reads the pinned registry snapshot and never re-resolves), so the
"historical OI is virtual" caveat does not apply — there is no derived-history variant of this
question.

### 5.1 The log line that fires at the branch: zero occurrences

`computed`. `FuturesUniverseResolver:94` and `:124` are the only two sites that emit
`no futures contracts for underlying {} — empty universe`.

⚠️ **The two sites emit a byte-identical string with opposite meanings** — `:94` is the empty-array
case (this chip), `:124` is the correct screener 404-skip. See §9.1: this evidence is only decisive
*because the count is zero*; a non-zero count could not have been attributed from the log alone.

```
docker logs ay-strategy-signal-service 2>&1 | grep -c "no futures contracts for underlying"   → 0
docker logs ay-strategy-signal-service 2>&1 | grep -c "malformed futures contracts response"  → 0
docker logs ay-strategy-signal-service 2>&1 | grep -c "futures universe resolution failed"    → 114
```

Window: `2026-08-02T14:07:46Z` → `2026-08-03T07:50:00Z` (container start to now; ~17.7 h, 2460 lines).
All 114 failures are the **INDEX** underlying and all are correctly the FAILURE class:

| n | message |
|---|---|
| 76 | `futures universe resolution failed for NIFTY 50: I/O error on GET request for "http://market-data-service:8081/api/v1/market/futures/term-structure": null` |
| 31 | `… 503 … {"code":"DATA_STALE","message":"no term structure available: no live Kite session…` |
| 7 | `… 503 … {"code":"DATA_STALE","message":"no term structure available: 403 Forbidden…` |

This is #877 working as designed: off-hours, with no Kite session, the producer 503s and the resolver
reports UNRESOLVED — never "empty".

⚠️ This window is short (container recreated 2026-08-02). It is the weaker of the two lines.

### 5.2 The durable ledger: 0 of 108 reloads show the signature

`computed`. `strategy.engine_reloads` (V046) is append-only — no retention or prune code exists
(`grep engine_reloads` over `services/` and `deploy/` returns only the writer, the migration, and a
grant), and the migration says so explicitly: *"a handful of rows per day … no retention needed at
this size"* (`deploy/flyway/strategy/V046__engine_reloads.sql`). It spans
**2026-07-25 14:49:48 → 2026-08-03 08:40:36 IST**, 108 rows.

```sql
SELECT loaded, unresolved, load_errors, count(*) AS reloads,
       min(reload_at AT TIME ZONE 'Asia/Kolkata') AS first_ist,
       max(reload_at AT TIME ZONE 'Asia/Kolkata') AS last_ist
FROM strategy.engine_reloads GROUP BY 1,2,3 ORDER BY 1 DESC, 2;
```
```
 loaded | unresolved | load_errors | reloads |         first_ist          |          last_ist
--------+------------+-------------+---------+----------------------------+----------------------------
     38 |          0 |           0 |      97 | 2026-07-25 22:11:58.140692 | 2026-08-03 08:40:36.840969
     37 |          1 |           0 |       1 | 2026-07-28 17:24:20.702857 | 2026-07-28 17:24:20.702857
      0 |         38 |           0 |      10 | 2026-07-25 14:49:48.432638 | 2026-08-03 08:19:47.315862
```

Exactly three states, all fully accounted for: a complete load, one correctly-counted UNRESOLVED, and
ten all-unresolved off-hours reloads. The direct search for the silent-drop signature:

```sql
SELECT id, reload_at AT TIME ZONE 'Asia/Kolkata' AS ist, loaded, unresolved, load_errors, installed
FROM strategy.engine_reloads
WHERE loaded < 38 AND unresolved = 0 AND load_errors = 0 ORDER BY reload_at;
```
```
 id | ist | loaded | unresolved | load_errors | installed
----+-----+--------+------------+-------------+-----------
(0 rows)
```

And every **in-session** reload across the whole record:

```sql
SELECT reload_at AT TIME ZONE 'Asia/Kolkata' AS ist, loaded, unresolved, load_errors
FROM strategy.engine_reloads
WHERE (reload_at AT TIME ZONE 'Asia/Kolkata')::time BETWEEN '08:30' AND '15:30' ORDER BY reload_at;
```
20 rows: **17 × `38/0/0`**, and **3 × `0/38/0`** — all three on 2026-07-31 between 08:57:29 and
08:58:04, recovering to `38/0/0` at 08:59:16. That is the F10 cold-boot/Kite-login race recurring
**post-#877 and being counted correctly as 38 UNRESOLVED** rather than silently swallowed. Useful
positive control: the mechanism that would surface an empty ladder demonstrably works on the
adjacent failure mode.

⚠️ **Caveat, stated plainly:** this test detects a `RESOLVED_EMPTY` drop, but it cannot *attribute*
one — the other silent classifications (`NOT_LIVE_RESOLVABLE`, `MISSING_VERSION_ROW`,
`NO_BOUNDING_EXIT`, `NOT_ROLLABLE_PRIMARY`) shrink `loaded` the same way. Since the result is **zero
rows**, the ambiguity does not matter here: no strategy has been silently dropped by *any* mechanism
in the persisted record. Had there been hits, the ledger alone could not have told them apart.

### 5.3 The ladder itself has never come close to empty

`computed`. The upstream condition that *would* empty the ladder (and today produce a 404, not an
empty array) has not occurred:

```sql
SELECT tradingsymbol, expiry, is_active, updated_at AT TIME ZONE 'Asia/Kolkata' AS updated_ist
FROM marketdata.instruments
WHERE instrument_type='FUT' AND underlying_tradingsymbol IN ('NIFTY 50','SENSEX')
ORDER BY underlying_tradingsymbol, expiry;
```
```
  tradingsymbol  |   expiry   | is_active |        updated_ist
-----------------+------------+-----------+----------------------------
 NIFTY26JUNFUT   | 2026-06-30 | f         | 2026-07-01 12:57:42.899776
 NIFTY26JULFUT   | 2026-07-28 | f         | 2026-07-29 09:05:05.557919
 NIFTY26AUGFUT   | 2026-08-25 | t         | 2026-08-03 08:30:01.914783
 NIFTY26SEPFUT   | 2026-09-29 | t         | 2026-08-03 08:30:01.914783
 NIFTY26OCTFUT   | 2026-10-27 | t         | 2026-08-03 08:30:01.914783
 NIFTY-FUT-CONT  |            | t         | 2026-07-31 16:14:58.788207
 SENSEX26JUNFUT  | 2026-06-25 | f         | 2026-06-26 13:12:20.390946
 SENSEX26JULFUT  | 2026-07-30 | f         | 2026-07-31 09:05:06.535201
 SENSEX26AUGFUT  | 2026-08-27 | t         | 2026-08-03 08:30:01.914783
 SENSEX26SEPFUT  | 2026-09-24 | t         | 2026-08-03 08:30:01.914783
 SENSEX26OCTFUT  | 2026-10-29 | t         | 2026-08-03 08:30:01.914783
 SENSEX-FUT-CONT |            | t         | 2026-07-31 16:15:23.006592
```

Three healthy rungs per index at all times. Deactivation happens the **day after** expiry
(JUN expiry 06-30 → `is_active=f` on 07-01; JUL expiry 07-28 → 07-29), and the next month's contract
is listed **weeks ahead** (`first_seen_at`: SEP on 07-01, OCT on 07-29), so the ladder is never even
transiently short. The `*-FUT-CONT` synthetic rows are excluded by
`InstrumentRepository:291-301`'s `expiry IS NOT NULL` filter — worth noting, because if they were
*not* excluded a tombstoned NFO sync would leave a one-row ladder of an untradeable synthetic
instead of the 404 that correctly reports the fault.

---

## 6. Is empty ever LEGITIMATE?

**On an INDEX ladder: no state exists.** Enumerated, with the state's actual code path:

| candidate legitimate state | what actually happens | is it the empty-array branch? |
|---|---|---|
| market-data cold, chain not warmed | `staleFallback` with empty `lastGood` ⇒ **throws 503 `DATA_STALE`** (`:186`) | **No** — this is the 2026-07-15/16 outage's own path, already UNRESOLVED post-#877 |
| outside session / off-hours | `lastGood` served with `stale: true` and a **non-empty** list (`:188-190`); if cold, 503 | **No** |
| an underlying with no listed futures | `ladder.isEmpty()` ⇒ **404 `NOT_FOUND_INSTRUMENT`** (`:102-105`) | **No** — and inapplicable to NIFTY 50 / SENSEX, which always have listed futures |
| a screener-picked **stock** mover that is cash-only | 404 ⇒ `missingInstrumentIsEmpty` skip (`:121-126`) | **No** — a separate, already-correct branch |
| a screener that picked **no movers** | `resolveScreener` returns `Optional.of(out)` with `out` empty (`:190`) — the genuine `RESOLVED_EMPTY` T9 §3.7 protects | **No** — a different site entirely |
| **monthly index expiry day** | ladder filter is `!expiry.isBefore(today)` — **strict**, so the expiring contract is still a rung | **No** (§6.1) |

This is the crux of the recommendation: **every legitimate "nothing to trade" reaches the resolver
through a different door.** The genuine stand-aside T9 §3.7 exists to protect lives at
`FuturesUniverseResolver:190`, not at `:93`. Reclassifying `:93` therefore **cannot** fire on a
legitimate state — which is the specific failure mode the brief warned about ("a fault classification
that fires on a legitimate state is worse than silence").

### 6.1 Monthly-expiry suppression — trap checked, discharged

`computed`. The trap does not apply, for three independent reasons:

1. **Different code block.** Suppression lives in `MarketOiClient.oi()`, and the basis read carries an
   explicit exemption at `MarketOiClient.java:464`: *"Front-contract absolute futures basis (F − S) —
   price-derived, so NOT suppressed on a monthly expiry."* Universe resolution is a third path again.
2. **The ladder does not shrink on expiry day.** `FuturesTermStructureService:99` filters
   `!i.expiry().isBefore(today)` — strict — so on 2026-07-28 the JUL contract was still a rung, and
   `is_active` did not flip until 2026-07-29 (§5.3).
3. **Measured on the day itself.** 2026-07-28 was a **Tuesday** = the NIFTY monthly expiry
   (NSE last-Tuesday); 2026-07-30 was a **Thursday** = the SENSEX monthly expiry (BSE last-Thursday)
   — both confirmed via python `zoneinfo`/`date.strftime`. The in-session reloads on both days:

```sql
SELECT id, reload_at AT TIME ZONE 'Asia/Kolkata' AS ist, loaded, unresolved, load_errors
FROM strategy.engine_reloads
WHERE reload_at >= timestamptz '2026-07-28T00:00:00+05:30'
  AND reload_at <  timestamptz '2026-07-29T00:00:00+05:30' ORDER BY reload_at;
```
19 rows; the two in-session ones (`08:23:59`, `08:40:35`) are both `38 / 0 / 0`. The single
`37 / 1 / 0` row is at **17:24 IST — post-close**, and is a counted UNRESOLVED, not an empty.
2026-07-30's `08:40:36` reload is likewise `38 / 0 / 0`.

**No empty case found falls on a monthly expiry, because no empty case was found at all.**

---

## 7. Recommendation

### 7.1 The call

**FAULT on the INDEX path; unchanged (legitimate skip) on the screener path.** Concretely, extend the
existing `missingInstrumentIsEmpty` asymmetry — the flag #877 already introduced, reviewed, and
documented for the 404 case — to cover the empty-array case at `FuturesUniverseResolver:93-96`, so
that both "the ladder is missing" and "the ladder is present but empty" mean the same thing on the
index path and the same thing on the screener path. That is a ~2-line change with **no new concept**,
and it makes the resolver correct *independently of what market-data does*.

**Rank the two halves — the producer-side half is the higher-value one:**

1. **Pin the invariant where it can actually be violated** (highest value, zero production change):
   a producer-side test asserting that every non-throwing return of
   `FuturesTermStructureService.termStructure` carries a non-empty `contracts`, plus the
   ladder-empty ⇒ 404 rule at the HTTP boundary. Today **nothing on either side of the wire pins it**
   (§3.3). This test fails the day someone makes the term-structure read fail-soft — which is the
   only realistic way the branch becomes reachable.
2. **Flip the consumer branch** (defence in depth): the asymmetry above, plus the
   `{"contracts":[]}` test case that `FuturesUniverseResolverTest` has never had.
3. **Make the two `empty universe` log sites distinguishable** (cheapest of the three, and the only
   one that improves live diagnosis *today*): `:94` and `:124` currently emit a byte-identical string
   for opposite conditions (§9.1). Even if the owner declines (1) and (2), this one should land —
   the next person to see that line in a live log cannot tell an upstream fault from a correct skip.
   Note that (2) partly subsumes it: if `:94` becomes a fault it stops sharing the message anyway.

### 7.2 Tier: clean

- **No live behaviour change.** The branch is unreachable from the current producer (§3.1), never
  observed (§5), and no legitimate state routes through it (§6). Golden/parity hold **by
  construction** — the same argument #877 made and had verified: universe resolution is live-only,
  `libs/strategy-engine` is untouched, and backtest replay reads the pinned registry snapshot rather
  than re-resolving.
- **Not a money surface.** The one live-gate consumer (`MarketOiClient` → the `basis` dot) cannot
  distinguish empty from failure today and would not be touched (§2.2).
- **The failure direction is correct.** If the branch ever fires, UNRESOLVED means loud (counted,
  retried, `abnormal()`, paged by the armed watchdog) instead of silent. Over-alerting here is
  bounded by §6: there is no legitimate state to over-alert on.

### 7.3 The honest counter-argument

`CLAUDE.md` working principle #2 says *"no error handling for impossible cases"*, and by §3.1 this
case **is** impossible today. A reasonable owner could rule "leave it; the producer's contract is the
right place, so do (1) and skip (2)". That position is defensible and I would not argue hard against
it. What tips me to recommending both is the asymmetry of consequences: the change costs two lines
and no behaviour, while the un-changed branch's failure mode is *the entire live book going dark with
every counter green and the pager suppressed* — and the invariant protecting it is a cross-service
assumption that is currently written down only in a merged PR body and a code comment.

### 7.4 Also worth recording (do not bundle)

`UniverseResolver.resolveFutures` (`:171-181`, consumer #3) has the **same** silent-empty shape on
the **backtest submission** path: a 404/503 throws loudly (`:249-259`), but a 200-with-empty would pin
an empty universe and the backtest would run to completion with zero instruments and zero trades.
#877 explicitly scoped this out ("a different class, backtest submission-time pinning"). It is
equally unreachable and equally unpinned, but it is not a live path and it should get its own chip
rather than ride this one.

### 7.5 Register hygiene

Per the H8 row's own instruction — *"Give it a §4b row when it is next touched"* — this chip should
receive a §4b register row now that it has been investigated, so the register's `awk` scan can see it
in future enumerations.

---

## 8. Open doubts

1. **The log-line evidence covers ~18 hours, not the chip's lifetime.** `docker logs` starts at the
   container's 2026-08-02 recreate. §5.2's ledger evidence covers 2026-07-25 → 2026-08-03 (~9 days).
   **Nothing here speaks to 2026-07-16 → 2026-07-25**, between #877 merging and V046 landing. I found
   no persisted source for that gap. The structural argument (§3.1) is what actually carries the
   "never happened" claim; the empirical evidence corroborates it over a limited window and should
   not be cited alone.
2. ~~**§3.1 is a claim about one commit; was the producer ever able to emit empty?**~~
   **DISCHARGED** — all five commits that have ever touched the file carry all three guards (§3.1
   table). The residual doubt is narrower: `git log --follow` on one file would miss the case where
   the endpoint was previously served by a *different* class since deleted. I did not check for that.
3. **The §5.2 silent-drop test cannot attribute a hit** (stated inline). It is decisive only because
   the result is zero rows.
4. **`RESOLVED_EMPTY` being non-abnormal is correct for the screener and wrong-if-reachable for the
   index — and the classification cannot currently express that difference.** I recommend fixing this
   in the resolver (so the index case never *becomes* `RESOLVED_EMPTY`) rather than in
   `Classification.abnormal()`, because changing `abnormal()` would page on the legitimate
   no-movers stand-aside that T9 §3.7 deliberately protects. I did not explore whether a third
   classification would be cleaner; someone should sanity-check that before building.
5. **I did not test the recommended change.** This is a read-only investigation; no red-proof was
   run, and the claim that the fix is behaviour-neutral rests on the unreachability argument rather
   than on a measured before/after.
6. **`futures_screener` has zero published strategies today**, so the screener half of the asymmetry
   is untested in production and its correctness rests on the tests at
   `FuturesUniverseResolverTest:74-101` alone.
7. **The 30 id gaps in `engine_reloads`** (min_id 1, max_id 138, count 108) are consistent with
   `nextval` burns from rolled-back inserts, and no delete/retention code exists — but I did not
   prove the gaps are burns rather than deletions by some path I failed to grep.
8. **Carried from the §9 verification, unresolved there too:** (a) `.env` was not read — the check
   that strategy-signal resolves market-data directly rests on the var being absent from its compose
   block with no `env_file:`, which a container started outside compose could defeat; (b) nothing
   in-repo can produce a 200 body carrying `"contracts":[]`, but an infrastructure element *outside*
   the repo (a proxy or error page) cannot be ruled out from source; (c) the §3.1 argument is a
   construction-site enumeration (`grep "TermStructure("` plus every injection of the service), not a
   whole-file read of all ~70 files matching `TermStructure`; (d) the concurrency argument
   (`legs` fully built before `lastGood.put`, `ConcurrentHashMap` happens-before) is by inspection,
   not by stress test.

---

## 9. Independent verification

The load-bearing claim in §3.1 — *"the producer cannot emit a 200 with empty `contracts`"* — was
issued to a second agent as a **falsification** task (find any way it is false: alternate return
paths, `lastGood` poisoning, exception handlers or `ResponseBodyAdvice` mapping an error to 200,
profile-swapped beans, Jackson serialization, gateway interposition, and any fixture stubbing
`{"contracts":[]}`).

**Result: the claim held on all six checks**, each `computed` independently:

| # | check | verdict | key evidence |
|---|---|---|---|
| 1 | every return / `TermStructure` construction; can `lastGood` be poisoned? | **holds** | exactly three constructions (`:139` guarded by `:135-137`, `:188` copies cached, `:71` copies); **exactly one** `lastGood.put` (`:147`), downstream of the guard |
| 2 | any advice / handler / filter / circuit-breaker returning 200 | **holds** | repo-wide: one `@RestControllerAdvice` (`libs/common-web/servlet/.../GlobalExceptionHandler.java:34`, `:39-42` preserves the status), **zero** `ResponseBodyAdvice`; resilience4j wraps only Kite calls, no `@Recover`; the gateway route (`edge-gateway/.../application.yml:47-50`) has no `fallbackUri` |
| 3 | profile / `@ConditionalOn*` / alternate bean | **holds** | all three types are bare `@Service`/`@RestController`/`@Repository`, no second bean. `QuoteGateway` has 4 impls but all feed the same guarded code — `MockQuoteGateway:54-70` can return a *partial* map ⇒ `legs` empty ⇒ `staleFallback` ⇒ **503**, never 200-with-`[]` |
| 4 | Jackson rendering non-empty as `[]` | **holds** | the only platform customizer is `ArthaJacksonAutoConfiguration` (`BigDecimal`→string, IST `OffsetDateTime`); no list serializer, filter provider or mixin. A *null* list would render `null`, which the consumer's `!isArray()` branch already catches |
| 5 | is the caller direct-to-service (not via the SPA-serving gateway)? | **holds** | `strategy-signal/.../application.yml:41` → `${ARTHA_MARKETDATA_BASE_URL:http://market-data-service:8081}`; `deploy/docker-compose.yml` sets that var on **backtest-service only** (`:690`) and strategy-signal has no `env_file:` ⇒ resolves to the default, bypassing edge-gateway |
| 6 | any fixture stubbing `{"contracts":[]}` | **holds** | repo-wide grep: **zero hits**. Nobody has ever encoded a belief that it is reachable |

It also independently re-derived the git-history point in §3.1 (`git log -S`: all three guards landed in
`8104bcaa`, the endpoint's introducing commit).

### 9.1 What it falsified — and it matters

`computed`. The verification **did** falsify something: not the endpoint claim, but an *inference* the
investigation was relying on. `FuturesUniverseResolver` emits the **byte-identical** warning
`no futures contracts for underlying {} — empty universe` from **two** sites with **opposite**
meanings:

- `:94` — reachable only by a literal 200 `{"contracts":[]}` (the `:89` guard means `:93` can only be
  a zero-length `ArrayNode`) — the case this chip is about;
- `:124` — reached by a **404 `NOT_FOUND_INSTRUMENT`** on the screener path
  (`missingInstrumentIsEmpty = true`, passed at `:180`) — the intended, correct skip.

**The log string cannot distinguish them.** §5.1's evidence survives intact *only because the count is
zero* — zero of either site is still zero of `:94`. But had the count been non-zero, that line alone
could not have told the two apart, and the natural reading ("the endpoint returned 200 with `[]`")
would have been unsupported. This is a live-diagnosis trap in its own right, and it is the cheapest
concrete improvement to come out of this investigation (§7.1, item 3).
