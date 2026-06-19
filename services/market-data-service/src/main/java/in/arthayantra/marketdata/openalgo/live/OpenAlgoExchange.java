package in.arthayantra.marketdata.openalgo.live;

import in.arthayantra.marketdata.kite.InstrumentKey;
import java.util.Map;

/**
 * Translates an ArthaYantra (Kite-shaped) {@link InstrumentKey} to OpenAlgo's {@code (exchange,
 * symbol)} request pair. The only non-trivial case today is index underlyings: ArthaYantra carries
 * them on exchange {@code NSE}/{@code BSE} with the Kite display name (e.g. {@code "NIFTY 50"}),
 * whereas OpenAlgo expects the {@code NSE_INDEX}/{@code BSE_INDEX} exchange and its own short symbol
 * ({@code "NIFTY"}/{@code "BANKNIFTY"}/...).
 *
 * <p>VERIFY (Phase 1, plan §4.6 / Risk R2): OpenAlgo's F&O symbol FORMAT differs from Kite's (e.g.
 * Kite {@code NIFTY2661624000CE} vs OpenAlgo {@code NIFTY16JUN2624000CE}); cash symbols pass through.
 * The routing migration (§4) reconciles the option/future symbol grammar against the live appliance.
 * Phase 0 proves the wire contract under WireMock (where the symbol is controlled), so pass-through
 * is correct here; do not over-build the grammar before the live cutover needs it.
 */
public final class OpenAlgoExchange {

  /** OpenAlgo's request pair. */
  public record Target(String exchange, String symbol) {}

  /** Kite display-name index underlyings -> OpenAlgo {@code (exchange, symbol)}. */
  private static final Map<String, Target> INDEX =
      Map.of(
          "NIFTY 50", new Target("NSE_INDEX", "NIFTY"),
          "NIFTY BANK", new Target("NSE_INDEX", "BANKNIFTY"),
          "NIFTY FIN SERVICE", new Target("NSE_INDEX", "FINNIFTY"),
          "NIFTY MID SELECT", new Target("NSE_INDEX", "MIDCPNIFTY"),
          "SENSEX", new Target("BSE_INDEX", "SENSEX"),
          "BANKEX", new Target("BSE_INDEX", "BANKEX"));

  /** Maps a domain key to OpenAlgo's {@code (exchange, symbol)}; indices remapped, others verbatim. */
  public static Target map(InstrumentKey key) {
    Target index = INDEX.get(key.tradingsymbol());
    if (index != null) {
      return index;
    }
    return new Target(key.exchange(), key.tradingsymbol());
  }

  private OpenAlgoExchange() {}
}
