package in.arthayantra.marketdata.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.kite.HistoricalCandleGateway.Candle;
import in.arthayantra.marketdata.kite.InstrumentKey;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link OiBackfillSampler}: 1m candles fold into IST-midnight-aligned N-minute OHLC+OI buckets. */
class OiBackfillSamplerTest {

  private static final InstrumentKey KEY = new InstrumentKey("NFO", "NIFTY2661624000CE");
  private static final LocalDate DAY = LocalDate.of(2026, 6, 15);

  @Test
  void foldsOneMinuteBarsIntoFiveMinuteBuckets() {
    // 09:15..09:19 → bucket 09:15; 09:20..09:21 → bucket 09:20 (pg time_bucket alignment).
    List<Candle> oneMin =
        List.of(
            bar(9, 15, "100", "101", "99", "100.5", 10, 1000L),
            bar(9, 16, "100.5", "103", "100", "102", 12, 1100L),
            bar(9, 19, "102", "102", "98", "101", 8, 1200L),
            bar(9, 20, "101", "105", "101", "104", 20, 1300L),
            bar(9, 21, "104", "104", "103", "103.5", 5, 1350L));

    List<OiBackfillSampler.Bucket> buckets = OiBackfillSampler.sample(oneMin, 5);

    assertThat(buckets).hasSize(2);

    OiBackfillSampler.Bucket first = buckets.get(0);
    assertThat(first.start()).isEqualTo(DAY.atTime(9, 15).atOffset(Ist.OFFSET));
    assertThat(first.open()).isEqualByComparingTo("100"); // first bar's open
    assertThat(first.high()).isEqualByComparingTo("103"); // extremum across the bucket
    assertThat(first.low()).isEqualByComparingTo("98");
    assertThat(first.close()).isEqualByComparingTo("101"); // last bar's close
    assertThat(first.volume()).isEqualTo(30); // summed
    assertThat(first.oi()).isEqualTo(1200L); // end-of-bucket OI

    OiBackfillSampler.Bucket second = buckets.get(1);
    assertThat(second.start()).isEqualTo(DAY.atTime(9, 20).atOffset(Ist.OFFSET));
    assertThat(second.close()).isEqualByComparingTo("103.5");
    assertThat(second.volume()).isEqualTo(25);
    assertThat(second.oi()).isEqualTo(1350L);
  }

  @Test
  void unsortedInputStillBucketsByTime() {
    List<Candle> shuffled =
        List.of(
            bar(9, 21, "104", "104", "103", "103.5", 5, 1350L),
            bar(9, 15, "100", "101", "99", "100.5", 10, 1000L),
            bar(9, 20, "101", "105", "101", "104", 20, 1300L));
    List<OiBackfillSampler.Bucket> buckets = OiBackfillSampler.sample(shuffled, 5);
    assertThat(buckets).hasSize(2);
    assertThat(buckets.get(0).start()).isEqualTo(DAY.atTime(9, 15).atOffset(Ist.OFFSET));
    assertThat(buckets.get(0).close()).isEqualByComparingTo("100.5"); // single bar in the 09:15 bucket
    assertThat(buckets.get(1).close()).isEqualByComparingTo("103.5"); // sorted within the 09:20 bucket
  }

  @Test
  void emptyInputYieldsEmpty() {
    assertThat(OiBackfillSampler.sample(List.of(), 5)).isEmpty();
  }

  private static Candle bar(
      int hh, int mm, String o, String h, String l, String c, long vol, Long oi) {
    OffsetDateTime t = DAY.atTime(hh, mm).atOffset(Ist.OFFSET);
    return new Candle(
        KEY, "1m", t, new BigDecimal(o), new BigDecimal(h), new BigDecimal(l), new BigDecimal(c),
        vol, oi);
  }
}
