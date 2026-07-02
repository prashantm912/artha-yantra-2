package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.options.OiInterpretation;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Audit §9.2-5: the chronological Big-OI session event log fold. */
class BigOiLogServiceTest {

  private final BigOiLogService service = new BigOiLogService();

  private static OptionsSnapshotReader.StrikePoint point(
      String bucketIst, String strike, String type, String ltp, Long oi, String spot) {
    return new OptionsSnapshotReader.StrikePoint(
        OffsetDateTime.parse(bucketIst + "+05:30"),
        new BigDecimal(strike),
        type,
        ltp == null ? null : new BigDecimal(ltp),
        oi,
        null,
        null,
        spot == null ? null : new BigDecimal(spot),
        null);
  }

  @Test
  void emptySeriesYieldsAnEmptyLog() {
    BigOiLogService.BigOiLog log = service.fold(List.of(), 4);
    assertThat(log.items()).isEmpty();
    assertThat(log.asOf()).isNull();
  }

  @Test
  void logsPerBucketDeltasNewestFirstRankedByAbsOiChange() {
    List<OptionsSnapshotReader.StrikePoint> series =
        List.of(
            // bucket 1 (09:15) — the baseline, never produces events
            point("2026-07-01T09:15:00", "24100", "CE", "100.00", 1_000L, "24100"),
            point("2026-07-01T09:15:00", "24100", "PE", "90.00", 2_000L, "24100"),
            point("2026-07-01T09:15:00", "24200", "CE", "60.00", 500L, "24100"),
            // bucket 2 (09:18) — CE 24100 +5_000 (price up -> LONG_BUILDUP), PE 24100 -300
            point("2026-07-01T09:18:00", "24100", "CE", "110.00", 6_000L, "24120"),
            point("2026-07-01T09:18:00", "24100", "PE", "85.00", 1_700L, "24120"),
            point("2026-07-01T09:18:00", "24200", "CE", "60.00", 500L, "24120"),
            // bucket 3 (09:21) — only PE 24100 moves (-700); CE 24100 unchanged (dropped)
            point("2026-07-01T09:21:00", "24100", "CE", "110.00", 6_000L, "24140"),
            point("2026-07-01T09:21:00", "24100", "PE", "80.00", 1_000L, "24140"));

    BigOiLogService.BigOiLog log = service.fold(series, 4);

    assertThat(log.asOf()).isEqualTo(OffsetDateTime.parse("2026-07-01T09:21:00+05:30"));
    // newest bucket's events first; zero-ΔOI legs and the baseline bucket never appear
    assertThat(log.items()).hasSize(3);
    BigOiLogService.LogEvent first = log.items().get(0);
    assertThat(first.time()).isEqualTo("09:21");
    assertThat(first.optionType()).isEqualTo("PE");
    assertThat(first.oiChange()).isEqualTo(-700);
    assertThat(first.spot()).isEqualByComparingTo("24140");
    // within 09:18, the +5000 CE outranks the -300 PE
    assertThat(log.items().get(1).oiChange()).isEqualTo(5_000);
    assertThat(log.items().get(1).interpretation()).isEqualTo(OiInterpretation.LONG_BUILDUP);
    assertThat(log.items().get(1).ltpChange()).isEqualByComparingTo("10.00");
    assertThat(log.items().get(2).oiChange()).isEqualTo(-300);
  }

  @Test
  void topPerBucketCapsEachBucketNotTheWholeLog() {
    List<OptionsSnapshotReader.StrikePoint> series =
        List.of(
            point("2026-07-01T09:15:00", "24100", "CE", "100.00", 1_000L, null),
            point("2026-07-01T09:15:00", "24100", "PE", "90.00", 1_000L, null),
            point("2026-07-01T09:18:00", "24100", "CE", "101.00", 1_100L, null),
            point("2026-07-01T09:18:00", "24100", "PE", "89.00", 3_000L, null),
            point("2026-07-01T09:21:00", "24100", "CE", "102.00", 1_300L, null),
            point("2026-07-01T09:21:00", "24100", "PE", "88.00", 3_500L, null));

    BigOiLogService.BigOiLog log = service.fold(series, 1);

    // one (largest) event per bucket survives
    assertThat(log.items()).hasSize(2);
    assertThat(log.items().get(0).time()).isEqualTo("09:21");
    assertThat(log.items().get(0).oiChange()).isEqualTo(500); // PE 3000->3500
    assertThat(log.items().get(1).time()).isEqualTo("09:18");
    assertThat(log.items().get(1).oiChange()).isEqualTo(2_000);
  }

  @Test
  void aCaptureGapIsBridgedByTheLastSeenReading() {
    List<OptionsSnapshotReader.StrikePoint> series =
        List.of(
            point("2026-07-01T09:15:00", "24100", "CE", "100.00", 1_000L, null),
            // 09:18 missing for this leg entirely
            point("2026-07-01T09:18:00", "24200", "CE", "60.00", 500L, null),
            point("2026-07-01T09:21:00", "24100", "CE", "104.00", 1_400L, null));

    BigOiLogService.BigOiLog log = service.fold(series, 4);

    // the 09:21 CE 24100 event diffs against its last-seen 09:15 reading
    assertThat(log.items())
        .anySatisfy(
            e -> {
              assertThat(e.time()).isEqualTo("09:21");
              assertThat(e.oiChange()).isEqualTo(400);
              assertThat(e.ltpChange()).isEqualByComparingTo("4.00");
            });
  }
}
