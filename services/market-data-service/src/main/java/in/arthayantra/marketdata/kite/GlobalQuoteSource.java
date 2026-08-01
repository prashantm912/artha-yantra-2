package in.arthayantra.marketdata.kite;

import in.arthayantra.marketdata.kite.QuoteGateway.Quote;
import java.util.Optional;

/**
 * Dedicated latest-quote fetch for GLOBAL indices (e.g. Dow Jones) used by the Connecting-Dots Dow
 * factor (data-foundation milestone, plan §3). A SEPARATE port from the routed {@link QuoteGateway}
 * on purpose: globals are LTP-only and Indian brokers do not serve them, while the routed {@code
 * quotes} capability stays on Kite (which has no global indices). Its own type also keeps the global
 * client out of the {@code QuoteGateway} bean pool.
 *
 * <p>TWO live-profile implementations exist, each behind its own default-OFF flag and MUTUALLY
 * EXCLUSIVE (both on ⇒ the context fails fast at {@code GlobalQuoteSourceExclusivityGuard}; two
 * candidates would otherwise break the consumers' {@code ObjectProvider.getIfAvailable()}):
 *
 * <ul>
 *   <li>{@code artha.openalgo.global-quotes-enabled=true} — {@code OpenAlgoGlobalQuoteClient}, which
 *       needs an <b>Upstox-backed</b> OpenAlgo appliance. The appliance actually deployed is
 *       Zerodha-backed, so this path cannot serve US indices today.
 *   <li>{@code artha.upstox.global-quotes-enabled=true} — {@code UpstoxGlobalQuoteClient}, riding the
 *       already-live Upstox world-indices feed (needs {@code artha.upstox.analytics.enabled=true}).
 * </ul>
 *
 * <p>With neither flag on the bean is absent and the Dow factor stays Neutral (the historical
 * default). A degradation is never silent: implementations log WARN and count {@link
 * #DEGRADED_METRIC} so a dark Dow dot is distinguishable from a genuine Neutral.
 */
public interface GlobalQuoteSource {

  /**
   * Counter for every fail-soft degradation to "no global quote" — tagged {@code source}
   * ({@code openalgo}/{@code upstox}) and {@code reason} ({@code error}/{@code absent}/{@code
   * unmapped}). A Dow factor reading Neutral WITHOUT this counter advancing is a real Neutral.
   */
  String DEGRADED_METRIC = "ay_global_quote_degraded_total";

  /** Latest quote (LTP + prev close in the OHLC close slot) for {@code key}; empty if unavailable. */
  Optional<Quote> latest(InstrumentKey key);
}
