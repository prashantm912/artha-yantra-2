package in.arthayantra.backtest.regime;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The resolved benchmark instrument for regime attribution (guard 6, §D.4): {@code
 * config.backtest.defaults.benchmark} (a {@code "EXCHANGE:tradingsymbol"} string, default {@code
 * "NSE:NIFTY 50"}) split into its {@code exchange} + {@code tradingsymbol} parts. The benchmark is
 * read from {@code marketdata} read-only at the daily ({@code 1d}) interval.
 *
 * @param exchange the benchmark exchange (e.g. {@code NSE})
 * @param tradingsymbol the benchmark trading symbol (e.g. {@code NIFTY 50})
 * @param raw the original {@code "EXCHANGE:tradingsymbol"} string (for diagnostics / coverage errors)
 */
public record BenchmarkSeries(String exchange, String tradingsymbol, String raw) {

  /** The §D.4 default benchmark when the config omits {@code backtest.defaults.benchmark}. */
  public static final String DEFAULT = "NSE:NIFTY 50";

  /**
   * Resolves the benchmark from the resolved config JSONB (falling back to {@link #DEFAULT}). The
   * benchmark string is {@code EXCHANGE:tradingsymbol}; the colon splits on the FIRST occurrence so
   * a symbol containing a colon survives.
   *
   * @param config the resolved strategy config (may be {@code null})
   * @return the resolved benchmark, never {@code null}
   */
  public static BenchmarkSeries resolve(JsonNode config) {
    String raw = DEFAULT;
    if (config != null) {
      String configured =
          config.path("backtest").path("defaults").path("benchmark").asText(null);
      if (configured != null && !configured.isBlank()) {
        raw = configured;
      }
    }
    int colon = raw.indexOf(':');
    if (colon <= 0 || colon == raw.length() - 1) {
      throw new IllegalArgumentException(
          "benchmark must be EXCHANGE:tradingsymbol, got: " + raw);
    }
    return new BenchmarkSeries(raw.substring(0, colon), raw.substring(colon + 1), raw);
  }
}
