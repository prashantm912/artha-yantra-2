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

/**
 * Banks Analysis IT (§futures/banks-analysis, mock). The bank universe is OVERRIDDEN to a PRIVATE set
 * (BANKONE/BANKTWO/BANKTHREE) so the seeded OI cannot collide with the other futures ITs' real bank names
 * on the shared singleton DB. The fixture proves the three load-bearing faithfulness facts: (1) cumulative
 * LTP% is off the captured day_open and OI% off the first captured bucket; (2) the interpretation badge is
 * PER-INTERVAL (vs the prior bucket), so it can diverge in SIGN from the cumulative OI% (a regression
 * guard); (3) the active FRONT contract (most buckets) wins over a far contract.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false",
      "artha.futures.bank-analysis-stocks=BANKONE,BANKTWO,BANKTHREE"
    })
@AutoConfigureMockMvc
class FuturesBankAnalysisIntegrationTest extends MarketDataIntegrationTestBase {

  private static final LocalDate JUN = LocalDate.of(2026, 6, 25);
  private static final LocalDate JUL = LocalDate.of(2026, 7, 30);
  private static final OffsetDateTime B0 =
      OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
  private static final OffsetDateTime B1 = B0.plusMinutes(5);
  private static final OffsetDateTime B2 = B0.plusMinutes(10);

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;

  /** Seeds one bank-future snapshot with an explicit captured day_open (the LTP baseline). */
  private void seed(
      OffsetDateTime ts, String underlying, String sym, LocalDate exp, String ltp, long oi, String dayOpen) {
    jdbc.update(
        "INSERT INTO futures_oi_snapshots "
            + "(ts, underlying, tradingsymbol, expiry, ltp, volume, oi, oi_change, day_open) "
            + "VALUES (?,?,?,?,?::numeric,?,?,?,?::numeric) ON CONFLICT DO NOTHING",
        java.sql.Timestamp.from(ts.toInstant()),
        underlying,
        sym,
        java.sql.Date.valueOf(exp),
        ltp,
        100L,
        oi,
        0L,
        dayOpen);
  }

  @Test
  void banksAnalysisPivotsCumulativePercentsAndPerIntervalBadges() throws Exception {
    // BANKONE front (JUN), 3 buckets; day_open=100. ltp 100→110→121, oi 1000→1100→1050.
    //   cum LTP% @b2 = (121-100)/100 = 21.00 ; cum OI% @b2 = (1050-1000)/1000 = 5.00 (POSITIVE)
    //   per-interval badge @b2 = price↑(121>110) × OI↓(1050<1100) = SHORT_COVERING (OI DOWN) — sign-DIVERGES
    //   from the +5.00 cumulative OI%, proving the badge is per-interval, not cumulative.
    seed(B0, "BANKONE", "BANKONE26JUNFUT", JUN, "100", 1000L, "100");
    seed(B1, "BANKONE", "BANKONE26JUNFUT", JUN, "110", 1100L, "100");
    seed(B2, "BANKONE", "BANKONE26JUNFUT", JUN, "121", 1050L, "100");
    // BANKONE far (JUL), 1 bucket — must be IGNORED (front-pick = most buckets).
    seed(B0, "BANKONE", "BANKONE26JULFUT", JUL, "200", 50L, "200");
    // BANKTWO + BANKTHREE: 2 buckets each (columns present, no b2).
    seed(B0, "BANKTWO", "BANKTWO26JUNFUT", JUN, "50", 500L, "50");
    seed(B1, "BANKTWO", "BANKTWO26JUNFUT", JUN, "55", 520L, "50");
    seed(B0, "BANKTHREE", "BANKTHREE26JUNFUT", JUN, "80", 800L, "80");
    seed(B1, "BANKTHREE", "BANKTHREE26JUNFUT", JUN, "79", 810L, "80");

    mockMvc
        .perform(get("/api/v1/market/futures/banks-analysis").param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.banks[0]").value("BANKONE")) // ordered columns
        .andExpect(jsonPath("$.banks[1]").value("BANKTWO"))
        .andExpect(jsonPath("$.banks[2]").value("BANKTHREE"))
        .andExpect(jsonPath("$.rows.length()").value(3)) // b0,b1,b2 newest-first
        // newest row = b2: BANKONE only (front=JUN; a wrong JUL pick would null this cell).
        .andExpect(jsonPath("$.rows[0].cells[0].bank").value("BANKONE"))
        .andExpect(jsonPath("$.rows[0].cells[0].ltpPct").value("21.00")) // cum LTP% off day_open
        .andExpect(jsonPath("$.rows[0].cells[0].oiPct").value("5.00")) // cum OI% off first bucket (POSITIVE)
        .andExpect(jsonPath("$.rows[0].cells[0].interpretation").value("SHORT_COVERING")) // per-interval (OI↓)
        // b1 row: BANKONE per-interval badge = LONG_BUILDUP (price↑ OI↑).
        .andExpect(jsonPath("$.rows[1].cells[0].interpretation").value("LONG_BUILDUP"))
        // b0 row (oldest): first bucket → cum 0.00 / 0.00, no prior → null interpretation.
        .andExpect(jsonPath("$.rows[2].cells[0].ltpPct").value("0.00"))
        .andExpect(jsonPath("$.rows[2].cells[0].oiPct").value("0.00"));
  }

  @Test
  void banksAnalysisHistoryWithNoDataReturns422() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/futures/banks-analysis")
                .param("mode", "history")
                .param("date", "2000-01-03")
                .param("interval", "5m"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void banksAnalysisHistoryWithoutDateIs400() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/futures/banks-analysis").param("mode", "history").param("interval", "5m"))
        .andExpect(status().isBadRequest());
  }
}
