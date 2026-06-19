package in.arthayantra.marketdata.openalgo.live;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Locale;

/**
 * OpenAlgo option-symbol grammar helpers (plan §4.6 / §17.4). The {@code /optionchain} and
 * {@code /expiry} requests carry the expiry as OpenAlgo's {@code DDMMMYY} token (e.g.
 * {@code 30DEC26}), and the canary / parity probes parse it back. Spot {@code (exchange, symbol)}
 * remapping lives in {@link OpenAlgoExchange}; this class owns ONLY the expiry/date grammar so the
 * two concerns stay small and separately testable.
 *
 * <p>The 2-digit year pivots on base 2000 ({@code 26} -&gt; 2026, {@code 99} -&gt; 2099) — adequate
 * for listed F&amp;O expiries; parsing is case-insensitive so {@code 30dec26} resolves too.
 */
public final class OpenAlgoSymbols {

  private static final DateTimeFormatter DDMMMYY =
      new DateTimeFormatterBuilder()
          .parseCaseInsensitive()
          .appendPattern("ddMMM")
          .appendValueReduced(ChronoField.YEAR, 2, 2, 2000)
          .toFormatter(Locale.ENGLISH);

  private OpenAlgoSymbols() {}

  /** A {@link LocalDate} -&gt; OpenAlgo's uppercase {@code DDMMMYY} expiry token (e.g. {@code 30DEC26}). */
  public static String expiryToken(LocalDate expiry) {
    return DDMMMYY.format(expiry).toUpperCase(Locale.ENGLISH);
  }

  /** Parses an OpenAlgo {@code DDMMMYY} expiry token back to a {@link LocalDate} (case-insensitive). */
  public static LocalDate parseExpiry(String token) {
    return LocalDate.parse(token, DDMMMYY);
  }
}
