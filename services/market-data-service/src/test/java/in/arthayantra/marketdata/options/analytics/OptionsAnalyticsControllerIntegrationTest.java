package in.arthayantra.marketdata.options.analytics;

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
class OptionsAnalyticsControllerIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;

  @Test
  void oiStatsReturnsPcrAndMaxPainEnvelope() throws Exception {
    String u = "CTRLTEST";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime t0 =
        OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, t0, u, exp, "22500", "CE", "100", 1000L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, t0, u, exp, "22500", "PE", "90", 1500L, 0L);

    mockMvc
        .perform(
            get("/api/v1/market/options/oi-stats")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isOk())
        // BigDecimal serializes as a JSON string (decimal-string wire convention)
        .andExpect(jsonPath("$.pcr").value("1.5000"))
        .andExpect(jsonPath("$.maxPain").value("22500.00"));
  }

  @Test
  void unsupportedIntervalIs400WithCode() throws Exception {
    mockMvc
        .perform(get("/api/v1/market/options/oi-stats").param("name", "X").param("interval", "7m"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_INTERVAL_UNSUPPORTED"));
  }
}
