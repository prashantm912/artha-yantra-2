package in.arthayantra.marketdata.candles;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * T19 (2026-07-25): {@code backfillRange} is the ONE coverage entry that bypasses
 * {@link GapDetector}'s aligned bucket grid — {@code LiveTickerFeed} hands it the raw tick-gap
 * instants, and a sub-minute {@code from} persisted phantom 1m rows keyed off the grid
 * ({@code 12:51:38}, …) that every {@code time_bucket} rollup double-counted. The window must snap
 * to the grid before any fetch.
 */
class CandleQueryServiceTest {

  @Test
  void rawTickGapInstantsSnapToTheMinuteGrid() {
    // the 2026-07-22 outage shape: last tick 12:51:38, recovery 13:05:41
    GapDetector.Gap window =
        CandleQueryService.alignBackfillWindow(
            OffsetDateTime.parse("2026-07-22T12:51:38+05:30"),
            OffsetDateTime.parse("2026-07-22T13:05:41+05:30"));

    assertThat(window.from()).isEqualTo(OffsetDateTime.parse("2026-07-22T12:51:00+05:30"));
    assertThat(window.to()).as("ceil keeps the partial recovery minute covered")
        .isEqualTo(OffsetDateTime.parse("2026-07-22T13:06:00+05:30"));
  }

  @Test
  void alreadyAlignedBoundsPassThroughUnchanged() {
    GapDetector.Gap window =
        CandleQueryService.alignBackfillWindow(
            OffsetDateTime.parse("2026-07-22T12:51:00+05:30"),
            OffsetDateTime.parse("2026-07-22T13:06:00+05:30"));

    assertThat(window.from()).isEqualTo(OffsetDateTime.parse("2026-07-22T12:51:00+05:30"));
    assertThat(window.to()).isEqualTo(OffsetDateTime.parse("2026-07-22T13:06:00+05:30"));
  }
}
