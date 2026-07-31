package in.arthayantra.marketdata.candles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketdata.kite.HistoricalCandleGateway;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.mockfeed.MockHistoricalCandleGateway;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpServerErrorException;

/**
 * Phase-11 IT (Flow 3): cold fetch fills, the second read is a PURE cache hit (zero gateway-port
 * invocations), partial coverage fetches only the missing sub-range (the v1 full-re-fetch
 * anti-pattern is the FAIL criterion), fetch failure serves cached data flagged stale, 1w never
 * touches the gateway, and the REST surface returns the envelope.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
class CandleQueryIntegrationTest extends MarketDataIntegrationTestBase {

  /** Counts and records every gateway-port invocation; can be told to fail. */
  static class CountingGateway implements HistoricalCandleGateway {
    final AtomicInteger calls = new AtomicInteger();
    final List<String> intervals = new CopyOnWriteArrayList<>();
    final List<Instant> fetchStarts = new CopyOnWriteArrayList<>();
    final AtomicBoolean fail = new AtomicBoolean();
    private final HistoricalCandleGateway delegate;

    CountingGateway(HistoricalCandleGateway delegate) {
      this.delegate = delegate;
    }

    @Override
    public List<HistoricalCandleGateway.Candle> fetch(
        InstrumentKey key, String interval, Instant from, Instant to) {
      calls.incrementAndGet();
      intervals.add(interval);
      fetchStarts.add(from);
      if (fail.get()) {
        throw new HttpServerErrorException(org.springframework.http.HttpStatus.BAD_GATEWAY);
      }
      return delegate.fetch(key, interval, from, to);
    }
  }

  @TestConfiguration
  static class CountingGatewayConfig {
    @Bean
    @Primary
    CountingGateway countingGateway(TradingBuckets tradingBuckets) {
      return new CountingGateway(
          new MockHistoricalCandleGateway(
              tradingBuckets, 42, "NONE", BigDecimal.ONE, LocalDate.parse("2026-01-01"), false));
    }
  }

  @Autowired private CandleQueryService queryService;
  @Autowired private CandleRepository repository;
  @Autowired private CountingGateway gateway;
  @Autowired private MockMvc mockMvc;
  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

  private static OffsetDateTime ist(String text) {
    return OffsetDateTime.parse(text + "+05:30");
  }

  // a 2026 trading hour comfortably outside the trailing 2 h recency window
  private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-02-03T09:15:00+05:30");
  private static final OffsetDateTime TO = OffsetDateTime.parse("2026-02-03T10:15:00+05:30");

  @AfterEach
  void resetGateway() {
    gateway.fail.set(false);
  }

  @Test
  void coldFetchFillsThenWarmReadIsProvablyKiteFree() {
    int before = gateway.calls.get();

    CandleQueryService.CandleRead cold = queryService.read("NSE", "CQHIT", "1m", FROM, TO);
    assertThat(cold.items()).hasSize(60);
    assertThat(cold.stale()).isFalse();
    assertThat(gateway.calls.get() - before).as("one gap, one page, one call").isEqualTo(1);

    int afterCold = gateway.calls.get();
    CandleQueryService.CandleRead warm = queryService.read("NSE", "CQHIT", "1m", FROM, TO);
    assertThat(warm.items()).hasSize(60);
    assertThat(gateway.calls.get()).as("pure cache hit — zero gateway invocations").isEqualTo(afterCold);
  }

  @Test
  void partialCoverageFetchesOnlyTheMissingSubRange() {
    // seed the first 30 minutes by hand — only [09:45, 10:15) is missing
    for (int i = 0; i < 30; i++) {
      repository.upsert(
          new Candle(
              "NSE", "CQPART", "1m", FROM.plusMinutes(i),
              new BigDecimal("100.00"), new BigDecimal("101.00"),
              new BigDecimal("99.00"), new BigDecimal("100.50"),
              100, null, "MOCK"));
    }
    int before = gateway.calls.get();

    CandleQueryService.CandleRead read = queryService.read("NSE", "CQPART", "1m", FROM, TO);

    assertThat(read.items()).hasSize(60);
    assertThat(gateway.calls.get() - before).isEqualTo(1);
    assertThat(gateway.fetchStarts.get(gateway.fetchStarts.size() - 1))
        .as("no full re-fetch on partial coverage (v1 anti-pattern)")
        .isEqualTo(ist("2026-02-03T09:45:00").toInstant());
  }

  @Test
  void midIntervalsServeFromAggregatesAfterEnsuring1mCoverage() {
    CandleQueryService.CandleRead read = queryService.read("NSE", "CQAGG", "5m", FROM, TO);

    assertThat(read.items()).hasSize(12); // 60 minutes / 5
    assertThat(read.items().get(0).bucket()).isEqualTo(ist("2026-02-03T09:15:00"));
    assertThat(gateway.intervals).as("caggs are derived — only 1m is ever fetched").doesNotContain("5m");
  }

  @Test
  void backfillBehindAdvancedCaggWatermarkStaysVisible() {
    // simulate the live stack: the 5m policy already ran, watermark sits past our window —
    // real-time union then SKIPS history inserted behind it unless the read path refreshes
    jdbc.execute(
        "CALL public.refresh_continuous_aggregate("
            + "'candles_5m', '2026-01-01T00:00:00Z'::timestamptz, '2026-03-01T00:00:00Z'::timestamptz)");

    CandleQueryService.CandleRead read = queryService.read("NSE", "CQWMK", "5m", FROM, TO);

    assertThat(read.items()).as("backfilled bars must survive an advanced watermark").hasSize(12);
  }

  @Test
  void weeklyIsRolledLocallyNeverFetched() {
    int before = gateway.calls.get();

    CandleQueryService.CandleRead read =
        queryService.read("NSE", "CQWK", "1w", ist("2026-02-01T00:00:00"), ist("2026-03-01T00:00:00"));

    assertThat(gateway.calls.get()).as("1w never touches the gateway (B-21)").isEqualTo(before);
    assertThat(read.stale()).isFalse();

    assertThatThrownBy(
            () ->
                queryService.refreshAsync(
                    "NSE", "CQWK", "1w", ist("2026-02-01T00:00:00"), ist("2026-03-01T00:00:00")))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCodes.VALIDATION_INTERVAL_UNSUPPORTED));
  }

  @Test
  void fetchFailureServesCachedPartialsFlaggedStale() {
    for (int i = 0; i < 30; i++) {
      repository.upsert(
          new Candle(
              "NSE", "CQSTALE", "1m", FROM.plusMinutes(i),
              new BigDecimal("100.00"), new BigDecimal("101.00"),
              new BigDecimal("99.00"), new BigDecimal("100.50"),
              100, null, "MOCK"));
    }
    gateway.fail.set(true);

    CandleQueryService.CandleRead read = queryService.read("NSE", "CQSTALE", "1m", FROM, TO);

    assertThat(read.stale()).as("B-3 ladder: degraded fetch → cached data marked stale").isTrue();
    assertThat(read.items()).hasSize(30);
  }

  @Test
  void databaseSizeGaugeExceedsCandlesOnlyHypertableSize() {
    // AYDB-03: the 50 GB review trigger is a WHOLE-DB volume. hypertableBytes() is candles-only
    // (~half the live DB), so the DB total must be at least the candles hypertable and strictly
    // positive — this is the size ay status now surfaces vs the trigger.
    long candlesBytes = repository.hypertableBytes();
    long dbBytes = repository.databaseSizeBytes();
    assertThat(candlesBytes).isGreaterThanOrEqualTo(0);
    assertThat(dbBytes).isGreaterThan(0);
    assertThat(dbBytes).as("whole-DB size includes candles + caggs + catalogs").isGreaterThanOrEqualTo(candlesBytes);
  }

  @Test
  void unsupportedIntervalRejectedWith400() {
    assertThatThrownBy(() -> queryService.read("NSE", "CQBAD", "2m", FROM, TO))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.httpStatus()).isEqualTo(400);
              assertThat(e.code()).isEqualTo(ErrorCodes.VALIDATION_INTERVAL_UNSUPPORTED);
            });
  }

  @Test
  void threeMinuteIsRolledReadTimeFromOneMinute() {
    // 3m has NO materialised cagg — it is time_bucket'd from the 1m base on read (the scalper PRIMARY).
    // Seed 6 known 1m bars 09:15-09:20 → two 3m buckets [09:15,09:18) + [09:18,09:21).
    long[] vol = {10, 20, 30, 40, 50, 60};
    String[][] ohlc = {
      {"100", "105", "99", "101"}, {"101", "110", "100", "108"}, {"108", "109", "104", "106"},
      {"106", "107", "102", "103"}, {"103", "104", "98", "99"}, {"99", "100", "95", "97"}
    };
    for (int i = 0; i < 6; i++) {
      repository.upsert(
          new Candle(
              "NSE", "CQ3M", "1m", FROM.plusMinutes(i),
              new BigDecimal(ohlc[i][0]), new BigDecimal(ohlc[i][1]),
              new BigDecimal(ohlc[i][2]), new BigDecimal(ohlc[i][3]),
              vol[i], null, "MOCK"));
    }
    OffsetDateTime to3 = FROM.plusMinutes(6); // [09:15, 09:21)

    CandleQueryService.CandleRead read = queryService.read("NSE", "CQ3M", "3m", FROM, to3);

    assertThat(read.items()).hasSize(2);
    Candle b0 = read.items().get(0); // 09:15-09:18 from bars 0,1,2
    assertThat(b0.interval()).isEqualTo("3m");
    assertThat(b0.open()).isEqualByComparingTo("100"); // first
    assertThat(b0.high()).isEqualByComparingTo("110"); // max
    assertThat(b0.low()).isEqualByComparingTo("99"); // min
    assertThat(b0.close()).isEqualByComparingTo("106"); // last
    assertThat(b0.volume()).isEqualTo(60L); // 10+20+30
    Candle b1 = read.items().get(1); // 09:18-09:21 from bars 3,4,5
    assertThat(b1.open()).isEqualByComparingTo("106");
    assertThat(b1.high()).isEqualByComparingTo("107");
    assertThat(b1.low()).isEqualByComparingTo("95");
    assertThat(b1.close()).isEqualByComparingTo("97");
    assertThat(b1.volume()).isEqualTo(150L); // 40+50+60
  }

  @Test
  void restSurfaceServesDecimalStringPricesWithPaging() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/candles")
                .param("exchange", "NSE")
                .param("tradingsymbol", "CQHTTP")
                .param("interval", "5m")
                .param("from", "2026-02-03T09:15:00+05:30")
                .param("to", "2026-02-03T10:15:00+05:30")
                .param("limit", "5")
                .param("offset", "5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(5))
        .andExpect(jsonPath("$.total").value(12))
        .andExpect(jsonPath("$.stale").value(false))
        .andExpect(jsonPath("$.items[0].open").isString());
  }

  @Test
  void refreshIsAccepted202AndFillsAsynchronously() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/market/candles/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"exchange":"NSE","tradingsymbol":"CQREF","interval":"1m",
                     "from":"2026-02-03T09:15:00+05:30","to":"2026-02-03T10:15:00+05:30"}
                    """))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.jobId").isString());

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () -> assertThat(repository.range("NSE", "CQREF", "1m", FROM, TO)).hasSize(60));
  }

  /**
   * An empty body (task_e2e01m(a)) used to reach {@code CandleQueryService.refreshAsync} with
   * {@code interval == null} and NPE on {@code interval.equals("1w")} — the catch-all 500. Bean
   * validation on {@link CandlesController.RefreshRequest} now rejects the missing fields before
   * the service is ever called.
   */
  @Test
  void refreshWithEmptyBodyIs400ValidationFailedNotNpe() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/market/candles/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }
}
