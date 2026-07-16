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
class BreadthControllerIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;

  private void insertBhav(
      LocalDate d, String sym, String prevClose, String close, String delivPer) {
    jdbc.update(
        "INSERT INTO nse_eod_bhavcopy "
            + "(trade_date, symbol, series, prev_close, close_price, deliv_per) "
            + "VALUES (?,?,?,?::numeric,?::numeric,?::numeric) ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(d),
        sym,
        "EQ",
        prevClose,
        close,
        delivPer);
  }

  // Breadth AGGREGATES every bhavcopy row for its date, and the ITs share one singleton DB with no
  // per-method cleanup — so this date must belong to this test alone. It previously used 2026-06-12,
  // which EquityControllerIntegrationTest also seeds (prev_close=100 -> close=108, an advancer):
  // whenever that class happened to run first, advances came back 3 instead of 2. Nothing in
  // market-data uses 2024, and the date is inside the bundled market-calendar range (2024-2026).
  // Do NOT reuse this date elsewhere.
  private static final LocalDate BREADTH_ONLY_DATE = LocalDate.of(2024, 6, 12);

  @Test
  void breadthCountsAdvancesDeclinesAndDeliveryLeaders() throws Exception {
    LocalDate d = BREADTH_ONLY_DATE;
    insertBhav(d, "RELIANCE", "100", "110", "55.50"); // advancer
    insertBhav(d, "TCS", "200", "210", "60.00"); // advancer, top delivery
    insertBhav(d, "INFY", "300", "290", "40.00"); // decliner

    mockMvc
        .perform(get("/api/v1/market/breadth").param("date", BREADTH_ONLY_DATE.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.advances").value(2))
        .andExpect(jsonPath("$.summary.declines").value(1))
        .andExpect(jsonPath("$.summary.total").value(3))
        .andExpect(jsonPath("$.topDelivery[0].symbol").value("TCS")); // 60% highest
  }

  @Test
  void breadthIs422WhenNoBhavcopyForDate() throws Exception {
    mockMvc
        .perform(get("/api/v1/market/breadth").param("date", "1999-01-04"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("DATA_GAP"));
  }
}
