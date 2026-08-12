package in.arthayantra.marketdata.nse.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.nse.analytics.EquityDeliveryService.DeliveryDay;
import in.arthayantra.marketdata.nse.analytics.EquityReturnsService.ReturnsRow;
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
 * FID P0-4 / audit-H6 equality IT for the CA-adjusted equity-returns price plane (adopting the same
 * shared multiplicative rule as {@link in.arthayantra.marketdata.equitydaily.AdjustedEquityDailySql} into
 * {@link EquityReturnsService}). The Equity-Returns screener reconstructs multi-day returns from the
 * ACTUAL historical closes (rn 2/6/22/127/253 sessions back), not the exchange-published prev_close —
 * so a split/bonus inside a window used to open a false price cliff (the raw pre-split close sits on a
 * different price scale than the post-split LTP) that cratered the 6m/1y legs.
 *
 * <p>The fixture seeds ONE split name (a 2:1 split, ratio 0.5, ex-date INSIDE the 6m/1y windows) whose
 * RAW bhavcopy carries the cliff (pre-ex close 2× the continuous level on half the share count) plus its
 * {@code eod_corporate_actions} ratio, and a control name whose bhavcopy IS the continuous adjusted
 * series with no action. The continuous series {@code A(i) = 100 + i} is the plane the adjusted-candles
 * deep-sim reads. After the fix the split name's ADJUSTED returns reconstruct that continuous series —
 * so every window return equals both the hand-computed continuous value AND the no-CA control's,
 * decimal-for-decimal. Without the fix the split name reads the raw cliff and the 6m/1y legs diverge.
 *
 * <p>Second test PINS the deliberate KEEP-RAW decision for {@link EquityDeliveryService}: its per-day
 * OHLC series is a faithful per-session record (its % change rides the exchange-published ex-adjusted
 * prev_close), so it must NOT ride the adjusted plane — a pre-ex bar still reports the RAW 2× close.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class EquityReturnsCorporateActionAdjustmentIntegrationTest extends MarketDataIntegrationTestBase {

  private static final String SPLIT = "GRASIM"; // raw cliff + a CA ratio row (sector-mapped)
  private static final String CTRL = "BRITANNIA"; // continuous adjusted series, no CA (sector-mapped)
  private static final List<String> SYMS = List.of(SPLIT, CTRL);
  private static final int DAYS = 260; // rn up to 253 (1y base) must exist
  // Ex-date bar index — INSIDE the 6m/1y windows (their bases are pre-split) so the split boundary is
  // stressed across the multi-day return; the 1d/1w/1m bases are post-split.
  private static final int EX_INDEX = 235;
  // The Equity-Returns HAVING keeps a symbol only when its latest bar is the GLOBAL max EQ session, so
  // the series must END on today IST (the same anchor the other Equity-Returns ITs use in the shared DB).
  private static final LocalDate AS_OF = LocalDate.now(Ist.ZONE);

  @Autowired private JdbcTemplate jdbc;
  @Autowired private EquityReturnsService returns;
  @Autowired private EquityDeliveryService delivery;

  /** The continuous, already-adjusted close for bar {@code i} — the plane the deep-sim reads. */
  private static double adjusted(int i) {
    return 100.0 + i;
  }

  private static LocalDate dateOf(int i) {
    return AS_OF.minusDays(DAYS - 1L - i);
  }

  private void purge() {
    for (String s : SYMS) {
      jdbc.update("DELETE FROM nse_eod_bhavcopy WHERE symbol=?", s);
      jdbc.update("DELETE FROM eod_corporate_actions WHERE tradingsymbol=?", s);
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
    // Split name: pre-ex close is 2× the continuous level (the raw cliff a 2:1 split leaves in
    // bhavcopy) on HALF the share count; post-ex bars ARE the continuous level at the full count.
    seedSeries(
        SPLIT,
        i -> i < EX_INDEX ? adjusted(i) / 0.5 : adjusted(i),
        i -> i < EX_INDEX ? 50_000L : 100_000L);
    jdbc.update(
        "INSERT INTO eod_corporate_actions(exchange,tradingsymbol,ex_date,ratio,kind)"
            + " VALUES('NSE',?,?,0.5,'SPLIT')",
        SPLIT, java.sql.Date.valueOf(dateOf(EX_INDEX)));
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
  void adjustedReturnsReconstructTheContinuousDeepSimPlaneAcrossASplit() {
    Map<String, ReturnsRow> by = new java.util.HashMap<>();
    returns.returns().items().forEach(r -> by.put(r.symbol(), r));

    ReturnsRow split = by.get(SPLIT);
    ReturnsRow ctrl = by.get(CTRL);
    assertThat(split).as("split name survives the as-of-latest screen").isNotNull();
    assertThat(ctrl).as("control name survives the as-of-latest screen").isNotNull();

    // Hand-computed continuous returns (A(i)=100+i, latest bar rn1 i=259 → 359; rn N → i=260-N):
    //   1d  rn2  i258 358 → (359-358)/358 =  0.28%
    //   1w  rn6  i254 354 → (359-354)/354 =  1.41%
    //   1m  rn22 i238 338 → (359-338)/338 =  6.21%
    //   6m  rn127 i133 233 → (359-233)/233 = 54.08%   (base PRE-split — the stressed leg)
    //   1y  rn253 i7  107 → (359-107)/107 = 235.51%   (base PRE-split — the stressed leg)
    assertThat(split.ltp()).as("adjusted LTP = continuous level").isEqualByComparingTo("359");
    assertThat(split.r1d()).isEqualByComparingTo("0.28");
    assertThat(split.r1w()).isEqualByComparingTo("1.41");
    assertThat(split.r1m()).isEqualByComparingTo("6.21");
    assertThat(split.r6m()).as("6m over the adjusted plane, NOT the raw pre-split cliff")
        .isEqualByComparingTo("54.08");
    assertThat(split.r1y()).as("1y over the adjusted plane, NOT the raw pre-split cliff")
        .isEqualByComparingTo("235.51");

    // Equality with the no-CA control: the split name's whole returns row is identical to the
    // continuous control, decimal-for-decimal — the cliff is gone.
    assertThat(split.ltp()).isEqualByComparingTo(ctrl.ltp());
    assertThat(split.r1d()).isEqualByComparingTo(ctrl.r1d());
    assertThat(split.r1w()).isEqualByComparingTo(ctrl.r1w());
    assertThat(split.r1m()).isEqualByComparingTo(ctrl.r1m());
    assertThat(split.r6m()).isEqualByComparingTo(ctrl.r6m());
    assertThat(split.r1y()).isEqualByComparingTo(ctrl.r1y());
  }

  @Test
  void keptRawDeliverySeriesStillReportsTheRawPreSplitClose() {
    // EquityDeliveryService is DELIBERATELY kept raw — a per-session faithful record whose % change
    // rides the exchange-published ex-adjusted prev_close. A pre-ex bar must report the RAW 2× close,
    // NOT the adjusted continuous level; a post-ex bar reads the continuous level (raw == adjusted).
    Map<LocalDate, DeliveryDay> splitByDate = new java.util.HashMap<>();
    delivery.delivery(SPLIT, 180).items().forEach(d -> splitByDate.put(d.date(), d));
    Map<LocalDate, DeliveryDay> ctrlByDate = new java.util.HashMap<>();
    delivery.delivery(CTRL, 180).items().forEach(d -> ctrlByDate.put(d.date(), d));

    int preEx = 200; // date today-59, well inside the 180-session window, PRE the ex at 235
    int postEx = 250; // date today-9, POST the ex
    DeliveryDay splitPre = splitByDate.get(dateOf(preEx));
    DeliveryDay ctrlPre = ctrlByDate.get(dateOf(preEx));
    assertThat(splitPre).as("pre-ex bar present in the delivery window").isNotNull();

    // The kept-raw fold reports the RAW cliff (2× the continuous level), NOT the adjusted plane.
    assertThat(splitPre.close())
        .as("delivery keeps the RAW pre-split close (2×), unadjusted")
        .isEqualByComparingTo("600"); // 2 * adjusted(200)=2*300
    assertThat(splitPre.close())
        .as("delivery does NOT ride the adjusted plane")
        .isNotEqualByComparingTo("300");
    // The no-CA control reads the continuous level on the same date — the split name is provably raw.
    assertThat(ctrlPre.close()).isEqualByComparingTo("300");
    // Post-ex the two planes coincide (factor 1) — sanity that the window/keying is right.
    assertThat(splitByDate.get(dateOf(postEx)).close()).isEqualByComparingTo("350"); // adjusted(250)
  }
}
