package in.arthayantra.marketdata.futures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.candles.CandleRepository;
import in.arthayantra.marketdata.candles.RollEventsRepository;
import in.arthayantra.marketdata.instruments.Instrument;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 2b-E1 refresh guard: {@link ContinuousFuturesRoller#stitch} must refresh the CONT mid-interval
 * continuous aggregates ONLY when asked, and ONLY over the range it actually stitched. The
 * historical backfill passes {@code false} — refreshing a wide historical range re-aggregates ~106k
 * expired contracts' buckets in one lock-holding, OOM-risky call, and backtests read CONT 1m from
 * the base table anyway. The live roll passes {@code true} (narrow daily invalidation → cheap, and
 * live charts need fresh mid-interval bars).
 *
 * <p><b>Why the window itself is now asserted.</b> This class used to verify only that a refresh
 * HAPPENED ({@code any(), any()}), which is exactly the hole the 2026-08-04 defect walked through:
 * the roller passed its requested segment window, whose first segment starts at the roller's
 * {@code STITCH_EPOCH} of 2000-01-01, so every nightly roll asked for a ~26-year refresh of all
 * five caggs. It was invisible while the caggs were uncompressed and became a hard failure once
 * V049 compressed them — the roll aborted for all six index roots with {@code tuple decompression
 * limit exceeded by operation … tuples decompressed: 341820}. Verifying that a collaborator was
 * called says nothing about the argument that broke production, so these tests pin the argument.
 */
class ContinuousFuturesStitchRefreshTest {

  private final InstrumentRepository instruments = mock(InstrumentRepository.class);
  private final CandleRepository candles = mock(CandleRepository.class);
  private final RollEventsRepository rollEvents = mock(RollEventsRepository.class);
  private final ContinuousFuturesRoller roller =
      new ContinuousFuturesRoller(
          instruments, candles, rollEvents, MarketCalendar.nse(), Clock.systemUTC(), List.of(), 1);

  private static final LocalDate TODAY = LocalDate.parse("2026-06-26");

  /** The bars a normal daily roll really inserts: one session of the front contract. */
  private static final OffsetDateTime FIRST_INSERTED =
      OffsetDateTime.parse("2026-06-26T09:15:00+05:30");
  private static final OffsetDateTime LAST_INSERTED =
      OffsetDateTime.parse("2026-06-26T15:29:00+05:30");

  /** {@code ContinuousFuturesRoller.STITCH_EPOCH} — the segment start that must NEVER be refreshed. */
  private static final OffsetDateTime STITCH_EPOCH =
      OffsetDateTime.parse("2000-01-01T00:00:00+05:30");

  /** One currently-active front contract (expiry in the future → no roll, one stitched segment). */
  private static List<Instrument> oneFront() {
    return List.of(
        new Instrument(
            "NFO", "NIFTY26JULFUT", null, "NIFTY", "NFO-FUT", "FUT", "NSE", "NIFTY 50",
            TODAY.plusDays(10), null, null, null, true));
  }

  private void stitchReturns(CandleRepository.StitchedRange range) {
    when(candles.stitchInto(anyString(), anyString(), anyString(), any(), any())).thenReturn(range);
  }

  @Test
  void backfillModeNeverRefreshesAggregates() {
    stitchReturns(new CandleRepository.StitchedRange(5, FIRST_INSERTED, LAST_INSERTED));

    roller.stitch(oneFront(), "NIFTY 50", TODAY, false);

    verify(candles).stitchInto(anyString(), anyString(), anyString(), any(), any());
    verify(candles, never()).refreshDerivedAggregates(any(), any());
  }

  @Test
  void liveModeRefreshesAggregatesForTheStitchedSegment() {
    stitchReturns(new CandleRepository.StitchedRange(5, FIRST_INSERTED, LAST_INSERTED));

    roller.stitch(oneFront(), "NIFTY 50", TODAY, true);

    verify(candles, times(1)).refreshDerivedAggregates(any(OffsetDateTime.class), any());
  }

  // ---- the argument, not just the call: the 2026-08-04 all-roots roll failure lived here

  @Test
  void liveModeRefreshesTheInsertedRangeAndNotTheRequestedSegmentWindow() {
    stitchReturns(new CandleRepository.StitchedRange(5, FIRST_INSERTED, LAST_INSERTED));

    roller.stitch(oneFront(), "NIFTY 50", TODAY, true);

    ArgumentCaptor<OffsetDateTime> from = ArgumentCaptor.forClass(OffsetDateTime.class);
    ArgumentCaptor<OffsetDateTime> to = ArgumentCaptor.forClass(OffsetDateTime.class);
    verify(candles).refreshDerivedAggregates(from.capture(), to.capture());

    assertThat(from.getValue())
        .as(
            "refresh must start at the first INSERTED bucket; STITCH_EPOCH here is a ~26-year"
                + " refresh over compressed caggs — the 2026-08-04 roll failure")
        .isEqualTo(FIRST_INSERTED)
        .isNotEqualTo(STITCH_EPOCH);
    assertThat(to.getValue()).as("and end at the last INSERTED bucket").isEqualTo(LAST_INSERTED);
  }

  @Test
  void theStitchWindowStillReachesBackToTheEpochEvenThoughTheRefreshDoesNot() {
    // the fix narrows the REFRESH only. Narrowing the INSERT too would silently stop reconstructing
    // CONT history, so the two windows are deliberately different — pin that they stay different.
    stitchReturns(new CandleRepository.StitchedRange(5, FIRST_INSERTED, LAST_INSERTED));

    roller.stitch(oneFront(), "NIFTY 50", TODAY, true);

    ArgumentCaptor<OffsetDateTime> stitchFrom = ArgumentCaptor.forClass(OffsetDateTime.class);
    verify(candles).stitchInto(anyString(), anyString(), anyString(), stitchFrom.capture(), any());

    assertThat(stitchFrom.getValue())
        .as("the INSERT still sweeps the contract's whole nominal segment from STITCH_EPOCH")
        .isEqualTo(STITCH_EPOCH);
  }

  @Test
  void anEmptyStitchRefreshesNothing() {
    // StitchedRange.NONE carries NULL bucket bounds — refreshing on it would NPE (or worse, refresh
    // an unbounded window), and there is nothing to publish anyway.
    stitchReturns(new CandleRepository.StitchedRange(0, null, null));

    roller.stitch(oneFront(), "NIFTY 50", TODAY, true);

    verify(candles, never()).refreshDerivedAggregates(any(), any());
  }
}
