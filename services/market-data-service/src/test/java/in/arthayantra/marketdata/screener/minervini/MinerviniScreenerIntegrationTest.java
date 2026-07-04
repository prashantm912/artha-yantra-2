package in.arthayantra.marketdata.screener.minervini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Track-1 IT: seeds ~260 native daily bars for three synthetic NSE equities (rising winner / falling
 * loser / flat middle) + their instrument rows, then asserts the 8 Trend-Template gates, the
 * cross-sectional RS-rank percentile, the derived Stage label, coverage, and the persisted-read
 * endpoint — all against hand-computed expectations, with ZERO gateway-port invocations (pure SQL).
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
class MinerviniScreenerIntegrationTest extends MarketDataIntegrationTestBase {

  private static final String WIN = "MVWIN";
  private static final String LOSE = "MVLOSE";
  private static final String MID = "MVMID";
  private static final List<String> SYMS = List.of(WIN, LOSE, MID);
  private static final int DAYS = 260;
  private static final LocalDate AS_OF = LocalDate.of(2026, 6, 30);

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TrendTemplateService screener;
  @Autowired private MinerviniScreenRepository repo;

  @BeforeEach
  void seed() {
    for (String s : SYMS) {
      jdbc.update("DELETE FROM nse_eod_bhavcopy WHERE symbol=?", s);
      jdbc.update("DELETE FROM minervini_screen_results WHERE symbol=?", s);
    }
    // rising 100->200 (winner), falling 200->100 (loser), flat 100 (middle)
    seedSeries(WIN, i -> 100.0 + 100.0 * i / (DAYS - 1));
    seedSeries(LOSE, i -> 200.0 - 100.0 * i / (DAYS - 1));
    seedSeries(MID, i -> 100.0);
  }

  private void seedSeries(String symbol, DoubleUnaryOperator shape) {
    List<Object[]> batch = new ArrayList<>(DAYS);
    for (int i = 0; i < DAYS; i++) {
      LocalDate day = AS_OF.minusDays(DAYS - 1L - i);
      double px = Math.round(shape.applyAsDouble(i) * 100.0) / 100.0;
      batch.add(new Object[] {day, symbol, px, px, px, 100_000L});
    }
    jdbc.batchUpdate(
        "INSERT INTO nse_eod_bhavcopy(trade_date,symbol,series,close_price,high_price,low_price,"
            + "ttl_trd_qnty) VALUES(?,?, 'EQ', ?,?,?,?) ON CONFLICT DO NOTHING",
        batch);
  }

  @Test
  void gatesRsAndStageMatchTheHandComputedFixture() {
    TrendTemplateService.ScreenResult res = screener.screen(AS_OF);
    assertThat(res.screenDate()).isEqualTo(AS_OF);
    assertThat(res.coverage()).isEqualTo(3);

    Map<String, TrendCandidate> by = new java.util.HashMap<>();
    res.candidates().forEach(c -> by.put(c.symbol(), c));

    TrendCandidate win = by.get(WIN);
    assertThat(win).isNotNull();
    assertThat(win.passesAll()).as("clean uptrend passes all 8 gates").isTrue();
    assertThat(win.gatesPassed()).isEqualTo(8);
    assertThat(win.stage()).as("winner is Stage 2").isEqualTo(2);
    assertThat(win.rsRank()).as("winner tops the RS percentile").isEqualByComparingTo("100.00");

    TrendCandidate lose = by.get(LOSE);
    assertThat(lose.passesAll()).isFalse();
    assertThat(lose.stage()).as("loser below a falling 200-day is Stage 4").isEqualTo(4);
    assertThat(lose.rsRank()).isEqualByComparingTo("0.00");
    assertThat(lose.gate(1)).as("price below the 150/200-day").isFalse();

    TrendCandidate mid = by.get(MID);
    assertThat(mid.passesAll()).isFalse();
    assertThat(mid.gate(3)).as("flat 200-day is not rising").isFalse();
    assertThat(mid.rsRank()).isEqualByComparingTo("50.00");
  }

  @Test
  void runThenGetServesOnlyThePasserThroughTheEndpoint() throws Exception {
    mockMvc
        .perform(post("/api/v1/market/screener/minervini/run").param("asOf", AS_OF.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.screenDate").value(AS_OF.toString()))
        .andExpect(jsonPath("$.coverage").value(3))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].symbol").value(WIN))
        .andExpect(jsonPath("$.items[0].passesAll").value(true))
        .andExpect(jsonPath("$.items[0].stage").value(2))
        .andExpect(jsonPath("$.items[0].gatesPassed").value(8));

    // persisted read serves the same passer (decimal wire-format asserted in the service test)
    mockMvc
        .perform(get("/api/v1/market/screener/minervini").param("asOf", AS_OF.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].symbol").value(WIN))
        .andExpect(jsonPath("$.items[0].rsRank").exists())
        .andExpect(jsonPath("$.items[0].close").exists());

    // all-rows view (passesAllOnly=false) exposes the fails for the reasoning UI
    mockMvc
        .perform(
            get("/api/v1/market/screener/minervini")
                .param("asOf", AS_OF.toString())
                .param("passesAllOnly", "false")
                .param("minRsRank", "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(3));
  }
}
