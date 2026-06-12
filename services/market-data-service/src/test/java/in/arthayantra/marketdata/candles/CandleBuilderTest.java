package in.arthayantra.marketdata.candles;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.feed.NormalizedTick;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Phase-10 builder math: B-6 merge rules, cumulative-delta volume, dup/out-of-order, OI. */
class CandleBuilderTest {

  private final SessionBucketer bucketer = new SessionBucketer(MarketCalendar.nse());
  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-06-10T04:45:00Z")); // 10:15 IST

  private final Clock clock =
      new Clock() {
        @Override
        public ZoneOffset getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return now.get();
        }
      };

  private static NormalizedTick tick(String time, String price, long cumVol, Long oi, long seq) {
    return new NormalizedTick(
        "NSE", "TEST", new BigDecimal(price), cumVol, oi, OffsetDateTime.parse(time + "+05:30"), seq);
  }

  private record Run(List<Candle> bars, CandleBuilder builder) {}

  private Run run() {
    List<Candle> bars = new ArrayList<>();
    CandleBuilder builder = new CandleBuilder(bucketer, bars::add, clock, "MOCK");
    return new Run(bars, builder);
  }

  @Test
  void postCloseTicksNeverReopenTheFlushedSessionCloseBar() {
    // AUDIT REGRESSION (B-6/B-7): every after-close print clamps into the 15:29 bucket;
    // once the flush sweep closes that bar it must stay closed FOREVER — the mock feed
    // free-runs all evening and used to clobber the true session close once per sweep
    Run run = run();
    run.builder.onNormalizedTick(tick("2026-06-10T15:29:10", "100.00", 1000, null, 1));
    run.builder.onNormalizedTick(tick("2026-06-10T15:29:50", "101.00", 1500, null, 2));
    now.set(Instant.parse("2026-06-10T10:00:06Z")); // 15:30:06 IST — past grace
    run.builder.flush();
    assertThat(run.bars).hasSize(1);
    Candle close = run.bars.get(0);
    assertThat(close.close()).isEqualByComparingTo("101.00");
    assertThat(close.volume()).isEqualTo(500);

    // evening prints (clamped to 15:29) and further sweeps must produce NOTHING new
    run.builder.onNormalizedTick(tick("2026-06-10T18:00:00", "55.00", 9_000, null, 3));
    run.builder.onNormalizedTick(tick("2026-06-10T21:00:00", "260.00", 12_000, null, 4));
    now.set(Instant.parse("2026-06-10T16:30:00Z"));
    run.builder.flush();
    assertThat(run.bars).as("the session close bar is immutable").hasSize(1);
    assertThat(run.bars.get(0).close()).isEqualByComparingTo("101.00");
  }

  @Test
  void singleMinuteBarOhlcAndVolumeDelta() {
    Run run = run();
    run.builder.onNormalizedTick(tick("2026-06-10T10:15:01", "100.00", 1000, null, 1));
    run.builder.onNormalizedTick(tick("2026-06-10T10:15:20", "101.50", 1300, null, 2));
    run.builder.onNormalizedTick(tick("2026-06-10T10:15:40", "99.75", 1450, null, 3));
    run.builder.onNormalizedTick(tick("2026-06-10T10:15:59", "100.25", 1600, null, 4));

    now.set(Instant.parse("2026-06-10T04:46:06Z")); // 10:16:06 IST — past grace
    run.builder.flush();

    assertThat(run.bars).hasSize(1);
    Candle bar = run.bars.get(0);
    assertThat(bar.bucket()).isEqualTo(OffsetDateTime.parse("2026-06-10T10:15:00+05:30"));
    assertThat(bar.open()).isEqualByComparingTo("100.00");
    assertThat(bar.high()).isEqualByComparingTo("101.50");
    assertThat(bar.low()).isEqualByComparingTo("99.75");
    assertThat(bar.close()).isEqualByComparingTo("100.25");
    // first-ever tick baselines at its own cumulative: 1600 - 1000
    assertThat(bar.volume()).isEqualTo(600);
    assertThat(bar.source()).isEqualTo("MOCK");
  }

  @Test
  void newerBucketClosesThePreviousBarImmediately() {
    Run run = run();
    run.builder.onNormalizedTick(tick("2026-06-10T10:15:10", "100.00", 100, null, 1));
    run.builder.onNormalizedTick(tick("2026-06-10T10:16:02", "101.00", 250, null, 2));

    assertThat(run.bars).hasSize(1);
    assertThat(run.bars.get(0).bucket()).isEqualTo(OffsetDateTime.parse("2026-06-10T10:15:00+05:30"));
    // second bar volume baseline = first bar's cumulative end (100)
    now.set(Instant.parse("2026-06-10T04:47:10Z"));
    run.builder.flush();
    assertThat(run.bars).hasSize(2);
    assertThat(run.bars.get(1).volume()).isEqualTo(150);
  }

  @Test
  void duplicateAndOutOfOrderSeqsAreDropped() {
    Run run = run();
    run.builder.onNormalizedTick(tick("2026-06-10T10:15:10", "100.00", 100, null, 5));
    run.builder.onNormalizedTick(tick("2026-06-10T10:15:20", "200.00", 300, null, 5)); // dup seq
    run.builder.onNormalizedTick(tick("2026-06-10T10:15:30", "300.00", 500, null, 4)); // older seq

    now.set(Instant.parse("2026-06-10T04:46:06Z"));
    run.builder.flush();

    assertThat(run.bars).hasSize(1);
    assertThat(run.bars.get(0).high()).isEqualByComparingTo("100.00");
    assertThat(run.bars.get(0).volume()).isZero();
  }

  @Test
  void cumulativeVolumeResetRollsBaselineToZero() {
    Run run = run();
    run.builder.onNormalizedTick(tick("2026-06-10T15:29:10", "100.00", 50_000, null, 1));
    // next session day: cumulative reset by the exchange
    run.builder.onNormalizedTick(tick("2026-06-11T09:15:05", "102.00", 400, null, 2));

    now.set(Instant.parse("2026-06-11T03:46:10Z")); // 09:16:10 IST next day
    run.builder.flush();

    assertThat(run.bars).hasSize(2);
    assertThat(run.bars.get(1).volume()).as("reset detected — baseline restarts at 0").isEqualTo(400);
  }

  @Test
  void openInterestIsCarriedForFnoTicks() {
    Run run = run();
    run.builder.onNormalizedTick(tick("2026-06-10T10:15:10", "100.00", 100, 5_000L, 1));
    run.builder.onNormalizedTick(tick("2026-06-10T10:15:40", "101.00", 200, 5_500L, 2));

    now.set(Instant.parse("2026-06-10T04:46:06Z"));
    run.builder.flush();

    assertThat(run.bars.get(0).oi()).isEqualTo(5_500L);
  }

  @Test
  void identicalTickStreamsProduceIdenticalBars() {
    List<NormalizedTick> ticks =
        List.of(
            tick("2026-06-10T10:15:01", "100.00", 1000, 10L, 1),
            tick("2026-06-10T10:15:31", "101.00", 1200, 12L, 2),
            tick("2026-06-10T10:16:01", "102.00", 1500, 14L, 3),
            tick("2026-06-10T10:17:01", "103.00", 1900, 16L, 4));
    Run first = run();
    Run second = run();
    ticks.forEach(first.builder::onNormalizedTick);
    ticks.forEach(second.builder::onNormalizedTick);
    now.set(Instant.parse("2026-06-10T04:48:10Z"));
    first.builder.flush();
    second.builder.flush();

    assertThat(first.bars).isEqualTo(second.bars);
    assertThat(first.bars).hasSize(3);
  }
}
