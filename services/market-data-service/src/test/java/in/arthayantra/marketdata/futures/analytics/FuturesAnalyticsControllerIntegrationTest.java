package in.arthayantra.marketdata.futures.analytics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
class FuturesAnalyticsControllerIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;

  @Test
  void oiAnalysisReturnsItemsEnvelope() throws Exception {
    String u = "FUTCTRL";
    OffsetDateTime t0 =
        OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    jdbc.update(
        "INSERT INTO futures_oi_snapshots (ts, underlying, tradingsymbol, expiry, ltp, volume, oi, oi_change) "
            + "VALUES (?,?,?,?,?::numeric,?,?,?) ON CONFLICT DO NOTHING",
        java.sql.Timestamp.from(t0.toInstant()),
        u,
        u + "26JUNFUT",
        java.sql.Date.valueOf(LocalDate.of(2026, 6, 25)),
        "100.00",
        10L,
        5000L,
        200L);

    mockMvc
        .perform(get("/api/v1/market/futures/oi-analysis").param("name", u).param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.items[0].tradingsymbol").value(u + "26JUNFUT"));
  }
}
