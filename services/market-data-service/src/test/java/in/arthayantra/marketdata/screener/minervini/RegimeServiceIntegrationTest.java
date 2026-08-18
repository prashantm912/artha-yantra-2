package in.arthayantra.marketdata.screener.minervini;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MV-6.9 IT: the regime reads favorable / hostile from the trailing advance-vs-decline breadth on
 * {@code nse_eod_bhavcopy}. Seeds a date with an advancing majority (→ FAVORABLE) and one with a
 * declining majority (→ HOSTILE). Shares the singleton DB → unique symbols + purge before/after.
 *
 * <p>Also pins H24 PR-2: the breadth population is EQ+BE, not EQ alone. {@link #beSeriesRowsVote}
 * seeds a day whose EQ fold alone reads FAVORABLE and whose BE fold flips it HOSTILE, so a
 * regression to {@code series = 'EQ'} fails on the verdict rather than on a rounding delta.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class RegimeServiceIntegrationTest extends MarketDataIntegrationTestBase {

  private static final LocalDate FAV = LocalDate.of(2026, 5, 11);
  private static final LocalDate HOSTILE = LocalDate.of(2026, 5, 12);
  private static final LocalDate SERIES = LocalDate.of(2026, 6, 15);
  private static final String PREFIX = "RGM";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private RegimeService regime;

  private void purge() {
    jdbc.update("DELETE FROM nse_eod_bhavcopy WHERE symbol LIKE ?", PREFIX + "%");
  }

  @AfterEach
  void tearDown() {
    purge();
  }

  @BeforeEach
  void seed() {
    purge();
    // FAV: 60 advancing + 40 declining = 0.60 ratio (>= 0.52 favorable-floor).
    seedDay(FAV, 60, 40);
    // HOSTILE: 40 advancing + 60 declining = 0.40 ratio (<= 0.45 hostile-ceiling).
    seedDay(HOSTILE, 40, 60);
  }

  @Test
  void advancingBreadthReadsFavorable() {
    RegimeService.Regime r = regime.regime(FAV);
    assertThat(r.regime()).isEqualTo("FAVORABLE");
    assertThat(r.advanceRatio()).isEqualByComparingTo("0.60");
  }

  @Test
  void decliningBreadthReadsHostile() {
    // Window is trailing so HOSTILE's window also sees FAV's day; seed HOSTILE far enough that its
    // majority dominates — here both days are in-window, 100 adv vs 100 dec = 0.50 (neutral). Assert
    // the single-day read by narrowing: query HOSTILE with only its own rows present.
    jdbc.update("DELETE FROM nse_eod_bhavcopy WHERE symbol LIKE ? AND trade_date = ?", PREFIX + "%", java.sql.Date.valueOf(FAV));
    RegimeService.Regime r = regime.regime(HOSTILE);
    assertThat(r.regime()).isEqualTo("HOSTILE");
    assertThat(r.advanceRatio()).isEqualByComparingTo("0.40");
  }

  @Test
  void beSeriesRowsVote() {
    // H24 PR-2. The EQ fold alone is 60/40 = 0.60 (FAVORABLE); the BE fold adds 60 decliners, so
    // the cash universe reads 60 adv / 100 dec = 0.375 (HOSTILE). EQ-only would report FAVORABLE.
    // SERIES sits > 10 days after FAV/HOSTILE, so the trailing window sees only this day's rows.
    seedDay(SERIES, 60, 40);
    seedSeriesDay(SERIES, "BE", 0, 60);

    RegimeService.Regime r = regime.regime(SERIES);

    assertThat(r.regime()).isEqualTo("HOSTILE");
    assertThat(r.advanceRatio()).isEqualByComparingTo("0.375");
    assertThat(r.sessions()).isEqualTo(1);
  }

  private void seedDay(LocalDate date, int advances, int declines) {
    seedSeriesDay(date, "EQ", advances, declines);
  }

  private void seedSeriesDay(LocalDate date, String series, int advances, int declines) {
    for (int i = 0; i < advances; i++) {
      seedRow(date, PREFIX + series + "A" + date.getDayOfMonth() + "_" + i, series, 110.0, 100.0); // advance
    }
    for (int i = 0; i < declines; i++) {
      seedRow(date, PREFIX + series + "D" + date.getDayOfMonth() + "_" + i, series, 90.0, 100.0); // decline
    }
  }

  private void seedRow(LocalDate date, String symbol, String series, double close, double prevClose) {
    jdbc.update(
        "INSERT INTO nse_eod_bhavcopy(trade_date,symbol,series,close_price,high_price,low_price,"
            + "prev_close,ttl_trd_qnty) VALUES(?,?,?,?,?,?,?, 1000) ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(date), symbol, series, close, close, close, prevClose);
  }
}
