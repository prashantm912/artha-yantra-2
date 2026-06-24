package in.arthayantra.marketdata.options;

import in.arthayantra.marketdata.constituents.StockUpstoxKeyMap;
import in.arthayantra.marketdata.kite.InstrumentKey;
import java.util.Map;

/**
 * Translates an ArthaYantra (Kite-shaped) {@link InstrumentKey} to the Upstox {@code instrument_key}
 * the Wave-U2 market-quote call needs. Two cases are mappable today:
 *
 * <ul>
 *   <li><b>Index underlyings</b> — carried on exchange {@code NSE}/{@code BSE} with the Kite display
 *       name (e.g. {@code "NIFTY 50"}); remapped to {@code NSE_INDEX|Nifty 50} (the same verified map
 *       as {@link UpstoxOptionChainQuoteSource} / {@code UpstoxOptionAnalyticsSource}).</li>
 *   <li><b>Cash equities</b> — looked up in the existing {@link StockUpstoxKeyMap} reference
 *       ({@code NSE_EQ|<ISIN>}, ~500 symbols, seeded from the NSE factsheets) rather than duplicating
 *       it.</li>
 * </ul>
 *
 *   <li><b>FUT / options</b> — when an {@link UpstoxFnoInstrumentKeys} resolver is supplied (the
 *       Wave-U4 enabler), the F&amp;O leg is resolved to its Upstox {@code NSE_FO|<token>} /
 *       {@code BSE_FO|<token>} key via the Upstox instrument master. Without the resolver (the
 *       pre-U4 behavior) F&amp;O still falls through to {@code null}.</li>
 * </ul>
 *
 * <p>Anything still unresolved returns {@code null}; the {@code QuoteGateway} adapter then simply OMITS
 * that key from the batch, so the Kite path stays the source for the unmapped instrument.
 */
final class UpstoxQuoteInstrumentKeys {

  /** Kite display-name index underlyings → the Upstox {@code instrument_key}. */
  private static final Map<String, String> INDEX_KEYS =
      Map.of(
          "NIFTY 50", "NSE_INDEX|Nifty 50",
          "NIFTY BANK", "NSE_INDEX|Nifty Bank",
          "NIFTY FIN SERVICE", "NSE_INDEX|Nifty Fin Service",
          "NIFTY MID SELECT", "NSE_INDEX|NIFTY MID SELECT",
          "SENSEX", "BSE_INDEX|SENSEX",
          "BANKEX", "BSE_INDEX|BANKEX");

  private final StockUpstoxKeyMap stockKeys;
  private final UpstoxFnoInstrumentKeys fnoKeys;

  /** Index + equity only (the pre-U4 shape; F&amp;O stays unmapped). */
  UpstoxQuoteInstrumentKeys(StockUpstoxKeyMap stockKeys) {
    this(stockKeys, null);
  }

  /** Index + equity, plus FUT/option coverage via the {@code fnoKeys} resolver when non-null. */
  UpstoxQuoteInstrumentKeys(StockUpstoxKeyMap stockKeys, UpstoxFnoInstrumentKeys fnoKeys) {
    this.stockKeys = stockKeys;
    this.fnoKeys = fnoKeys;
  }

  /**
   * The Upstox {@code instrument_key} for a domain {@link InstrumentKey}: an index by display name, an
   * NSE cash equity via the stock reference, a FUT/option via the F&amp;O resolver (when supplied),
   * else {@code null} (unmappable — left to the Kite path).
   */
  String key(InstrumentKey instrument) {
    String index = INDEX_KEYS.get(instrument.tradingsymbol());
    if (index != null) {
      return index;
    }
    if ("NSE".equals(instrument.exchange())) {
      String equity = stockKeys.key(instrument.tradingsymbol());
      if (equity != null) {
        return equity;
      }
    }
    return fnoKeys == null ? null : fnoKeys.key(instrument);
  }
}
