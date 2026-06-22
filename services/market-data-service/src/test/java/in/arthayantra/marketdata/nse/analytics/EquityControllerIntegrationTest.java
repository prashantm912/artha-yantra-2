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
class EquityControllerIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;

  // Unique symbol — the IT singleton DB has no per-method cleanup.
  private static final String SYM = "DLVTSYM";

  private void insertBhav(
      LocalDate d,
      String prevClose,
      String open,
      String high,
      String low,
      String close,
      String delivPer,
      long delivQty,
      long ttq) {
    jdbc.update(
        "INSERT INTO nse_eod_bhavcopy "
            + "(trade_date, symbol, series, prev_close, open_price, high_price, low_price, "
            + " close_price, deliv_per, deliv_qty, ttl_trd_qnty) "
            + "VALUES (?,?,?,?::numeric,?::numeric,?::numeric,?::numeric,?::numeric,?::numeric,?,?) "
            + "ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(d),
        SYM,
        "EQ",
        prevClose,
        open,
        high,
        low,
        close,
        delivPer,
        delivQty,
        ttq);
  }

  @Test
  void deliverySeriesIsNewestFirstWithComputedChange() throws Exception {
    insertBhav(LocalDate.of(2026, 6, 10), "95", "96", "101", "94", "100", "50.00", 4000, 9000);
    insertBhav(LocalDate.of(2026, 6, 11), "100", "101", "106", "99", "104", "52.00", 4500, 9500);
    insertBhav(LocalDate.of(2026, 6, 12), "100", "101", "110", "99", "108", "55.50", 5000, 10000);

    mockMvc
        .perform(get("/api/v1/market/equity/delivery").param("symbol", SYM).param("days", "15"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.symbol").value(SYM))
        .andExpect(jsonPath("$.items.length()").value(3))
        .andExpect(jsonPath("$.items[0].date").value("2026-06-12")) // newest first
        .andExpect(jsonPath("$.items[0].ltpChangePct").value("8.00")) // (108-100)/100*100
        .andExpect(jsonPath("$.items[0].deliveryQty").value(5000));
  }

  @Test
  void deliveryIs422WhenSymbolHasNoBhavcopy() throws Exception {
    mockMvc
        .perform(get("/api/v1/market/equity/delivery").param("symbol", "NOSUCHSYM").param("days", "15"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("DATA_GAP"));
  }
}
