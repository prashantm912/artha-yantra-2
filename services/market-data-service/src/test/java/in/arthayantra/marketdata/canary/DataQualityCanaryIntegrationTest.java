package in.arthayantra.marketdata.canary;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.options.analytics.CrossSourceOiCanary;
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
 * IT for the two Phase-3 data-quality canaries: V8 bhavcopy-close vs Kite-1d-close (seeded divergence
 * detected + counted) and a V7 cross-source OI smoke (evaluate returns a report on an empty overlap).
 * Unique far-past date + symbols so the shared DB can't collide.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class DataQualityCanaryIntegrationTest extends MarketDataIntegrationTestBase {

  private static final LocalDate DQ = LocalDate.of(2019, 3, 4);
  private static final List<String> SYMS = List.of("BCQDIV", "BCQOK", "BCQNONE", "BCQBHAV");

  @Autowired JdbcTemplate jdbc;
  @Autowired BhavcopyCloseCanary bhavcopyClose;
  @Autowired CrossSourceOiCanary crossSourceOi;

  private void purge() {
    for (String s : SYMS) {
      jdbc.update("DELETE FROM nse_eod_bhavcopy WHERE symbol = ?", s);
      jdbc.update("DELETE FROM candles WHERE tradingsymbol = ?", s);
    }
  }

  @AfterEach
  void clean() {
    purge();
  }

  @BeforeEach
  void seed() {
    purge();
    // BCQDIV: bhav 130 vs Kite 100 → |130-100|/100 = 30% divergence (flagged)
    bhav("BCQDIV", "130");
    kiteDaily("BCQDIV", "100");
    // BCQOK: bhav 100 vs Kite 100.5 → 0.5% (< 1% threshold, compared but not divergent)
    bhav("BCQOK", "100");
    kiteDaily("BCQOK", "100.5");
    // BCQNONE: bhav only, no Kite candle → not compared
    bhav("BCQNONE", "100");
    // BCQBHAV: a BHAVCOPY-source candle must be EXCLUDED (would compare against itself)
    bhav("BCQBHAV", "100");
    bhavcopyDaily("BCQBHAV", "999");
  }

  @Test
  void v8DetectsBhavcopyVsKiteCloseDivergence() {
    BhavcopyCloseCanary.BhavcopyCloseReport report = bhavcopyClose.evaluate(DQ);
    assertThat(report.tradeDate()).isEqualTo(DQ);
    assertThat(report.compared()).isEqualTo(2); // BCQDIV + BCQOK (BCQNONE + BCQBHAV excluded)
    assertThat(report.divergent()).isEqualTo(1); // only BCQDIV
    assertThat(report.status()).isEqualTo("YELLOW");
    assertThat(report.offenders()).hasSize(1);
    assertThat(report.offenders().get(0).symbol()).isEqualTo("BCQDIV");
    assertThat(report.offenders().get(0).relDiffPct()).isEqualByComparingTo("30.00");
  }

  @Test
  void v8LatestTradeDateResolves() {
    assertThat(bhavcopyClose.latestTradeDate()).isNotNull();
    // a null date evaluates to a GREEN empty report (no 422)
    assertThat(bhavcopyClose.evaluate(null).status()).isEqualTo("GREEN");
  }

  @Test
  void v7EvaluateReturnsAReportOnEmptyOverlap() {
    CrossSourceOiCanary.CrossSourceReport report = crossSourceOi.evaluate();
    assertThat(report).isNotNull();
    assertThat(report.underlyings()).isNotEmpty();
    // no snapshot rows for the default underlying in a fresh mock DB → a non-RED "no overlap" verdict
    assertThat(report.status()).isIn("GREEN", "YELLOW");
  }

  private void bhav(String sym, String close) {
    jdbc.update(
        "INSERT INTO nse_eod_bhavcopy (trade_date, symbol, series, prev_close, close_price) "
            + "VALUES (?,?, 'EQ', ?::numeric, ?::numeric) ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(DQ), sym, close, close);
  }

  private void kiteDaily(String sym, String close) {
    candle(sym, close, "KITE");
  }

  private void bhavcopyDaily(String sym, String close) {
    candle(sym, close, "BHAVCOPY");
  }

  private void candle(String sym, String close, String source) {
    jdbc.update(
        "INSERT INTO candles(exchange,tradingsymbol,interval,bucket,open,high,low,close,volume,source) "
            + "VALUES('NSE', ?, '1d', '2019-03-04 00:00:00+05:30'::timestamptz, "
            + " ?::numeric, ?::numeric, ?::numeric, ?::numeric, 0, ?) ON CONFLICT DO NOTHING",
        sym, close, close, close, close, source);
  }
}
