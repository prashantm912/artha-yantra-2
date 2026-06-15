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

  @Test
  void historyModeWithoutDateIs400() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/options/oi-stats")
                .param("name", "X")
                .param("mode", "history")
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void historyModeWithDateReturnsThatDaysBucket() throws Exception {
    String u = "CTRLHIST";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime day1 =
        OffsetDateTime.of(2026, 6, 18, 10, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime day2 =
        OffsetDateTime.of(2026, 6, 19, 10, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, day1, u, exp, "22500", "CE", "100", 1000L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, day1, u, exp, "22500", "PE", "90", 1500L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, day2, u, exp, "22500", "CE", "999", 9L, 0L);

    mockMvc
        .perform(
            get("/api/v1/market/options/oi-stats")
                .param("name", u)
                .param("mode", "history")
                .param("date", "2026-06-18")
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ceOi").value(1000))
        .andExpect(jsonPath("$.peOi").value(1500)); // day2's decoy CE excluded
  }

  @Test
  void bigOiRanksByAbsOiChange() throws Exception {
    String u = "BIGOICTRL";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime t0 =
        OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, t0, u, exp, "22500", "CE", "100", 1000L, 50L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, t0, u, exp, "22600", "PE", "80", 2000L, -900L);

    mockMvc
        .perform(
            get("/api/v1/market/options/big-oi")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].optionType").value("PE")) // |-900| ranks first
        .andExpect(jsonPath("$.items[0].oiChange").value(-900));
  }

  @Test
  void premiumFoldsAtmStraddle() throws Exception {
    String u = "PREMCTRL";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime t0 =
        OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, t0, u, exp, "22500", "CE", "80", 1000L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, t0, u, exp, "22500", "PE", "70", 1500L, 0L);

    mockMvc
        .perform(
            get("/api/v1/market/options/premium")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        // BigDecimal -> string; scale depends on the column, so match the integer part
        .andExpect(jsonPath("$.atmStraddle").value(org.hamcrest.Matchers.startsWith("150")));
  }

  @Test
  void trendingReturnsBucketSeries() throws Exception {
    String u = "TRENDCTRL";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime b0 =
        OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime b1 = b0.plusMinutes(5);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b0, u, exp, "22500", "CE", "100", 1000L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b1, u, exp, "22500", "CE", "110", 1500L, 0L);

    mockMvc
        .perform(
            get("/api/v1/market/options/trending")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[1].trend").value("UP")); // 1500 > 1000
  }

  @Test
  void spurtReturnsRowsAndSummary() throws Exception {
    String u = "SPURTCTRL";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime b0 =
        OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime b1 = b0.plusMinutes(5);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b0, u, exp, "22500", "CE", "100", 1000L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, b1, u, exp, "22500", "CE", "110", 1200L, 0L);

    mockMvc
        .perform(
            get("/api/v1/market/options/spurt")
                .param("name", u)
                .param("expiry", "2026-06-25")
                .param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].optionType").value("CE"))
        .andExpect(jsonPath("$.items[0].interpretation").value("LONG_BUILDUP"))
        .andExpect(jsonPath("$.items[0].oiChange").value(200))
        .andExpect(jsonPath("$.items[0].spurtPct").value("20.00"))
        .andExpect(jsonPath("$.summary.interpretation").value("LONG_BUILDUP"));
  }
}
