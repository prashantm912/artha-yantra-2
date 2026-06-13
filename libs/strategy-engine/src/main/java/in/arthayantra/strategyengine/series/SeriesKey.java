package in.arthayantra.strategyengine.series;

import java.util.Locale;

/**
 * Multi-timeframe series key {@code (instrument, interval)} — also the shared context-series
 * cache key (§C-2.3): N strategies referencing NIFTY 1d cost one series, not N.
 */
public record SeriesKey(String exchange, String tradingsymbol, String interval) {

  /** Canonical display form {@code EXCHANGE:SYMBOL@interval}. */
  public String canonical() {
    return exchange.toUpperCase(Locale.ROOT) + ":" + tradingsymbol + "@" + interval;
  }
}
