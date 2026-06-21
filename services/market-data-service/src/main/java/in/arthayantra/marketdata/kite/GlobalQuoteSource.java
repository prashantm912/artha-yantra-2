package in.arthayantra.marketdata.kite;

import in.arthayantra.marketdata.kite.QuoteGateway.Quote;
import java.util.Optional;

/**
 * Dedicated latest-quote fetch for GLOBAL indices (e.g. Dow Jones) used by the Connecting-Dots Dow
 * factor (data-foundation milestone, plan §3). A SEPARATE port from the routed {@link QuoteGateway}
 * on purpose: globals are LTP-only and only OpenAlgo (Upstox-backed) serves them, while the routed
 * {@code quotes} capability stays on Kite (which has no global indices). Its own type also keeps the
 * global client out of the {@code QuoteGateway} bean pool.
 *
 * <p>The single live implementation (OpenAlgo) is wired only when {@code
 * artha.openalgo.global-quotes-enabled=true} on the live profile; absent otherwise (the Dow factor
 * then stays Neutral, the faithful intraday default).
 */
public interface GlobalQuoteSource {

  /** Latest quote (LTP + prev close in the OHLC close slot) for {@code key}; empty if unavailable. */
  Optional<Quote> latest(InstrumentKey key);
}
