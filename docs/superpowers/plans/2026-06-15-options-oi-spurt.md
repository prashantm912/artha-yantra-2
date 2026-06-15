# Options OI-Spurt endpoint + OI-Interpretation badge — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: `superpowers:executing-plans`. Follow-on slice #1 of `2026-06-15-phase2-oi-analytics-backend.md` (the `/spurt` part) + the Phase-3 `<ay-oi-int-badge>` shared component from `2026-06-15-oipulse-parity-pages.md`. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Ship `GET /api/v1/market/options/spurt` (oipulse "Options OI Spurt") and surface the flagship **4-state OI-Interpretation badge** on the Options OI page, both driven by the universal control-bar contract.

**Architecture:** Spurt is an **interval delta**, so the read diffs the **latest two snapshot buckets** per `(strike, optionType)` — the stored `oi_change` column is Kite's *day* change and is wrong for an interval spurt. The same two-bucket read yields the underlying **spot price-Δ**, so the endpoint also returns a one-shot `SpurtSummary` (spot-dir × total-OI-dir → the 4-state). Backend reuses the existing `OiSpurtService.classify` primitive + `OptionsSnapshotReader`; frontend reuses `OiAnalyticsStore` + `SymbolContextStore` + `core/decimal` (all merged in #28/#29).

**Tech Stack:** Java 21 / Spring Boot · TimescaleDB `time_bucket`/`last()` · JUnit5 + AssertJ + Testcontainers (`MarketDataIntegrationTestBase`) · springdoc · Angular 21 zoneless + PrimeNG 21 `p-tag` · NgRx Signals · vitest.

---

## Conventions (already established — match exactly)

- Endpoints: `@RestController @RequestMapping("/api/v1/market/options")`; params `mode,name(req),date,interval,expiry`; `OiQuery.of(...)` validates; `requireExpiry(q)` 400s on null expiry; empty data → `throw new ApiException(422, ErrorCodes.DATA_GAP, ...)`.
- BigDecimal serializes as a JSON **string** (wire convention) — ITs assert `"1.50"`, frontend hand-types decimals as `string`.
- ITs are `*IntegrationTest`, extend `in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase`, share the singleton DB with NO cleanup → **unique underlying slug per test method**.
- New `@GetMapping` path DOES drift the OpenAPI spec → recapture + regen TS (Task 2).
- Frontend: decimals via `core/decimal`; per-loader **generation token** drops stale responses; `pi-*` icons only on `p-button`; `p-tag` is safe.

---

## Task 1 — Backend: `/spurt` endpoint (reader pair + rollup + controller)

### 1a. `OptionsSnapshotReader.latestPair()` — the two most-recent buckets

**Files:**
- Modify: `services/market-data-service/src/main/java/in/arthayantra/marketdata/options/analytics/OptionsSnapshotReader.java`
- Test: `services/market-data-service/src/test/java/in/arthayantra/marketdata/options/analytics/OptionsSnapshotReaderIntegrationTest.java`

- [ ] **Step 1: Failing IT** — append to `OptionsSnapshotReaderIntegrationTest`:

```java
  @Test
  void latestPairReturnsTwoMostRecentBuckets() {
    String u = "PAIRTEST";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime b0 = OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime b1 = b0.plusMinutes(5); // next 5-min bucket
    OffsetDateTime b2 = b0.plusMinutes(10); // newest 5-min bucket
    insertRow(jdbc, b0, u, exp, "22500", "CE", "100.00", 1000L, 0L);
    insertRow(jdbc, b1, u, exp, "22500", "CE", "110.00", 1200L, 0L);
    insertRow(jdbc, b2, u, exp, "22500", "CE", "130.00", 1500L, 0L);

    List<OptionsSnapshotReader.StrikePoint> pair = reader.latestPair(u, exp, OiInterval.M5);

    // exactly the two newest buckets (b1, b2) — b0 excluded
    assertThat(pair).extracting(p -> p.ltp().toPlainString()).containsExactly("110.00", "130.00");
  }
```

- [ ] **Step 2:** Run `mvnw.cmd -pl services/market-data-service -am test -Dtest=OptionsSnapshotReaderIntegrationTest` → FAIL (`latestPair` missing).

- [ ] **Step 3:** Add to `OptionsSnapshotReader` (after `latest`):

```java
  /**
   * Rows for the two most-recent snapshot buckets (newest + the prior captured bucket), used to
   * compute interval deltas (LTP-Δ, OI-Δ) for spurt. Robust to gaps: it picks the two most-recent
   * buckets that ACTUALLY hold data, not two wall-clock-adjacent slots. Empty if no snapshot; a
   * single bucket if only one exists (caller then has no prior to diff against).
   */
  public List<StrikePoint> latestPair(String underlying, LocalDate expiry, OiInterval interval) {
    List<OffsetDateTime> buckets =
        jdbc.query(
            "SELECT DISTINCT public.time_bucket(INTERVAL '"
                + interval.pgInterval()
                + "', ts, 'Asia/Kolkata') AS b "
                + "FROM options_chain_snapshots WHERE underlying = ? AND expiry = ? "
                + "ORDER BY b DESC LIMIT 2",
            (rs, n) -> rs.getObject("b", OffsetDateTime.class),
            underlying,
            java.sql.Date.valueOf(expiry));
    if (buckets.isEmpty()) {
      return List.of();
    }
    OffsetDateTime newest = buckets.get(0);
    OffsetDateTime earliest = buckets.get(buckets.size() - 1);
    return series(underlying, expiry, interval, earliest, newest.plus(interval.bucket()));
  }
```

- [ ] **Step 4:** Run the IT → PASS.

### 1b. `OiSpurtService.spurts()` — per-strike rows + underlying summary

**Files:**
- Modify: `services/market-data-service/src/main/java/in/arthayantra/marketdata/options/analytics/OiSpurtService.java`
- Test: `services/market-data-service/src/test/java/in/arthayantra/marketdata/options/analytics/OiSpurtServiceTest.java`

- [ ] **Step 1: Failing unit test** — append to `OiSpurtServiceTest`:

```java
  private static OptionsSnapshotReader.StrikePoint pt(
      java.time.OffsetDateTime b, String strike, String type, String ltp, long oi, String spot) {
    return new OptionsSnapshotReader.StrikePoint(
        b, new BigDecimal(strike), type, new BigDecimal(ltp), oi, 0L, null, new BigDecimal(spot));
  }

  @Test
  void spurtsDiffsLatestTwoBucketsPerStrikeAndRollsUpSummary() {
    java.time.OffsetDateTime b0 =
        java.time.OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, java.time.ZoneOffset.ofHoursMinutes(5, 30));
    java.time.OffsetDateTime b1 = b0.plusMinutes(5);
    // CE: ltp 100->110 (up), oi 1000->1200 (up) => LONG_BUILDUP, spurt +20.00%
    // PE: ltp 90->80 (down), oi 800->1000 (up) => SHORT_BUILDUP, spurt +25.00%
    // spot 22480 -> 22500 (up); total OI-Δ = +200 +200 = +400 (up) => summary LONG_BUILDUP
    java.util.List<OptionsSnapshotReader.StrikePoint> pair =
        java.util.List.of(
            pt(b0, "22500", "CE", "100", 1000, "22480"),
            pt(b1, "22500", "CE", "110", 1200, "22500"),
            pt(b0, "22500", "PE", "90", 800, "22480"),
            pt(b1, "22500", "PE", "80", 1000, "22500"));

    OiSpurtService.SpurtChain chain = new OiSpurtService().spurts(pair);

    assertThat(chain.items()).hasSize(2);
    OiSpurtService.StrikeSpurt ce =
        chain.items().stream().filter(r -> r.optionType().equals("CE")).findFirst().orElseThrow();
    assertThat(ce.interpretation()).isEqualTo(OiInterpretation.LONG_BUILDUP);
    assertThat(ce.oiChange()).isEqualTo(200L);
    assertThat(ce.spurtPct()).isEqualByComparingTo("20.00");
    assertThat(chain.summary().interpretation()).isEqualTo(OiInterpretation.LONG_BUILDUP);
    assertThat(chain.summary().spotDelta()).isEqualByComparingTo("20");
    assertThat(chain.summary().oiChange()).isEqualTo(400L);
    assertThat(chain.asOf()).isEqualTo(b1);
  }

  @Test
  void spurtsEmptyWhenOnlyOneBucket() {
    java.time.OffsetDateTime b0 =
        java.time.OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, java.time.ZoneOffset.ofHoursMinutes(5, 30));
    OiSpurtService.SpurtChain chain =
        new OiSpurtService().spurts(java.util.List.of(pt(b0, "22500", "CE", "100", 1000, "22480")));
    assertThat(chain.items()).isEmpty();
    assertThat(chain.summary()).isNull();
    assertThat(chain.asOf()).isEqualTo(b0);
  }
```

- [ ] **Step 2:** Run `mvnw.cmd -pl services/market-data-service -am test -Dtest=OiSpurtServiceTest` → FAIL.

- [ ] **Step 3:** Add to `OiSpurtService` (keep `classify` + `SpurtRow` unchanged):

```java
  /** One spurt row per (strike, optionType): interval LTP/OI deltas + 4-state classification. */
  public record StrikeSpurt(
      BigDecimal strike,
      String optionType,
      BigDecimal ltp,
      Long oi,
      long oiChange,
      BigDecimal spurtPct,
      OiInterpretation interpretation) {}

  /** Underlying rollup: spot direction × total OI-Δ direction → the 4-state badge. */
  public record SpurtSummary(OiInterpretation interpretation, BigDecimal spotDelta, long oiChange) {}

  public record SpurtChain(List<StrikeSpurt> items, SpurtSummary summary, OffsetDateTime asOf) {}

  /**
   * Fold the latest-two-bucket window (from {@link OptionsSnapshotReader#latestPair}) into spurt
   * rows: per (strike, optionType) diff the newest bucket against the prior one. Strikes present in
   * only the newest bucket are skipped (no interval signal). The summary diffs the underlying spot
   * and sums every strike's OI-Δ. {@code items} empty and {@code summary} null when there is no
   * prior bucket; {@code asOf} is always the newest bucket (or null when the window is empty).
   */
  public SpurtChain spurts(List<OptionsSnapshotReader.StrikePoint> pair) {
    OffsetDateTime newest =
        pair.stream().map(OptionsSnapshotReader.StrikePoint::bucket).max(Comparator.naturalOrder()).orElse(null);
    if (newest == null) {
      return new SpurtChain(List.of(), null, null);
    }
    OffsetDateTime prior =
        pair.stream()
            .map(OptionsSnapshotReader.StrikePoint::bucket)
            .filter(b -> b.isBefore(newest))
            .max(Comparator.naturalOrder())
            .orElse(null);
    if (prior == null) {
      return new SpurtChain(List.of(), null, newest);
    }
    Map<String, OptionsSnapshotReader.StrikePoint> newAt = new HashMap<>();
    Map<String, OptionsSnapshotReader.StrikePoint> oldAt = new HashMap<>();
    for (OptionsSnapshotReader.StrikePoint p : pair) {
      String key = p.strike().toPlainString() + p.optionType();
      if (p.bucket().equals(newest)) {
        newAt.put(key, p);
      } else if (p.bucket().equals(prior)) {
        oldAt.put(key, p);
      }
    }
    List<StrikeSpurt> items = new ArrayList<>();
    long totalOiDelta = 0;
    for (Map.Entry<String, OptionsSnapshotReader.StrikePoint> e : newAt.entrySet()) {
      OptionsSnapshotReader.StrikePoint cur = e.getValue();
      OptionsSnapshotReader.StrikePoint old = oldAt.get(e.getKey());
      if (old == null) {
        continue; // no prior bucket for this strike → no spurt
      }
      long priorOi = old.oi() == null ? 0 : old.oi();
      long curOi = cur.oi() == null ? 0 : cur.oi();
      long oiDelta = curOi - priorOi;
      BigDecimal ltpDelta =
          (cur.ltp() == null ? BigDecimal.ZERO : cur.ltp())
              .subtract(old.ltp() == null ? BigDecimal.ZERO : old.ltp());
      SpurtRow r = classify(ltpDelta, oiDelta, priorOi);
      items.add(
          new StrikeSpurt(
              cur.strike(), cur.optionType(), cur.ltp(), cur.oi(), oiDelta, r.spurtPct(),
              r.interpretation()));
      totalOiDelta += oiDelta;
    }
    items.sort(Comparator.comparing(StrikeSpurt::strike).thenComparing(StrikeSpurt::optionType));
    BigDecimal spotNew = pair.stream().filter(p -> p.bucket().equals(newest)).map(OptionsSnapshotReader.StrikePoint::spot).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    BigDecimal spotOld = pair.stream().filter(p -> p.bucket().equals(prior)).map(OptionsSnapshotReader.StrikePoint::spot).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    SpurtSummary summary = null;
    if (spotNew != null && spotOld != null) {
      BigDecimal spotDelta = spotNew.subtract(spotOld);
      summary = new SpurtSummary(OiInterpretation.classify(spotDelta, totalOiDelta), spotDelta, totalOiDelta);
    }
    return new SpurtChain(items, summary, newest);
  }
```

Add imports: `java.time.OffsetDateTime`, `java.util.ArrayList`, `java.util.Comparator`, `java.util.HashMap`, `java.util.List`, `java.util.Map`.

- [ ] **Step 4:** Run unit test → PASS.

### 1c. Controller `/spurt`

**Files:**
- Modify: `OptionsAnalyticsController.java` (inject `OiSpurtService`, add `@GetMapping("/spurt")`)
- Test: `OptionsAnalyticsControllerIntegrationTest.java`

- [ ] **Step 1: Failing IT** — append:

```java
  @Test
  void spurtReturnsRowsAndSummary() throws Exception {
    String u = "SPURTCTRL";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime b0 =
        OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime b1 = b0.plusMinutes(5);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b0, u, exp, "22500", "CE", "100", 1000L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b1, u, exp, "22500", "CE", "110", 1200L, 0L);

    mockMvc
        .perform(
            get("/api/v1/market/options/spurt")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].optionType").value("CE"))
        .andExpect(jsonPath("$.items[0].interpretation").value("LONG_BUILDUP"))
        .andExpect(jsonPath("$.items[0].oiChange").value(200))
        .andExpect(jsonPath("$.items[0].spurtPct").value("20.00"))
        .andExpect(jsonPath("$.summary.interpretation").value("LONG_BUILDUP"));
  }
```

(Note: `insertRow` hardcodes `spot_price` `22480.00`, identical in both buckets → `spotDelta` 0 → with OI up, `classify(0, +)` = LONG_BUILDUP. Assertion holds.)

- [ ] **Step 2:** Run `mvnw.cmd -pl services/market-data-service -am test -Dtest=OptionsAnalyticsControllerIntegrationTest` → FAIL (404).

- [ ] **Step 3:** In `OptionsAnalyticsController`: add `private final OiSpurtService spurt;` ctor param + assignment, then:

```java
  /** /spurt: oipulse Options OI Spurt — per-strike interval buildup + the underlying 4-state rollup. */
  @GetMapping("/spurt")
  public OiSpurtService.SpurtChain spurt(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry) {
    OiQuery q = OiQuery.of(mode, name, date, interval, expiry);
    LocalDate exp = requireExpiry(q);
    List<OptionsSnapshotReader.StrikePoint> pair = reader.latestPair(q.name(), exp, q.interval());
    if (pair.isEmpty()) {
      throw new ApiException(422, ErrorCodes.DATA_GAP, "no snapshot for " + q.name() + " " + exp);
    }
    return spurt.spurts(pair);
  }
```

- [ ] **Step 4:** Run the controller IT → PASS.

- [ ] **Step 5: Commit**

```
feat(market-data): options OI-spurt endpoint (interval buildup + 4-state rollup)
```

---

## Task 2 — Recapture OpenAPI contract + regen TS (PowerShell, repo root)

- [ ] **Step 1:** `mvnw.cmd -pl services/market-data-service -am test -Dtest=ContractCaptureTest "-Dcontracts.capture=true"`
- [ ] **Step 2:** `git diff --stat contracts/market-data-service.openapi.json` shows `/api/v1/market/options/spurt` added.
- [ ] **Step 3:** `npx -y openapi-typescript@7 contracts/market-data-service.openapi.json -o contracts/gen/market-data-service.d.ts`
- [ ] **Step 4:** tsc strict check (mirror ci-contracts): init a temp tsconfig `{"compilerOptions":{"strict":true,"noEmit":true},"include":["*.d.ts"]}` over the gen file → no errors.
- [ ] **Step 5: Commit** `chore(contracts): capture options /spurt endpoint + regen TS client`

---

## Task 3 — Full market-data verify

- [ ] `mvnw.cmd -pl services/market-data-service -am verify` → all `*Test`/`*IntegrationTest` green; JaCoCo ≥60%; Modulith verify green (analytics stays inside the `options` module). If Modulith flags the cross-package use, follow the parent plan Task 13 remediation.

---

## Task 4 — Frontend: OI-Interpretation badge + store loadSpurt + Options page wire

### 4a. `core/oi-interpretation.ts` — label + tone map (shared, testable)

**File (create):** `frontend-ui/src/app/core/oi-interpretation.ts`

```ts
export type OiInterpretation =
  | 'LONG_BUILDUP'
  | 'SHORT_BUILDUP'
  | 'SHORT_COVERING'
  | 'LONG_UNWINDING';

interface OiIntMeta {
  readonly label: string; // human label
  readonly severity: 'success' | 'danger' | 'info' | 'warn'; // p-tag severity
  readonly arrow: string; // non-colour cue: price↑/↓ + OI↑/↓
}

const META: Record<OiInterpretation, OiIntMeta> = {
  LONG_BUILDUP: { label: 'Long Buildup', severity: 'success', arrow: '↑ price · ↑ OI' },
  SHORT_BUILDUP: { label: 'Short Buildup', severity: 'danger', arrow: '↓ price · ↑ OI' },
  SHORT_COVERING: { label: 'Short Covering', severity: 'info', arrow: '↑ price · ↓ OI' },
  LONG_UNWINDING: { label: 'Long Unwinding', severity: 'warn', arrow: '↓ price · ↓ OI' },
};

export function oiIntMeta(value: OiInterpretation): OiIntMeta {
  return META[value];
}
```

### 4b. `<ay-oi-int-badge>` component

**File (create):** `frontend-ui/src/app/shared/oi-int-badge.ts` — input `value: OiInterpretation | null`; renders a `p-tag` with `[severity]`, `[value]="label"`, and a visually-hidden `arrow` for the non-colour cue; renders `—` when null.

```ts
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TagModule } from 'primeng/tag';
import { type OiInterpretation, oiIntMeta } from '../core/oi-interpretation';

@Component({
  selector: 'ay-oi-int-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TagModule],
  template: `
    @if (meta(); as m) {
      <p-tag [severity]="m.severity" [value]="m.label" />
      <span class="ay-sr-only">{{ m.arrow }}</span>
    } @else {
      <span aria-hidden="true">—</span>
      <span class="ay-sr-only">no interpretation</span>
    }
  `,
})
export class OiIntBadge {
  readonly value = input<OiInterpretation | null>(null);
  protected readonly meta = computed(() => {
    const v = this.value();
    return v ? oiIntMeta(v) : null;
  });
}
```

### 4c. `OiAnalyticsStore.loadSpurt()` + spurt state

**File (modify):** `frontend-ui/src/app/stores/oi-analytics.store.ts`
- Add interfaces: `SpurtRow { strike:string; optionType:'CE'|'PE'; ltp:string|null; oi:number|null; oiChange:number; spurtPct:string|null; interpretation:OiInterpretation }`, `SpurtSummary { interpretation:OiInterpretation; spotDelta:string; oiChange:number }`, `SpurtChain { items:SpurtRow[]; summary:SpurtSummary|null; asOf:string|null }`.
- State: add `spurt: SpurtChain|null = null`, `loadingSpurt = false`.
- Computed: `oiInterpretation = computed(() => spurt()?.summary?.interpretation ?? null)`.
- Method `loadSpurt()`: same guard (`unsatisfiable(true)`), generation token (`let spurtGen=0`), GET `/api/v1/market/options/spurt` with `params(true)`, SILENT context (422/no-data → null), stale-drop on `g===spurtGen`.

### 4d. Wire badge into Options OI page

**File (modify):** `frontend-ui/src/app/pages/oi/oi-options-page.ts`
- Add `OiIntBadge` to imports; call `store.loadSpurt()` in the existing `effect()` (alongside `loadOptions()`).
- In the stats meta header add: `<ay-oi-int-badge [value]="store.oiInterpretation()" />` with a label "Bias:".

### 4e. Specs (vitest)
- [ ] `oi-int-badge.spec.ts` — renders label for each of the 4 states; renders `—` + "no interpretation" when null.
- [ ] Extend `oi-analytics.store.spec.ts` — `loadSpurt` maps summary→`oiInterpretation`; 422 → null; skipped without expiry.
- [ ] Extend `oi-options-page.spec.ts` — flush a `/spurt` response, assert the badge label renders.

- [ ] **Commit** `feat(frontend-ui): OI-Interpretation 4-state badge on Options OI page`

---

## Task 5 — Frontend verify trio
- [ ] `Push-Location frontend-ui; npm run lint; npm run test:ci; npm run build` → all green.

---

## Task 6 — Adversarial review, PR
- [ ] Review the slice (domain: bucket/delta/IST + decimal; a11y: badge non-colour cue + contrast). Fix findings.
- [ ] Push `feat/options-oi-spurt`; open PR (CI: ci-java market-data shard + ci-contracts + ci-e2e). **Do NOT auto-merge** — owner drives merges.

---

## Follow-on (not this slice)
- Dedicated **Options OI Spurt page** (archetype #3 buildup-multi-table) consuming `spurt().items`.
- **Futures `/spurt`** (same pattern over `FuturesSnapshotReader`).
- `/big-oi`, `/premium`, `/trending` (+`-pa`) — parent plan follow-on #1 remainder.
- History-mode wiring for all options analytics endpoints (currently `latest`/`latestPair` ignore `date` — pre-existing, affects every endpoint).
