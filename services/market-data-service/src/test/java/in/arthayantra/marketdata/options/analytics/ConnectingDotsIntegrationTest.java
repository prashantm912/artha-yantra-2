package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.marketdata.instruments.InstrumentSyncService;
import in.arthayantra.marketdata.options.OiInterval;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Connecting Dots IT (§20.7.8, mock profile): the per-interval factor matrix builds off the front-month
 * futures candle spine (mock fabricates option/futures candles on a trading day), every factor is a
 * valid 3-state code, the composite is a valid 5-state, rows are newest-interval-first, and the 10-min
 * interval (the new OiInterval.M10) is accepted.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
class ConnectingDotsIntegrationTest extends MarketDataIntegrationTestBase {

  private static final String UNDERLYING = "NIFTY 50";
  private static final String SESSION = "2026-06-15"; // a trading Monday

  @Autowired private MockMvc mockMvc;
  @Autowired private InstrumentSyncService syncService;
  @Autowired private ConnectingDotsService service;

  @BeforeEach
  void seed() {
    syncService.runSync();
  }

  @Test
  void matrixBuildsValidFactorCodesNewestFirst() {
    ConnectingDotsService.ConnectingDots cd =
        service.matrix(UNDERLYING, OiInterval.M3, LocalDate.parse(SESSION));

    assertThat(cd.underlying()).isEqualTo(UNDERLYING);
    assertThat(cd.interval()).isEqualTo("3m");
    assertThat(cd.rows()).as("mock fabricates the futures candle spine on a trading day").isNotEmpty();

    for (ConnectingDotsService.ConnectingDotsRow r : cd.rows()) {
      assertThat(r.timeInterval()).matches("\\d{2}:\\d{2}-\\d{2}:\\d{2}");
      assertThat(r.trend()).isBetween(0, 4);
      for (int f :
          new int[] {
            r.dow(), r.vix(), r.volume(), r.activeStrikeIv(), r.activeStrikeOi(), r.futOi(),
            r.vwap(), r.supertrend(), r.rsi(), r.futPrice(), r.dailyTrend()
          }) {
        assertThat(f).isBetween(0, 2);
      }
    }
    // Dow is Neutral (Upstox global feed pending) on every row.
    assertThat(cd.rows()).allSatisfy(r -> assertThat(r.dow()).isZero());
    // newest interval first (descending timeInterval label).
    assertThat(cd.rows())
        .isSortedAccordingTo(
            java.util.Comparator.comparing(ConnectingDotsService.ConnectingDotsRow::timeInterval)
                .reversed());
  }

  @Test
  void restEnvelopeAndTenMinuteInterval() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/connecting-dots")
                .param("name", UNDERLYING)
                .param("mode", "history")
                .param("date", SESSION)
                .param("interval", "10m")) // OiInterval.M10 — newly added
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.underlying").value(UNDERLYING))
        .andExpect(jsonPath("$.interval").value("10m"))
        .andExpect(jsonPath("$.rows").isArray())
        .andExpect(jsonPath("$.rows[0].trend").isNumber())
        .andExpect(jsonPath("$.rows[0].timeInterval").isString());
  }
}
