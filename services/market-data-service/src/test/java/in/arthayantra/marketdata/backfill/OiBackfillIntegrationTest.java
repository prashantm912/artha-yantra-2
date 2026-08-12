package in.arthayantra.marketdata.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.instruments.Instrument;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import in.arthayantra.marketdata.instruments.InstrumentSyncService;
import in.arthayantra.marketdata.kite.HistoricalCandleGateway.Candle;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.OiHistorySource;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * OI-backfill importer IT (data-foundation milestone, mock profile). A stub {@link OiHistorySource}
 * (the only such bean in mock — the live OpenAlgo client is live-profile-only) feeds canned 1m
 * OHLC+OI, so the backfill writes {@code source='BACKFILL'} rows into the snapshot tables, is
 * idempotent on a re-run, and the admin endpoint 202s.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
@Import(OiBackfillIntegrationTest.StubHistory.class)
class OiBackfillIntegrationTest extends MarketDataIntegrationTestBase {

  private static final String UNDERLYING = "NIFTY 50";
  private static final LocalDate SESSION = LocalDate.parse("2026-06-15"); // a trading Monday

  @Autowired private MockMvc mockMvc;
  @Autowired private InstrumentSyncService syncService;
  @Autowired private InstrumentRepository instruments;
  @Autowired private OiBackfillService service;
  @Autowired private JdbcTemplate jdbc;

  private LocalDate expiry;

  @BeforeEach
  void seed() {
    syncService.runSync();
    expiry = instruments.expiries(UNDERLYING).get(0);
    assertThat(instruments.optionChain(UNDERLYING, expiry)).isNotEmpty();
  }

  @Test
  void backfillWritesBackfillProvenanceRowsAndIsIdempotent() {
    OiBackfillService.BackfillResult first = service.runBackfill(UNDERLYING, expiry, SESSION, "it-1");
    assertThat(first.optionRows()).as("each chain leg samples to 5-min buckets").isGreaterThan(0);

    long backfilled = ours("source = 'BACKFILL'");
    assertThat(backfilled).isGreaterThan(0);

    // oi_change is null on a leg's first bucket and a Long thereafter (sampled OI diff).
    assertThat(ours("source = 'BACKFILL' AND oi_change IS NOT NULL")).isGreaterThan(0);

    // greeks left null; provenance also stamped on the row fields.
    assertThat(ours("source = 'BACKFILL' AND (iv IS NOT NULL OR delta IS NOT NULL)")).isZero();
    assertThat(ours("price_source = 'BACKFILL'")).isEqualTo(backfilled);

    // a re-run is a no-op (ON CONFLICT DO NOTHING on the snapshot PK).
    service.runBackfill(UNDERLYING, expiry, SESSION, "it-2");
    assertThat(ours("source = 'BACKFILL'")).isEqualTo(backfilled);
  }

  /**
   * ⚠️ THE ISOLATION PROOF. Seeds a BACKFILL row for a DIFFERENT underlying and expiry, carrying a
   * non-null {@code iv} and a DIFFERENT {@code price_source} — mirroring the real contaminant in
   * {@code OptionsChainIntegrationTest}, and precisely the row shape that reddened CI run
   * 31515420664 with
   * "expected: 0L but was: 1L" — then asserts the scoped queries do not see it while an unscoped
   * one does.
   *
   * <p>Without the second half this test would pass for the wrong reason: a scoped query returning
   * zero proves nothing unless the foreign row is demonstrably THERE to be miscounted.
   */
  @Test
  void assertionsIgnoreAnotherTestsBackfillRows() {
    jdbc.update(
        "INSERT INTO options_chain_snapshots"
            + " (ts, underlying, expiry, strike, option_type, tradingsymbol, ltp, oi, spot_price,"
            + " iv, source, price_source)"
            + " VALUES (?,?,?,?::numeric,?,?,?::numeric,?,?::numeric,?::numeric,'BACKFILL','MID')"
            + " ON CONFLICT DO NOTHING",
        java.sql.Timestamp.from(
            SESSION.atTime(10, 0).atZone(Ist.ZONE).toInstant()),
        "SOMEONE ELSES INDEX",
        java.sql.Date.valueOf(expiry.plusDays(7)),
        "12345",
        "CE",
        "FOREIGNCE12345",
        "1.5",
        42L,
        "100",
        "0.25");

    assertThat(count("SELECT count(*) FROM options_chain_snapshots"
            + " WHERE source = 'BACKFILL' AND iv IS NOT NULL"))
        .as("the foreign row must really be in the table, or this proves nothing")
        .isGreaterThan(0);
    assertThat(ours("source = 'BACKFILL' AND (iv IS NOT NULL OR delta IS NOT NULL)"))
        .as("...and the scoped query must not see it — this is the assertion that reddened on CI")
        .isZero();
    assertThat(ours("source = 'BACKFILL'"))
        .as("a foreign row must not inflate our own row count either")
        .isEqualTo(ours("price_source = 'BACKFILL'"));
  }

  @Test
  void adminEndpointAccepts() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/market/admin/oi-backfill")
                .contentType("application/json")
                .content(
                    "{\"underlying\":\""
                        + UNDERLYING
                        + "\",\"expiry\":\""
                        + expiry
                        + "\",\"date\":\""
                        + SESSION
                        + "\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.jobId").isString());
  }

  @Test
  void migrationAddsUuidJobIdColumnToBackfillJobs() {
    Long cols =
        jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema = 'marketdata' AND table_name = 'backfill_jobs' "
                + "AND column_name = 'job_id' AND data_type = 'uuid'",
            Long.class);
    assertThat(cols).as("V041 adds the uuid job_id column").isEqualTo(1L);
  }

  @Test
  void triggerAsyncWritesAnOiAuditRowCorrelatableByJobId() {
    awaitNotRunning();
    Map<String, String> handle = service.triggerAsync(UNDERLYING, expiry, SESSION);
    String jobId = handle.get("jobId");
    assertThat(jobId).isNotBlank();

    OiBackfillService.Status done = awaitDone();
    assertThat(done.state()).isEqualTo("OK");

    // The OI-backfill path now writes its OWN backfill_jobs row (audit V12 — it wrote NONE before),
    // kind='OI', carrying the same jobId the trigger + status quote, so a status poll can be joined
    // back to its ledger row.
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT kind, job_id::text AS job_id, status, rows_written "
                + "FROM backfill_jobs WHERE job_id = ?::uuid",
            jobId);
    assertThat(row.get("kind")).isEqualTo("OI");
    assertThat(row.get("job_id")).isEqualTo(jobId);
    assertThat(row.get("status")).isEqualTo("COMPLETED");
    assertThat(((Number) row.get("rows_written")).longValue())
        .isEqualTo((long) done.optionRows() + done.futuresRows());
  }

  /** Waits until no OI backfill holds the single-run lock (a prior async run may still be finishing). */
  private void awaitNotRunning() {
    for (int i = 0; i < 250; i++) {
      if (!"RUNNING".equals(service.status().state())) {
        return;
      }
      sleep();
    }
    throw new AssertionError("an OI backfill stayed RUNNING too long");
  }

  /** Blocks until the async run leaves RUNNING (OK/FAILED), or fails after ~5s. */
  private OiBackfillService.Status awaitDone() {
    for (int i = 0; i < 250; i++) {
      OiBackfillService.Status s = service.status();
      if ("OK".equals(s.state()) || "FAILED".equals(s.state())) {
        return s;
      }
      sleep();
    }
    throw new AssertionError("OI backfill did not finish in time: " + service.status().state());
  }

  private static void sleep() {
    try {
      Thread.sleep(20);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private long count(String sql) {
    Long n = jdbc.queryForObject(sql, Long.class);
    return n == null ? 0 : n;
  }

  /**
   * Rows in {@code options_chain_snapshots} that THIS test created, matching {@code predicate}.
   *
   * <p>⚠️ Every assertion here goes through this, and that is the point. They used to count over the
   * whole table filtered only by {@code source = 'BACKFILL'}, which made them assertions about every
   * other test's data as well, on a singleton Testcontainers DB with NO per-method cleanup that keeps
   * state across methods and across surefire reruns. Execution order then decided whether
   * {@code assertThat(greeked).isZero()} passed. It failed that way on CI run 31515420664
   * ("expected: 0L but was: 1L") while passing locally every time.
   *
   * <p>The contaminating row is NAMED, not hypothesised:
   * {@code OptionsChainIntegrationTest.theDegradeIgnoresBackfillUpstoxAndQuarantinedRows} inserts a
   * {@code source='BACKFILL'} leg carrying {@code iv=0.181500} and {@code delta=0.512300}, which is
   * precisely what makes {@code greeked} 1 instead of 0. It shares this fixture's SESSION
   * (2026-06-15) and its EXPIRY (2026-06-16) — only {@code underlying} separates them, so the
   * underlying predicate below is the one doing the work and the other two are defence in depth.
   *
   * <p>An earlier version of this comment claimed five classes write such rows. That came from
   * grepping for the string "BACKFILL", not from reading what each writes: two of them touch only
   * candles or mock data, and one never inserts a snapshot at all. Cross-vendor review corrected it.
   *
   * <p>A table-wide DELETE in {@code @BeforeEach} would be the wrong fix: the DB is shared with
   * other classes, so deleting their rows turns a read-flake into a write-flake, which is worse and
   * harder to attribute.
   *
   * <p>The session bound is written as explicit {@code +05:30} literals. {@code ts} is a
   * {@code timestamptz} and the container's {@code now()}/{@code ::date} are UTC, so a bare
   * {@code ::date} comparison is off by one across IST midnight.
   */
  private long ours(String predicate) {
    return count(
        "SELECT count(*) FROM options_chain_snapshots"
            + " WHERE underlying = '" + UNDERLYING + "'"
            + " AND expiry = DATE '" + expiry + "'"
            + " AND ts >= TIMESTAMPTZ '" + SESSION + "T00:00:00+05:30'"
            + " AND ts <  TIMESTAMPTZ '" + SESSION.plusDays(1) + "T00:00:00+05:30'"
            + " AND " + predicate);
  }

  /** Canned 1m OHLC+OI for any key: a 12-minute rising ramp from the session start (≥3 buckets). */
  @TestConfiguration
  static class StubHistory {
    @Bean
    OiHistorySource stubHistorySource() {
      return (key, from, to) -> ramp(key, from);
    }

    private static List<Candle> ramp(InstrumentKey key, Instant from) {
      OffsetDateTime start = OffsetDateTime.ofInstant(from, Ist.ZONE);
      List<Candle> bars = new ArrayList<>();
      for (int i = 0; i < 12; i++) {
        BigDecimal close = BigDecimal.valueOf(100 + i);
        bars.add(
            new Candle(
                key, "1m", start.plusMinutes(i), close, close, close, close, 10L, 1000L + i * 5L));
      }
      return bars;
    }
  }
}
