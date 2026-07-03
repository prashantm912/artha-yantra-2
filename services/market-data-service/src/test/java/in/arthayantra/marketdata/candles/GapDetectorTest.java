package in.arthayantra.marketdata.candles;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketcalendar.MarketCalendar;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase-11 gap math against calendar fixtures (Flow 3). */
class GapDetectorTest {

  private final TradingBuckets buckets = new TradingBuckets(MarketCalendar.nse());
  private final GapDetector detector = new GapDetector(buckets);

  private static OffsetDateTime ist(String text) {
    return OffsetDateTime.parse(text + "+05:30");
  }

  /** A now far past the range — the recency window stays out of the way. */
  private static final OffsetDateTime LATER = OffsetDateTime.parse("2026-06-13T12:00:00+05:30");

  @Test
  void fullCoverageYieldsNoGaps() {
    List<OffsetDateTime> present =
        buckets.minuteBuckets(ist("2026-02-03T09:15:00"), ist("2026-02-03T10:15:00"));

    List<GapDetector.Gap> gaps =
        detector.gaps("1m", ist("2026-02-03T09:15:00"), ist("2026-02-03T10:15:00"), present, LATER);

    assertThat(gaps).isEmpty();
  }

  @Test
  void holeInTheMiddleBecomesOneContiguousGap() {
    List<OffsetDateTime> present =
        new ArrayList<>(
            buckets.minuteBuckets(ist("2026-02-03T09:15:00"), ist("2026-02-03T10:15:00")));
    present.removeIf(
        b -> !b.isBefore(ist("2026-02-03T09:30:00")) && b.isBefore(ist("2026-02-03T09:35:00")));

    List<GapDetector.Gap> gaps =
        detector.gaps("1m", ist("2026-02-03T09:15:00"), ist("2026-02-03T10:15:00"), present, LATER);

    assertThat(gaps).hasSize(1);
    assertThat(gaps.get(0).from()).isEqualTo(ist("2026-02-03T09:30:00"));
    assertThat(gaps.get(0).to()).isEqualTo(ist("2026-02-03T09:35:00"));
  }

  @Test
  void emptyCacheYieldsOneGapSpanningTheRange() {
    List<GapDetector.Gap> gaps =
        detector.gaps(
            "1m", ist("2026-02-03T09:15:00"), ist("2026-02-03T10:15:00"), List.of(), LATER);

    assertThat(gaps).hasSize(1);
    assertThat(gaps.get(0).from()).isEqualTo(ist("2026-02-03T09:15:00"));
    assertThat(gaps.get(0).to()).isEqualTo(ist("2026-02-03T10:15:00"));
  }

  @Test
  void onlyTheTrailingRecencyTailIsRefetchedWhenPresent() {
    // TREADMILL REGRESSION (2026-07-03 ledger #9): with a 2 h window, every cache-first read whose
    // `to` touched "now" re-fetched two hours of ALREADY-COVERED bars from Kite all session long.
    // The tail is now 10 min: covered CLOSED bars older than that are cache-final; only buckets
    // whose END clears now-10m re-fetch (the in-progress bucket always does — that is B-4's point).
    List<OffsetDateTime> present =
        buckets.minuteBuckets(ist("2026-02-03T09:15:00"), ist("2026-02-03T10:15:00"));

    // now is 10:30 — recencyStart 10:20; every present bucket ENDS at/before 10:15 → pure hit
    List<GapDetector.Gap> allClosed =
        detector.gaps(
            "1m",
            ist("2026-02-03T09:15:00"),
            ist("2026-02-03T10:15:00"),
            present,
            ist("2026-02-03T10:30:00"));
    assertThat(allClosed).isEmpty();

    // now is 10:16 — recencyStart 10:06; buckets 10:06..10:14 (ends 10:07..10:15) re-fetch,
    // everything before stays a cache hit
    List<GapDetector.Gap> tail =
        detector.gaps(
            "1m",
            ist("2026-02-03T09:15:00"),
            ist("2026-02-03T10:15:00"),
            present,
            ist("2026-02-03T10:16:00"));
    assertThat(tail).hasSize(1);
    assertThat(tail.get(0).from()).isEqualTo(ist("2026-02-03T10:06:00"));
    assertThat(tail.get(0).to()).isEqualTo(ist("2026-02-03T10:15:00"));
  }

  @Test
  void inProgressDayBucketAlwaysRefetches() {
    // the B-4 intent the recency rule exists for: today's 1d bucket ENDS tomorrow, so it must
    // re-fetch mid-day even when a stale row is cached — narrowing the window must not break this
    List<GapDetector.Gap> gaps =
        detector.gaps(
            "1d",
            ist("2026-02-03T00:00:00"),
            ist("2026-02-04T00:00:00"),
            List.of(ist("2026-02-03T00:00:00")),
            ist("2026-02-03T12:00:00"));

    assertThat(gaps).hasSize(1);
    assertThat(gaps.get(0).from()).isEqualTo(ist("2026-02-03T00:00:00"));
  }

  @Test
  void closedBarsOutsideRecencyAreImmutableCacheHits() {
    List<OffsetDateTime> present =
        buckets.minuteBuckets(ist("2026-02-03T09:15:00"), ist("2026-02-03T10:15:00"));

    // now is two days later — the same present set is a pure hit
    List<GapDetector.Gap> gaps =
        detector.gaps(
            "1m",
            ist("2026-02-03T09:15:00"),
            ist("2026-02-03T10:15:00"),
            present,
            ist("2026-02-05T10:30:00"));

    assertThat(gaps).isEmpty();
  }

  @Test
  void dayIntervalSplitsGapsAroundPresentDays() {
    // Thu 22, Fri 23, [Mon 26 = Republic Day], Tue 27, Wed 28; only Tue 27 cached
    List<GapDetector.Gap> gaps =
        detector.gaps(
            "1d",
            ist("2026-01-22T00:00:00"),
            ist("2026-01-29T00:00:00"),
            List.of(ist("2026-01-27T00:00:00")),
            LATER);

    assertThat(gaps).hasSize(2);
    assertThat(gaps.get(0).from()).isEqualTo(ist("2026-01-22T00:00:00"));
    assertThat(gaps.get(0).to()).isEqualTo(ist("2026-01-24T00:00:00"));
    assertThat(gaps.get(1).from()).isEqualTo(ist("2026-01-28T00:00:00"));
    assertThat(gaps.get(1).to()).isEqualTo(ist("2026-01-29T00:00:00"));
  }

  @Test
  void pagesSplitAtSixtyDays() {
    GapDetector.Gap hundredDays =
        new GapDetector.Gap(ist("2026-01-01T00:00:00"), ist("2026-04-11T00:00:00"));

    List<GapDetector.Gap> pages = GapDetector.pages(hundredDays);

    assertThat(pages).hasSize(2);
    assertThat(pages.get(0).from()).isEqualTo(ist("2026-01-01T00:00:00"));
    assertThat(pages.get(0).to()).isEqualTo(ist("2026-03-02T00:00:00"));
    assertThat(pages.get(1).from()).isEqualTo(ist("2026-03-02T00:00:00"));
    assertThat(pages.get(1).to()).isEqualTo(ist("2026-04-11T00:00:00"));
  }
}
