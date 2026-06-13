package in.arthayantra.strategysignal.registry;

/**
 * D8 seam: context-instrument existence resolves via market-data REST (this service holds NO
 * {@code marketdata} grant). Implementations throw {@link MarketDataUnavailableException} when
 * the upstream cannot answer — publish fails CLOSED on that (an engine must never subscribe to
 * unverifiable symbols), while {@code POST /validate} degrades to a warning.
 */
public interface MarketDataInstrumentClient {

  /** True when {@code (exchange, tradingsymbol)} resolves in the instruments master. */
  boolean exists(String exchange, String tradingsymbol);

  /** Raised when market-data cannot be reached (distinct from a clean 404). */
  class MarketDataUnavailableException extends RuntimeException {
    /** With cause detail. */
    public MarketDataUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
