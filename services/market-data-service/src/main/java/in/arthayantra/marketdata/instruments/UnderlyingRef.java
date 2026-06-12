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
