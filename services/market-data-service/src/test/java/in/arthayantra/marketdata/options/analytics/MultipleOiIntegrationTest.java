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

/**
 * Multiple OI Chart IT (§options/multiple-oi-chart, mock): the selected legs ("57200 CE") each become an
 * OI line + one shared underlying-price (spot) line, all folded from ONE snapshot read. Proves the leg
 * join (strike-scale normalised), the per-bucket spot, the all-null line for an unseeded leg, and the
 * 400 (no leg) / 422 (no underlying snapshot) semantics.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
class MultipleOiIntegrationTest extends MarketDataIntegrationTestBase {

  private static final String U = "MULTIOICTRL";
  private static final LocalDate EXP = LocalDate.of(2026, 6, 25);
  private static final String SESSION = "2026-06-20";
  private static final OffsetDateTime B0 =
      OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
  private static final OffsetDateTime B1 = B0.plusMinutes(5);

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;

  private void seed() {
    // Two strikes × two sides × two buckets (insertRow hardcodes spot_price = 22480.00 per row).
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, B0, U, EXP, "57200", "CE", "100", 134820L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, B1, U, EXP, "57200", "CE", "105", 140000L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, B0, U, EXP, "57100", "PE", "90", 90450L, 0L);
    OptionsSnapshotReaderIntegrationTest.insertRow(jdbc, B1, U, EXP, "57100", "PE", "92", 92000L, 0L);
  }

  @Test
  void foldsSelectedLegsToOiLinesPlusASpotLine() throws Exception {
    seed();
    mockMvc
        .perform(
            get("/api/v1/market/options/multiple-oi")
                .param("name", U)
                .param("expiry", EXP.toString())
                .param("mode", "history")
                .param("date", SESSION)
                .param("interval", "5m")
                .param("leg", "57200 CE", "57100 PE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].leg").value("57200 CE"))
        .andExpect(jsonPath("$.items[0].points.length()").value(2)) // b0, b1
        .andExpect(jsonPath("$.items[0].points[0].oi").value(134820))
        .andExpect(jsonPath("$.items[0].points[1].oi").value(140000))
        .andExpect(jsonPath("$.items[1].leg").value("57100 PE"))
        .andExpect(jsonPath("$.items[1].points[0].oi").value(90450))
        // one shared underlying-price line (spot per bucket, decimal string).
        .andExpect(jsonPath("$.spot.length()").value(2))
        .andExpect(jsonPath("$.spot[0].spot").value(org.hamcrest.Matchers.startsWith("22480")));
  }

  @Test
  void anUnseededLegIsAnAllNullLineNot422() throws Exception {
    seed();
    mockMvc
        .perform(
            get("/api/v1/market/options/multiple-oi")
                .param("name", U)
                .param("expiry", EXP.toString())
                .param("mode", "history")
                .param("date", SESSION)
                .param("interval", "5m")
                .param("leg", "99999 CE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].leg").value("99999 CE"))
        .andExpect(jsonPath("$.items[0].points[0].oi").doesNotExist()); // null oi (no rows for this leg)
  }

  @Test
  void noLegParamIs400() throws Exception {
    seed();
    mockMvc
        .perform(
            get("/api/v1/market/options/multiple-oi")
                .param("name", U)
                .param("expiry", EXP.toString())
                .param("mode", "history")
                .param("date", SESSION)
                .param("interval", "5m"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void unknownUnderlyingIs422() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/options/multiple-oi")
                .param("name", "MULTIOINONE")
                .param("expiry", EXP.toString())
                .param("mode", "history")
                .param("date", SESSION)
                .param("interval", "5m")
                .param("leg", "57200 CE"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("DATA_GAP"));
  }
}
