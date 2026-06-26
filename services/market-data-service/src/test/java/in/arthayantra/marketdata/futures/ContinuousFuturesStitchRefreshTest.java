package in.arthayantra.marketdata.futures;

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

/**
 * 2b-E1 refresh guard: {@link ContinuousFuturesRoller#stitch} must refresh the CONT mid-interval
 * continuous aggregates ONLY when asked. The historical backfill passes {@code false} — refreshing a
 * wide historical range re-aggregates ~106k expired contracts' buckets in one lock-holding,
 * OOM-risky call, and backtests read CONT 1m from the base table anyway. The live roll passes {@code
 * true} (narrow daily invalidation → cheap, and live charts need fresh mid-interval bars).
 */
class ContinuousFuturesStitchRefreshTest {

  private final InstrumentRepository instruments = mock(InstrumentRepository.class);
  private final CandleRepository candles = mock(CandleRepository.class);
  private final RollEventsRepository rollEvents = mock(RollEventsRepository.class);
  private final ContinuousFuturesRoller roller =
      new ContinuousFuturesRoller(
          instruments, candles, rollEvents, MarketCalendar.nse(), Clock.systemUTC(), List.of(), 1);

  private static final LocalDate TODAY = LocalDate.parse("2026-06-26");

  /** One currently-active front contract (expiry in the future → no roll, one stitched segment). */
  private static List<Instrument> oneFront() {
    return List.of(
        new Instrument(
            "NFO", "NIFTY26JULFUT", null, "NIFTY", "NFO-FUT", "FUT", "NSE", "NIFTY 50",
            TODAY.plusDays(10), null, null, null, true));
  }

  @Test
  void backfillModeNeverRefreshesAggregates() {
    when(candles.stitchInto(anyString(), anyString(), anyString(), any(), any())).thenReturn(5);

    roller.stitch(oneFront(), "NIFTY 50", TODAY, false);

    verify(candles).stitchInto(anyString(), anyString(), anyString(), any(), any());
    verify(candles, never()).refreshDerivedAggregates(any(), any());
  }

  @Test
  void liveModeRefreshesAggregatesForTheStitchedSegment() {
    when(candles.stitchInto(anyString(), anyString(), anyString(), any(), any())).thenReturn(5);

    roller.stitch(oneFront(), "NIFTY 50", TODAY, true);

    verify(candles, times(1)).refreshDerivedAggregates(any(OffsetDateTime.class), any());
  }
}
