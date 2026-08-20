package in.arthayantra.marketdata.nse.analytics;

import static org.hamcrest.Matchers.startsWith;
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
    insertBhav(d, sym, "EQ", prevClose, close, delivPer);
  }

  /** {@code delivPer} may be null — NSE publishes no delivery figure for BE-series rows. */
  private void insertBhav(
      LocalDate d, String sym, String series, String prevClose, String close, String delivPer) {
    jdbc.update(
        "INSERT INTO nse_eod_bhavcopy "
            + "(trade_date, symbol, series, prev_close, close_price, deliv_per) "
            + "VALUES (?,?,?,?::numeric,?::numeric,?::numeric) ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(d),
        sym,
        series,
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

  /** Second private date, same rule as above — owned by {@link #breadthCountsTheCashUniverse}. */
  private static final LocalDate SERIES_ONLY_DATE = LocalDate.of(2024, 6, 13);

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

  /**
   * H24 PR-3: breadth counts the EQ+BE cash universe, and nothing wider. The SM fold is what makes
   * this test detect OVER-widening as well as a reversion — SM is not exotic (352 symbols on the
   * latest live session, more than BE's 229) and nothing else pins {@code SERIES_PREDICATE}'s
   * literal value. The BE rows carry a NULL {@code deliv_per}, as every real BE row does, which is
   * also what keeps them out of the delivery leaderboard.
   */
  @Test
  void breadthCountsTheCashUniverse() throws Exception {
    LocalDate d = SERIES_ONLY_DATE;
    insertBhav(d, "SER_EQ_UP", "EQ", "100", "110", "50.00"); // advancer, top of the leaderboard
    insertBhav(d, "SER_EQ_DN", "EQ", "100", "90", "30.00"); // decliner
    insertBhav(d, "SER_BE_DN1", "BE", "100", "90", null); // BE decliners: must COUNT ...
    insertBhav(d, "SER_BE_DN2", "BE", "100", "90", null); // ... but never reach topDelivery
    insertBhav(d, "SER_SM_UP1", "SM", "100", "110", "99.00"); // SME: must NOT vote, and must not
    insertBhav(d, "SER_SM_UP2", "SM", "100", "110", "99.00"); // top the leaderboard on 99%
    insertBhav(d, "SER_SM_UP3", "SM", "100", "110", "99.00");

    mockMvc
        .perform(get("/api/v1/market/breadth").param("date", SERIES_ONLY_DATE.toString()))
        .andExpect(status().isOk())
        // EQ-only would read 1/1/2; admitting SM would read 4/3/7.
        .andExpect(jsonPath("$.summary.advances").value(1))
        .andExpect(jsonPath("$.summary.declines").value(3))
        .andExpect(jsonPath("$.summary.total").value(4))
        // avg(deliv_per) ignores NULLs, so the BE rows cannot drag it; 50.00 + 30.00 over 2 rows.
        // startsWith, not an exact string: Postgres avg() widens numeric(8,2)'s scale, and pinning
        // that scale would be pinning an implementation detail. If the BE NULLs counted as 0 this
        // would read 20, so the invariant under test is unambiguous either way.
        .andExpect(jsonPath("$.summary.avgDeliveryPct").value(startsWith("40")))
        // NULL deliv_per sorts FIRST in Postgres DESC — the existing IS NOT NULL guard is what
        // stops the two BE rows heading this list, and the predicate is what keeps SM's 99% out.
        // Exactly the two EQ rows, in deliv_per order. Length is the load-bearing half: admitting
        // SM would put three 99% rows on top and read 5; dropping the IS NOT NULL guard would put
        // the two BE NULLs first and read 4.
        .andExpect(jsonPath("$.topDelivery.length()").value(2))
        .andExpect(jsonPath("$.topDelivery[0].symbol").value("SER_EQ_UP"))
        .andExpect(jsonPath("$.topDelivery[1].symbol").value("SER_EQ_DN"));
  }

  @Test
  void breadthIs422WhenNoBhavcopyForDate() throws Exception {
    mockMvc
        .perform(get("/api/v1/market/breadth").param("date", "1999-01-04"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("DATA_GAP"));
  }
}
