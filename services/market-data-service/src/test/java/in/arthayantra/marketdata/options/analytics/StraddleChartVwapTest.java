package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.options.analytics.StraddleChartService.StraddleCandle;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The combined-premium session VWAP that anchors the Siva #11 long-straddle SL ({@code combinedVwap −
 * 15} points, owner S24 ruling). Typical-price ((H+L+C)/3), volume-weighted, with a zero-volume
 * fallback to the simple mean of the combined closes.
 */
class StraddleChartVwapTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  private static StraddleCandle bar(int idx, String high, String low, String close, long vol) {
    BigDecimal c = new BigDecimal(close);
    return new StraddleCandle(
        OffsetDateTime.of(2026, 6, 26, 9, 15, 0, 0, IST).plusMinutes(idx * 5L),
        c, // open (unused by VWAP)
        new BigDecimal(high),
        new BigDecimal(low),
        c, // close
        c, // ceClose (unused)
        c, // peClose (unused)
        vol);
  }

  @Test
  void volumeWeightsTheTypicalPrice() {
    // typical = (H+L+C)/3 → 100 @vol10, 200 @vol20 → (100·10 + 200·20)/30 = 5000/30 = 166.6667.
    BigDecimal vwap =
        StraddleChartService.combinedVwap(
            List.of(bar(0, "110", "90", "100", 10), bar(1, "220", "180", "200", 20)));
    assertThat(vwap).isEqualByComparingTo("166.6667");
  }

  @Test
  void zeroVolumeFallsBackToTheMeanOfCombinedCloses() {
    BigDecimal vwap =
        StraddleChartService.combinedVwap(
            List.of(bar(0, "110", "90", "100", 0), bar(1, "220", "180", "200", 0)));
    assertThat(vwap).isEqualByComparingTo("150"); // mean(100, 200)
  }

  @Test
  void emptySessionIsNull() {
    assertThat(StraddleChartService.combinedVwap(List.of())).isNull();
  }
}
