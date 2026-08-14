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
  private static final List<String> SYMS =
      List.of("BCQDIV", "BCQOK", "BCQNONE", "BCQBHAV", "BCQALGO");
  /** Own symbols + own date, so the shared no-cleanup IT database cannot cross-contaminate. */
  private static final List<String> THIN_SYMS = List.of("BCQTHIN1", "BCQTHIN2");

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
    // ⚠️ BCQALGO: a NON-Kite fetched candle must still be COMPARED. The population this canary now
    // seeds itself goes out through GapBackfiller -> CandleQueryService, which stamps
    // `gateway.sourceLabel()` (CandleQueryService:65) — deliberately the fetching impl's label. Flip
    // artha.marketdata.source.candles to openalgo and every seeded row is OPENALGO, so the old
    // `source='KITE'` predicate would count NONE of them: 202 rows seeded, 0 compared, a permanent
    // coverage YELLOW produced by a routing flag with nothing to do with the close feeds. 0.5%
    // apart, so it is comparable-but-clean and moves `compared` without touching `divergent`.
    bhav("BCQALGO", "100");
    candle("BCQALGO", "100.5", "OPENALGO");
  }

  @Test
  void v8DetectsBhavcopyVsKiteCloseDivergence() {
    BhavcopyCloseCanary.BhavcopyCloseReport report = bhavcopyClose.evaluate(DQ);
    assertThat(report.tradeDate()).isEqualTo(DQ);
    assertThat(report.compared())
        .as("BCQDIV + BCQOK + BCQALGO; BCQNONE has no candle and BCQBHAV is self-sourced")
        .isEqualTo(3);
    assertThat(report.divergent()).isEqualTo(1); // only BCQDIV
    assertThat(report.status()).isEqualTo("YELLOW");
    assertThat(report.offenders()).hasSize(1);
    assertThat(report.offenders().get(0).symbol()).isEqualTo("BCQDIV");
    assertThat(report.offenders().get(0).relDiffPct()).isEqualByComparingTo("30.00");
  }

  @Test
  void v8LatestTradeDateResolves() {
    assertThat(bhavcopyClose.latestTradeDate()).isNotNull();
    // A null date still evaluates without a 422 — but it is no longer GREEN. An empty comparison
    // set is the smallest population there is, so the coverage floor withholds certification:
    // "nothing to compare" is not "the feeds agree".
    assertThat(bhavcopyClose.evaluate(null).status()).isEqualTo("YELLOW");
  }

  /**
   * ⚠️ The realistic small-population case, end-to-end through the real SQL and schema.
   *
   * <p>This is the 2026-08-11 shape: every comparable symbol agrees perfectly, and there are far too
   * few of them to mean anything. Before the coverage floor this reported GREEN — the canary's whole
   * purpose is catching a bad close feed, and at that population it would miss a universe-wide
   * corruption while certifying the feed as clean.
   *
   * <p>Its own date + symbols, never shared with {@link #seed()}, because the IT database is a
   * singleton with no per-method cleanup.
   */
  @Test
  void v8ThinPopulationIsNotCertifiedGreen() {
    LocalDate thin = LocalDate.of(2019, 3, 5);
    for (String s : THIN_SYMS) {
      jdbc.update("DELETE FROM nse_eod_bhavcopy WHERE symbol = ?", s);
      jdbc.update("DELETE FROM candles WHERE tradingsymbol = ?", s);
      jdbc.update(
          "INSERT INTO nse_eod_bhavcopy (trade_date, symbol, series, prev_close, close_price) "
              + "VALUES (?,?, 'EQ', 100::numeric, 100::numeric) ON CONFLICT DO NOTHING",
          java.sql.Date.valueOf(thin), s);
      jdbc.update(
          "INSERT INTO candles(exchange,tradingsymbol,interval,bucket,open,high,low,close,volume,"
              + "source) VALUES('NSE', ?, '1d', '2019-03-05 00:00:00+05:30'::timestamptz, "
              + " 100,100,100,100, 0, 'KITE') ON CONFLICT DO NOTHING",
          s);
    }
    try {
      BhavcopyCloseCanary.BhavcopyCloseReport report = bhavcopyClose.evaluate(thin);

      assertThat(report.compared()).isEqualTo(THIN_SYMS.size());
      assertThat(report.divergent()).as("both closes are exactly 100 — nothing diverges").isZero();
      assertThat(report.compared())
          .as("the fixture must actually be below the floor or this test proves nothing")
          .isLessThan(report.minCompared());
      assertThat(report.status())
          .as("zero divergence over a population this thin must not certify the close feed")
          .isEqualTo("YELLOW");
    } finally {
      for (String s : THIN_SYMS) {
        jdbc.update("DELETE FROM nse_eod_bhavcopy WHERE symbol = ?", s);
        jdbc.update("DELETE FROM candles WHERE tradingsymbol = ?", s);
      }
    }
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
