package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.marketdata.instruments.InstrumentRepository;
import in.arthayantra.marketdata.instruments.InstrumentSyncService;
import in.arthayantra.marketdata.kite.QuoteGateway;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Straddle Chart IT (plan §20.7.6, mock profile): the combined CE+PE premium candle series is read
 * cache-first from the generic candle path (no new capture pipeline), aggregated to the requested
 * minute interval and summed leg-wise. Faithful invariant: {@code close == ceClose + peClose} per
 * interval. A strangle is the same shape with distinct call/put strikes; an unlisted strike 422s and
 * an off-set interval 400s.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
class OptionsStraddleChartIntegrationTest extends MarketDataIntegrationTestBase {

  // The mock NIFTY 50 ladder resolves to expiry 2026-06-16; 2026-06-15 is an open Monday session.
  private static final String UNDERLYING = "NIFTY 50";
  private static final LocalDate EXPIRY = LocalDate.parse("2026-06-16");
  private static final String SESSION = "2026-06-15";

  /**
   * A spot-quote gateway whose failure mode is switchable: off-hours the live Kite quote throws
   * (no session), and the chart's candle data must survive that — the header degrades, the chart does
   * not 401. Defaults to an empty map (header null) so boot never needs a live quote.
   */
  @TestConfiguration
  static class QuoteConfig {
    static final AtomicBoolean THROW = new AtomicBoolean(false);

    @Bean
    @Primary
    QuoteGateway quoteGateway() {
      return keys -> {
        if (THROW.get()) {
          throw new IllegalStateException("no live Kite session for quotes");
        }
        return Map.of();
      };
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private InstrumentSyncService syncService;
  @Autowired private InstrumentRepository instruments;
  @Autowired private StraddleChartService straddleChartService;

  @BeforeEach
  void seedChain() {
    QuoteConfig.THROW.set(false);
    syncService.runSync();
  }

  /** A listed strike near mid-ladder (CE+PE both present after the sync). */
  private BigDecimal midStrike() {
    List<BigDecimal> strikes = instruments.strikes(UNDERLYING, EXPIRY);
    assertThat(strikes).isNotEmpty();
    return strikes.get(strikes.size() / 2);
  }

  @Test
  void straddleCandlesSumBothLegsPerInterval() {
    BigDecimal strike = midStrike();

    StraddleChartService.StraddleChart chart =
        straddleChartService.chart(UNDERLYING, EXPIRY, strike, null, null, 5, LocalDate.parse(SESSION));

    assertThat(chart.interval()).isEqualTo("5m");
    assertThat(chart.callStrike()).isEqualByComparingTo(strike);
    assertThat(chart.putStrike()).isEqualByComparingTo(strike);
    assertThat(chart.items()).as("mock fabricates 1m option bars on a trading day").isNotEmpty();

    // faithful invariant: the straddle close is the leg-wise sum; OHLC stays well-ordered; volume ≥ 0.
    for (StraddleChartService.StraddleCandle c : chart.items()) {
      assertThat(c.close()).isEqualByComparingTo(c.ceClose().add(c.peClose()));
      assertThat(c.high()).isGreaterThanOrEqualTo(c.low());
      assertThat(c.high()).isGreaterThanOrEqualTo(c.open());
      assertThat(c.high()).isGreaterThanOrEqualTo(c.close());
      assertThat(c.low()).isLessThanOrEqualTo(c.open());
      assertThat(c.volume()).isGreaterThanOrEqualTo(0L);
    }
    // buckets are session-aligned (09:15 IST) and ascending.
    assertThat(chart.items())
        .isSortedAccordingTo(java.util.Comparator.comparing(StraddleChartService.StraddleCandle::time));
    assertThat(chart.items().get(0).time().toString()).startsWith(SESSION + "T09:15");
  }

  @Test
  void underlyingQuoteFailureDegradesHeaderNotTheChart() {
    QuoteConfig.THROW.set(true); // off-hours: the live Kite quote throws (KITE_TOKEN_EXPIRED)
    BigDecimal strike = midStrike();

    StraddleChartService.StraddleChart chart =
        straddleChartService.chart(UNDERLYING, EXPIRY, strike, null, null, 5, LocalDate.parse(SESSION));

    // the candle series is read cache-first (no live quote) → it MUST still render; the header degrades.
    assertThat(chart.items()).isNotEmpty();
    assertThat(chart.underlyingLtp()).isNull();
    assertThat(chart.underlyingDayOpen()).isNull();
  }

  @Test
  void strangleResolvesTwoDistinctStrikes() {
    List<BigDecimal> strikes = instruments.strikes(UNDERLYING, EXPIRY);
    BigDecimal call = strikes.get(strikes.size() / 2 + 2);
    BigDecimal put = strikes.get(strikes.size() / 2 - 2);

    StraddleChartService.StraddleChart chart =
        straddleChartService.chart(UNDERLYING, EXPIRY, null, call, put, 15, LocalDate.parse(SESSION));

    assertThat(chart.interval()).isEqualTo("15m");
    assertThat(chart.callStrike()).isEqualByComparingTo(call);
    assertThat(chart.putStrike()).isEqualByComparingTo(put);
    assertThat(chart.items()).isNotEmpty();
  }

  @Test
  void restEnvelopeServesDecimalStrings() throws Exception {
    BigDecimal strike = midStrike();

    mockMvc
        .perform(
            get("/api/v1/market/options/straddle-chart")
                .param("name", UNDERLYING)
                .param("expiry", EXPIRY.toString())
                .param("mode", "history")
                .param("date", SESSION)
                .param("strike", strike.toPlainString())
                .param("interval", "5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.interval").value("5m"))
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.items[0].open").isString())
        .andExpect(jsonPath("$.items[0].ceClose").isString())
        .andExpect(jsonPath("$.items[0].peClose").isString());
  }

  @Test
  void unlistedStrikeIs422() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/options/straddle-chart")
                .param("name", UNDERLYING)
                .param("expiry", EXPIRY.toString())
                .param("mode", "history")
                .param("date", SESSION)
                .param("strike", "999999")
                .param("interval", "5"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("DATA_GAP"));
  }

  @Test
  void offSetIntervalIs400() throws Exception {
    BigDecimal strike = midStrike();

    mockMvc
        .perform(
            get("/api/v1/market/options/straddle-chart")
                .param("name", UNDERLYING)
                .param("expiry", EXPIRY.toString())
                .param("mode", "history")
                .param("date", SESSION)
                .param("strike", strike.toPlainString())
                .param("interval", "7"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_INTERVAL_UNSUPPORTED"));
  }
}
