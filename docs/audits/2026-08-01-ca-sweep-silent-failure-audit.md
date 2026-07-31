# Corporate-action sweep — two silent-failure paths (audit)

**Date:** 2026-08-01
**Scope:** `CorporateActionJob` (16:30 IST sweep) + `CandleQueryService.ensureCoverage`
**Type:** read-only audit. No production source was edited. Every recommended change is written
below as an exact, ready-to-apply patch.
**Baseline commit:** `c4c56bc3` (worktree `agent-acf9c0f122330a2ee`, cut from `origin/main`).
**Depends on:** PR **#1156** (`feat/ca-resume-failed-refresh`, OPEN at time of writing). Both
patches are written against the **post-#1156** tree; see §5.1 for the one merge hazard.

Both paths audited here were explicitly deferred by #1156's own cross-vendor review as
*"confirmed pre-existing, not blocking"* — this document is the follow-up they asked for.

---

## 0. Summary of verdicts

| # | Question | Verdict |
|---|---|---|
| **P1** | Can `prefetch` return normally with incomplete coverage, making `BASE_REBUILT` a lie? | **YES — confirmed in code.** One mechanism only: `gateway.fetch` returning an empty list. Every other failure mode throws. |
| **P1b** | Is `CandleQueryService:93-95`'s swallowing catch on the corporate-action path? | **NO.** The CA path calls `prefetch` → `ensureCoverage` directly and is *not* wrapped. Two other call sites *are*. |
| **P1c** | Can legitimately-empty and failed-but-empty be told apart in code? | **Partially, and cheaply — by page POSITION, not by page content.** An empty page followed by a non-empty page is anomalous; a leading run of empty pages is normal and happens on **100% of remediations**. |
| **P2** | Does a `sweepSymbol` failure make "found nothing" indistinguishable from "did nothing"? | **YES — confirmed in code.** `log.warn` only; no counter, no alert, no summary, and the Redis integrity key publishes success-shaped output either way. |

**Sweep scope, measured live (`artha`, 2026-08-01):** **22,496** symbols enter the loop; **1,403**
survive the bhavcopy gate (an upper bound on those reaching the Kite-diff — a symbol also needs
≥ 2 cached anchor closes, `CorporateActionJob.java:198`). A per-symbol alert is therefore not a
viable shape — see §4.2.

---

## 1. Path 1 — can `BASE_REBUILT` be a lie?

### 1.1 The checkpoint contract

`submitRemediation` (`CorporateActionJob.java:254-289`, post-#1156 `:300-…`) runs, in order:

```
purgeSymbol            → deletes EVERY cached bar for the symbol   (CandleRepository.java:527-560)
prefetch 1d, 7300d     → refreshAggregates = true  (no-op for 1d)  (CorporateActionJob.java:267-269)
prefetch 1m, 4400d     → refreshAggregates = false                 (CorporateActionJob.java:270-272)
updateStatus BASE_REBUILT                                          (CorporateActionJob.java:273)
refreshRebuiltAggregates                                           (CorporateActionJob.java:274)
```

`BASE_REBUILT` is not a progress marker. Post-#1156 it is a **capability grant**: it is a member of
`BASE_COMMITTED = {"BASE_REBUILT", "REFRESH_FAILED"}`, and membership is the sole gate on
`resumeOrAbandon` → `submitRefreshOnly`, which **skips purge and prefetch entirely** and
materialises five continuous aggregates over whatever base is on disk. #1156 makes that grant
*more* consequential, because it is now re-entered on up to `max-refresh-attempts` (default 3)
subsequent daily sweeps rather than only after a hard crash.

So the load-bearing assertion is: *by the time line 273 runs, the 1m base for this symbol is
complete over the rebackfill window.* Nothing verifies it.

### 1.2 The exhaustive throw-vs-return-empty trace

`ensureCoverage` (`CandleQueryService.java:126-170`) — the fetch loop, verbatim:

```java
for (GapDetector.Gap gap : gaps) {
  for (GapDetector.Gap page : GapDetector.pages(gap)) {
    List<HistoricalCandleGateway.Candle> fetched =
        gateway.fetch(key, baseInterval, page.from().toInstant(), page.to().toInstant());
    if (!fetched.isEmpty()) {            // CandleQueryService.java:152
      repository.upsertAuthoritativeAll(...);
      backfilled = true;
    }
  }
}
```

There is no `catch` inside this method, so a throw propagates out of `prefetch`, out of the
`submitRemediation` lambda, into its `catch (Exception e)` — where post-#1156 `recordFailure`
reads the status back, finds `REBACKFILL_RUNNING` (∉ `BASE_COMMITTED`), and writes plain terminal
`FAILED` with an **urgent** ntfy. That is the correct, loud outcome. **The throwing paths are
handled; only the empty return is silent.**

Every exit of the live Kite gateway (`LiveHistoricalCandleGateway.java:65-164`):

| Condition | Site | Outcome |
|---|---|---|
| No live Kite session | `:66-72` | **throws** `ApiException 401 KITE_TOKEN_EXPIRED` |
| Instrument not resolvable | `:73-79` | **throws** `NotFoundException` |
| Interval ∉ {1m, 1d} | `:84` | **throws** `IllegalArgumentException` |
| Local limiter saturated (after the 5 s queue) | `KiteCallExecutor.java:163-165` | **throws** `ApiException 429 RATE_LIMIT_LOCAL` |
| Breaker open | `KiteCallExecutor.java:166-168` | **throws** `ApiException 503 KITE_CIRCUIT_OPEN` |
| Kite HTTP 429 | `:111-116` | `KiteRateLimitedException` → retried ×4 honouring `Retry-After` → **throws** if exhausted |
| Kite HTTP 5xx | `:117-122` | `HttpServerErrorException` → retryable → **throws** if exhausted |
| Kite HTTP 4xx (≠429) | RestClient default | **throws** `HttpClientErrorException`, never retried (`KiteCallExecutor.java:127`) |
| Body unparseable | `:160-163` | **throws** `ApiException 502 KITE_UPSTREAM_ERROR` |
| **HTTP 2xx, `data == null` or `data.candles == null`** | **`:142-144`** | **returns `List.of()` — SILENT** |
| **HTTP 2xx, `candles: []`** | **`:145` loop body never entered** | **returns empty — SILENT** |

Confirmed: no decorator wraps the gateway bean (`LiveKiteConfig.java:280`,
`MockKitePorts.java:58`, `OpenAlgoConfig.java:59` — three mutually-exclusive impls, no delegate).
The OpenAlgo impl has the identical shape (`OpenAlgoHistoricalCandleGateway.java:105-107`).

**So the original trace is correct.** An empty page fills nothing, logs nothing, counts nothing,
does not set `backfilled`, and the loop proceeds to the next page. `ensureCoverage` returns
normally. `BASE_REBUILT` is written.

One correction to the framing, in the direction of *worse*: `backfilled` at
`CandleQueryService.java:147/162` is a single boolean across **all** pages. If any one page
returns data, `backfilled` is `true` — so even the sole existing signal cannot distinguish
"74 of 74 pages landed" from "1 of 74 landed". It is not used as a coverage assertion today
(only to decide whether to refresh aggregates, `:166`), but it is the nearest thing to one and
it carries no information.

### 1.3 Legitimately empty vs failed-but-empty — the crux

This distinction is where a naive fix fails, so it is worth being exact.

**Legitimately empty is not an edge case — it is the common case, on every single remediation.**

The 1m rebackfill window is `today − 4400 days` (`CorporateActionJob.java:103`, ≈ 12.05 years).
Kite's minute history does not go back that far. Measured live, read-only, for a symbol whose
latest event is `FAILED`:

```
NSE:ABBOTINDIA  oldest 1m bucket = 2015-02-02
```

Its event was detected 2026-07-24, so its window opened **2014-07-07**. That is **210 days**
before any minute bar Kite will ever serve — and at `GapDetector.MAX_PAGE = 60 days`
(`GapDetector.java:33`) that is **4 pages that come back empty, correctly, every time**, out of
**74** pages in the window. `TradingBuckets.minuteBuckets` still enumerates those buckets
(2014-15 is outside the bundled calendar, so `isTradingDay` degrades to weekday-only,
`TradingBuckets.java:66-74`), `GapDetector` still marks them missing, and `ensureCoverage` still
fetches them.

Legitimately-empty pages therefore arise from:
- **pre-Kite-epoch** (~2015-02) — universal, ~4 pages per remediation;
- **pre-listing** for anything newer than 2015 — much larger, symbol-specific;
- **post-delisting / long suspension** — trailing or interior;
- a page entirely inside holidays — impossible at 60-day granularity.

Failed-but-empty is much narrower than the throw table suggests. From the trace above, a *failure*
that reaches the caller as `2xx + no candles` requires Kite to answer **HTTP 200 with an error or
empty body**. There is exactly one code-level way this can pass unnoticed:

> **`KiteHistoricalResponse.status` is captured and never asserted.** The record declares
> `status` (`KiteHistoricalResponse.java:15`) and `parse` never reads it
> (`LiveHistoricalCandleGateway.java:138-164`). A `200 {"status":"error", ...}` — or any 200 whose
> `data` block is absent — degrades to an empty list instead of an error.

Whether Kite ever actually emits that shape I **cannot confirm without a live capture** (see §6).
Documented Kite behaviour is a non-2xx status for errors, which the table above handles loudly. So
the honest position is: *failed-but-empty is a narrow, unproven-but-unguarded window, and the
`status` field is the cheap place to close it.*

**The discriminator that does work is position, not content.** After `purgeSymbol`
(`CandleRepository.java:527` — deletes the symbol's entire bar span, all intervals),
`presentBuckets` returns nothing, so `GapDetector.gaps` produces exactly **one contiguous gap**
spanning the whole window, and `GapDetector.pages` splits it into a **strictly ascending**
sequence. In that sequence:

- a **leading** run of empty pages = pre-history. Normal. ~4 pages minimum, always.
- a **trailing** run of empty pages = delisting / range end. Normal.
- an **interior** empty page — one that is *followed by* a non-empty page — means Kite served bars
  on both sides of a 60-day window it declined to serve. That is anomalous.

Residual false positives for the interior rule: a trading suspension spanning a full 60-day page,
and a genuine hole in Kite's own minute history. Both are rare enough to page on, and neither is
nightly. Residual false **negatives**: a page that returns *some* bars but fewer than it should is
invisible to this test — it catches whole-page loss, not partial-page truncation (§6, doubt 2).

### 1.4 Is the corporate-action path affected by the `CandleQueryService:93-95` swallow?

**No.** Measured by call-site enumeration:

| Caller | Route | Failure handling |
|---|---|---|
| `CandleQueryService.read` `:93` | `ensureCoverage` (5-arg) | **swallowed** → `log.warn` + `stale = true` (`:94-99`) |
| `CandleQueryService.prefetch` `:225` | `ensureCoverage` (6-arg) | **propagates** to caller |
| `CorporateActionJob:267,270` | `queryService.prefetch` **directly** | **propagates** → `submitRemediation` catch → `FAILED` |
| `GapBackfillService:44` | `queryService.prefetch` | **swallowed** → `log.warn` (`GapBackfillService.java:50-52`) |
| `EodBackfillJob:68-69` | `GapBackfiller.prefetch` → `GapBackfillService` | **swallowed** (same site) |

So there are **three** independent swallow sites in this area — `CandleQueryService:94`,
`GapBackfillService:37`, `GapBackfillService:50` — and the corporate-action remediation is on
**none** of them. This is one place where the pre-audit reading was pessimistic: the CA path's
*throwing* behaviour is already correct end to end.

(`GapBackfillService:50` swallowing the EOD prefetch is its own latent hole — a systemically
failing nightly EOD prefetch is a `log.warn` per symbol with no counter. Out of scope here;
flagged for a separate chip.)

### 1.5 What a correct checkpoint should assert — and what it must not

Four candidate shapes were evaluated. **The expensive-but-thorough one is not merely expensive,
it is wrong.**

**Option A — full post-prefetch gap re-check over the same range. REJECTED.**
Cost first: `presentBuckets` (`CandleRepository.java:335-346`) materialises every present bucket
in the range as an `OffsetDateTime`. Over 4400 days that is ≈ **3,012 sessions × 375 min ≈ 1.13 M**
expected buckets, with `GapDetector.gaps` building a second `HashSet` of ~1.08 M more
(`GapDetector.java:57-59`) plus the `expected` list — order **3 M live objects and ~1 M scanned
rows per re-check**. That is not a new *class* of cost (`ensureCoverage` already pays it once,
`:138-140`) but it doubles it, on the exact code path whose memory pressure OOM-killed live
Postgres three times and is the reason the kill switch at `CorporateActionJob.java:48-51` exists.
While auditing this, a single unbounded aggregate over `marketdata.candles` in the live container
crashed a backend and put the database into recovery mode — the fragility is current, not
historical.

But the disqualifying problem is correctness, not cost: **a full re-check would fail on 100% of
remediations.** `GapDetector` would re-report the 210-day pre-Kite-epoch stretch as a gap, because
nothing in the codebase models "Kite has no minute data before ~2015-02" or per-symbol listing
dates. A checkpoint that refuses `BASE_REBUILT` every time is not a safety property; it is an
outage. Making it work would require building a pre-history model first — a materially larger
change than the defect warrants.

**Option B — row-count / min-max sanity probe. REJECTED.** There is no reference to compare
against: `purgeSymbol` destroys the pre-image, and a pre-purge `count(*)` is (a) itself a ~1 M-row
scan over compressed chunks and (b) unreliable, since the pre-existing base may already have been
partial. `min(bucket)` cannot discriminate either — a truncated fetch and a genuine listing date
look identical.

**Option C — sampled probe. REJECTED.** Sampling inherits Option A's pre-history blindness at
lower confidence. A sample that hits the 2014 stretch reports a false gap; a sample that misses a
60-day hole reports false health.

**Option D — record which pages came back empty, and refuse `BASE_REBUILT` only on an *interior*
empty page. RECOMMENDED.**
Cost: **zero extra DB work, zero extra Kite calls, zero extra memory** — three ints in a loop that
already runs. The information is already in hand at exactly the right moment and is simply
discarded. It ignores the pre-history prefix *by construction* rather than by a model, which is
what makes it both cheap and correct. Accuracy is discussed in §1.3.

Behaviour on trip: `ensureCoverage` does **not** throw (that would change `read()` and the EOD
path). It reports; the CA job decides, and throws from its own lambda. Post-#1156 that lands on
`recordFailure` → status is `REBACKFILL_RUNNING` → plain terminal **`FAILED`** + **urgent** ntfy —
which is exactly right: the base is *not* committed, refresh-only resume must never be granted,
and a human is paged. The symbol is left purged-and-partial either way; the change converts silent
corruption into a loud, correctly-classified failure. It does not repair anything, and should not
be described as if it does.

### 1.6 Patch 1 — exact

Two files. **Patch 1A does not conflict with #1156** (that PR does not touch
`CandleQueryService`). **Patch 1B is written against the post-#1156 `submitRemediation`.**

#### 1A — `services/market-data-service/src/main/java/in/arthayantra/marketdata/candles/CandleQueryService.java`

```diff
@@
   /** Read result with staleness markers (B-3 ladder step 3). */
   public record CandleRead(List<Candle> items, boolean stale, OffsetDateTime asOf) {}
 
+  /**
+   * What one coverage pass actually fetched. {@code interiorEmptyPages} counts pages that came
+   * back EMPTY but are followed by a page that came back with data — Kite served bars on both
+   * sides of a 60-day window it declined to serve, which is anomalous. A LEADING run of empty
+   * pages is not counted: it is pre-listing / pre-Kite-epoch history and occurs on every deep
+   * rebackfill (a 4400-day 1m window opens ~210 days before Kite's ~2015-02 minute epoch, so ~4
+   * of its 74 pages are legitimately empty every single time). A TRAILING run is not counted
+   * either — delisting, or the range end. Only the corporate-action rebuild consumes this; every
+   * other caller ignores it.
+   */
+  public record Coverage(int pagesAttempted, int pagesEmpty, int interiorEmptyPages) {}
+
@@
-  /** Coverage check + gap fetch for a base interval (1m or 1d), refreshing derived aggregates. */
-  void ensureCoverage(
+  /** Coverage check + gap fetch for a base interval (1m or 1d), refreshing derived aggregates. */
+  Coverage ensureCoverage(
       String exchange, String tradingsymbol, String baseInterval, OffsetDateTime from, OffsetDateTime to) {
-    ensureCoverage(exchange, tradingsymbol, baseInterval, from, to, true);
+    return ensureCoverage(exchange, tradingsymbol, baseInterval, from, to, true);
   }
@@
-  void ensureCoverage(
+  Coverage ensureCoverage(
       String exchange,
       String tradingsymbol,
       String baseInterval,
       OffsetDateTime from,
       OffsetDateTime to,
       boolean refreshAggregates) {
     OffsetDateTime now = OffsetDateTime.now(clock);
     OffsetDateTime capped = to.isAfter(now) ? now : to;
     if (!from.isBefore(capped)) {
-      return;
+      return new Coverage(0, 0, 0);
     }
     List<OffsetDateTime> present =
         repository.presentBuckets(exchange, tradingsymbol, baseInterval, from, capped);
     List<GapDetector.Gap> gaps = gapDetector.gaps(baseInterval, from, capped, present, now);
     if (gaps.isEmpty()) {
       cacheHits.increment();
-      return;
+      return new Coverage(0, 0, 0);
     }
     cacheMisses.increment();
     InstrumentKey key = new InstrumentKey(exchange, tradingsymbol);
     boolean backfilled = false;
+    // Pages arrive in ascending time order (one gap per contiguous missing run, each split
+    // oldest-first by GapDetector.pages), so "empty pages seen since the last page that had data"
+    // becomes an INTERIOR run the moment another page has data — and stays a harmless trailing
+    // run otherwise. `seenData` suppresses the leading pre-history run the same way.
+    int attempted = 0;
+    int empty = 0;
+    int interiorEmpty = 0;
+    int pendingEmpty = 0;
+    boolean seenData = false;
     for (GapDetector.Gap gap : gaps) {
       for (GapDetector.Gap page : GapDetector.pages(gap)) {
         List<HistoricalCandleGateway.Candle> fetched =
             gateway.fetch(key, baseInterval, page.from().toInstant(), page.to().toInstant());
-        if (!fetched.isEmpty()) {
+        attempted++;
+        if (fetched.isEmpty()) {
+          empty++;
+          if (seenData) {
+            pendingEmpty++;
+          }
+        } else {
+          interiorEmpty += pendingEmpty;
+          pendingEmpty = 0;
+          seenData = true;
           repository.upsertAuthoritativeAll(
               fetched.stream()
                   .map(
                       c ->
                           new Candle(
                               exchange, tradingsymbol, baseInterval, c.bucketStart(),
                               c.open(), c.high(), c.low(), c.close(), c.volume(), c.oi(),
                               fetchSource))
                   .toList());
           backfilled = true;
         }
       }
     }
     if (backfilled && baseInterval.equals("1m") && refreshAggregates) {
       // backfilled history behind a cagg watermark is invisible until explicitly refreshed
       repository.refreshDerivedAggregates(gaps.get(0).from(), gaps.get(gaps.size() - 1).to());
     }
+    return new Coverage(attempted, empty, interiorEmpty);
   }
@@
-  public void prefetch(
+  public Coverage prefetch(
       String exchange, String tradingsymbol, String baseInterval, OffsetDateTime from, OffsetDateTime to) {
-    prefetch(exchange, tradingsymbol, baseInterval, from, to, true);
+    return prefetch(exchange, tradingsymbol, baseInterval, from, to, true);
   }
@@
-  public void prefetch(
+  public Coverage prefetch(
       String exchange,
       String tradingsymbol,
       String baseInterval,
       OffsetDateTime from,
       OffsetDateTime to,
       boolean refreshAggregates) {
-    ensureCoverage(exchange, tradingsymbol, baseInterval, from, to, refreshAggregates);
+    return ensureCoverage(exchange, tradingsymbol, baseInterval, from, to, refreshAggregates);
   }
```

Note the `if (!fetched.isEmpty())` block is *inverted* into an `if/else`; the upsert body and the
`backfilled = true` line are unchanged and stay in the `else`.

**Existing callers are unaffected.** `read()` at `:93` and both test call sites
(`CorporateActionIntegrationTest.java:131-132`) invoke these as statements and simply ignore the
new return. ErrorProne's `CheckReturnValue` fires only on annotated APIs, so no new warning.

#### 1B — `services/market-data-service/src/main/java/in/arthayantra/marketdata/corporateactions/CorporateActionJob.java`

Applies inside `submitRemediation`, **post-#1156** (where the two lines after the 1m `prefetch`
read `events.updateStatus(id, "BASE_REBUILT");` then `refreshRebuiltAggregates(id, today, now);`):

```diff
@@ private void submitRemediation(UUID id, Instrument equity, LocalDate today) {
             queryService.prefetch(
                 equity.exchange(), equity.tradingsymbol(), "1d",
                 today.minusDays(rebackfillDays1d).atStartOfDay().atOffset(Ist.OFFSET), now);
-            queryService.prefetch(
-                equity.exchange(), equity.tradingsymbol(), "1m",
-                today.minusDays(rebackfillDays1m).atStartOfDay().atOffset(Ist.OFFSET), now, false);
+            CandleQueryService.Coverage base =
+                queryService.prefetch(
+                    equity.exchange(), equity.tradingsymbol(), "1m",
+                    today.minusDays(rebackfillDays1m).atStartOfDay().atOffset(Ist.OFFSET), now, false);
+            // BASE_REBUILT is a CAPABILITY GRANT, not a progress marker: it authorises a later
+            // refresh-only resume that skips purge + prefetch and materialises five caggs over
+            // whatever base is on disk. Every fetch FAILURE throws and lands in the catch below,
+            // but an EMPTY page fills nothing, logs nothing and returns normally — so without this
+            // guard a partly-fetched base is checkpointed as committed, and the resume quietly
+            // materialises aggregates over it. A leading run of empty pages is normal (a 4400-day
+            // window opens ~210 days before Kite's ~2015-02 minute epoch); an INTERIOR empty page
+            // is not. Throwing here is deliberate: the base is NOT committed, so recordFailure
+            // writes plain terminal FAILED — never a resumable state.
+            if (base.interiorEmptyPages() > 0) {
+              throw new IllegalStateException(
+                  "1m rebackfill incomplete: "
+                      + base.interiorEmptyPages()
+                      + " of "
+                      + base.pagesAttempted()
+                      + " pages returned no bars mid-window — refusing BASE_REBUILT");
+            }
             events.updateStatus(id, "BASE_REBUILT");
```

Requires the import `in.arthayantra.marketdata.candles.CandleQueryService` — **already present**
at `CorporateActionJob.java:7`.

#### 1C — optional, separate: assert Kite's `status` field

Closes the one code-level route by which a *failure* can present as `2xx + no candles`. Small,
independent of the above, and carries its own (low) live risk — if Kite ever omits `status` on a
success this would start throwing, so the guard is deliberately written to fire only on a
**present and non-success** value.

`services/market-data-service/src/main/java/in/arthayantra/marketdata/kite/live/LiveHistoricalCandleGateway.java`:

```diff
   private List<Candle> parse(InstrumentKey key, String interval, String body) {
     try {
       KiteHistoricalResponse response = objectMapper.readValue(body, KiteHistoricalResponse.class);
       List<Candle> out = new ArrayList<>();
+      // `status` was mirrored from the wire and never read, so a 200 carrying {"status":"error"}
+      // (or any 200 with no `data` block) degraded to an EMPTY candle list — indistinguishable
+      // from a genuinely empty range, and silently accepted by every coverage caller. Only a
+      // PRESENT non-success value is treated as an error: a success that omits the field must
+      // keep working, since the live feed must never become coupled to Kite's exact shape.
+      if (response.status() != null && !"success".equals(response.status())) {
+        throw new ApiException(
+            502, ErrorCodes.KITE_UPSTREAM_ERROR, "historical status=" + response.status());
+      }
       if (response.data() == null || response.data().candles() == null) {
         return out;
       }
```

⚠️ The surrounding `catch (Exception e)` at `:160` would re-wrap this `ApiException` into another
`ApiException 502`; harmless (still loud, still a throw) but the message nests. If that matters,
add `catch (ApiException rethrow) { throw rethrow; }` ahead of it.

---

## 2. Path 1 — what would confirm the parts I could not execute

- **Whether Kite ever answers 200 + empty for a failure.** Confirmable by a WireMock case in
  `LiveHistoricalCandleGatewayTest`, and definitively by capturing raw bodies from a live
  rate-limited/erroring window. Untested here.
- **Whether any currently-`FAILED` symbol actually has a truncated 1m base.** The natural query
  (per-symbol `count`/`min`/`max` over `marketdata.candles` at 1m for the 166 affected symbols) is
  precisely the unbounded aggregate that crashed the backend during this audit. It needs a
  chunk-pruned, symbol-at-a-time script run off-hours, not an ad-hoc query. **Not established.**

---

## 3. Incidental findings (not in scope, recorded)

Measured live, read-only, `marketdata.corporate_action_events` (2026-06-23 → 2026-07-31):

| Fact | Value |
|---|---|
| Total event rows | 448 |
| Distinct symbols | 190 |
| Rows by status | `DETECTED` 248 · `FAILED` 175 · `RESOLVED` 25 |
| **Symbols whose LATEST event is `FAILED`** | **166** |
| Symbols whose latest event is `RESOLVED` | 24 |

Two things stand out.

**(a) The stranded population is 166 symbols, not 13.** #1156's PR body reports 13, measured as a
cagg-row deficit against a control. By *event state* — the thing #1156's resume gate reads — 166
symbols sit at terminal `FAILED`. #1156 deliberately does not auto-promote them (correctly: their
phase was destroyed by the old single-column overwrite). Whatever the re-arming procedure ends up
being, it is sized at ~166, and the two numbers should not be conflated in the ledger.

**(b) 248 rows sit at `DETECTED` and never moved.** A `DETECTED` row means `submitRemediation`'s
task never reached its first statement (`updateStatus(id, "REBACKFILL_RUNNING")`). The executor is
a single unbounded in-memory queue (`CorporateActionJob.java:79-85`), so every queued remediation
is lost on restart. `DETECTED` is not in `BASE_COMMITTED`, so it is never resumed — but detection
*will* re-fire (the base was never rebuilt, so the cache still diverges), minting yet another
`DETECTED` row. That is consistent with 448 rows across 190 symbols. This is a **third**
silent-failure surface in the same class and deserves its own chip.

---

## 4. Path 2 — `sweepSymbol` failures vanish into a WARN

### 4.1 Confirmed

`CorporateActionJob.java:141-155`:

```java
for (Instrument equity : scope) {
  try {
    sweepSymbol(equity, today).ifPresent(id -> { detections.add(id); detectedSymbols.add(...); });
  } catch (Exception e) {
    log.warn("corporate-action sweep failed for {}: {}", equity.tradingsymbol(), e.getMessage());
  }
}
publishIntegrity(detectedSymbols);
return detections;
```

Not-aborting on a per-symbol failure is correct and must stay. What is wrong is that the failure
leaves **no durable trace anywhere an operator looks**:

- no counter (the class registers `ay_corporate_action_anchor_noise_total` at `:122` and nothing else);
- no ntfy (the class *has* an ntfy path — `:245`, `:276`, `:283` — and never uses it for this);
- no sweep-level summary log;
- and `publishIntegrity` (`:338-349`) writes `{"lastRun": …, "detected": []}` — **success-shaped
  output** — whether the sweep checked 22,496 symbols cleanly or threw on every one of them.

The `GET /api/v1/system/status` rollup passes that JSON through as an **opaque string**
(`SystemStatusController.java:125`, `integrity == null ? "" : integrity`), so an operator reading
the status page sees `detected: []` in both worlds. That is the exact indistinguishability named
in the brief, and it is worse than "the log is quiet": the visible artefact positively asserts
health.

The originally-observed cause — a bare `ObjectMapper` throwing on a `LocalDate` in
`objectMapper.writeValueAsString(d.diverged())` (`:244`) — is indeed not live, since the injected
mapper (`:98`) is Spring's. But the swallow is general: `events.latestEvent` (`:173`),
`candles.hasNonBhavcopyDaily` (`:182`), `candles.closeAt`/`range` (`:190-193`), and
`gateway.fetch` (`:203`) all throw, and all land in the same `log.warn`. A Kite token expiry at
16:30 fails **up to 1,403** symbols with `ApiException 401` (only those that reach the fetch —
the rest short-circuit at the bhavcopy gate and return empty without error) — and reports success.

### 4.2 Choosing the shape — the symbol count matters

Measured live (`SET max_parallel_workers_per_gather = 0`, exact predicates from
`InstrumentRepository.java:321-330` and `CandleRepository.java:595-604`):

| Population | Count |
|---|---|
| `activeEquities()` — symbols entering the loop | **22,496** |
| …surviving `hasNonBhavcopyDaily` (upper bound on those reaching the Kite-diff) | **1,403** |

That settles the design:

- **Per-symbol alert — REJECTED.** A single systemic cause fires up to 22,496 ntfy pushes. This is
  the archetype of the over-alerting failure the brief warns about.
- **Counter only — INSUFFICIENT.** Correct and cheap, but nothing pushes; a nightly job's counter
  is only seen by someone already looking, and the whole defect is that nobody is looking.
- **Absolute-count threshold — REJECTED.** One chronically broken symbol (a delisted scrip with a
  malformed row) would page every night forever. That is precisely how an operator is trained to
  ignore a channel.
- **Rate threshold + one aggregate alert per sweep — RECOMMENDED.** It separates the two regimes
  cleanly: idiosyncratic failure is ~0.004 % (1 of 22,496) and never pages; a token expiry or DB
  outage is ~100 % and pages exactly once. Alert frequency is hard-capped at 1/day by the cron.

**Recommended combination — all three layers, because they answer different questions:**

1. `ay_corporate_action_sweep_symbols_total{result="ok"|"failed"}` — the time series. Follows the
   existing `ay_candle_cache_requests_total{result=…}` convention (`CandleQueryService.java:66-67`)
   and gives the rate directly, which a bare failure counter cannot.
2. `attempted` + `failed` in the integrity JSON — makes the status page **stop lying**. Free: the
   gateway forwards the blob opaquely, so no typed record changes and **no contract-spec drift**.
   This is the highest-value line in the patch and costs nothing.
3. One aggregate ntfy at `urgent` past the threshold — the push. `urgent` is justified because a
   breach means the CA integrity job was effectively blind for the day, and the rate limit is one
   per 24 h.

Threshold as a **compile-time constant (1 %)**, not a `@Value`: it is a coarse systemic/idiosyncratic
discriminator, not a number anyone tunes, and a constant keeps the constructor signature untouched
— which matters because #1156 is concurrently editing that signature. (If ops later wants it
tunable, promote it to `@Value("${artha.corporate-actions.sweep-failure-alert-pct:1}")` following
the `max-refresh-attempts` precedent.)

### 4.3 Patch 2 — exact

`services/market-data-service/src/main/java/in/arthayantra/marketdata/corporateactions/CorporateActionJob.java`.
All three hunks anchor on lines **#1156 does not modify**, so this applies cleanly before or after it.

```diff
@@
   private static final List<Period> ANCHOR_OFFSETS =
       List.of(
           Period.ofDays(7), Period.ofMonths(1), Period.ofMonths(3), Period.ofMonths(6),
           Period.ofYears(1), Period.ofYears(2), Period.ofYears(3), Period.ofYears(5));
 
+  /**
+   * Fraction of the sweep (percent) that must fail before ONE aggregate alert goes out. A
+   * per-symbol alert is not an option: the scope is ~22.5k symbols, so a single systemic cause
+   * (an expired Kite session at 16:30, a DB blip) would push that many notifications. A flat
+   * count is no better in the other direction — one chronically broken scrip would page every
+   * night forever, which is how an operator learns to ignore the channel. A RATE separates the
+   * two: one bad symbol is ~0.004%, a token expiry is ~100%. Deliberately a constant, not a
+   * knob — it is a systemic/idiosyncratic discriminator, not a tuning parameter.
+   */
+  private static final int SWEEP_FAILURE_ALERT_PCT = 1;
+
@@
   private final List<String> symbolOverride;
   private final Counter anchorNoise;
+  private final Counter sweepOk;
+  private final Counter sweepFailed;
@@
     this.anchorNoise = meterRegistry.counter("ay_corporate_action_anchor_noise_total");
+    this.sweepOk = meterRegistry.counter("ay_corporate_action_sweep_symbols_total", "result", "ok");
+    this.sweepFailed =
+        meterRegistry.counter("ay_corporate_action_sweep_symbols_total", "result", "failed");
```

```diff
-  /** One synchronous detection sweep; remediation runs async per detection. */
+  /**
+   * One synchronous detection sweep; remediation runs async per detection.
+   *
+   * <p>A per-symbol failure must NOT abort the sweep — one unreadable scrip cannot be allowed to
+   * blind the other ~22.5k. But swallowing it to a WARN made "ran cleanly and found nothing"
+   * indistinguishable from "threw on every symbol and found nothing": both returned an empty list
+   * AND published a success-shaped integrity key, which the /api/v1/system/status rollup forwards
+   * verbatim. So the outcome is now COUNTED per symbol, CARRIED in the integrity payload, and —
+   * only past {@link #SWEEP_FAILURE_ALERT_PCT} — alerted ONCE for the whole sweep.
+   */
   public List<UUID> sweepNow() {
     LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
     List<Instrument> scope = sweepScope();
     List<UUID> detections = new ArrayList<>();
     List<String> detectedSymbols = new ArrayList<>();
+    int failed = 0;
     for (Instrument equity : scope) {
       try {
         sweepSymbol(equity, today)
             .ifPresent(
                 id -> {
                   detections.add(id);
                   detectedSymbols.add(equity.tradingsymbol());
                 });
+        sweepOk.increment();
       } catch (Exception e) {
+        failed++;
+        sweepFailed.increment();
         log.warn("corporate-action sweep failed for {}: {}", equity.tradingsymbol(), e.getMessage());
       }
     }
-    publishIntegrity(detectedSymbols);
+    int attempted = scope.size();
+    log.info(
+        "corporate-action sweep: {} attempted, {} failed, {} detected",
+        attempted, failed, detections.size());
+    publishIntegrity(detectedSymbols, attempted, failed);
+    alertOnSweepFailureRate(attempted, failed);
     return detections;
   }
+
+  /**
+   * ONE aggregate alert per sweep, and only past the rate threshold. Deliberately not per symbol
+   * and not on the first failure: the sweep is nightly, and a channel that pages for a single
+   * chronically broken scrip is a channel the operator stops reading. Past the threshold the CA
+   * integrity job was effectively blind for the day, which is what {@code urgent} is for; the cron
+   * caps this at one push per 24 h.
+   */
+  private void alertOnSweepFailureRate(int attempted, int failed) {
+    if (failed == 0 || attempted == 0 || failed * 100 < attempted * SWEEP_FAILURE_ALERT_PCT) {
+      return;
+    }
+    log.error("corporate-action sweep failed on {} of {} symbols", failed, attempted);
+    ntfy.send(
+        "Corporate action sweep DEGRADED",
+        "urgent",
+        failed
+            + " of "
+            + attempted
+            + " symbols threw during the 16:30 corporate-action sweep — detections for those"
+            + " symbols did NOT run; see market-data logs");
+  }
```

```diff
-  private void publishIntegrity(List<String> detected) {
+  /**
+   * The B-13 integrity key. {@code attempted}/{@code failed} ride along because {@code detected}
+   * alone cannot distinguish a clean sweep that found nothing from one that threw on every symbol
+   * — both publish an empty list. The gateway forwards this payload as an OPAQUE STRING
+   * (SystemStatusController), so added keys reach the status page with no typed-record change and
+   * no contract-spec drift.
+   */
+  private void publishIntegrity(List<String> detected, int attempted, int failed) {
     try {
       redis
           .opsForValue()
           .set(
               INTEGRITY_KEY,
               objectMapper.writeValueAsString(
-                  Map.of("lastRun", OffsetDateTime.now(clock).toString(), "detected", detected)));
+                  Map.of(
+                      "lastRun", OffsetDateTime.now(clock).toString(),
+                      "detected", detected,
+                      "attempted", attempted,
+                      "failed", failed)));
     } catch (Exception e) {
       log.warn("integrity key publish failed: {}", e.getMessage());
     }
   }
```

**Test impact — none expected.** `CorporateActionIntegrationTest` asserts exact ntfy counts
(`NTFY.verify(2, …)` at `:188`, `NTFY.verify(0, …)` at `:209`). Those runs have zero sweep
failures, so `alertOnSweepFailureRate` returns before sending and the counts are unchanged. The
integrity assertion at `:189` is `.contains("TCS")`, a substring test that survives added keys.

---

## 5. Merge and sequencing notes

### 5.1 ⚠️ #1156 is based on a pre-#1151 tree

`gh pr diff 1156` shows `refreshRebuiltAggregates` calling **`candles.refreshDerivedAggregates(...)`**,
but `origin/main` at `c4c56bc3` has **`candles.refreshDerivedAggregatesForRebuild(...)`**
(`CorporateActionJob.java:326`, introduced by #1151 `8b002f2d` to raise the per-DML decompression
cap). #1156 will conflict on exactly that line and **must be rebased**. If the conflict is resolved
by taking #1156's side, the raised decompression cap is silently lost and the CHEVIOT/ULTRACEMCO
abort class returns — while every test still passes. Worth calling out to whoever merges it.

### 5.2 Suggested order

1. Merge #1156 (rebased; verify §5.1).
2. Apply **Patch 2** — independent of Patch 1, lowest risk, and it is what makes the *next* sweep's
   real behaviour observable.
3. Apply **Patch 1A + 1B** together (1B does not compile without 1A).
4. Apply **Patch 1C** separately, with a WireMock case for the 200-with-`status:error` shape.
5. Separately chip: the 166-symbol re-arming procedure (§3a), the 248 orphaned `DETECTED` rows
   (§3b), and `GapBackfillService:50`'s silent EOD prefetch swallow (§1.4).

Each of Patch 1 and Patch 2 wants a regression test. Neither is written here (this is an audit),
but the seams are cheap: Patch 1 is unit-testable against a stub gateway returning
`[empty, data, empty, data]` (expect a throw and terminal `FAILED`) versus
`[empty, empty, data, data]` (expect `BASE_REBUILT`); Patch 2 is testable by injecting a repository
stub that throws for every symbol and asserting one ntfy plus a non-zero `failed` in the integrity
payload. Note the singleton-Testcontainers rule — never assert "exactly one row matches X".

---

## 6. Claims, labelled

| # | Claim | Label | Evidence |
|---|---|---|---|
| 1 | An empty `gateway.fetch` return is silently skipped; the method returns normally | `[sourced]` | `CandleQueryService.java:150-164` |
| 2 | No `catch` inside `ensureCoverage`'s fetch loop; a throw propagates | `[sourced]` | `CandleQueryService.java:126-170` |
| 3 | Empty return happens iff HTTP 2xx and `data`/`candles` absent or empty; all other conditions throw | `[sourced]` | `LiveHistoricalCandleGateway.java:65-164`, `KiteCallExecutor.java:156-170` |
| 4 | `KiteHistoricalResponse.status` is declared but never asserted | `[sourced]` | `KiteHistoricalResponse.java:15` vs `LiveHistoricalCandleGateway.java:138-164` |
| 5 | The CA remediation is **not** on the `CandleQueryService:93-95` swallow | `[sourced]` | `CorporateActionJob.java:267,270` → `CandleQueryService.java:225` |
| 6 | Three other swallow sites exist: `CandleQueryService:94`, `GapBackfillService:37`, `GapBackfillService:50` | `[sourced]` | those lines |
| 7 | `purgeSymbol` deletes the symbol's entire bar span ⇒ one contiguous gap ⇒ strictly ascending pages | `[sourced]` | `CandleRepository.java:527-560`, `GapDetector.java:64-99` |
| 8 | `NSE:ABBOTINDIA` oldest 1m bucket = **2015-02-02** | `[computed]` | live `psql`, index-bounded `ORDER BY bucket ASC LIMIT 1` |
| 9 | Its 4400-day window opens 2014-07-07 ⇒ **210 days / ~4 of 74 pages** legitimately empty | `[computed]` | date arithmetic on claim 8 + `CorporateActionJob.java:103`, `GapDetector.java:33` |
| 10 | A full re-check would re-report that stretch as a gap ⇒ fails on 100% of remediations | `[computed]` | claim 9 + `TradingBuckets.java:66-74` (weekday fallback enumerates 2014 buckets) |
| 11 | Full re-check ≈ **1.13 M expected buckets / ~3 M live objects** per pass | `[computed]` | 4400 d × 250 sessions/yr × 375 min; `CandleRepository.java:335-346`, `GapDetector.java:52-59` |
| 12 | `activeEquities()` = **22,496** | `[computed]` | live `psql`, predicate from `InstrumentRepository.java:321-330` |
| 13 | Symbols past the bhavcopy gate = **1,403** (upper bound on Kite-diff participants) | `[computed]` | live `psql`, predicate from `CandleRepository.java:595-604`; anchor gate at `CorporateActionJob.java:198` |
| 14 | 448 events / 190 symbols; `DETECTED` 248, `FAILED` 175, `RESOLVED` 25 | `[computed]` | live `psql` |
| 15 | **166** symbols have a terminal `FAILED` as their latest event (not 13) | `[computed]` | live `psql`, `DISTINCT ON … ORDER BY detected_at DESC` |
| 16 | The integrity JSON reaches the status page as an opaque string ⇒ no contract drift | `[sourced]` | `SystemStatusController.java:41,125` |
| 17 | Post-#1156 a throw before `BASE_REBUILT` yields plain terminal `FAILED` + urgent ntfy | `[sourced]` | `gh pr diff 1156` — `recordFailure`, `BASE_COMMITTED` |
| 18 | #1156 conflicts with #1151 on `refreshDerivedAggregatesForRebuild` | `[sourced]` | `gh pr diff 1156` vs `CorporateActionJob.java:326` @ `c4c56bc3` |
| 19 | ntfy exact-count assertions in the IT stay green under Patch 2 | `[sourced]` | `CorporateActionIntegrationTest.java:188,209` + threshold returns early at 0 failures |
| 20 | Kite emits non-2xx for API errors, so failed-but-empty is narrow | `[assumed]` | not verified against a live capture — see doubt 1 |
| 21 | Interior-empty pages are rare enough not to be nightly noise | `[assumed]` | reasoned from suspension frequency; **not measured** — see doubt 3 |
| 22 | Patches compile and the suites pass | `[assumed]` | **NOT BUILT OR RUN** — this is an audit; no production file was edited |

---

## 7. Open doubts

1. **What does Kite actually return under stress?** The whole legitimate-vs-failed distinction
   rests on "a Kite failure carries a non-2xx status." I did not execute a single live fetch. If
   Kite ever answers `200` with an empty `candles` array under throttling or a partial upstream
   outage, then failed-but-empty is *common*, not narrow — and interior-empty detection is the only
   thing standing between that and silent corruption. **Only a live capture settles this.** Patch 1C
   is cheap insurance either way.
2. **Partial-page truncation is completely uncovered.** A page returning 100 bars where 22,500 were
   expected sets `seenData`, clears `pendingEmpty`, and reads as healthy. If Kite truncates rather
   than empties, Patch 1B is blind to it. Detecting that needs a per-page expected-bucket count,
   which means threading `TradingBuckets` through `GapDetector.pages` — materially larger, and I do
   not have evidence it is warranted.
3. **The interior-empty false-positive rate is unmeasured.** I argued from first principles that
   60-day interior holes are rare; I did not count them. If NSE suspensions produce more than a
   handful a year, Patch 1B converts them into terminal `FAILED` rows — a *new* way to strand a
   symbol. Mitigation if it bites: log-only for one cycle before arming the throw.
4. **Whether the stranded 166 actually have truncated bases is unknown**, and it is the single
   most decision-relevant unknown here. It is the difference between "Path 1 is a latent risk" and
   "Path 1 already fired 166 times." The query needed is chunk-pruned and per symbol; the naive
   version crashed a live backend during this audit and must not be repeated.
5. **`SWEEP_FAILURE_ALERT_PCT = 1` is a judgement call, not a measurement.** At 22,496 symbols the
   trip point is ~225. I have no baseline for the sweep's *normal* failure count — if it routinely
   fails on, say, 300 delisted scrips, this alert fires every night from day one, which is the
   outcome the brief explicitly warns against. **Read the counter for one week before trusting the
   alert**; the counter and the integrity payload are useful immediately and independently.
6. **#1156 is still OPEN and under `REQUEST_CHANGES`.** Its final merged shape may differ from the
   diff I read. Patch 1B's anchor (the 1m `prefetch` call and the `BASE_REBUILT` write) is stable
   across both the current and the proposed text, but it should be re-checked against the merged
   commit before applying.
7. **I put the live database into recovery mode** with an unbounded aggregate over
   `marketdata.candles` while gathering §3's numbers. It self-recovered (verified), and all figures
   quoted here were re-read afterwards from the small `corporate_action_events` and `instruments`
   tables. But it is a real operational event during a read-only audit and is recorded rather than
   omitted.
