package in.arthayantra.backtest.replay;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketcalendar.MarketCalendar;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The §D.6 pre-flight coverage check: expected vs present 1m buckets per {@link MarketCalendar}.
 * Fails fast with 422 {@code DATA_GAP} naming the window to backfill, rather than silently replaying
 * a holey series. (Context-instrument and benchmark warm-up coverage extend this in Phases 30/32.)
 */
@Component
public class PreflightCoverage {

  private final CandleReader reader;
  private final MarketCalendar calendar = MarketCalendar.nse();

  /** Wires the candle reader. */
  public PreflightCoverage(CandleReader reader) {
    this.reader = reader;
  }

  /** Throws 422 {@code DATA_GAP} when the primary 1m series is short over {@code [from, to)}. */
  public void check(String exchange, String tradingsymbol, OffsetDateTime from, OffsetDateTime to) {
    long expected = calendar.expectedMinuteBuckets(from.toInstant(), to.toInstant());
    long present = reader.count1mBuckets(exchange, tradingsymbol, from, to);
    if (present < expected) {
      throw new ApiException(
          422,
          ErrorCodes.DATA_GAP,
          "insufficient 1m candle coverage for " + exchange + ":" + tradingsymbol,
          Map.of(
              "exchange", exchange,
              "tradingsymbol", tradingsymbol,
              "from", from.toString(),
              "to", to.toString(),
              "expectedBars", expected,
              "presentBars", present,
              "missingBars", expected - present));
    }
  }
}
