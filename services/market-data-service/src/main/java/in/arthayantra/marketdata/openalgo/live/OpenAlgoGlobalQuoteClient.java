package in.arthayantra.marketdata.openalgo.live;

import in.arthayantra.marketdata.kite.GlobalQuoteSource;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import in.arthayantra.marketdata.kite.QuoteGateway.Quote;
import java.util.List;
import java.util.Optional;

/**
 * OpenAlgo adapter for the {@link GlobalQuoteSource} port (Connecting-Dots Dow factor, plan §3). Thin
 * wrapper over an {@link OpenAlgoQuoteGateway} that exposes the global feed under the backfill-style
 * dedicated port type (so it never competes in the routed {@code QuoteGateway} bean pool, which stays
 * Kite). Best-effort: any upstream failure returns empty so the Dow factor degrades to Neutral rather
 * than failing the whole matrix.
 */
public final class OpenAlgoGlobalQuoteClient implements GlobalQuoteSource {

  private final QuoteGateway delegate;

  /** Wraps an OpenAlgo {@code /quotes} gateway dedicated to the global-index path. */
  public OpenAlgoGlobalQuoteClient(QuoteGateway delegate) {
    this.delegate = delegate;
  }

  @Override
  public Optional<Quote> latest(InstrumentKey key) {
    try {
      return Optional.ofNullable(delegate.quotes(List.of(key)).get(key));
    } catch (RuntimeException unavailable) {
      return Optional.empty();
    }
  }
}
