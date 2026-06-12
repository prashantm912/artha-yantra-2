package in.arthayantra.marketdata.futures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.candles.Candle;
import in.arthayantra.marketdata.candles.CandleRepository;
import in.arthayantra.marketdata.feed.LastTickStore;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import in.arthayantra.marketdata.instruments.InstrumentSyncService;
import in.arthayantra.marketdata.kite.QuoteGateway;
import in.arthayantra.marketdata.kite.ticker.EodBackfillJob;
import in.arthayantra.marketdata.kite.ticker.FuturesPinner;
import in.arthayantra.marketdata.kite.ticker.SubscriptionRegistry;
import in.arthayantra.marketdata.mockfeed.MockQuoteGateway;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase-15A IT (mock, credential-free): the term structure assembles from EXACTLY ONE batched
 * quote invocation; FUT pins land with pinned priority after a sync; FUT 1m bars carry OI through
 * to the 1d cagg's {@code last(oi)}; INDIA VIX history serves through the ordinary candles
 * surface — no special casing anywhere.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
class FuturesSliceIntegrationTest extends MarketDataIntegrationTestBase {

  private static final Instant OPEN = OffsetDateTime.parse("2026-06-15T11:00:00+05:30").toInstant();
  private static final AtomicReference<Instant> NOW = new AtomicReference<>(OPEN);
  private static final AtomicInteger QUOTE_CALLS = new AtomicInteger();

  @TestConfiguration
  static class FixedClockAndCountingQuotes {
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

    @Bean
    @Primary
    QuoteGateway countingQuoteGateway(
        LastTickStore lastTickStore,
        InstrumentRepository instruments,
        MarketCalendar calendar,
        Clock clock) {
      MockQuoteGateway delegate = new MockQuoteGateway(lastTickStore, instruments, calendar, clock);
      return keys -> {
        QUOTE_CALLS.incrementAndGet();
        return delegate.quotes(keys);
      };
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private InstrumentSyncService syncService;
  @Autowired private FuturesPinner futuresPinner;
  @Autowired private SubscriptionRegistry registry;
  @Autowired private EodBackfillJob eodBackfillJob;
  @Autowired private CandleRepository candleRepository;
  @Autowired private FuturesTermStructureService termStructureService;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void seedAndPin() {
    NOW.set(OPEN);
    syncService.runSync(); // publishes InstrumentMasterUpdated → pins re-resolve
  }

  @Test
  void termStructureUsesExactlyOneBatchedQuoteCall() throws Exception {
    int before = QUOTE_CALLS.get();

    mockMvc
        .perform(get("/api/v1/market/futures/term-structure").param("underlying", "NIFTY 50"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contracts.length()").value(3))
        .andExpect(jsonPath("$.state").value("CONTANGO"))
        .andExpect(jsonPath("$.stale").value(false))
        .andExpect(jsonPath("$.spot").value("24137.55"))
        .andExpect(jsonPath("$.contracts[0].tradingsymbol").value("NIFTY26JUNFUT"))
        .andExpect(jsonPath("$.contracts[0].oi").isNumber());

    assertThat(QUOTE_CALLS.get() - before)
        .as("spot + 3 contracts in ONE gateway invocation (B-18)")
        .isEqualTo(1);

    // the cost-of-carry construction puts annualized basis near the pinned 6.5%
    FuturesTermStructureService.TermStructure ts = termStructureService.termStructure("NIFTY 50");
    assertThat(ts.contracts().get(2).basisAnnualized().doubleValue()).isBetween(0.04, 0.09);
    assertThat(ts.calendarSpread().signum()).isPositive();
  }

  @Test
  void futPinsLandAtPinnedPriorityAfterSync() {
    futuresPinner.repin();

    List<SubscriptionRegistry.SubscriptionView> pins =
        registry.view().stream()
            .filter(v -> v.tradingsymbol().endsWith("FUT"))
            .toList();
    assertThat(pins)
        .extracting(SubscriptionRegistry.SubscriptionView::tradingsymbol)
        .contains(
            "NIFTY26JUNFUT", "NIFTY26JULFUT", "NIFTY26AUGFUT",
            "BANKNIFTY26JUNFUT", "BANKNIFTY26JULFUT", "BANKNIFTY26AUGFUT");
    assertThat(pins)
        .allSatisfy(
            v ->
                assertThat(v.priority())
                    .isEqualTo(in.arthayantra.marketdata.kite.ticker.SubscriptionPriority.PINNED_INDEX));
    assertThat(futuresPinner.pinnedContracts()).hasSize(6);
  }

  @Test
  void futBarsCarryOiThroughToTheDailyAggregate() {
    String symbol = "NIFTY26JUNFUT";
    // two sessions of 1m FUT bars with evolving OI
    for (int day = 9; day <= 10; day++) {
      for (int i = 0; i < 3; i++) {
        long oi = 100_000L + day * 1_000L + i; // last of day 9 → 109002; day 10 → 110002
        candleRepository.upsert(
            new Candle(
                "NFO", symbol, "1m",
                OffsetDateTime.parse(String.format("2026-06-%02dT09:%02d:00+05:30", day, 15 + i)),
                new BigDecimal("24100"), new BigDecimal("24110"),
                new BigDecimal("24090"), new BigDecimal("24105"),
                100, oi, "MOCK"));
      }
    }
    jdbc.execute("CALL public.refresh_continuous_aggregate('candles_1d', NULL, NULL)");

    List<Candle> daily =
        candleRepository.rangeFromAggregate(
            "candles_1d", "NFO", symbol,
            OffsetDateTime.parse("2026-06-09T00:00:00+05:30"),
            OffsetDateTime.parse("2026-06-11T00:00:00+05:30"));

    assertThat(daily).hasSize(2);
    assertThat(daily.get(0).oi()).as("last(oi) of day 1").isEqualTo(109_002L);
    assertThat(daily.get(1).oi()).as("last(oi) of day 2 — day-over-day OI delta basis").isEqualTo(110_002L);
  }

  @Test
  void vixHistoryServesThroughTheOrdinaryCandlesSurface() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/candles")
                .param("exchange", "NSE")
                .param("tradingsymbol", "INDIA VIX")
                .param("interval", "1d")
                .param("from", "2026-06-01T00:00:00+05:30")
                .param("to", "2026-06-13T00:00:00+05:30"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(org.hamcrest.Matchers.greaterThan(5)))
        .andExpect(jsonPath("$.items[0].close").isString());
  }

  @Test
  void eodBackfillCoversThePinnedContracts() {
    futuresPinner.repin();

    int covered = eodBackfillJob.backfillNow();

    assertThat(covered).isGreaterThanOrEqualTo(6);
    List<Candle> futBars =
        candleRepository.range(
            "NFO", "NIFTY26JUNFUT", "1m",
            OffsetDateTime.parse("2026-06-15T09:15:00+05:30"),
            OffsetDateTime.parse("2026-06-15T11:00:00+05:30"));
    assertThat(futBars).isNotEmpty();
    assertThat(futBars).allSatisfy(bar -> assertThat(bar.oi()).as("per-bar FUT OI").isNotNull());
  }

}
