package in.arthayantra.marketdata.screener.minervini;

import static org.assertj.core.api.Assertions.assertThat;

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
 * MV-6.7 IT: the SEPA funnel buckets the day's passers into immediately-buyable / on-deck / watch by
 * pivot proximity + a valid VCP base. Seeds screen + geometry rows directly (no candle replay), then
 * asserts the three-list. Shares the singleton DB → purge before AND after.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class MinerviniFunnelIntegrationTest extends MarketDataIntegrationTestBase {

  private static final LocalDate AS_OF = LocalDate.of(2026, 6, 29);
  private static final List<String> SYMS =
      List.of("MVFBUY", "MVFDECK", "MVFNOVCP", "MVFEXT");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private MinerviniFunnelService funnel;

  private void purge() {
    for (String s : SYMS) {
      jdbc.update("DELETE FROM minervini_screen_results WHERE symbol=?", s);
      jdbc.update("DELETE FROM minervini_setups WHERE symbol=?", s);
    }
  }

  @AfterEach
  void tearDown() {
    purge();
  }

  @BeforeEach
  void seed() {
    purge();
    // (symbol, close, rs, is_vcp, pivot): at-pivot buyable / below-pivot on-deck / no-base watch /
    // extended-past-pivot watch.
    passer("MVFBUY", 100.0, 99.0, true, 100.0); // close == pivot -> BUYABLE
    passer("MVFDECK", 95.0, 90.0, true, 100.0); // 95 in [90,98) -> ON_DECK
    passer("MVFNOVCP", 100.0, 80.0, false, null); // no valid base -> WATCH
    passer("MVFEXT", 110.0, 70.0, true, 100.0); // 110 > 105 (chased) -> WATCH
  }

  @Test
  void bucketsPassersByPivotProximityAndBase() {
    MinerviniFunnelService.Funnel f = funnel.funnel(AS_OF);

    assertThat(names(f.immediatelyBuyable())).containsExactly("MVFBUY");
    assertThat(names(f.onDeck())).containsExactly("MVFDECK");
    assertThat(names(f.watch())).contains("MVFNOVCP", "MVFEXT");
    // ranked by RS-rank desc within a bucket is exercised by the watch order (novcp rs80 > ext rs70)
    assertThat(names(f.watch())).containsExactly("MVFNOVCP", "MVFEXT");
  }

  private static List<String> names(List<MinerviniFunnelService.FunnelRow> rows) {
    return rows.stream().map(MinerviniFunnelService.FunnelRow::symbol).toList();
  }

  private void passer(String symbol, double close, double rs, boolean isVcp, Double pivot) {
    jdbc.update(
        "INSERT INTO minervini_screen_results(screen_date,symbol,exchange,close_price,rs_rank,"
            + "gate1,gate2,gate3,gate4,gate5,gate6,gate7,gate8,gates_passed,passes_all,stage) "
            + "VALUES(?,?, 'NSE', ?,?, TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,TRUE, 8, TRUE, 2) "
            + "ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(AS_OF), symbol, close, rs);
    jdbc.update(
        "INSERT INTO minervini_setups(screen_date,symbol,is_vcp,pivot,footprint) "
            + "VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(AS_OF), symbol, isVcp, pivot, isVcp ? "8W 8/4 2T" : null);
  }
}
