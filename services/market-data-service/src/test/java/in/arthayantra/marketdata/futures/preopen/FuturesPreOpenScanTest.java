package in.arthayantra.marketdata.futures.preopen;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.futures.preopen.FuturesPreOpenScan.PreOpenRow;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class FuturesPreOpenScanTest {

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }

  @Test
  void rowDerivesPrevCloseFromPriceMinusChange() {
    PreOpenRow r = FuturesPreOpenScan.row("HDFCBANK", bd("1816"), bd("2.00"), null, null);
    assertThat(r.prevClose()).isEqualByComparingTo("1814.00");
    assertThat(r.change()).isEqualByComparingTo("2.00");
  }

  @Test
  void changePctIsNetChangeOverPrevClose() {
    // 2 / 1814 * 100 = 0.11 (2dp HALF_UP)
    PreOpenRow r = FuturesPreOpenScan.row("HDFCBANK", bd("1816"), bd("2.00"), null, null);
    assertThat(r.changePct()).isEqualByComparingTo("0.11");
  }

  @Test
  void changePctNullWhenPriceless() {
    PreOpenRow r = FuturesPreOpenScan.row("SBIN", null, null, bd("900"), bd("880"));
    assertThat(r.prevClose()).isNull();
    assertThat(r.changePct()).isNull();
    assertThat(r.change()).isNull();
  }

  @Test
  void changePctNullWhenPrevCloseZero() {
    assertThat(FuturesPreOpenScan.changePct(bd("5"), BigDecimal.ZERO)).isNull();
  }

  @Test
  void highBreakWhenPreOpenAbovePrevHigh() {
    PreOpenRow r = FuturesPreOpenScan.row("RELIANCE", bd("2510"), bd("15"), bd("2500"), bd("2450"));
    assertThat(r.prevDayBreak()).isEqualTo("H");
  }

  @Test
  void lowBreakWhenPreOpenBelowPrevLow() {
    PreOpenRow r = FuturesPreOpenScan.row("TATAMOTORS", bd("940"), bd("-12"), bd("980"), bd("950"));
    assertThat(r.prevDayBreak()).isEqualTo("L");
  }

  @Test
  void noBreakInsidePrevRange() {
    PreOpenRow r = FuturesPreOpenScan.row("INFY", bd("1450"), bd("3"), bd("1480"), bd("1420"));
    assertThat(r.prevDayBreak()).isNull();
  }

  @Test
  void noBreakWhenBoundsMissing() {
    PreOpenRow r = FuturesPreOpenScan.row("ITC", bd("450"), bd("1"), null, null);
    assertThat(r.prevDayBreak()).isNull();
  }

  @Test
  void countsSplitBySignWithUnchangedAndPriceless() {
    List<PreOpenRow> rows =
        List.of(
            FuturesPreOpenScan.row("A", bd("100"), bd("2"), null, null), // advance
            FuturesPreOpenScan.row("B", bd("100"), bd("-2"), null, null), // decline
            FuturesPreOpenScan.row("C", bd("100"), bd("0"), null, null), // unchanged (zero)
            FuturesPreOpenScan.row("D", null, null, null, null)); // priceless -> unchanged
    int[] c = FuturesPreOpenScan.counts(rows);
    assertThat(c).containsExactly(1, 1, 2);
  }
}
