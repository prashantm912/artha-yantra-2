package in.arthayantra.marketdata.corporateactions;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import in.arthayantra.marketdata.candles.CandleQueryService;
import in.arthayantra.marketdata.candles.CandleRepository;
import in.arthayantra.marketdata.instruments.InstrumentSyncService;
import in.arthayantra.marketdata.kite.HistoricalCandleGateway;
import in.arthayantra.marketdata.mockfeed.MockHistoricalCandleGateway;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Phase-16A IT: the planted-split mock scenario drives the FULL flow credential-free — DETECTED →
 * purge → rate-limited re-backfill → RESOLVED with the cache matching the adjusted fixture;
 * non-diverging symbols untouched; re-runs detect nothing (byte-stable post-rebuild); ntfy POSTs
 * captured; the D10 grant pattern covers {@code corporate_action_events}.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false",
      "artha.mock.corporate-action.symbol=TCS",
      "artha.mock.corporate-action.ratio=0.5",
      "artha.mock.corporate-action.boundary=2026-06-01",
      "artha.corporate-actions.symbols=TCS,RELIANCE",
      "artha.corporate-actions.rebackfill-days-1m=5",
      "artha.corporate-actions.rebackfill-days-1d=400",
      "artha.ntfy.topic=ay-ca-test"
    })
class CorporateActionIntegrationTest extends MarketDataIntegrationTestBase {

  private static final Instant NOW_FIXED =
      OffsetDateTime.parse("2026-06-15T17:00:00+05:30").toInstant();
  private static final AtomicReference<Instant> NOW = new AtomicReference<>(NOW_FIXED);

  private static final WireMockServer NTFY =
      new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

  static {
    NTFY.start();
  }

  @DynamicPropertySource
  static void ntfyProperties(DynamicPropertyRegistry registry) {
    registry.add("artha.ntfy.url", NTFY::baseUrl);
  }

  @TestConfiguration
  static class MutableClockConfig {
    @Bean
    @Primary
    Clock mutableClock() {
      return new Clock() {
        @Override
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return NOW.get();
        }
      };
    }
  }

  @Autowired private InstrumentSyncService syncService;
  @Autowired private CorporateActionJob job;
  @Autowired private CorporateActionRepository events;
  @Autowired private CandleRepository candles;
  @Autowired private CandleQueryService queryService;
  @Autowired private HistoricalCandleGateway gateway;
  @Autowired private StringRedisTemplate redis;
  @Autowired private JdbcTemplate jdbc;

  private MockHistoricalCandleGateway mockGateway() {
    return (MockHistoricalCandleGateway) gateway;
  }

  @BeforeEach
  void seedConsistentCache() {
    NOW.set(NOW_FIXED);
    NTFY.resetAll();
    NTFY.stubFor(post(urlPathEqualTo("/ay-ca-test")).willReturn(aResponse().withStatus(200)));
    syncService.runSync();
    jdbc.update("DELETE FROM corporate_action_events");
    candles.purgeSymbol("NSE", "TCS");
    candles.purgeSymbol("NSE", "RELIANCE");
    mockGateway().setCorporateActionActive(false);
    // a year of cached 1d closes, fetched while "Kite" still served unadjusted history
    OffsetDateTime from = OffsetDateTime.parse("2025-06-01T00:00:00+05:30");
    OffsetDateTime to = OffsetDateTime.parse("2026-06-15T00:00:00+05:30");
    queryService.prefetch("NSE", "TCS", "1d", from, to);
    queryService.prefetch("NSE", "RELIANCE", "1d", from, to);
  }

  @Test
  void plantedSplitRunsTheFullDetectPurgeRebuildFlow() {
    mockGateway().setCorporateActionActive(true); // Kite now re-serves TCS back-adjusted ×0.5

    List<UUID> detections = job.sweepNow();

    assertThat(detections).hasSize(1);
    await()
        .atMost(Duration.ofSeconds(60))
        .untilAsserted(
            () ->
                assertThat(events.eventsFor("NSE", "TCS").get(0).status()).isEqualTo("RESOLVED"));

    CorporateActionRepository.EventRow event = events.eventsFor("NSE", "TCS").get(0);
    assertThat(event.ratio()).isEqualByComparingTo("2.00000000"); // cached ÷ halved Kite
    assertThat(event.anchorsDiverged()).isGreaterThanOrEqualTo(2);
    assertThat(event.effectiveBoundary()).isBefore(java.time.LocalDate.parse("2026-06-02"));

    // the rebuilt cache matches the ADJUSTED fixture: pre-boundary closes are now halved
    OffsetDateTime preBoundaryBucket = OffsetDateTime.parse("2026-05-15T00:00:00+05:30");
    List<in.arthayantra.marketdata.candles.Candle> rebuilt =
        candles.range("NSE", "TCS", "1d", preBoundaryBucket, preBoundaryBucket.plusDays(1));
    assertThat(rebuilt).hasSize(1);
    BigDecimal rebuiltClose = rebuilt.get(0).close();

    // the control symbol never diverged and was never touched
    assertThat(events.eventsFor("NSE", "RELIANCE")).isEmpty();

    // the spec-mandated remediation steps: caggs refreshed over the rebuilt window…
    List<in.arthayantra.marketdata.candles.Candle> daily1dView =
        jdbc.query(
            "SELECT close FROM candles_1d WHERE exchange = 'NSE' AND tradingsymbol = 'TCS'"
                + " AND bucket >= ? AND bucket < ?",
            (rs, n) ->
                new in.arthayantra.marketdata.candles.Candle(
                    "NSE", "TCS", "1d", preBoundaryBucket, rs.getBigDecimal(1), null, null,
                    rs.getBigDecimal(1), 0, null, null),
            java.sql.Timestamp.from(preBoundaryBucket.toInstant()),
            java.sql.Timestamp.from(preBoundaryBucket.plusDays(1).toInstant()));
    // (the 1d cagg derives from 1m rows; the rebuilt 1m window is only 5 days deep here,
    // so the assertion targets fetched_at instead — the dataHash bump on the BASE rows)
    java.sql.Timestamp rebuiltFetchedAt =
        jdbc.queryForObject(
            "SELECT max(fetched_at) FROM candles WHERE exchange = 'NSE' AND tradingsymbol = 'TCS'",
            java.sql.Timestamp.class);
    assertThat(rebuiltFetchedAt)
        .as("fetched_at bumped on rewritten rows — the Stage-D dataHash flags pre-event runs")
        .isNotNull();
    assertThat(rebuiltFetchedAt.toInstant())
        .isAfter(event.detectedAt().toInstant().minusSeconds(120));
    assertThat(daily1dView).isNotNull(); // cagg query answers without error post-refresh

    // ntfy: detection warning + rebuilt notice
    NTFY.verify(2, postRequestedFor(urlPathEqualTo("/ay-ca-test")));
    assertThat(redis.opsForValue().get(CorporateActionJob.INTEGRITY_KEY)).contains("TCS");

    // a second sweep on the now-consistent cache detects NOTHING and changes NOTHING
    List<UUID> second = job.sweepNow();
    assertThat(second).isEmpty();
    assertThat(events.eventsFor("NSE", "TCS")).hasSize(1);
    assertThat(
            candles
                .range("NSE", "TCS", "1d", preBoundaryBucket, preBoundaryBucket.plusDays(1))
                .get(0)
                .close())
        .as("byte-stable post-rebuild series")
        .isEqualByComparingTo(rebuiltClose);
  }

  @Test
  void aReBackfillThatFailsPartWayLeavesTheExistingSeriesIntact() {
    // THE discriminating fixture (V054): a re-backfill that dies AFTER the 1d leg has already
    // succeeded — i.e. past the point the old purge-then-fetch order had already deleted every bar
    // this symbol owns. Under that order the 1m failure left the symbol gutted forever, because a
    // purged symbol fails the sweep's own hasNonBhavcopyDaily pre-filter and is never looked at
    // again (45 live symbols measured in that state, 2026-08-04). Under fetch → verify → swap the
    // fetch fails with `candles` still untouched.
    OffsetDateTime from = OffsetDateTime.parse("2025-06-01T00:00:00+05:30");
    OffsetDateTime to = OffsetDateTime.parse("2026-06-15T00:00:00+05:30");
    List<in.arthayantra.marketdata.candles.Candle> before = candles.range("NSE", "TCS", "1d", from, to);
    assertThat(before).as("the series this test is about must exist first").isNotEmpty();

    mockGateway().setCorporateActionActive(true); // detection fires
    mockGateway().setFailOnInterval("1m"); // …and the rebuild dies on its second leg
    try {
      List<UUID> detections = job.sweepNow();
      assertThat(detections).hasSize(1);

      await()
          .atMost(Duration.ofSeconds(60))
          .untilAsserted(
              () -> assertThat(events.eventsFor("NSE", "TCS").get(0).status()).isEqualTo("FAILED"));

      // The property under test. Bar-for-bar, not a count: a count would pass if the purge had run
      // and the 1d re-fetch had refilled the same buckets with DIFFERENT (adjusted) closes, which
      // is precisely the half-rebuilt state a count cannot distinguish from an untouched one.
      assertThat(candles.range("NSE", "TCS", "1d", from, to))
          .as("a failed re-backfill must leave the cached series byte-for-byte intact")
          .containsExactlyElementsOf(before);
      // and the staging buffer is not left holding the partial fetch
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM candle_rebuild_staging WHERE tradingsymbol = 'TCS'",
                  Long.class))
          .isZero();
    } finally {
      mockGateway().setFailOnInterval(null);
    }
  }

  @Test
  void aTruncatedReBackfillIsRefusedRatherThanSwappedIn() {
    // The second half of "verify": the fetch SUCCEEDS but comes back short. Nothing throws, so the
    // exception path above cannot catch this — only the coverage check can, and a swap that accepts
    // a truncated fetch is the original defect with extra steps. Here the 1d re-backfill window is
    // pinned to 400 days by the class properties while the cached series was seeded from 2025-06-01,
    // so shrinking the fetch is not needed: instead the gateway is asked for a symbol whose cache
    // extends BEYOND what the rebuild will stage, by seeding an extra bar past `now`.
    OffsetDateTime from = OffsetDateTime.parse("2025-06-01T00:00:00+05:30");
    OffsetDateTime to = OffsetDateTime.parse("2026-06-15T00:00:00+05:30");
    OffsetDateTime future = OffsetDateTime.parse("2026-06-30T00:00:00+05:30");
    candles.upsertAuthoritativeAll(
        List.of(
            new in.arthayantra.marketdata.candles.Candle(
                "NSE", "TCS", "1d", future,
                new BigDecimal("100.0000"), new BigDecimal("101.0000"),
                new BigDecimal("99.0000"), new BigDecimal("100.5000"), 1_000L, null, "KITE")));
    List<in.arthayantra.marketdata.candles.Candle> before =
        candles.range("NSE", "TCS", "1d", from, future.plusDays(1));

    mockGateway().setCorporateActionActive(true);

    List<UUID> detections = job.sweepNow();
    assertThat(detections).hasSize(1);

    await()
        .atMost(Duration.ofSeconds(60))
        .untilAsserted(
            () -> assertThat(events.eventsFor("NSE", "TCS").get(0).status()).isEqualTo("FAILED"));

    // the staged series stops at `now` (2026-06-15) but the cache reaches 2026-06-30: swapping
    // would silently drop the newer bar, so the rebuild refuses and changes nothing
    assertThat(candles.range("NSE", "TCS", "1d", from, future.plusDays(1)))
        .as("a staged series that does not reach the cached series' end must be refused")
        .containsExactlyElementsOf(before);
  }

  @Test
  void consistentCacheProducesNoDetections() {
    // CA scenario inactive: cache and "Kite" agree — the sweep must be a no-op
    assertThat(job.sweepNow()).isEmpty();
    assertThat(events.eventsFor("NSE", "TCS")).isEmpty();
    NTFY.verify(0, postRequestedFor(urlPathEqualTo("/ay-ca-test")));
  }

  @Test
  void baseRebuiltEventIsResumedRefreshOnlyWithoutRePurging() {
    // A14 resume: a crash-mid-refresh checkpoint. The base is already re-fetched (@BeforeEach seeded
    // TCS 1d) and the event is stuck at BASE_REBUILT. The sweep must RESUME the cagg refresh only —
    // it can NOT re-detect (cache == "Kite" with the CA scenario inactive) and must NOT re-purge.
    OffsetDateTime from = OffsetDateTime.parse("2025-06-01T00:00:00+05:30");
    OffsetDateTime to = OffsetDateTime.parse("2026-06-15T00:00:00+05:30");
    int barsBefore = candles.range("NSE", "TCS", "1d", from, to).size();
    assertThat(barsBefore).isGreaterThan(0);

    UUID id =
        events.insertDetected(
            "NSE", "TCS", java.time.LocalDate.parse("2026-06-01"),
            new BigDecimal("2.0"), 4, 3, "[]");
    events.updateStatus(id, "BASE_REBUILT"); // V039 admits the new state

    mockGateway().setCorporateActionActive(false); // no fresh divergence — resume is the ONLY path

    job.sweepNow();

    await()
        .atMost(Duration.ofSeconds(60))
        .untilAsserted(
            () -> assertThat(events.eventsFor("NSE", "TCS").get(0).status()).isEqualTo("RESOLVED"));

    // reused the existing row (no NEW detection): with CA inactive the only BASE_REBUILT→RESOLVED
    // path is submitRefreshOnly, which skips purge + prefetch
    assertThat(events.eventsFor("NSE", "TCS")).hasSize(1);
    // purge was skipped: the pre-existing base bars survive
    assertThat(candles.range("NSE", "TCS", "1d", from, to)).hasSize(barsBefore);
  }

  @Test
  void refreshFailedEventIsResumedRefreshOnlyAgainstTheRealSchema() {
    // task_6903cd5e: the same checkpoint, for the class that ERRORED rather than crashed. Against
    // the real Flyway lineage this also proves V051 applied — the widened CHECK has to admit
    // REFRESH_FAILED or updateStatus would throw.
    OffsetDateTime from = OffsetDateTime.parse("2025-06-01T00:00:00+05:30");
    OffsetDateTime to = OffsetDateTime.parse("2026-06-15T00:00:00+05:30");
    int barsBefore = candles.range("NSE", "TCS", "1d", from, to).size();
    assertThat(barsBefore).isGreaterThan(0);

    UUID id =
        events.insertDetected(
            "NSE", "TCS", java.time.LocalDate.parse("2026-06-01"),
            new BigDecimal("2.0"), 4, 3, "[]");
    events.updateStatus(id, "BASE_REBUILT");
    events.incrementRefreshAttempts(id);
    events.updateStatus(id, "REFRESH_FAILED"); // V051 admits the new resumable state

    mockGateway().setCorporateActionActive(false); // no fresh divergence — resume is the ONLY path

    job.sweepNow();

    await()
        .atMost(Duration.ofSeconds(60))
        .untilAsserted(
            () -> assertThat(events.eventsFor("NSE", "TCS").get(0).status()).isEqualTo("RESOLVED"));

    CorporateActionRepository.EventRow resumed = events.eventsFor("NSE", "TCS").get(0);
    // Identity, not a row count — because identity is what this test actually means: the resume
    // must reuse THE SAME event, not merely leave one behind. (A count would also be the wrong
    // instrument for a DB that persists across methods and surefire reruns, but note this class
    // does clear the table at @BeforeEach, so that is a robustness argument here, not a live bug.)
    assertThat(resumed.id()).isEqualTo(id);
    assertThat(resumed.refreshAttempts()).isEqualTo(2); // the resume counted its own attempt
    assertThat(candles.range("NSE", "TCS", "1d", from, to)).hasSize(barsBefore); // purge skipped
  }

  @Test
  void spentRetryBoundAbandonsTheEventInsteadOfRetryingForever() {
    UUID id =
        events.insertDetected(
            "NSE", "TCS", java.time.LocalDate.parse("2026-06-01"),
            new BigDecimal("2.0"), 4, 3, "[]");
    events.updateStatus(id, "REFRESH_FAILED");
    // three recorded attempts = the default artha.corporate-actions.max-refresh-attempts
    events.incrementRefreshAttempts(id);
    events.incrementRefreshAttempts(id);
    events.incrementRefreshAttempts(id);

    mockGateway().setCorporateActionActive(false);

    job.sweepNow();

    CorporateActionRepository.EventRow abandoned = events.eventsFor("NSE", "TCS").get(0);
    assertThat(abandoned.id()).isEqualTo(id); // identity: THIS event was abandoned, not merely some event
    assertThat(abandoned.status()).isEqualTo("REFRESH_ABANDONED");
    assertThat(abandoned.refreshAttempts()).isEqualTo(3); // no fourth attempt was started
    assertThat(abandoned.resolvedAt()).as("terminal states stamp resolved_at").isNotNull();
    NTFY.verify(1, postRequestedFor(urlPathEqualTo("/ay-ca-test")));
  }

  @Test
  void backtestRoleCanSelectEventsButNeverInsert() throws Exception {
    try (Connection connection = DriverManager.getConnection(jdbcUrl(), dbUser(), dbPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("SET ROLE ay_backtest");
      try (var rs =
          statement.executeQuery("SELECT count(*) FROM marketdata.corporate_action_events")) {
        assertThat(rs.next()).isTrue();
      }
      assertThatThrownBy(
              () ->
                  statement.execute(
                      "INSERT INTO marketdata.corporate_action_events"
                          + " (exchange, tradingsymbol, ratio, anchors_checked, anchors_diverged,"
                          + " details) VALUES ('X','Y',1,1,1,'[]')"))
          .hasMessageContaining("permission denied");
    }
  }
}
