# Phase 2 — OI Analytics Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the analytics compute + read layer over the live-accruing `marketdata.option_chain_snapshots` / `futures_oi_snapshots` hypertables — the 3 oipulse primitives (OI-Interpretation, Active-Strike, Buildup) + core derived metrics (PCR history, Max Pain) — and expose them through `/api/v1/market/{options,futures}/*` endpoints driven by the universal control-bar contract.

**Architecture:** New `options/analytics/` + `futures/analytics/` packages inside **market-data-service** (Decision D1 — no new service, no new schema role). Reads use **query-time `time_bucket`+`last()` downsampling** (Decision 2026-06-15 — NO continuous aggregates; chain pages query one `(underlying, expiry)` at a time, narrow indexed filter). Compute is pure functions over snapshot rows so they unit-test against hand-computed fixtures. Controllers stay thin (parse + delegate); springdoc infers the spec; `ContractCaptureTest` gates drift.

**Tech Stack:** Java 21 / Spring Boot · `JdbcTemplate` (direct, no NamedParameter) · TimescaleDB `time_bucket`/`last()` · `libs/black76-math` (Greeks, already wired at write) · JUnit5 + AssertJ + Testcontainers (`MarketDataIntegrationTestBase`) · springdoc OpenAPI.

---

## Scope

Phase 2 spans four endpoint families (options, futures, fii-dii, option-strategies). This plan covers the **critical-path core** that unblocks the Phase 3/4 frontend, in full TDD detail:

- The shared read/downsample layer (Tasks 3–4)
- All three primitives (Tasks 2, 6, 8)
- Core derived metrics: PCR history + Max Pain (Tasks 5, 7)
- The universal control-bar request contract + OI interval set (Tasks 1, 9)
- Three representative endpoints proving the full pattern end-to-end + contract recapture (Tasks 10–14)

The remaining endpoint families (futures spurt/buzz/movers/banks/eod, fii-dii/\*, option-strategies straddle/strangle/multi-leg/payoff) **reuse these exact components** and are deferred to thin follow-on sub-plans — see "Follow-on sub-plans" at the end. This split follows the writing-plans scope-check (one shippable, testable increment per plan).

---

## Conventions discovered (match these exactly)

- **Base path:** `@RestController @RequestMapping("/api/v1/market/{domain}")` — e.g. `OptionsChainController` = `/api/v1/market/options`. (The master plan wrote `/api/v1/options/*`; the real convention is `/api/v1/market/options/*`.)
- **Date params:** `@RequestParam(required=false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiry`.
- **List response:** a record `…Response(List<T> items, …, boolean stale, OffsetDateTime asOf)` — never a bare array (CLAUDE.md).
- **Validation:** manual in the service: `throw new ApiException(400, ErrorCodes.VALIDATION_*, msg)`. Not-found: `throw new NotFoundException(ErrorCodes.NOT_FOUND_RESOURCE, msg)`. No-data: `throw new ApiException(422, ErrorCodes.DATA_GAP, msg)`.
- **OpenAPI:** no `@Operation`/`@Tag` — springdoc infers; the error envelope is auto-attached by `OpenApiConfig.errorEnvelopeCustomizer()`.
- **Tests:** `*Test` or `*IntegrationTest` ONLY (no failsafe — `*IT` is silently skipped). ITs extend `MarketDataIntegrationTestBase` (singleton Testcontainers; no per-method cleanup → unique keys per test).
- **Build one service:** `./mvnw -pl services/market-data-service -am test` (full reactor + `-am`, never bare `-pl`).
- **Repository style:** constructor-wired `JdbcTemplate jdbc`; `jdbc.query(sql, (rs,n) -> new Record(...), args...)`.

---

## File Structure

```
services/market-data-service/src/main/java/in/arthayantra/marketdata/
  options/
    OiInterval.java                      (NEW) enum 1m/3m/5m/15m/30m/60m + parse/validate
    OiInterpretation.java                (NEW) 4-state enum + classify(priceDelta, oiDelta)
    analytics/
      OptionsSnapshotReader.java         (NEW) downsampled strike-series reads (time_bucket+last)
      MaxPainCalculator.java             (NEW) pure: argmin total intrinsic over listed strikes
      ActiveStrikeService.java           (NEW) peak-OI strike series + sentiment % (v1, configurable)
      PcrHistoryService.java             (NEW) PCR series over snapshots (reuses putCallRatio)
      OiSpurtService.java                (NEW) buildup classifier across strikes/contracts
      OiQuery.java                       (NEW) control-bar request record + factory/validate
      OptionsAnalyticsController.java     (NEW) /api/v1/market/options/{oi-analysis,oi-stats,active-strikes}
  futures/
    analytics/
      FuturesSnapshotReader.java         (NEW) downsampled futures OI reads
      FuturesAnalyticsController.java     (NEW) /api/v1/market/futures/oi-analysis
  screener/ScreenerService.java          (MODIFY) reuse OiInterpretation enum (was inline strings)
  futures/FuturesOiSnapshotRepository.java (MODIFY) add nearestSnapshotTs() + rowsAt() reads

services/market-data-service/src/test/java/in/arthayantra/marketdata/
  options/OiIntervalTest.java
  options/OiInterpretationTest.java
  options/analytics/MaxPainCalculatorTest.java
  options/analytics/ActiveStrikeServiceTest.java
  options/analytics/OptionsSnapshotReaderIntegrationTest.java
  options/analytics/PcrHistoryServiceIntegrationTest.java
  options/analytics/OiSpurtServiceTest.java
  options/analytics/OptionsAnalyticsControllerIntegrationTest.java
  futures/analytics/FuturesAnalyticsControllerIntegrationTest.java
  ContractCaptureTest.java               (existing — re-run with -Dcontracts.capture=true)

contracts/market-data-service.openapi.json   (regenerated)
contracts/gen/*.d.ts                          (regenerated via openapi-typescript@7)
```

---

## Task 1: OI interval enum

oipulse intervals (`1m/3m/5m/15m/30m/60m`) differ from the candle set (`1m/5m/15m/1h/1d/1w`). A dedicated enum maps each to a `Duration` for `time_bucket`.

**Files:**
- Create: `services/market-data-service/src/main/java/in/arthayantra/marketdata/options/OiInterval.java`
- Test: `services/market-data-service/src/test/java/in/arthayantra/marketdata/options/OiIntervalTest.java`

- [ ] **Step 1: Write the failing test**

```java
package in.arthayantra.marketdata.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.common.web.error.ApiException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OiIntervalTest {

  @Test
  void parsesEverySupportedToken() {
    assertThat(OiInterval.parse("1m").bucket()).isEqualTo(Duration.ofMinutes(1));
    assertThat(OiInterval.parse("3m").bucket()).isEqualTo(Duration.ofMinutes(3));
    assertThat(OiInterval.parse("60m").bucket()).isEqualTo(Duration.ofMinutes(60));
  }

  @Test
  void rejectsUnknownTokenWithValidationCode() {
    assertThatThrownBy(() -> OiInterval.parse("7m"))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).code())
            .isEqualTo("VALIDATION_INTERVAL_UNSUPPORTED"));
  }

  @Test
  void pgIntervalLiteralIsSafe() {
    assertThat(OiInterval.M5.pgInterval()).isEqualTo("5 minutes");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl services/market-data-service -am test -Dtest=OiIntervalTest`
Expected: FAIL — `OiInterval` does not exist (compile error).

- [ ] **Step 3: Write minimal implementation**

```java
package in.arthayantra.marketdata.options;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import java.time.Duration;
import java.util.Arrays;

/** oipulse OI interval set (D3). Downsample bucket for time_bucket(). */
public enum OiInterval {
  M1(1), M3(3), M5(5), M15(15), M30(30), M60(60);

  private final int minutes;

  OiInterval(int minutes) {
    this.minutes = minutes;
  }

  public String token() {
    return minutes + "m";
  }

  public Duration bucket() {
    return Duration.ofMinutes(minutes);
  }

  /** Literal for SQL public.time_bucket(INTERVAL '<x> minutes', ts). Derived from a fixed int — no injection. */
  public String pgInterval() {
    return minutes + " minutes";
  }

  public static OiInterval parse(String token) {
    return Arrays.stream(values())
        .filter(i -> i.token().equals(token))
        .findFirst()
        .orElseThrow(
            () ->
                new ApiException(
                    400,
                    ErrorCodes.VALIDATION_INTERVAL_UNSUPPORTED,
                    "interval must be one of 1m,3m,5m,15m,30m,60m"));
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl services/market-data-service -am test -Dtest=OiIntervalTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add services/market-data-service/src/main/java/in/arthayantra/marketdata/options/OiInterval.java \
        services/market-data-service/src/test/java/in/arthayantra/marketdata/options/OiIntervalTest.java
git commit -m "feat(market-data): OI interval enum (1m/3m/5m/15m/30m/60m)"
```

---

## Task 2: OI-Interpretation enum + classifier (promote from screener)

The 4-state logic exists as inline strings in `ScreenerService.oiBuildup()` (lines ~229-234). Promote to a shared enum so options + futures analytics reuse the identical rule, then refactor the screener to use it (no behaviour change).

**Files:**
- Create: `services/market-data-service/src/main/java/in/arthayantra/marketdata/options/OiInterpretation.java`
- Modify: `services/market-data-service/src/main/java/in/arthayantra/marketdata/screener/ScreenerService.java` (the `oiBuildup()` label computation)
- Test: `services/market-data-service/src/test/java/in/arthayantra/marketdata/options/OiInterpretationTest.java`

- [ ] **Step 1: Write the failing test**

```java
package in.arthayantra.marketdata.options;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OiInterpretationTest {

  @Test
  void classifiesAllFourQuadrants() {
    // priceDelta sign × oiDelta sign  (boundary 0 counts as the "up"/build side, matching screener)
    assertThat(OiInterpretation.classify(new BigDecimal("1.0"), 100)).isEqualTo(OiInterpretation.LONG_BUILDUP);
    assertThat(OiInterpretation.classify(new BigDecimal("-1.0"), 100)).isEqualTo(OiInterpretation.SHORT_BUILDUP);
    assertThat(OiInterpretation.classify(new BigDecimal("1.0"), -100)).isEqualTo(OiInterpretation.SHORT_COVERING);
    assertThat(OiInterpretation.classify(new BigDecimal("-1.0"), -100)).isEqualTo(OiInterpretation.LONG_UNWINDING);
  }

  @Test
  void boundaryZeroIsBuildSide() {
    assertThat(OiInterpretation.classify(BigDecimal.ZERO, 0)).isEqualTo(OiInterpretation.LONG_BUILDUP);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl services/market-data-service -am test -Dtest=OiInterpretationTest`
Expected: FAIL — `OiInterpretation` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package in.arthayantra.marketdata.options;

import java.math.BigDecimal;

/**
 * The oipulse 4-state OI interpretation (primitive #1): price direction × OI direction.
 * Boundary convention (delta == 0 counts as the "up" side) matches the pre-existing
 * {@code ScreenerService.oiBuildup()} rule it replaces, so the screener output is byte-stable.
 */
public enum OiInterpretation {
  LONG_BUILDUP, // price up, OI up
  SHORT_BUILDUP, // price down, OI up
  SHORT_COVERING, // price up, OI down
  LONG_UNWINDING; // price down, OI down

  public static OiInterpretation classify(BigDecimal priceDelta, long oiDelta) {
    boolean priceUp = priceDelta.signum() >= 0;
    boolean oiUp = oiDelta >= 0;
    if (priceUp) {
      return oiUp ? LONG_BUILDUP : SHORT_COVERING;
    }
    return oiUp ? SHORT_BUILDUP : LONG_UNWINDING;
  }
}
```

- [ ] **Step 4: Refactor the screener to reuse it (no behaviour change)**

In `ScreenerService.oiBuildup()`, replace the inline ternary that builds the `label` string with:

```java
String label =
    in.arthayantra.marketdata.options.OiInterpretation.classify(priceDelta, oiDelta).name();
```

(Leaves `Row.label` as the same `String` value, e.g. `"LONG_BUILDUP"`.)

- [ ] **Step 5: Run the screener IT + the new unit test to verify no regression**

Run: `./mvnw -pl services/market-data-service -am test -Dtest=OiInterpretationTest,WatchlistScreenerIntegrationTest`
Expected: PASS — `oiBuildupClassifiesAllFourQuadrants` still green; the 4 enum cases green.

- [ ] **Step 6: Commit**

```bash
git add services/market-data-service/src/main/java/in/arthayantra/marketdata/options/OiInterpretation.java \
        services/market-data-service/src/main/java/in/arthayantra/marketdata/screener/ScreenerService.java \
        services/market-data-service/src/test/java/in/arthayantra/marketdata/options/OiInterpretationTest.java
git commit -m "refactor(market-data): promote OI 4-state to shared OiInterpretation enum"
```

---

## Task 3: OptionsSnapshotReader — query-time downsampled strike series

The read side of the caggs-vs-query-time decision. One method returns, for a `(underlying, expiry, interval, [from,to])`, a downsampled series per `(strike, optionType)` using `time_bucket` + `last()` (latest value in each bucket — OI/LTP/IV are point-in-time stats, NOT summed). A second returns the single latest snapshot bucket (for "current chain" analytics).

**Files:**
- Create: `services/market-data-service/src/main/java/in/arthayantra/marketdata/options/analytics/OptionsSnapshotReader.java`
- Test: `services/market-data-service/src/test/java/in/arthayantra/marketdata/options/analytics/OptionsSnapshotReaderIntegrationTest.java`

- [ ] **Step 1: Write the failing integration test**

```java
package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.MarketDataIntegrationTestBase;
import in.arthayantra.marketdata.options.OiInterval;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class OptionsSnapshotReaderIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired JdbcTemplate jdbc;
  @Autowired OptionsSnapshotReader reader;

  @Test
  void downsamplesToLastValuePerBucket() {
    // Two 1-min snapshots inside the same 5-min bucket; last() must win per strike/side.
    String u = "READER_TEST";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime t0 = OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime t1 = t0.plusMinutes(2); // same 5-min bucket as t0
    insertRow(jdbc, t0, u, exp, "22500", "CE", "100.00", 1000L, 10L);
    insertRow(jdbc, t1, u, exp, "22500", "CE", "120.00", 1500L, 25L); // later → wins

    List<OptionsSnapshotReader.StrikePoint> pts =
        reader.series(u, exp, OiInterval.M5, t0.minusMinutes(1), t0.plusMinutes(6));

    assertThat(pts).hasSize(1);
    assertThat(pts.get(0).optionType()).isEqualTo("CE");
    assertThat(pts.get(0).ltp()).isEqualByComparingTo("120.00");
    assertThat(pts.get(0).oi()).isEqualTo(1500L);
  }

  /** Helper: minimal insert into options_chain_snapshots (only the columns the reader touches). */
  static void insertRow(
      JdbcTemplate jdbc, OffsetDateTime ts, String u, LocalDate exp, String strike,
      String type, String ltp, Long oi, Long oiChange) {
    jdbc.update(
        "INSERT INTO options_chain_snapshots "
            + "(ts, underlying, expiry, strike, option_type, tradingsymbol, ltp, oi, oi_change, spot_price) "
            + "VALUES (?,?,?,?::numeric,?,?,?::numeric,?,?,?::numeric) "
            + "ON CONFLICT DO NOTHING",
        java.sql.Timestamp.from(ts.toInstant()), u, java.sql.Date.valueOf(exp), strike, type,
        u + strike + type, ltp, oi, oiChange, "22480.00");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl services/market-data-service -am test -Dtest=OptionsSnapshotReaderIntegrationTest`
Expected: FAIL — `OptionsSnapshotReader` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package in.arthayantra.marketdata.options.analytics;

import in.arthayantra.marketdata.options.OiInterval;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;

/** Query-time downsample of option_chain_snapshots (NO cagg — decision 2026-06-15). */
@Repository
public class OptionsSnapshotReader {

  private final JdbcTemplate jdbc;

  public OptionsSnapshotReader(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** One downsampled point per (bucket, strike, optionType): last() of each point-in-time stat. */
  public record StrikePoint(
      OffsetDateTime bucket, BigDecimal strike, String optionType,
      BigDecimal ltp, Long oi, Long oiChange, BigDecimal iv, BigDecimal spot) {}

  public List<StrikePoint> series(
      String underlying, LocalDate expiry, OiInterval interval, OffsetDateTime from, OffsetDateTime to) {
    String sql =
        "SELECT public.time_bucket(INTERVAL '" + interval.pgInterval() + "', ts) AS b, "
            + "  strike, option_type, "
            + "  public.last(ltp, ts) AS ltp, public.last(oi, ts) AS oi, "
            + "  public.last(oi_change, ts) AS oi_change, public.last(iv, ts) AS iv, "
            + "  public.last(spot_price, ts) AS spot "
            + "FROM options_chain_snapshots "
            + "WHERE underlying = ? AND expiry = ? AND ts >= ? AND ts < ? "
            + "GROUP BY b, strike, option_type "
            + "ORDER BY b, strike, option_type";
    return jdbc.query(
        sql,
        (rs, n) ->
            new StrikePoint(
                rs.getObject("b", OffsetDateTime.class),
                rs.getBigDecimal("strike"),
                rs.getString("option_type"),
                rs.getBigDecimal("ltp"),
                (Long) rs.getObject("oi"),
                (Long) rs.getObject("oi_change"),
                rs.getBigDecimal("iv"),
                rs.getBigDecimal("spot")),
        underlying, java.sql.Date.valueOf(expiry),
        Timestamp.from(from.toInstant()), Timestamp.from(to.toInstant()));
  }

  /** The most recent snapshot bucket's rows (for "current" analytics). Empty if none. */
  public List<StrikePoint> latest(String underlying, LocalDate expiry, OiInterval interval) {
    OffsetDateTime maxTs =
        jdbc.query(
                "SELECT max(ts) AS m FROM options_chain_snapshots WHERE underlying = ? AND expiry = ?",
                (rs, n) -> rs.getObject("m", OffsetDateTime.class),
                underlying, java.sql.Date.valueOf(expiry))
            .stream()
            .findFirst()
            .orElse(null);
    if (maxTs == null) {
      return List.of();
    }
    // one bucket wide, ending just after maxTs
    return series(underlying, expiry, interval, maxTs.minus(interval.bucket()), maxTs.plusSeconds(1));
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl services/market-data-service -am test -Dtest=OptionsSnapshotReaderIntegrationTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/market-data-service/src/main/java/in/arthayantra/marketdata/options/analytics/OptionsSnapshotReader.java \
        services/market-data-service/src/test/java/in/arthayantra/marketdata/options/analytics/OptionsSnapshotReaderIntegrationTest.java
git commit -m "feat(market-data): query-time downsampled option-chain snapshot reader"
```

---

## Task 4: FuturesSnapshotReader + repository read methods

`FuturesOiSnapshotRepository` is write-only. Add the symmetric read (mirror `OptionsSnapshotRepository.nearestSnapshotTs`/`rowsAt`) and a downsampled per-contract series.

**Files:**
- Create: `services/market-data-service/src/main/java/in/arthayantra/marketdata/futures/analytics/FuturesSnapshotReader.java`
- Test: `services/market-data-service/src/test/java/in/arthayantra/marketdata/futures/analytics/FuturesSnapshotReaderIntegrationTest.java`

- [ ] **Step 1: Write the failing integration test**

```java
package in.arthayantra.marketdata.futures.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.MarketDataIntegrationTestBase;
import in.arthayantra.marketdata.options.OiInterval;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class FuturesSnapshotReaderIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired JdbcTemplate jdbc;
  @Autowired FuturesSnapshotReader reader;

  @Test
  void downsamplesPerContractToLastValue() {
    String u = "FUTREAD";
    OffsetDateTime t0 = OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    jdbc.update(
        "INSERT INTO futures_oi_snapshots (ts, underlying, tradingsymbol, expiry, ltp, volume, oi, oi_change) "
            + "VALUES (?,?,?,?,?::numeric,?,?,?) ON CONFLICT DO NOTHING",
        java.sql.Timestamp.from(t0.toInstant()), u, u + "26JUNFUT",
        java.sql.Date.valueOf(LocalDate.of(2026, 6, 25)), "100.00", 10L, 5000L, 200L);

    List<FuturesSnapshotReader.FutPoint> pts =
        reader.series(u, OiInterval.M5, t0.minusMinutes(1), t0.plusMinutes(6));

    assertThat(pts).hasSize(1);
    assertThat(pts.get(0).tradingsymbol()).isEqualTo(u + "26JUNFUT");
    assertThat(pts.get(0).oi()).isEqualTo(5000L);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl services/market-data-service -am test -Dtest=FuturesSnapshotReaderIntegrationTest`
Expected: FAIL — `FuturesSnapshotReader` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package in.arthayantra.marketdata.futures.analytics;

import in.arthayantra.marketdata.options.OiInterval;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Query-time downsample of futures_oi_snapshots, per contract. */
@Repository
public class FuturesSnapshotReader {

  private final JdbcTemplate jdbc;

  public FuturesSnapshotReader(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record FutPoint(
      OffsetDateTime bucket, String tradingsymbol, BigDecimal ltp, Long oi, Long oiChange) {}

  public List<FutPoint> series(
      String underlying, OiInterval interval, OffsetDateTime from, OffsetDateTime to) {
    String sql =
        "SELECT public.time_bucket(INTERVAL '" + interval.pgInterval() + "', ts) AS b, "
            + "  tradingsymbol, public.last(ltp, ts) AS ltp, public.last(oi, ts) AS oi, "
            + "  public.last(oi_change, ts) AS oi_change "
            + "FROM futures_oi_snapshots "
            + "WHERE underlying = ? AND ts >= ? AND ts < ? "
            + "GROUP BY b, tradingsymbol ORDER BY b, tradingsymbol";
    return jdbc.query(
        sql,
        (rs, n) ->
            new FutPoint(
                rs.getObject("b", OffsetDateTime.class),
                rs.getString("tradingsymbol"),
                rs.getBigDecimal("ltp"),
                (Long) rs.getObject("oi"),
                (Long) rs.getObject("oi_change")),
        underlying, Timestamp.from(from.toInstant()), Timestamp.from(to.toInstant()));
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl services/market-data-service -am test -Dtest=FuturesSnapshotReaderIntegrationTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/market-data-service/src/main/java/in/arthayantra/marketdata/futures/analytics/FuturesSnapshotReader.java \
        services/market-data-service/src/test/java/in/arthayantra/marketdata/futures/analytics/FuturesSnapshotReaderIntegrationTest.java
git commit -m "feat(market-data): downsampled futures OI snapshot reader"
```

---

## Task 5: MaxPainCalculator (derived metric, pure)

Max pain = the listed strike that minimises total option-writer payout at expiry: `Σ_s ceOi(s)·max(0, P−s) + Σ_s peOi(s)·max(0, s−P)`, evaluated at each candidate `P` in the strike set. Pure function → unit-testable with no DB.

**Files:**
- Create: `services/market-data-service/src/main/java/in/arthayantra/marketdata/options/analytics/MaxPainCalculator.java`
- Test: `services/market-data-service/src/test/java/in/arthayantra/marketdata/options/analytics/MaxPainCalculatorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class MaxPainCalculatorTest {

  @Test
  void picksStrikeMinimisingTotalIntrinsic() {
    // CE OI heavy at 22400, PE OI heavy at 22600 → max pain pulled to the middle (22500).
    List<MaxPainCalculator.StrikeOi> chain =
        List.of(
            new MaxPainCalculator.StrikeOi(new BigDecimal("22400"), 100, 900),
            new MaxPainCalculator.StrikeOi(new BigDecimal("22500"), 500, 500),
            new MaxPainCalculator.StrikeOi(new BigDecimal("22600"), 900, 100));
    assertThat(MaxPainCalculator.maxPain(chain)).isEqualByComparingTo("22500");
  }

  @Test
  void returnsNullForEmptyChain() {
    assertThat(MaxPainCalculator.maxPain(List.of())).isNull();
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl services/market-data-service -am test -Dtest=MaxPainCalculatorTest`
Expected: FAIL — class missing.

- [ ] **Step 3: Write minimal implementation**

```java
package in.arthayantra.marketdata.options.analytics;

import java.math.BigDecimal;
import java.util.List;

/** Max pain: the listed strike minimising total writer payout at expiry. */
public final class MaxPainCalculator {

  private MaxPainCalculator() {}

  public record StrikeOi(BigDecimal strike, long ceOi, long peOi) {}

  public static BigDecimal maxPain(List<StrikeOi> chain) {
    BigDecimal best = null;
    BigDecimal bestPain = null;
    for (StrikeOi candidate : chain) {
      BigDecimal p = candidate.strike();
      BigDecimal pain = BigDecimal.ZERO;
      for (StrikeOi s : chain) {
        if (p.compareTo(s.strike()) > 0) { // CE in the money: P - strike
          pain = pain.add(p.subtract(s.strike()).multiply(BigDecimal.valueOf(s.ceOi())));
        }
        if (s.strike().compareTo(p) > 0) { // PE in the money: strike - P
          pain = pain.add(s.strike().subtract(p).multiply(BigDecimal.valueOf(s.peOi())));
        }
      }
      if (bestPain == null || pain.compareTo(bestPain) < 0) {
        bestPain = pain;
        best = p;
      }
    }
    return best;
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl services/market-data-service -am test -Dtest=MaxPainCalculatorTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/market-data-service/src/main/java/in/arthayantra/marketdata/options/analytics/MaxPainCalculator.java \
        services/market-data-service/src/test/java/in/arthayantra/marketdata/options/analytics/MaxPainCalculatorTest.java
git commit -m "feat(market-data): max-pain calculator"
```

---

## Task 6: ActiveStrikeService — peak-OI strikes + Active Strike Sentiment % (v1)

Primitive #2. **Active strikes** = top-N by total OI (CE+PE), N configurable (default 5). **Sentiment %** is a documented, tunable v1 formula (Decision D4 — exact oipulse weights are their IP):

```
bullishFlow = Σ_active peΔOI − Σ_active ceΔOI        (put-writing + call-unwinding = bullish)
baseOi      = Σ_active (ceOi + peOi) at the START of the interval
sentiment%  = 100 · bullishFlow / baseOi             (can exceed ±100 when flow > base — matches oipulse)
```

This is a pure function over the latest downsampled `StrikePoint`s. (Wiring to live OI/IV series is a follow-on; this task delivers the metric + active-strike selection.)

**Files:**
- Create: `services/market-data-service/src/main/java/in/arthayantra/marketdata/options/analytics/ActiveStrikeService.java`
- Test: `services/market-data-service/src/test/java/in/arthayantra/marketdata/options/analytics/ActiveStrikeServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActiveStrikeServiceTest {

  private static ActiveStrikeService.StrikeOiSnap snap(
      String strike, long ceOi, long ceChg, long peOi, long peChg) {
    return new ActiveStrikeService.StrikeOiSnap(new BigDecimal(strike), ceOi, ceChg, peOi, peChg);
  }

  @Test
  void selectsTopNByTotalOi() {
    List<ActiveStrikeService.StrikeOiSnap> chain =
        List.of(snap("22400", 100, 0, 100, 0), snap("22500", 900, 0, 900, 0), snap("22600", 50, 0, 50, 0));
    ActiveStrikeService svc = new ActiveStrikeService(1);
    assertThat(svc.activeStrikes(chain)).extracting(s -> s.strike().toPlainString()).containsExactly("22500");
  }

  @Test
  void sentimentBullishWhenPutsBuildAndCallsUnwind() {
    // active strike 22500: PE OI building (+300), CE OI unwinding (-200); base = 1000+1000
    List<ActiveStrikeService.StrikeOiSnap> chain = List.of(snap("22500", 1000, -200, 1000, 300));
    ActiveStrikeService svc = new ActiveStrikeService(1);
    // bullishFlow = 300 - (-200) = 500; base = 2000; sentiment = 100*500/2000 = 25.00
    assertThat(svc.sentimentPct(chain)).isEqualByComparingTo("25.00");
  }

  @Test
  void sentimentNullWhenNoBaseOi() {
    assertThat(new ActiveStrikeService(5).sentimentPct(List.of())).isNull();
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl services/market-data-service -am test -Dtest=ActiveStrikeServiceTest`
Expected: FAIL — class missing.

- [ ] **Step 3: Write minimal implementation**

```java
package in.arthayantra.marketdata.options.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Primitive #2: active (peak-OI) strikes + Active Strike Sentiment % (v1, tunable — D4). */
@Service
public class ActiveStrikeService {

  private final int topN;

  public ActiveStrikeService(@Value("${artha.options.active-strikes-top-n:5}") int topN) {
    this.topN = topN;
  }

  public record StrikeOiSnap(BigDecimal strike, long ceOi, long ceOiChange, long peOi, long peOiChange) {}

  public List<StrikeOiSnap> activeStrikes(List<StrikeOiSnap> chain) {
    return chain.stream()
        .sorted(Comparator.comparingLong((StrikeOiSnap s) -> s.ceOi() + s.peOi()).reversed())
        .limit(topN)
        .toList();
  }

  /** 100 · (ΣpeΔOI − ΣceΔOI) / Σ(ceOi+peOi) over active strikes; null when no base OI. */
  public BigDecimal sentimentPct(List<StrikeOiSnap> chain) {
    List<StrikeOiSnap> active = activeStrikes(chain);
    long bullishFlow = 0;
    long baseOi = 0;
    for (StrikeOiSnap s : active) {
      bullishFlow += s.peOiChange() - s.ceOiChange();
      baseOi += s.ceOi() + s.peOi();
    }
    if (baseOi == 0) {
      return null;
    }
    return BigDecimal.valueOf(bullishFlow)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(baseOi), 2, RoundingMode.HALF_UP);
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl services/market-data-service -am test -Dtest=ActiveStrikeServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/market-data-service/src/main/java/in/arthayantra/marketdata/options/analytics/ActiveStrikeService.java \
        services/market-data-service/src/test/java/in/arthayantra/marketdata/options/analytics/ActiveStrikeServiceTest.java
git commit -m "feat(market-data): active-strike tracking + v1 sentiment %"
```

---

## Task 7: PcrHistoryService — PCR series over snapshots

Reuse the existing `OptionsChainService.putCallRatio(long ceOi, long peOi)` (already null-safe on zero CE OI). For each downsampled bucket, sum CE OI and PE OI across strikes, then apply the helper. Requires making `putCallRatio` accessible (it is package-private `static`; expose a thin public wrapper to avoid widening the original's visibility surface unexpectedly).

**Files:**
- Modify: `services/market-data-service/src/main/java/in/arthayantra/marketdata/options/OptionsChainService.java` (add a `public static BigDecimal pcr(long, long)` delegating to the existing private `putCallRatio`)
- Create: `services/market-data-service/src/main/java/in/arthayantra/marketdata/options/analytics/PcrHistoryService.java`
- Test: `services/market-data-service/src/test/java/in/arthayantra/marketdata/options/analytics/PcrHistoryServiceIntegrationTest.java`

- [ ] **Step 1: Write the failing integration test**

```java
package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.MarketDataIntegrationTestBase;
import in.arthayantra.marketdata.options.OiInterval;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PcrHistoryServiceIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired JdbcTemplate jdbc;
  @Autowired PcrHistoryService pcr;

  @Test
  void computesPcrPerBucketFromSummedOi() {
    String u = "PCRTEST";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime t0 = OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    // CE OI total 1000, PE OI total 1500 → PCR 1.5000
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, t0, u, exp, "22500", "CE", "100", 1000L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, t0, u, exp, "22500", "PE", "90", 1500L, 0L);

    List<PcrHistoryService.PcrPoint> pts =
        pcr.history(u, exp, OiInterval.M5, t0.minusMinutes(1), t0.plusMinutes(6));

    assertThat(pts).hasSize(1);
    assertThat(pts.get(0).pcr()).isEqualByComparingTo("1.5000");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl services/market-data-service -am test -Dtest=PcrHistoryServiceIntegrationTest`
Expected: FAIL — `PcrHistoryService` missing.

- [ ] **Step 3a: Expose the PCR helper**

In `OptionsChainService`, add beside the existing private `putCallRatio`:

```java
/** Public, reusable PCR for analytics over stored snapshots. */
public static BigDecimal pcr(long ceOi, long peOi) {
  return putCallRatio(ceOi, peOi);
}
```

- [ ] **Step 3b: Write the service**

```java
package in.arthayantra.marketdata.options.analytics;

import in.arthayantra.marketdata.options.OiInterval;
import in.arthayantra.marketdata.options.OptionsChainService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** PCR time-series over stored snapshots (reuses OptionsChainService.pcr). */
@Service
public class PcrHistoryService {

  private final OptionsSnapshotReader reader;

  public PcrHistoryService(OptionsSnapshotReader reader) {
    this.reader = reader;
  }

  public record PcrPoint(OffsetDateTime bucket, BigDecimal pcr, long ceOi, long peOi) {}

  public List<PcrPoint> history(
      String underlying, LocalDate expiry, OiInterval interval, OffsetDateTime from, OffsetDateTime to) {
    Map<OffsetDateTime, long[]> perBucket = new LinkedHashMap<>(); // [ceOi, peOi]
    for (OptionsSnapshotReader.StrikePoint p : reader.series(underlying, expiry, interval, from, to)) {
      long[] sums = perBucket.computeIfAbsent(p.bucket(), k -> new long[2]);
      long oi = p.oi() == null ? 0 : p.oi();
      if ("CE".equals(p.optionType())) {
        sums[0] += oi;
      } else {
        sums[1] += oi;
      }
    }
    List<PcrPoint> out = new ArrayList<>();
    perBucket.forEach((bucket, sums) -> out.add(new PcrPoint(bucket, OptionsChainService.pcr(sums[0], sums[1]), sums[0], sums[1])));
    return out;
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl services/market-data-service -am test -Dtest=PcrHistoryServiceIntegrationTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/market-data-service/src/main/java/in/arthayantra/marketdata/options/OptionsChainService.java \
        services/market-data-service/src/main/java/in/arthayantra/marketdata/options/analytics/PcrHistoryService.java \
        services/market-data-service/src/test/java/in/arthayantra/marketdata/options/analytics/PcrHistoryServiceIntegrationTest.java
git commit -m "feat(market-data): PCR history series over snapshots"
```

---

## Task 8: OiSpurtService — buildup classifier across strikes (primitive #3)

Primitive #3: classify each strike/contract's `(ltpΔ, oiΔ)` into the 4-state `OiInterpretation`, plus an OI-spurt % (`oiChange / priorOi · 100`). Pure over `StrikePoint`s — reuses Task 2's enum.

**Files:**
- Create: `services/market-data-service/src/main/java/in/arthayantra/marketdata/options/analytics/OiSpurtService.java`
- Test: `services/market-data-service/src/test/java/in/arthayantra/marketdata/options/analytics/OiSpurtServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.options.OiInterpretation;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OiSpurtServiceTest {

  @Test
  void classifiesAndComputesSpurtPct() {
    OiSpurtService svc = new OiSpurtService();
    // ltp up (+5), oi up (+200 from prior 1000) → LONG_BUILDUP, spurt 20.00%
    OiSpurtService.SpurtRow r = svc.classify(new BigDecimal("5"), 200, 1000);
    assertThat(r.interpretation()).isEqualTo(OiInterpretation.LONG_BUILDUP);
    assertThat(r.spurtPct()).isEqualByComparingTo("20.00");
  }

  @Test
  void spurtPctNullWhenNoPriorOi() {
    assertThat(new OiSpurtService().classify(BigDecimal.ONE, 50, 0).spurtPct()).isNull();
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl services/market-data-service -am test -Dtest=OiSpurtServiceTest`
Expected: FAIL — class missing.

- [ ] **Step 3: Write minimal implementation**

```java
package in.arthayantra.marketdata.options.analytics;

import in.arthayantra.marketdata.options.OiInterpretation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

/** Primitive #3: per-strike buildup classification + OI-spurt %. */
@Service
public class OiSpurtService {

  public record SpurtRow(OiInterpretation interpretation, BigDecimal spurtPct) {}

  /** priorOi = oi before this interval's change (= currentOi - oiChange). */
  public SpurtRow classify(BigDecimal ltpDelta, long oiChange, long priorOi) {
    OiInterpretation interp = OiInterpretation.classify(ltpDelta, oiChange);
    BigDecimal spurt =
        priorOi == 0
            ? null
            : BigDecimal.valueOf(oiChange)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(priorOi), 2, RoundingMode.HALF_UP);
    return new SpurtRow(interp, spurt);
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl services/market-data-service -am test -Dtest=OiSpurtServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/market-data-service/src/main/java/in/arthayantra/marketdata/options/analytics/OiSpurtService.java \
        services/market-data-service/src/test/java/in/arthayantra/marketdata/options/analytics/OiSpurtServiceTest.java
git commit -m "feat(market-data): OI-spurt buildup classifier (primitive #3)"
```

---

## Task 9: OiQuery — universal control-bar request contract

The "Mode·Name·Date·Expiry·Interval" bar every page sends. A record + a static factory that parses/validates raw params (reusing `OiInterval.parse`), so every controller method delegates identically.

**Files:**
- Create: `services/market-data-service/src/main/java/in/arthayantra/marketdata/options/analytics/OiQuery.java`
- Test: `services/market-data-service/src/test/java/in/arthayantra/marketdata/options/analytics/OiQueryTest.java`

- [ ] **Step 1: Write the failing test**

```java
package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.marketdata.options.OiInterval;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class OiQueryTest {

  @Test
  void buildsLiveQueryWithDefaultInterval() {
    OiQuery q = OiQuery.of("live", "NIFTY 50", null, null, null);
    assertThat(q.live()).isTrue();
    assertThat(q.interval()).isEqualTo(OiInterval.M3); // default
    assertThat(q.name()).isEqualTo("NIFTY 50");
  }

  @Test
  void historyModeRequiresDate() {
    assertThatThrownBy(() -> OiQuery.of("history", "NIFTY 50", null, "5m", null))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("VALIDATION_FAILED"));
  }

  @Test
  void blankNameRejected() {
    assertThatThrownBy(() -> OiQuery.of("live", "  ", null, "3m", null)).isInstanceOf(ApiException.class);
  }

  @Test
  void parsesDateAndExpiry() {
    OiQuery q = OiQuery.of("history", "NIFTY 50", "2026-06-20", "15m", "2026-06-25");
    assertThat(q.live()).isFalse();
    assertThat(q.date()).isEqualTo(LocalDate.of(2026, 6, 20));
    assertThat(q.expiry()).isEqualTo(LocalDate.of(2026, 6, 25));
    assertThat(q.interval()).isEqualTo(OiInterval.M15);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl services/market-data-service -am test -Dtest=OiQueryTest`
Expected: FAIL — `OiQuery` missing.

- [ ] **Step 3: Write minimal implementation**

```java
package in.arthayantra.marketdata.options.analytics;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketdata.options.OiInterval;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Universal control-bar contract: Mode·Name·Date·Expiry·Interval. */
public record OiQuery(boolean live, String name, LocalDate date, OiInterval interval, LocalDate expiry) {

  public static OiQuery of(String mode, String name, String date, String interval, String expiry) {
    if (name == null || name.isBlank()) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "name is required");
    }
    boolean live = mode == null || mode.isBlank() || "live".equalsIgnoreCase(mode);
    OiInterval iv = interval == null || interval.isBlank() ? OiInterval.M3 : OiInterval.parse(interval);
    LocalDate d = parseDate(date, "date");
    LocalDate e = parseDate(expiry, "expiry");
    if (!live && d == null) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "history mode requires date");
    }
    return new OiQuery(live, name.trim(), d, iv, e);
  }

  private static LocalDate parseDate(String raw, String field) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(raw);
    } catch (DateTimeParseException ex) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, field + " must be ISO yyyy-MM-dd");
    }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl services/market-data-service -am test -Dtest=OiQueryTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/market-data-service/src/main/java/in/arthayantra/marketdata/options/analytics/OiQuery.java \
        services/market-data-service/src/test/java/in/arthayantra/marketdata/options/analytics/OiQueryTest.java
git commit -m "feat(market-data): universal OI control-bar request contract"
```

---

## Task 10: OptionsAnalyticsController — /oi-analysis + /oi-stats + /active-strikes

Three representative endpoints that wire the read layer + primitives + derived through the control-bar contract. Proves the end-to-end pattern; the remaining options endpoints (spurt/big-oi/premium/trending) follow this template in the follow-on plan.

**Files:**
- Create: `services/market-data-service/src/main/java/in/arthayantra/marketdata/options/analytics/OptionsAnalyticsController.java`
- Test: `services/market-data-service/src/test/java/in/arthayantra/marketdata/options/analytics/OptionsAnalyticsControllerIntegrationTest.java`

- [ ] **Step 1: Write the failing integration test**

```java
package in.arthayantra.marketdata.options.analytics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.marketdata.MarketDataIntegrationTestBase;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class OptionsAnalyticsControllerIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;

  @Test
  void oiStatsReturnsPcrAndMaxPainEnvelope() throws Exception {
    String u = "CTRLTEST";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime t0 = OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, t0, u, exp, "22500", "CE", "100", 1000L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, t0, u, exp, "22500", "PE", "90", 1500L, 0L);

    mockMvc
        .perform(
            get("/api/v1/market/options/oi-stats")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pcr").value(1.5))
        .andExpect(jsonPath("$.maxPain").value(22500));
  }

  @Test
  void unsupportedIntervalIs400WithCode() throws Exception {
    mockMvc
        .perform(get("/api/v1/market/options/oi-stats").param("name", "X").param("interval", "7m"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_INTERVAL_UNSUPPORTED"));
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl services/market-data-service -am test -Dtest=OptionsAnalyticsControllerIntegrationTest`
Expected: FAIL — controller/route missing → 404.

- [ ] **Step 3: Write minimal implementation**

```java
package in.arthayantra.marketdata.options.analytics;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketdata.options.OptionsChainService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/market/options")
public class OptionsAnalyticsController {

  private final OptionsSnapshotReader reader;
  private final ActiveStrikeService activeStrikes;

  public OptionsAnalyticsController(OptionsSnapshotReader reader, ActiveStrikeService activeStrikes) {
    this.reader = reader;
    this.activeStrikes = activeStrikes;
  }

  public record OiStats(BigDecimal pcr, BigDecimal maxPain, long ceOi, long peOi, OffsetDateTime asOf) {}

  public record ActiveStrikesResponse(BigDecimal sentimentPct, List<StrikeView> items, OffsetDateTime asOf) {}

  public record StrikeView(BigDecimal strike, long ceOi, long peOi) {}

  @GetMapping("/oi-stats")
  public OiStats oiStats(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry) {
    OiQuery q = OiQuery.of(mode, name, date, interval, expiry);
    LocalDate exp = requireExpiry(q);
    List<OptionsSnapshotReader.StrikePoint> latest = reader.latest(q.name(), exp, q.interval());
    if (latest.isEmpty()) {
      throw new ApiException(422, ErrorCodes.DATA_GAP, "no snapshot for " + q.name() + " " + exp);
    }
    Map<BigDecimal, long[]> byStrike = foldByStrike(latest); // [ceOi, peOi]
    long ce = 0;
    long pe = 0;
    List<MaxPainCalculator.StrikeOi> chain = new ArrayList<>();
    for (Map.Entry<BigDecimal, long[]> e : byStrike.entrySet()) {
      ce += e.getValue()[0];
      pe += e.getValue()[1];
      chain.add(new MaxPainCalculator.StrikeOi(e.getKey(), e.getValue()[0], e.getValue()[1]));
    }
    OffsetDateTime asOf = latest.get(latest.size() - 1).bucket();
    return new OiStats(OptionsChainService.pcr(ce, pe), MaxPainCalculator.maxPain(chain), ce, pe, asOf);
  }

  @GetMapping("/active-strikes")
  public ActiveStrikesResponse activeStrikes(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry) {
    OiQuery q = OiQuery.of(mode, name, date, interval, expiry);
    LocalDate exp = requireExpiry(q);
    List<OptionsSnapshotReader.StrikePoint> latest = reader.latest(q.name(), exp, q.interval());
    if (latest.isEmpty()) {
      throw new ApiException(422, ErrorCodes.DATA_GAP, "no snapshot for " + q.name() + " " + exp);
    }
    List<ActiveStrikeService.StrikeOiSnap> snaps = toSnaps(latest);
    BigDecimal sentiment = activeStrikes.sentimentPct(snaps);
    List<StrikeView> items =
        activeStrikes.activeStrikes(snaps).stream()
            .map(s -> new StrikeView(s.strike(), s.ceOi(), s.peOi()))
            .toList();
    return new ActiveStrikesResponse(sentiment, items, latest.get(latest.size() - 1).bucket());
  }

  /** /oi-analysis: the data-table archetype source (per-strike rows for the latest bucket). */
  @GetMapping("/oi-analysis")
  public Map<String, Object> oiAnalysis(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry) {
    OiQuery q = OiQuery.of(mode, name, date, interval, expiry);
    LocalDate exp = requireExpiry(q);
    List<OptionsSnapshotReader.StrikePoint> latest = reader.latest(q.name(), exp, q.interval());
    return Map.of("items", latest); // {items:[...]} envelope (CLAUDE.md)
  }

  private LocalDate requireExpiry(OiQuery q) {
    if (q.expiry() == null) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "expiry is required");
    }
    return q.expiry();
  }

  private static Map<BigDecimal, long[]> foldByStrike(List<OptionsSnapshotReader.StrikePoint> pts) {
    Map<BigDecimal, long[]> m = new LinkedHashMap<>();
    for (OptionsSnapshotReader.StrikePoint p : pts) {
      long[] v = m.computeIfAbsent(p.strike(), k -> new long[2]);
      long oi = p.oi() == null ? 0 : p.oi();
      if ("CE".equals(p.optionType())) {
        v[0] += oi;
      } else {
        v[1] += oi;
      }
    }
    return m;
  }

  private static List<ActiveStrikeService.StrikeOiSnap> toSnaps(List<OptionsSnapshotReader.StrikePoint> pts) {
    Map<BigDecimal, long[]> m = new LinkedHashMap<>(); // [ceOi, ceChg, peOi, peChg]
    for (OptionsSnapshotReader.StrikePoint p : pts) {
      long[] v = m.computeIfAbsent(p.strike(), k -> new long[4]);
      long oi = p.oi() == null ? 0 : p.oi();
      long chg = p.oiChange() == null ? 0 : p.oiChange();
      if ("CE".equals(p.optionType())) {
        v[0] += oi;
        v[1] += chg;
      } else {
        v[2] += oi;
        v[3] += chg;
      }
    }
    List<ActiveStrikeService.StrikeOiSnap> out = new ArrayList<>();
    m.forEach((strike, v) -> out.add(new ActiveStrikeService.StrikeOiSnap(strike, v[0], v[1], v[2], v[3])));
    return out;
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl services/market-data-service -am test -Dtest=OptionsAnalyticsControllerIntegrationTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add services/market-data-service/src/main/java/in/arthayantra/marketdata/options/analytics/OptionsAnalyticsController.java \
        services/market-data-service/src/test/java/in/arthayantra/marketdata/options/analytics/OptionsAnalyticsControllerIntegrationTest.java
git commit -m "feat(market-data): options OI analytics endpoints (oi-analysis/oi-stats/active-strikes)"
```

---

## Task 11: FuturesAnalyticsController — /oi-analysis

Mirror of Task 10 for futures, proving the futures read layer + `OiInterpretation` over per-contract series. The remaining futures endpoints (spurt/buzz/movers/banks/eod) follow this template in the follow-on plan.

**Files:**
- Create: `services/market-data-service/src/main/java/in/arthayantra/marketdata/futures/analytics/FuturesAnalyticsController.java`
- Test: `services/market-data-service/src/test/java/in/arthayantra/marketdata/futures/analytics/FuturesAnalyticsControllerIntegrationTest.java`

- [ ] **Step 1: Write the failing integration test**

```java
package in.arthayantra.marketdata.futures.analytics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.marketdata.MarketDataIntegrationTestBase;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class FuturesAnalyticsControllerIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;

  @Test
  void oiAnalysisReturnsItemsEnvelope() throws Exception {
    String u = "FUTCTRL";
    OffsetDateTime t0 = OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    jdbc.update(
        "INSERT INTO futures_oi_snapshots (ts, underlying, tradingsymbol, expiry, ltp, volume, oi, oi_change) "
            + "VALUES (?,?,?,?,?::numeric,?,?,?) ON CONFLICT DO NOTHING",
        java.sql.Timestamp.from(t0.toInstant()), u, u + "26JUNFUT",
        java.sql.Date.valueOf(LocalDate.of(2026, 6, 25)), "100.00", 10L, 5000L, 200L);

    mockMvc
        .perform(get("/api/v1/market/futures/oi-analysis").param("name", u).param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.items[0].tradingsymbol").value(u + "26JUNFUT"));
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl services/market-data-service -am test -Dtest=FuturesAnalyticsControllerIntegrationTest`
Expected: FAIL — route missing → 404.

- [ ] **Step 3: Write minimal implementation**

```java
package in.arthayantra.marketdata.futures.analytics;

import in.arthayantra.marketdata.options.analytics.OiQuery;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/market/futures")
public class FuturesAnalyticsController {

  private final FuturesSnapshotReader reader;

  public FuturesAnalyticsController(FuturesSnapshotReader reader) {
    this.reader = reader;
  }

  @GetMapping("/oi-analysis")
  public Map<String, Object> oiAnalysis(
      @RequestParam(required = false) String mode,
      @RequestParam String name,
      @RequestParam(required = false) String date,
      @RequestParam(required = false) String interval,
      @RequestParam(required = false) String expiry) {
    OiQuery q = OiQuery.of(mode, name, date, interval, expiry);
    // last 1 day window ending now (live); a date-scoped window in history mode is a follow-on.
    OffsetDateTime to = OffsetDateTime.now(ZoneOffset.ofHoursMinutes(5, 30));
    List<FuturesSnapshotReader.FutPoint> pts = reader.series(q.name(), q.interval(), to.minusDays(1), to);
    return Map.of("items", pts);
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl services/market-data-service -am test -Dtest=FuturesAnalyticsControllerIntegrationTest`
Expected: PASS.

Note: this controller reads `OffsetDateTime.now()` for the live window. If the IT proves flaky against the fixed fixture timestamp (2026-06-20), inject a `Clock` bean the test overrides — `OptionsSnapshotService` already wires a `Clock` (mirror that). Prefer the `Clock` injection from the start if the codebase's other analytics read services take one.

- [ ] **Step 5: Commit**

```bash
git add services/market-data-service/src/main/java/in/arthayantra/marketdata/futures/analytics/FuturesAnalyticsController.java \
        services/market-data-service/src/test/java/in/arthayantra/marketdata/futures/analytics/FuturesAnalyticsControllerIntegrationTest.java
git commit -m "feat(market-data): futures OI analysis endpoint"
```

---

## Task 12: Re-capture the OpenAPI contract + regenerate TS client

New `@GetMapping` paths + query params DO drift the spec (CLAUDE.md). Re-capture and regenerate the typed client so `ci-contracts` stays green.

**Files:**
- Modify: `contracts/market-data-service.openapi.json` (regenerated)
- Modify: `contracts/gen/*.d.ts` (regenerated)

- [ ] **Step 1: Re-capture the spec**

Run: `./mvnw -pl services/market-data-service -am -Dcontracts.capture=true test -Dtest=ContractCaptureTest`
Expected: PASS; `contracts/market-data-service.openapi.json` now contains the 4 new paths (`/api/v1/market/options/oi-stats`, `/oi-analysis`, `/active-strikes`, `/api/v1/market/futures/oi-analysis`).

- [ ] **Step 2: Verify the new paths are present**

Run: `git diff --stat contracts/market-data-service.openapi.json`
Expected: the file shows additions for the four new operations.

- [ ] **Step 3: Regenerate the TypeScript client**

Run (PowerShell, repo root):
```powershell
npx openapi-typescript@7 contracts/market-data-service.openapi.json -o contracts/gen/market-data-service.d.ts
```
Expected: `contracts/gen/market-data-service.d.ts` updated.

- [ ] **Step 4: Verify strict TS compile**

Run: `cd contracts; npx tsc --strict --noEmit gen/*.d.ts; cd ..` (or the repo's existing ci-contracts tsc command — match it exactly)
Expected: no errors.

- [ ] **Step 5: Commit**

```bash
git add contracts/market-data-service.openapi.json contracts/gen/
git commit -m "chore(contracts): capture OI analytics endpoints + regen TS client"
```

---

## Task 13: Full reactor verify + JaCoCo gate

- [ ] **Step 1: Run the full service verify (ITs + coverage gate)**

Run: `./mvnw -pl services/market-data-service -am verify`
Expected: all new `*Test`/`*IntegrationTest` green; JaCoCo ≥ 60% line on market-data-service; Modulith `verify` green (the new `analytics` packages stay within the `options`/`futures` modules — no illegal cross-module refs).

- [ ] **Step 2: If Modulith complains about the cross-package `OiQuery` use**

`FuturesAnalyticsController` imports `options.analytics.OiQuery`. If Spring Modulith flags the cross-module reference, move `OiQuery`, `OiInterval`, and `OiInterpretation` into a shared `marketdata.oi` package (or whatever the repo's shared-kernel convention is — check how `common`/shared types are placed) and update imports. Re-run verify.

- [ ] **Step 3: Commit any module-boundary fix**

```bash
git add services/market-data-service/
git commit -m "fix(market-data): keep OI analytics within module boundaries"
```

---

## Self-Review (completed)

**Spec coverage (Phase 2 deliverables → tasks):**
- Primitive #1 OI-Interpretation → Task 2 ✓
- Primitive #2 Active-Strike + sentiment % → Task 6 ✓
- Primitive #3 Buildup classifier → Task 8 ✓
- PCR (+history) → Task 7 ✓ ; Max Pain → Task 5 ✓
- Universal control-bar contract + interval set → Tasks 1, 9 ✓
- Read/downsample layer (query-time decision) → Tasks 3, 4 ✓
- Representative endpoints + contract gate → Tasks 10, 11, 12 ✓
- **Deferred to follow-on plans (named below):** straddle/strangle premium series, Greeks aggregation, big-OI-move, O=H/O=L probability, market movers, banks grid, all futures spurt/buzz/movers/banks/eod endpoints, fii-dii/\* endpoints, option-strategies endpoints. These are NOT gaps — they reuse Tasks 1–9 and are split out per the writing-plans scope-check.

**Type consistency:** `StrikePoint` (Task 3) fields reused unchanged in Tasks 7, 10; `StrikeOiSnap` (Task 6) built by the controller's `toSnaps` (Task 10); `MaxPainCalculator.StrikeOi` (Task 5) built in Task 10; `OiInterval`/`OiQuery`/`OiInterpretation` names consistent across all tasks. `OptionsChainService.pcr` added in Task 7, used in Tasks 7 + 10.

**Placeholder scan:** every code step has complete code; no TBD/TODO. The two "if X is flaky / if Modulith complains" notes (Tasks 11, 13) are conditional remediations with concrete instructions, not deferred work.

---

## Follow-on sub-plans (write each at execution time, scoped, reusing Tasks 1–9)

Each is a thin plan that adds endpoints + (where new) one pure metric, on the established read-layer + control-bar pattern:

1. **Options endpoints completion** — `/spurt` (OiSpurtService over the chain), `/big-oi` (top OI-change movers), `/premium` (straddle/strangle premium series — new pure calc), `/trending` + `/trending-pa` (OI trend over buckets). Archetypes: buildup-multi-table, single-chart, data-table.
2. **Futures endpoints completion** — `/spurt`, `/buzz` (heatmap), `/movers` (gainers/losers), `/banks` (bank-stock futures grid), `/eod` (from `futures_oi_snapshots` + bhavcopy). Needs the deferred `day_high`/`day_low` columns (Phase-1 gap) for buzz/movers — add the migration in this plan.
3. **FII/DII endpoints** — `/api/v1/market/fii-dii/{cash,participant-oi,long-short}` over `nse_eod_fii_dii` + `nse_eod_participant_oi`. Pure EOD-table reads; no primitives. (Resolve the path: `fii-dii` under `/api/v1/market/` to match the base-path convention.)
4. **Option-strategies endpoints** — `/straddle`, `/strangle`, `/multi-leg`, `/payoff` (uses `black76-math` Greeks + POP). **Naming decision required:** these collide with strategy-signal's `/api/v1/strategies/*`; namespace under `/api/v1/market/options/strategies/*` to disambiguate.
5. **Greeks aggregation + O=H/O=L probability** — OI-weighted portfolio Greeks roll-up; open=high / open=low probability from candle history. Feeds the Strategy Builder + Open&High pages.

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-06-15-phase2-oi-analytics-backend.md`.** Do NOT implement yet (per request). When ready, two execution options:

1. **Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks, fast iteration (`superpowers:subagent-driven-development`).
2. **Inline Execution** — execute tasks in-session with checkpoints (`superpowers:executing-plans`).
