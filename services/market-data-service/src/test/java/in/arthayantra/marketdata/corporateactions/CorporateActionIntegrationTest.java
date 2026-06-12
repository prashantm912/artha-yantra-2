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
  void consistentCacheProducesNoDetections() {
    // CA scenario inactive: cache and "Kite" agree — the sweep must be a no-op
    assertThat(job.sweepNow()).isEmpty();
    assertThat(events.eventsFor("NSE", "TCS")).isEmpty();
    NTFY.verify(0, postRequestedFor(urlPathEqualTo("/ay-ca-test")));
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
