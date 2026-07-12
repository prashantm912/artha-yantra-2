package in.arthayantra.marketdata.screener;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * IT for the screener CSV export (audit §10 Phase-3 CSV export standard): the endpoint streams a
 * {@code text/csv} attachment with the loud-truncation headers and the persisted screen rows.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
class ScreenerCsvExportIntegrationTest extends MarketDataIntegrationTestBase {

  private static final LocalDate D = LocalDate.of(2019, 4, 8);

  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mockMvc;

  @AfterEach
  void purge() {
    jdbc.update("DELETE FROM minervini_screen_results WHERE symbol = ?", "CSVEXP1");
  }

  @BeforeEach
  void seed() {
    jdbc.update("DELETE FROM minervini_screen_results WHERE symbol = ?", "CSVEXP1");
    jdbc.update(
        "INSERT INTO minervini_screen_results(screen_date,symbol,exchange,close_price,rs_rank,"
            + "gate1,gate2,gate3,gate4,gate5,gate6,gate7,gate8,gates_passed,passes_all,stage) "
            + "VALUES(?, 'CSVEXP1', 'NSE', 123.45, 88, "
            + " TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,TRUE, 8, TRUE, 2) ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(D));
  }

  @Test
  void minerviniExportStreamsCsvWithTruncationHeaders() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/screener/minervini/export")
                .param("asOf", "2019-04-08")
                .param("passesAllOnly", "false"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/csv"))
        .andExpect(header().string("Content-Disposition", containsString("attachment")))
        .andExpect(header().string("Content-Disposition", containsString("minervini_screen_2019-04-08.csv")))
        .andExpect(header().string("X-Result-Truncated", "false"))
        .andExpect(header().string("X-Result-Rows", "1"))
        .andExpect(content().string(startsWith("symbol,exchange,close")))
        .andExpect(content().string(containsString("CSVEXP1,NSE,123.45")));
  }
}
