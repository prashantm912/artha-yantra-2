package in.arthayantra.marketdata.openalgo.live;

import in.arthayantra.marketdata.kite.GlobalQuoteSource;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import in.arthayantra.marketdata.kite.QuoteGateway.Quote;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAlgo adapter for the {@link GlobalQuoteSource} port (Connecting-Dots Dow factor, plan §3). Thin
 * wrapper over an {@link OpenAlgoQuoteGateway} that exposes the global feed under the backfill-style
 * dedicated port type (so it never competes in the routed {@code QuoteGateway} bean pool, which stays
 * Kite). Best-effort: any upstream failure returns empty so the Dow factor degrades to Neutral rather
 * than failing the whole matrix.
 *
 * <p>Requires an <b>Upstox-backed</b> OpenAlgo appliance — Indian brokers serve no US indices, so on a
 * Zerodha-backed appliance this path degrades on every call. That degradation used to be SILENT (a
 * bare {@code catch → Optional.empty()}), which is how a permanently-Neutral Dow dot went unnoticed
 * for months; it now logs WARN and counts {@link GlobalQuoteSource#DEGRADED_METRIC}. The fail-soft
 * BEHAVIOUR is unchanged — degrading to Neutral is correct, being quiet about it was not.
 */
public final class OpenAlgoGlobalQuoteClient implements GlobalQuoteSource {

  private static final Logger log = LoggerFactory.getLogger(OpenAlgoGlobalQuoteClient.class);

  private final QuoteGateway delegate;
  private final MeterRegistry meterRegistry;

  /** Wraps an OpenAlgo {@code /quotes} gateway dedicated to the global-index path. */
  public OpenAlgoGlobalQuoteClient(QuoteGateway delegate, MeterRegistry meterRegistry) {
    this.delegate = delegate;
    this.meterRegistry = meterRegistry;
  }

  @Override
  public Optional<Quote> latest(InstrumentKey key) {
    Quote quote;
    try {
      quote = delegate.quotes(List.of(key)).get(key);
    } catch (RuntimeException unavailable) {
      return degraded(key, "error", unavailable);
    }
    if (quote == null) {
      return degraded(key, "absent", null);
    }
    // OpenAlgoMappers.toQuote maps a MISSING ltp to BigDecimal.ZERO. Passing that through would not
    // read as "no data" downstream — zero against a real prev close manufactures a violently BEARISH
    // Dow, a fabricated input to a live scoring dot. Reject it here, loudly.
    if (quote.lastPrice() == null || quote.lastPrice().signum() <= 0) {
      return degraded(key, "no-ltp", null);
    }
    if (quote.ohlc() == null || quote.ohlc().close() == null) {
      return degraded(key, "no-prev-close", null);
    }
    return Optional.of(quote);
  }

  /** Counts + logs one degradation-to-Neutral, then returns the fail-soft empty. */
  private Optional<Quote> degraded(InstrumentKey key, String reason, RuntimeException cause) {
    meterRegistry.counter(DEGRADED_METRIC, "source", "openalgo", "reason", reason).increment();
    log.warn(
        "global quote degraded to neutral: key={} source=openalgo reason={}{}",
        key == null ? "null" : key.canonical(),
        reason,
        cause == null ? "" : " cause=" + cause);
    return Optional.empty();
  }
}
