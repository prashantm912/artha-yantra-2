package in.arthayantra.marketdata.nse.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.context.EquityContextRepository;
import in.arthayantra.marketdata.nse.analytics.EquityBreadthDailyRepository.BreadthDay;
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
 * Audit-H6 / ledger §9-02 equality IT for the two BREADTH consumers that were never swept onto the
 * CA-adjusted plane: {@link EquityBreadthDailyRepository#compute} and {@link
 * EquityContextRepository#aboveMa}. Both used to read raw {@code nse_eod_bhavcopy} closes, so a
 * split or bonus ex-date inside the window collapsed the name against its unadjusted prior close
 * (counted a DECLINE) and dropped it below its own SMA for the next 50/200 sessions (counted out of
 * the above-MA numerator) — a data artifact, not market breadth.
 *
 * <p>The fixture seeds ONE 2:1 split name whose RAW bhavcopy carries the cliff (pre-ex closes are 2x
 * the continuous level) plus its {@code eod_corporate_actions} ratio row, alongside a control name
 * whose bhavcopy IS the continuous series with no action. On the adjusted plane the two names are
 * decimal-identical, so every count must move by TWO; on the raw plane the split name is missing
 * from exactly the buckets the cliff evicts it from and the counts move by ONE.
 *
 * <p>Every assertion is a DELTA against a baseline read taken before seeding. The IT DB is shared
 * with no per-method cleanup, so an absolute count would be a hostage to whatever else is in the
 * table; a delta is not.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class EquityBreadthCorporateActionAdjustmentIntegrationTest extends MarketDataIntegrationTestBase {

  private static final String SPLIT = "CADXSPLIT"; // raw cliff + a CA ratio row
  private static final String CTRL = "CADXCTRL"; // continuous series, no corporate action
  private static final List<String> SYMS = List.of(SPLIT, CTRL);

  // A far-past, otherwise-unused window so the seeded dates cannot collide with another IT's fixture.
  private static final LocalDate DAY0 = LocalDate.of(2016, 3, 1);
  private static final int BARS = 60; // >= 50 so the SMA50 / MA50 windows are full at the last bar
  private static final int EX_INDEX = 40; // the split's ex-date bar

  @Autowired private JdbcTemplate jdbc;
  @Autowired private EquityBreadthDailyRepository breadth;
  @Autowired private EquityContextRepository context;

  /** The continuous (already-adjusted) close for bar {@code i} — strictly rising, so no ties. */
  private static double adjusted(int i) {
    return 100.0 + i;
  }

  private static LocalDate dateOf(int i) {
    return DAY0.plusDays(i);
  }

  private void purge() {
    for (String s : SYMS) {
      jdbc.update("DELETE FROM nse_eod_bhavcopy WHERE symbol = ?", s);
      jdbc.update("DELETE FROM eod_corporate_actions WHERE tradingsymbol = ?", s);
    }
  }

  @AfterEach
  void tearDown() {
    purge();
  }

  @BeforeEach
  void purgeOnly() {
    purge();
  }

  private void seed() {
    for (int i = 0; i < BARS; i++) {
      // The control IS the continuous series.
      bhav(CTRL, dateOf(i), adjusted(i), adjusted(i == 0 ? 0 : i - 1));
      // The split name's RAW bhavcopy: pre-ex closes sit on the pre-split price scale (2x), post-ex
      // closes are the continuous level. That step at EX_INDEX is exactly the artifact under test.
      double raw = i < EX_INDEX ? adjusted(i) * 2 : adjusted(i);
      double rawPrev = i == 0 ? raw : (i - 1 < EX_INDEX ? adjusted(i - 1) * 2 : adjusted(i - 1));
      bhav(SPLIT, dateOf(i), raw, rawPrev);
    }
    // ratio is the PRE-ex-date price multiplier: a 2:1 split is 0.5 (V022 header).
    jdbc.update(
        "INSERT INTO eod_corporate_actions (exchange, tradingsymbol, ex_date, ratio, kind, source)"
            + " VALUES ('NSE', ?, ?, 0.5, 'SPLIT', 'TEST') ON CONFLICT DO NOTHING",
        SPLIT,
        java.sql.Date.valueOf(dateOf(EX_INDEX)));
  }

  private void bhav(String sym, LocalDate d, double close, double prevClose) {
    jdbc.update(
        "INSERT INTO nse_eod_bhavcopy (trade_date, symbol, series, prev_close, close_price, deliv_per)"
            + " VALUES (?,?, 'EQ', ?::numeric, ?::numeric, 50) ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(d),
        sym,
        java.math.BigDecimal.valueOf(prevClose),
        java.math.BigDecimal.valueOf(close));
  }

  private BreadthDay day(LocalDate from, LocalDate to, LocalDate wanted) {
    return breadth.compute(from, to).stream()
        .filter(d -> d.tradeDate().equals(wanted))
        .findFirst()
        .orElse(new BreadthDay(wanted, 0, 0, 0, 0, null, 0, 0, 0, 0));
  }

  /**
   * The ex-date bar. On the adjusted plane BOTH names rose by exactly 1.00 (adjusted(40) = 140
   * against adjusted(39) = 139), so advances must gain TWO. On the RAW plane the split name reads
   * 140 against its unadjusted prior close of 278 — a 49.6% "decline" that never happened — so
   * advances would gain only ONE and declines would gain one.
   */
  @Test
  void exDateBarIsAnAdvanceOnBothNamesNotADeclineOnTheSplitName() {
    LocalDate exDate = dateOf(EX_INDEX);
    LocalDate to = dateOf(BARS - 1);
    BreadthDay before = day(exDate, to, exDate);

    seed();

    BreadthDay after = day(exDate, to, exDate);
    assertThat(after.total() - before.total()).isEqualTo(2);
    assertThat(after.advances() - before.advances())
        .as("both names advanced 139 -> 140 on the adjusted plane")
        .isEqualTo(2);
    assertThat(after.declines() - before.declines())
        .as("the raw cliff (278 -> 140) must not be counted as a decline")
        .isZero();
  }

  /**
   * The last bar's 50-session SMA. Adjusted, both names close 159 against an SMA50 of 134.5, so
   * above_sma50 must gain TWO. Raw, the split name's SMA50 is dragged to 209.2 by the 2x pre-ex
   * bars, so its own close reads BELOW its own average and above_sma50 would gain only ONE — while
   * the denominator gains two either way, which is what makes the ratio wrong rather than merely
   * noisy.
   */
  @Test
  void splitNameCountsIntoAboveSma50OnTheAdjustedPlane() {
    LocalDate last = dateOf(BARS - 1);
    LocalDate from = dateOf(EX_INDEX);
    BreadthDay before = day(from, last, last);

    seed();

    BreadthDay after = day(from, last, last);
    assertThat(after.sma50Universe() - before.sma50Universe())
        .as("both names have a full 50-bar window either way")
        .isEqualTo(2);
    assertThat(after.aboveSma50() - before.aboveSma50())
        .as("raw SMA50 of the split name is 209.2 vs its 159 close; adjusted it is 134.5")
        .isEqualTo(2);
  }

  /**
   * {@link EquityContextRepository#aboveMa} — the equity digest's above-20/50-DMA fold, the second
   * site. Same discriminator: adjusted, the split name is above its MA50; raw, its MA50 is 209.2 and
   * it is not. MA20 is post-ex on both planes, so it moves by two either way and is NOT a
   * discriminator — asserted anyway so a regression that broke the whole fold is distinguishable
   * from one that only broke the adjustment.
   */
  @Test
  void aboveMaCountsTheSplitNameOnTheAdjustedPlane() {
    LocalDate last = dateOf(BARS - 1);
    EquityContextRepository.AboveMaCounts before = context.aboveMa(last);

    seed();

    EquityContextRepository.AboveMaCounts after = context.aboveMa(last);
    assertThat(after.universe50() - before.universe50()).isEqualTo(2);
    assertThat(after.universe20() - before.universe20()).isEqualTo(2);
    assertThat(after.above20() - before.above20()).isEqualTo(2);
    assertThat(after.above50() - before.above50())
        .as("raw MA50 of the split name is 209.2 vs its 159 close; adjusted it is 134.5")
        .isEqualTo(2);
  }
}
