package in.arthayantra.marketdata.kite;

/**
 * Stable instrument identity (COMMON §3): {@code (exchange, tradingsymbol)} — numeric Kite tokens
 * are session-scoped wire details, never identity.
 */
public record InstrumentKey(String exchange, String tradingsymbol) {

  /** Canonical {@code EXCHANGE:TRADINGSYMBOL} form (Redis hash fields, log lines). */
  public String canonical() {
    return exchange + ":" + tradingsymbol;
  }
}
