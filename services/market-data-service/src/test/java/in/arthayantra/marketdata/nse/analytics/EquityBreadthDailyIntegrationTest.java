package in.arthayantra.marketdata.nse.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.marketdata.nse.analytics.EquityBreadthDailyRepository.BreadthDay;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * IT for the daily-breadth materialization (audit §3.3 / §6.10): the self-contained EQ-bhavcopy fold
 * (adv/dec/unch + above-MA counts), the idempotent upsert, the history read, and the {@code
 * /breadth/history} endpoint. Unique far-past dates + symbols so the shared DB can't collide.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
class EquityBreadthDailyIntegrationTest extends MarketDataIntegrationTestBase {

  private static final LocalDate D1 = LocalDate.of(2019, 2, 4);
  private static final LocalDate D2 = LocalDate.of(2019, 2, 5);
  private static final List<String> SYMS = List.of("BRDX1", "BRDX2", "BRDX3", "BRDXBE", "BRDXSM");

  @Autowired JdbcTemplate jdbc;
  @Autowired EquityBreadthDailyRepository repo;
  @Autowired MockMvc mockMvc;

  private void purge() {
    for (String s : SYMS) {
      jdbc.update("DELETE FROM nse_eod_bhavcopy WHERE symbol = ?", s);
    }
    jdbc.update("DELETE FROM equity_breadth_daily WHERE trade_date IN (?,?)",
        java.sql.Date.valueOf(D1), java.sql.Date.valueOf(D2));
  }

  @AfterEach
  void clean() {
    purge();
  }

  @BeforeEach
  void seed() {
    purge();
    // D1: BRDX1 advancer, BRDX2 decliner  (total 2)
    bhav(D1, "BRDX1", "95", "100", "40");
    bhav(D1, "BRDX2", "205", "200", "50");
    // D2: BRDX1 advancer, BRDX2 decliner, BRDX3 unchanged  (total 3)
    bhav(D2, "BRDX1", "100", "110", "42");
    bhav(D2, "BRDX2", "200", "190", "55");
    bhav(D2, "BRDX3", "50", "50", "60");
  }

  /**
   * H24 PR-6: the materialized fold counts the EQ+BE cash universe, and nothing wider. This is the
   * pair to {@code EquityBreadthEodJob}'s cash-scoped watermark — population and pin move together,
   * which is the whole point of the row.
   */
  @Test
  void foldCountsTheCashUniverseAndNothingWider() {
    bhav(D2, "BRDXBE", "BE", "100", "90", null); // BE decliner: real cash equity, must count
    bhav(D2, "BRDXSM", "SM", "100", "110", "99"); // SME: must not count, and must not lift avg

    BreadthDay day2 =
        repo.compute(D1, D2).stream().filter(d -> d.tradeDate().equals(D2)).findFirst().orElseThrow();

    // EQ-only reads 1/1/1/3; admitting SM reads 2/2/1/5.
    assertThat(day2.advances()).isEqualTo(1);
    assertThat(day2.declines()).isEqualTo(2);
    assertThat(day2.unchanged()).isEqualTo(1);
    assertThat(day2.total()).isEqualTo(4);
    // avg(deliv_per) ignores the BE row's NULL, so it stays the EQ mean of 42/55/60 = 52.33.
    // If SM were admitted its 99 would drag it to ~64, so this pins the exclusion numerically.
    assertThat(day2.avgDeliveryPct()).isNotNull();
    assertThat(day2.avgDeliveryPct().doubleValue()).isBetween(52.0, 52.7);
  }

  @Test
  void computesAdvanceDeclineAcrossARange() {
    List<BreadthDay> days = repo.compute(D1, D2);
    assertThat(days).hasSize(2);

    BreadthDay day1 = days.get(0);
    assertThat(day1.tradeDate()).isEqualTo(D1);
    assertThat(day1.advances()).isEqualTo(1);
    assertThat(day1.declines()).isEqualTo(1);
    assertThat(day1.total()).isEqualTo(2);

    BreadthDay day2 = days.get(1);
    assertThat(day2.tradeDate()).isEqualTo(D2);
    assertThat(day2.advances()).isEqualTo(1);
    assertThat(day2.declines()).isEqualTo(1);
    assertThat(day2.unchanged()).isEqualTo(1);
    assertThat(day2.total()).isEqualTo(3);
    // insufficient history for a 50-session SMA → nobody counted, honest zero universe
    assertThat(day2.sma50Universe()).isZero();
    assertThat(day2.aboveSma50()).isZero();
  }

  @Test
  void upsertIsIdempotentAndHistoryReadsBack() {
    repo.upsertAll(repo.compute(D1, D2));
    repo.upsertAll(repo.compute(D1, D2)); // re-run must not duplicate (trade_date PK)

    List<BreadthDay> hist = repo.history(D1, D2);
    assertThat(hist).extracting(BreadthDay::tradeDate).containsExactly(D1, D2);
    assertThat(repo.latestDate()).isNotNull();
  }

  @Test
  void historyEndpointReturnsMaterializedSeries() throws Exception {
    repo.upsertAll(repo.compute(D1, D2));

    mockMvc
        .perform(
            get("/api/v1/market/breadth/history")
                .param("from", "2019-02-04")
                .param("to", "2019-02-05"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].tradeDate").value("2019-02-04"))
        .andExpect(jsonPath("$.items[1].advances").value(1));
  }

  private void bhav(LocalDate d, String sym, String prevClose, String close, String delivPer) {
    bhav(d, sym, "EQ", prevClose, close, delivPer);
  }

  private void bhav(
      LocalDate d, String sym, String series, String prevClose, String close, String delivPer) {
    jdbc.update(
        "INSERT INTO nse_eod_bhavcopy "
            + "(trade_date, symbol, series, prev_close, close_price, deliv_per) "
            + "VALUES (?,?,?, ?::numeric, ?::numeric, ?::numeric) ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(d), sym, series, prevClose, close, delivPer);
  }
}
