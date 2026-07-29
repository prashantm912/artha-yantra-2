package in.arthayantra.marketdata.screener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import in.arthayantra.marketdata.screener.minervini.TrendCandidate;
import in.arthayantra.marketdata.screener.minervini.TrendTemplateService;
import in.arthayantra.marketdata.screener.minervini.geometry.DailyBar;
import in.arthayantra.marketdata.screener.minervini.geometry.DailyBarReader;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * FID P0-4 / audit-H6 equality IT for the CA-adjusted screener + geometry price plane
 * ({@link in.arthayantra.marketdata.equitydaily.AdjustedEquityDailySql}). The live screener/geometry used to read split/bonus-UNADJUSTED
 * bhavcopy while the deep-sim/hit-rate plane reads broker-adjusted {@code candles}@1d — so a split
 * inside the window opened a false price cliff and the two planes disagreed.
 *
 * <p>The fixture seeds ONE split name (a 2:1 split, ratio 0.5, ex-date inside the trailing
 * 50-session turnover window) whose RAW bhavcopy series carries the cliff (pre-ex OHLC 2× the
 * continuous level on half the share count) plus its {@code eod_corporate_actions} ratio, and a
 * control name whose bhavcopy IS the continuous adjusted series with no action. The
 * continuous series {@code A(i) = 100 + i} is exactly the plane the adjusted-candles deep-sim reads.
 * The fix must make the split name's ADJUSTED plane reconstruct that continuous series — so the
 * screener's close / SMA / 52-week values on the split name must equal both the hand-computed
 * continuous values AND the control name's, decimal-for-decimal, and the geometry OHLC series must
 * match bar-for-bar. Without the fix the split name reads the raw cliff and diverges.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class ScreenerCorporateActionAdjustmentIntegrationTest extends MarketDataIntegrationTestBase {

  private static final String SPLIT = "CASPLIT"; // raw cliff + a CA ratio row
  private static final String CTRL = "CACTRL"; // continuous adjusted series, no CA
  private static final List<String> SYMS = List.of(SPLIT, CTRL);
  private static final int DAYS = 260;
  // First ex-date (post-split) bar — deliberately INSIDE the trailing 50-session turnover window
  // (indices 210..259), so the raw-close turnover invariance is stressed ACROSS the split boundary.
  private static final int EX_INDEX = 235;
  private static final LocalDate AS_OF = LocalDate.of(2026, 6, 30);

  @Autowired private JdbcTemplate jdbc;
  @Autowired private TrendTemplateService screener;
  @Autowired private DailyBarReader geometry;

  /** The continuous, already-adjusted close for bar {@code i} — the plane the deep-sim reads. */
  private static double adjusted(int i) {
    return 100.0 + i;
  }

  private void purge() {
    for (String s : SYMS) {
      jdbc.update("DELETE FROM nse_eod_bhavcopy WHERE symbol=?", s);
      jdbc.update("DELETE FROM eod_corporate_actions WHERE tradingsymbol=?", s);
      jdbc.update("DELETE FROM minervini_screen_results WHERE symbol=?", s);
    }
  }

  @AfterEach
  void tearDown() {
    purge();
  }

  @BeforeEach
  void seed() {
    purge();
    // Control: the continuous adjusted series, no corporate action.
    seedSeries(CTRL, i -> adjusted(i), i -> 100_000L);
    // Split name: pre-ex OHLC is 2× the continuous level (the raw cliff a 2:1 split leaves in
    // bhavcopy) on HALF the share count (real tape: the split doubles shares outstanding); post-ex
    // bars ARE the continuous level at the full count. The 0.5 pre-ex multiplier must undo the price
    // cliff, while raw rupee turnover (2P × Q/2 = P × Q) is identical to the control on every bar.
    seedSeries(SPLIT, i -> i < EX_INDEX ? adjusted(i) / 0.5 : adjusted(i),
        i -> i < EX_INDEX ? 50_000L : 100_000L);
    LocalDate exDate = dateOf(EX_INDEX);
    jdbc.update(
        "INSERT INTO eod_corporate_actions(exchange,tradingsymbol,ex_date,ratio,kind)"
            + " VALUES('NSE',?,?,0.5,'SPLIT')",
        SPLIT, java.sql.Date.valueOf(exDate));
  }

  private static LocalDate dateOf(int i) {
    return AS_OF.minusDays(DAYS - 1L - i);
  }

  private void seedSeries(
      String symbol,
      java.util.function.IntToDoubleFunction shape,
      java.util.function.IntToLongFunction volume) {
    List<Object[]> batch = new ArrayList<>(DAYS);
    for (int i = 0; i < DAYS; i++) {
      double px = shape.applyAsDouble(i); // integer-valued → exact
      batch.add(new Object[] {dateOf(i), symbol, px, px, px, px, volume.applyAsLong(i)});
    }
    jdbc.batchUpdate(
        "INSERT INTO nse_eod_bhavcopy(trade_date,symbol,series,open_price,close_price,high_price,"
            + "low_price,ttl_trd_qnty) VALUES(?,?, 'EQ', ?,?,?,?,?) ON CONFLICT DO NOTHING",
        batch);
  }

  @Test
  void adjustedScreenerPlaneReconstructsTheContinuousDeepSimPlaneAcrossASplit() {
    TrendTemplateService.ScreenResult res = screener.screen(AS_OF);
    Map<String, TrendCandidate> by = new java.util.HashMap<>();
    res.candidates().forEach(c -> by.put(c.symbol(), c));

    TrendCandidate split = by.get(SPLIT);
    TrendCandidate ctrl = by.get(CTRL);
    assertThat(split).as("split name survives the screen").isNotNull();
    assertThat(ctrl).as("control name survives the screen").isNotNull();

    // Hand-computed continuous values (A(i)=100+i): the last bar closes at 359; the trailing SMAs and
    // 52-week extremes are the averages/extremes of the continuous plane, NOT the raw cliff.
    assertThat(split.close()).as("adjusted close = continuous level").isEqualByComparingTo("359");
    assertThat(split.sma50()).as("SMA50 over adjusted closes 310..359").isEqualByComparingTo("334.5");
    assertThat(split.sma150()).as("SMA150 over 210..359").isEqualByComparingTo("284.5");
    assertThat(split.sma200()).as("SMA200 over 160..359").isEqualByComparingTo("259.5");
    assertThat(split.high52w()).as("52w high = continuous, not the pre-split raw 668")
        .isEqualByComparingTo("359");
    assertThat(split.low52w()).as("52w low = continuous 108, not a raw-cliff artifact")
        .isEqualByComparingTo("108");

    // Equality with the no-CA control: the split name's whole adjusted price plane is identical to the
    // continuous control, decimal-for-decimal — the cliff is gone. (RS-rank/gate-8 are cross-sectional
    // tie-break artifacts for two identical names, so assert the per-name price plane, RS raw, price
    // gates 1..7, and Stage.)
    assertThat(split.close()).isEqualByComparingTo(ctrl.close());
    assertThat(split.sma50()).isEqualByComparingTo(ctrl.sma50());
    assertThat(split.sma150()).isEqualByComparingTo(ctrl.sma150());
    assertThat(split.sma200()).isEqualByComparingTo(ctrl.sma200());
    assertThat(split.high52w()).isEqualByComparingTo(ctrl.high52w());
    assertThat(split.low52w()).isEqualByComparingTo(ctrl.low52w());
    assertThat(split.rsRaw()).as("weighted RS over the adjusted plane matches the control")
        .isEqualByComparingTo(ctrl.rsRaw());
    assertThat(split.stage()).isEqualTo(ctrl.stage());
    for (int g = 1; g <= 7; g++) {
      assertThat(split.gate(g)).as("price gate %d matches the control", g).isEqualTo(ctrl.gate(g));
    }

    // The rupee-turnover liquidity gate stays split-invariant ACROSS the boundary: the 50-session
    // window straddles the ex-date (indices 210..259, ex at 235), pre-split bars trade 2P × Q/2 and
    // post-split P × Q — raw_close × volume equals the control on every bar. Had the ADJUSTED close
    // fed the turnover, the pre-split legs would read HALF their true traded rupees.
    assertThat(split.avgTurnover50()).isEqualByComparingTo(ctrl.avgTurnover50());
  }

  @Test
  void adjustedGeometryOhlcMatchesTheContinuousControlBarForBar() {
    List<DailyBar> split = geometry.read(SPLIT, AS_OF, 420);
    List<DailyBar> ctrl = geometry.read(CTRL, AS_OF, 420);
    assertThat(split).hasSize(DAYS);
    assertThat(ctrl).hasSize(DAYS);
    for (int i = 0; i < DAYS; i++) {
      DailyBar s = split.get(i);
      DailyBar c = ctrl.get(i);
      assertThat(s.date()).isEqualTo(c.date());
      assertThat(s.open()).as("adjusted open at %s", s.date()).isEqualTo(c.open(), within(1e-9));
      assertThat(s.high()).isEqualTo(c.high(), within(1e-9));
      assertThat(s.low()).isEqualTo(c.low(), within(1e-9));
      assertThat(s.close()).as("adjusted close at %s", s.date()).isEqualTo(c.close(), within(1e-9));
      // and equal to the continuous adjusted level the deep-sim reads (open seeded = close, so this
      // pins the OPEN scaling path too — a NULL/unadjusted open would fail here, not read 0==0)
      assertThat(s.close()).isEqualTo(adjusted(i), within(1e-9));
      assertThat(s.open()).as("open rides the same factor at %s", s.date())
          .isEqualTo(adjusted(i), within(1e-9));
    }
  }
}
