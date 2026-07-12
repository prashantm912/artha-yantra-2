package in.arthayantra.marketdata.screener;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.screener.ScreenerHistory.AttritionCounts;
import in.arthayantra.marketdata.screener.ScreenerHistory.ScreenDate;
import in.arthayantra.marketdata.screener.ScreenerHistory.ScreenDiff;
import in.arthayantra.marketdata.screener.ScreenerHistoryRepository.Family;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * IT for the screener time-machine repository (audit §6.7): recent-dates, prior-date, day-over-day
 * passer diff, and funnel stage-attrition counts over persisted per-gate boolean rows. Uses far-past
 * dates + unique symbols so the shared-singleton-DB (no per-method cleanup) can't collide with the
 * other screener ITs; purges its own rows around each method.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class ScreenerHistoryIntegrationTest extends MarketDataIntegrationTestBase {

  private static final LocalDate D1 = LocalDate.of(2019, 1, 8);
  private static final LocalDate D2 = LocalDate.of(2019, 1, 9);
  private static final List<String> SYMS = List.of("SHISTA", "SHISTB", "SHISTC", "SHISTD");

  @Autowired JdbcTemplate jdbc;
  @Autowired ScreenerHistoryRepository history;

  private void purge() {
    for (String s : SYMS) {
      jdbc.update("DELETE FROM minervini_screen_results WHERE symbol = ?", s);
    }
  }

  @AfterEach
  void clean() {
    purge();
  }

  @BeforeEach
  void seed() {
    purge();
    // D1 passers: A, B
    seed(D1, "SHISTA", 90, true, true, 8);
    seed(D1, "SHISTB", 80, true, true, 8);
    // D2 passers: B, C ; non-passer D (gate1 fails)
    seed(D2, "SHISTB", 85, true, true, 8);
    seed(D2, "SHISTC", 75, true, true, 8);
    seed(D2, "SHISTD", 60, false, false, 7);
  }

  @Test
  void recentDatesCarryCoverageAndPasserCounts() {
    List<ScreenDate> dates = history.recentDates(Family.MINERVINI, 250);
    ScreenDate d2 = dates.stream().filter(d -> d.date().equals(D2)).findFirst().orElseThrow();
    ScreenDate d1 = dates.stream().filter(d -> d.date().equals(D1)).findFirst().orElseThrow();
    assertThat(d2.coverage()).isEqualTo(3);
    assertThat(d2.passers()).isEqualTo(2);
    assertThat(d1.coverage()).isEqualTo(2);
    assertThat(d1.passers()).isEqualTo(2);
    // newest-first ordering: D2 appears before D1
    assertThat(dates.indexOf(d2)).isLessThan(dates.indexOf(d1));
  }

  @Test
  void priorScreenDateIsTheImmediatelyEarlierDate() {
    assertThat(history.priorScreenDate(Family.MINERVINI, D2)).isEqualTo(D1);
  }

  @Test
  void diffReportsEnteredAndLeftPassers() {
    ScreenDiff diff = history.diff(Family.MINERVINI, D2, D1);
    assertThat(diff.screenDate()).isEqualTo(D2);
    assertThat(diff.priorDate()).isEqualTo(D1);
    assertThat(diff.entered().stream().map(ScreenerHistory.DiffRow::symbol)).containsExactly("SHISTC");
    assertThat(diff.left().stream().map(ScreenerHistory.DiffRow::symbol)).containsExactly("SHISTA");
    assertThat(diff.enteredCount()).isEqualTo(1);
    assertThat(diff.leftCount()).isEqualTo(1);
  }

  @Test
  void diffWithNullPriorCountsEveryPasserAsEntered() {
    ScreenDiff diff = history.diff(Family.MINERVINI, D1, null);
    assertThat(diff.entered().stream().map(ScreenerHistory.DiffRow::symbol))
        .containsExactlyInAnyOrder("SHISTA", "SHISTB");
    assertThat(diff.left()).isEmpty();
  }

  @Test
  void attritionCountsScannedPerGateAndPassesAll() {
    AttritionCounts a = history.attrition(Family.MINERVINI, D2);
    assertThat(a.scanned()).isEqualTo(3); // B, C, D
    assertThat(a.passesAll()).isEqualTo(2); // B, C
    assertThat(a.gates()).hasSize(8);
    // gate1: B, C pass; D fails → 2
    assertThat(a.gates().get(0).gate()).isEqualTo(1);
    assertThat(a.gates().get(0).passed()).isEqualTo(2);
    // gate2: all three pass → 3
    assertThat(a.gates().get(1).passed()).isEqualTo(3);
  }

  private void seed(
      LocalDate date, String symbol, double rs, boolean gate1, boolean passesAll, int gatesPassed) {
    jdbc.update(
        "INSERT INTO minervini_screen_results(screen_date,symbol,exchange,close_price,rs_rank,"
            + "gate1,gate2,gate3,gate4,gate5,gate6,gate7,gate8,gates_passed,passes_all,stage) "
            + "VALUES(?,?, 'NSE', 100, ?, ?, TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,TRUE, ?, ?, 2) "
            + "ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(date), symbol, rs, gate1, gatesPassed, passesAll);
  }
}
