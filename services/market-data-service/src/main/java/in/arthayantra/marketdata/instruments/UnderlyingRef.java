package in.arthayantra.marketdata.instruments;

import in.arthayantra.marketdata.kite.InstrumentDumpGateway.InstrumentRecord;
import java.util.Map;

/**
 * Derives the soft underlying reference for derivative rows (B-7): index derivatives map their
 * dump {@code name} (e.g. {@code NIFTY}) onto the cash index tradingsymbol ({@code NIFTY 50});
 * stock derivatives reference the cash leg by the same symbol on the parent exchange.
 */
public record UnderlyingRef(String exchange, String tradingsymbol) {

  private static final UnderlyingRef NONE = new UnderlyingRef(null, null);

  /** Kite dump index names → cash index tradingsymbols. */
  static final Map<String, String> INDEX_NAMES =
      Map.of(
          "NIFTY", "NIFTY 50",
          "BANKNIFTY", "NIFTY BANK",
          "FINNIFTY", "NIFTY FIN SERVICE",
          "MIDCPNIFTY", "NIFTY MID SELECT",
          "NIFTY 50", "NIFTY 50",
          "NIFTY BANK", "NIFTY BANK");

  /**
   * The canonical cash-index tradingsymbol for a loosely-written index name — {@code NIFTY} becomes
   * {@code NIFTY 50}. Anything not a known index alias passes through unchanged, which is correct
   * for {@code SENSEX} (already canonical) and for equity symbols.
   *
   * <p>⚠️ <b>Exposed because writing the alias into config is a REPEATING defect, not a one-off.</b>
   * {@code artha.insights.context.underlyings} carried a bare {@code NIFTY} for the whole life of
   * that feature (#1420), and {@code artha.context.options-name} defaulted to a bare {@code NIFTY}
   * for the whole life of day-context — 26 trading days of {@code market_context_days} rows with
   * every options scalar NULL, because {@code OptionsDigestService} answers "no option expiries for
   * NIFTY" and the caller fail-softs to a note. **A config value is not a canonical key, and the
   * only durable fix is to normalise at the point of USE rather than to keep correcting copies.**
   */
  public static String canonical(String indexName) {
    return indexName == null ? null : INDEX_NAMES.getOrDefault(indexName, indexName);
  }

  /** The underlying reference for a dump row; {@code (null, null)} for cash/index rows. */
  public static UnderlyingRef derive(InstrumentRecord row) {
    String type = row.instrumentType();
    boolean derivative =
        "FUT".equals(type) || "CE".equals(type) || "PE".equals(type);
    if (!derivative || row.name() == null || row.name().isBlank()) {
      return NONE;
    }
    String parentExchange =
        switch (row.exchange()) {
          case "NFO" -> "NSE";
          case "BFO" -> "BSE";
          default -> row.exchange();
        };
    String underlying = INDEX_NAMES.getOrDefault(row.name(), row.name());
    return new UnderlyingRef(parentExchange, underlying);
  }
}
