# Futures-OI Snapshot Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Persist a per-minute-ish time series of futures Open Interest (front/next/far monthly FUT of configured underlyings) into a new `marketdata.futures_oi_snapshots` hypertable — completing the Phase-1 intraday-capture foundation for the oipulse Futures-OI pages.

**Architecture:** Direct mirror of the just-shipped options snapshotter. A scheduled, market-hours-gated `FuturesOiSnapshotService` resolves the front/next/far contracts per underlying via the existing `FuturesContractSource.monthlyFutures()`, batch-quotes them through the existing `QuoteGateway` (which already returns OI/volume/LTP), and persists rows via a new `FuturesOiSnapshotRepository`. No new Kite wiring — reuses the live `QuoteGateway`/contract source. Retention follows A2 (keep history; compression after 7 d; manual prune only).

**Tech Stack:** Java/Spring · TimescaleDB hypertable + Flyway (marketdata lineage, next = V011) · existing `QuoteGateway` + `FuturesContractSource` · `MarketCalendar` session gate.

**Template files (read these — this is a faithful mirror):**
- `services/market-data-service/.../options/OptionsSnapshotService.java` (scheduling + loop + oi_change + market-gate)
- `services/market-data-service/.../options/OptionsSnapshotRepository.java` (`insertAll` batchUpdate)
- `deploy/flyway/marketdata/V006__options_chain_snapshots.sql` (hypertable + compression + A2 no-retention)
- `services/market-data-service/.../futures/FuturesTermStructureService.java:113` (how it already reads `quote.oi()` for front/next/far — same contracts, just not persisted)

---

## File Structure

| File | Responsibility |
|---|---|
| `deploy/flyway/marketdata/V011__futures_oi_snapshots.sql` (create) | hypertable + compression, A2 no-retention |
| `services/market-data-service/.../futures/FuturesOiSnapshotRepository.java` (create) | `insertAll(List<Row>)` batch insert |
| `services/market-data-service/.../futures/FuturesOiSnapshotService.java` (create) | scheduled capture: resolve → quote → persist + oi_change |
| `services/market-data-service/.../futures/FuturesOiSnapshotServiceTest.java` (create) | TDD: contract→row mapping + oi_change |
| `services/market-data-service/src/main/resources/application.yml` (modify) | live-profile underlyings + interval |

---

## Task 1: V011 migration — `futures_oi_snapshots` hypertable

**Files:** Create `deploy/flyway/marketdata/V011__futures_oi_snapshots.sql`

- [ ] **Step 1: Write the migration** (mirror V006, drop option-only columns)

```sql
-- Phase F / oipulse: intraday futures OI time-series (front/next/far monthly FUT).
-- A2: keep history (no auto-retention; compression after 7d). Manual prune mirrors options (V010).
CREATE TABLE futures_oi_snapshots (
    ts             TIMESTAMPTZ   NOT NULL,
    underlying     TEXT          NOT NULL,
    tradingsymbol  TEXT          NOT NULL,
    expiry         DATE          NOT NULL,
    ltp            NUMERIC(18,4),
    volume         BIGINT,
    oi             BIGINT,
    oi_change      BIGINT,
    PRIMARY KEY (ts, underlying, tradingsymbol)
);

SELECT public.create_hypertable('futures_oi_snapshots', 'ts',
    chunk_time_interval => INTERVAL '1 day');

ALTER TABLE futures_oi_snapshots SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'underlying, tradingsymbol',
    timescaledb.compress_orderby = 'ts'
);
SELECT public.add_compression_policy('futures_oi_snapshots', INTERVAL '7 days');
-- NO retention policy (A2). Manual relief mirrors V010's options prune if ever needed.
```

- [ ] **Step 2: Validate** — `(cd services/market-data-service && run OptionsChainIntegrationTest)` boots the full marketdata lineage; expect Flyway "Successfully validated N migrations" incl. V011 and BUILD pass. (ITs apply the real lineage — a bad migration fails here.)

- [ ] **Step 3: Commit** — `git add deploy/flyway/marketdata/V011__futures_oi_snapshots.sql && git commit -m "feat(market-data): futures_oi_snapshots hypertable (V011)"`

---

## Task 2: `FuturesOiSnapshotRepository` — batch insert

**Files:** Create `services/market-data-service/src/main/java/in/arthayantra/marketdata/futures/FuturesOiSnapshotRepository.java` (mirror `OptionsSnapshotRepository`)

- [ ] **Step 1: Write the repository**

```java
package in.arthayantra.marketdata.futures;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC writer for the futures OI time-series (mirrors OptionsSnapshotRepository). */
@Repository
public class FuturesOiSnapshotRepository {

  public record Row(
      OffsetDateTime ts,
      String underlying,
      String tradingsymbol,
      LocalDate expiry,
      java.math.BigDecimal ltp,
      Long volume,
      Long oi,
      Long oiChange) {}

  private final JdbcTemplate jdbc;

  public FuturesOiSnapshotRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insertAll(List<Row> rows) {
    jdbc.batchUpdate(
        """
        INSERT INTO futures_oi_snapshots
          (ts, underlying, tradingsymbol, expiry, ltp, volume, oi, oi_change)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (ts, underlying, tradingsymbol) DO NOTHING
        """,
        rows,
        rows.size(),
        (ps, r) -> {
          ps.setObject(1, r.ts());
          ps.setString(2, r.underlying());
          ps.setString(3, r.tradingsymbol());
          ps.setObject(4, r.expiry());
          ps.setBigDecimal(5, r.ltp());
          ps.setObject(6, r.volume());
          ps.setObject(7, r.oi());
          ps.setObject(8, r.oiChange());
        });
  }
}
```

- [ ] **Step 2: Compile** — `package -DskipTests`; expect BUILD SUCCESS.

- [ ] **Step 3: Commit** — `git commit -m "feat(market-data): futures OI snapshot repository"`

---

## Task 3: `FuturesOiSnapshotService` — scheduled capture (TDD)

**Files:**
- Create `services/market-data-service/.../futures/FuturesOiSnapshotService.java`
- Test: `services/market-data-service/src/test/java/.../futures/FuturesOiSnapshotServiceTest.java`

- [ ] **Step 1: Write the failing test** (contract→row mapping + oi_change vs previous pass)

```java
package in.arthayantra.marketdata.futures;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.kite.FuturesContractSource;
import in.arthayantra.marketdata.kite.FuturesContractSource.FutContract;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import in.arthayantra.marketcalendar.MarketCalendar;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FuturesOiSnapshotServiceTest {

  // Tue 2026-06-16 11:00 IST — inside the session
  private static final Clock CLOCK =
      Clock.fixed(OffsetDateTime.parse("2026-06-16T11:00:00+05:30").toInstant(), ZoneOffset.UTC);
  private static final InstrumentKey NIFTY_FUT = new InstrumentKey("NFO", "NIFTY26JUNFUT");

  @Test
  void snapshotMapsQuotesToRowsAndComputesOiChangeAcrossPasses() {
    List<FuturesOiSnapshotRepository.Row> captured = new ArrayList<>();
    FuturesOiSnapshotRepository repo =
        new FuturesOiSnapshotRepository(null) {
          @Override
          public void insertAll(List<Row> rows) {
            captured.addAll(rows);
          }
        };
    FuturesContractSource contracts =
        (underlying, onOrAfter) ->
            List.of(new FutContract(NIFTY_FUT, LocalDate.parse("2026-06-25")));
    Map<InstrumentKey, Long> oiByPass = new HashMap<>();
    QuoteGateway quotes =
        keys ->
            Map.of(
                NIFTY_FUT,
                new QuoteGateway.Quote(
                    NIFTY_FUT, new java.math.BigDecimal("23950"), null,
                    1000L, oiByPass.get(NIFTY_FUT), OffsetDateTime.now(CLOCK)));

    FuturesOiSnapshotService svc =
        new FuturesOiSnapshotService(
            contracts, quotes, repo, MarketCalendar.nse(), CLOCK,
            List.of("NIFTY 50"), new SimpleMeterRegistry());

    oiByPass.put(NIFTY_FUT, 5_000L);
    svc.snapshotNow();
    assertThat(captured).hasSize(1);
    assertThat(captured.get(0).oi()).isEqualTo(5_000L);
    assertThat(captured.get(0).oiChange()).isNull(); // first pass — no previous

    captured.clear();
    oiByPass.put(NIFTY_FUT, 5_300L);
    svc.snapshotNow();
    assertThat(captured.get(0).oi()).isEqualTo(5_300L);
    assertThat(captured.get(0).oiChange()).isEqualTo(300L); // 5300 - 5000
  }
}
```

- [ ] **Step 2: Run — expect RED** (`FuturesOiSnapshotService` does not exist).
  `... test -Dtest=FuturesOiSnapshotServiceTest -DfailIfNoTests=false` → compile error / fail.

- [ ] **Step 3: Implement the service**

```java
package in.arthayantra.marketdata.futures;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.kite.FuturesContractSource;
import in.arthayantra.marketdata.kite.FuturesContractSource.FutContract;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import in.arthayantra.common.web.time.Ist;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Phase-F futures OI snapshotter — mirrors OptionsSnapshotService. Live profile only. */
@Service
@org.springframework.context.annotation.Profile("live")
public class FuturesOiSnapshotService {

  private static final Logger log = LoggerFactory.getLogger(FuturesOiSnapshotService.class);

  private final FuturesContractSource contracts;
  private final QuoteGateway quoteGateway;
  private final FuturesOiSnapshotRepository repository;
  private final MarketCalendar calendar;
  private final Clock clock;
  private final List<String> underlyings;
  private final Counter rows;
  private final Map<String, Long> previousOi = new ConcurrentHashMap<>();

  public FuturesOiSnapshotService(
      FuturesContractSource contracts,
      QuoteGateway quoteGateway,
      FuturesOiSnapshotRepository repository,
      MarketCalendar calendar,
      Clock clock,
      @Value("${artha.futures.oi-snapshot-underlyings:NIFTY 50,NIFTY BANK}") List<String> underlyings,
      MeterRegistry meterRegistry) {
    this.contracts = contracts;
    this.quoteGateway = quoteGateway;
    this.repository = repository;
    this.calendar = calendar;
    this.clock = clock;
    this.underlyings = underlyings;
    this.rows = meterRegistry.counter("ay_futures_oi_snapshot_rows_total");
  }

  @Scheduled(fixedDelayString = "${artha.futures.oi-snapshot-interval-ms:180000}", initialDelay = 70_000)
  public void scheduledSnapshot() {
    if (!calendar.isOpen(clock.instant())) {
      return;
    }
    snapshotNow();
  }

  /** One pass across all configured underlyings' front/next/far contracts. */
  public void snapshotNow() {
    OffsetDateTime ts = OffsetDateTime.now(clock);
    LocalDate today = ts.atZoneSameInstant(Ist.ZONE).toLocalDate();
    List<FuturesOiSnapshotRepository.Row> out = new ArrayList<>();
    for (String underlying : underlyings) {
      List<FutContract> ladder;
      try {
        ladder = contracts.monthlyFutures(underlying.trim(), today);
      } catch (RuntimeException e) {
        log.warn("futures OI snapshot resolve failed for {}: {}", underlying, e.getMessage());
        continue;
      }
      if (ladder.isEmpty()) {
        continue;
      }
      List<InstrumentKey> keys = ladder.stream().map(FutContract::key).toList();
      Map<InstrumentKey, QuoteGateway.Quote> quotes = quoteGateway.quotes(keys);
      for (FutContract c : ladder) {
        QuoteGateway.Quote q = quotes.get(c.key());
        if (q == null) {
          continue;
        }
        String sym = c.key().tradingsymbol();
        Long oi = q.oi();
        Long prev = previousOi.get(sym);
        Long oiChange = (oi != null && prev != null) ? oi - prev : null;
        out.add(
            new FuturesOiSnapshotRepository.Row(
                ts, underlying.trim(), sym, c.expiry(), q.lastPrice(), q.volume(), oi, oiChange));
        if (oi != null) {
          previousOi.put(sym, oi);
        }
      }
    }
    if (!out.isEmpty()) {
      repository.insertAll(out);
      rows.increment(out.size());
    }
  }
}
```

- [ ] **Step 4: Run — expect GREEN** (`-Dtest=FuturesOiSnapshotServiceTest`). Check surefire `Tests run: 1, Failures: 0`.

- [ ] **Step 5: Commit** — `git commit -m "feat(market-data): futures OI snapshot service (TDD)"`

---

## Task 4: Live-profile config

**Files:** Modify `services/market-data-service/src/main/resources/application.yml` (the existing `on-profile: live` block added in PR #20)

- [ ] **Step 1: Add under the live `artha:` block**

```yaml
  futures:
    oi-snapshot-underlyings: NIFTY 50,NIFTY BANK
    oi-snapshot-interval-ms: 180000
```

- [ ] **Step 2: Commit** — `git commit -m "chore(market-data): live futures OI snapshot config"`

---

## Task 5: Regression + deploy + live verify

- [ ] **Step 1:** Full market-data suite green: `... -pl services/market-data-service -am test` → `Tests run: N, Failures: 0, Errors: 0` + ModularityTest pass (no new cross-module cycle — `futures` already depends on `kite`).
- [ ] **Step 2:** Build JAR (`package -DskipTests`), `docker compose ... build market-data-service && up -d --no-deps market-data-service` (live env `ARTHA_DB_NAME=artha ARTHA_REDIS_DB=0`), then `up --no-deps flyway-init` to apply V011 to `artha`.
- [ ] **Step 3 (verify live):** during market hours —
  `SELECT underlying, count(*) , count(distinct tradingsymbol), max(ts) FROM marketdata.futures_oi_snapshots WHERE ts > now()-interval '5 min' GROUP BY underlying;`
  Expected: NIFTY 50 + NIFTY BANK, ~3 contracts (front/next/far) each, fresh `ts`. OI non-null; oi_change null on first pass then populated.
- [ ] **Step 4:** branch → PR → CI green (`build-test`/`contracts`/`flyway`/`gitleaks`) → squash-merge.

---

## Self-review notes
- **Scope:** futures-OI only. NSE-EOD ingestion (Phase 1b) is a **separate plan** (external NSE fetch, anti-bot, caching — different subsystem).
- **No Modulith risk:** all new classes live in the `futures` package, which already depends on `kite` (QuoteGateway/FuturesContractSource) and `marketcalendar` — no new cross-module edge.
- **A2 retention:** matches options — keep history, compress after 7 d, manual prune only (no auto-drop).
- **Cadence:** 3-min (180 s) to match the options snapshotter; revisit toward 1-min + downsample caggs if the oipulse interval dropdown needs it (master-plan D3).
