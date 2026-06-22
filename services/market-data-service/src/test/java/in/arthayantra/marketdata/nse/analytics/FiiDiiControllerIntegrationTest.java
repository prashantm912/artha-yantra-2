package in.arthayantra.marketdata.nse.analytics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
class FiiDiiControllerIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;

  @Test
  void cashReturnsFiiDiiRows() throws Exception {
    jdbc.update(
        "INSERT INTO nse_eod_fii_dii (trade_date, category, buy_value, sell_value, net_value) "
            + "VALUES (?,?,?::numeric,?::numeric,?::numeric) ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(LocalDate.of(2026, 6, 12)),
        "FII/FPI",
        "1000.50",
        "800.25",
        "200.25");

    mockMvc
        .perform(get("/api/v1/market/fii-dii/cash").param("from", "2026-06-12"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].category").value("FII/FPI"))
        .andExpect(jsonPath("$.items[0].netValue").value(org.hamcrest.Matchers.startsWith("200.2")));
  }

  @Test
  void derivativeStatsReturnsFiiSegmentRows() throws Exception {
    jdbc.update(
        "INSERT INTO fii_derivative_stats (trade_date, segment, buy_value, sell_value, net_value) "
            + "VALUES (?,?,?::numeric,?::numeric,?::numeric) ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(LocalDate.of(2026, 6, 16)),
        "INDEX_OPTIONS",
        "10000.00",
        "15446.62",
        "-5446.62");

    mockMvc
        .perform(get("/api/v1/market/fii-dii/derivative-stats").param("from", "2026-06-16"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].segment").value("INDEX_OPTIONS"))
        .andExpect(
            jsonPath("$.items[0].netValue").value(org.hamcrest.Matchers.startsWith("-5446.6")));
  }

  @Test
  void longShortDerivesFiiIndexFuturesRatio() throws Exception {
    jdbc.update(
        "INSERT INTO nse_eod_participant_oi "
            + "(trade_date, client_type, future_index_long, future_index_short) "
            + "VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(LocalDate.of(2026, 6, 13)),
        "FII",
        2000L,
        1000L);

    mockMvc
        .perform(get("/api/v1/market/fii-dii/long-short").param("from", "2026-06-13"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].fiiLong").value(2000))
        .andExpect(jsonPath("$.items[0].fiiShort").value(1000))
        .andExpect(jsonPath("$.items[0].ratio").value(org.hamcrest.Matchers.startsWith("2")));
  }
}
