package in.arthayantra.marketdata.options;

import in.arthayantra.marketdata.instruments.Instrument;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.upstox.UpstoxFnoMasterClient;

/**
 * Resolves an ArthaYantra (Kite-shaped) FUT/option {@link InstrumentKey} to the Upstox
 * {@code NSE_FO|<token>} / {@code BSE_FO|<token>} {@code instrument_key} — the F&amp;O coverage the
 * Wave-U2 quote map and Wave-U3 ticker bridge previously SKIPPED (an exchange-traded F&amp;O symbol's
 * Upstox key is a numeric token not derivable from the tradingsymbol).
 *
 * <p>The Kite F&amp;O grammar ({@code NIFTY26JUNFUT}, {@code NIFTY2661618000CE}) differs from Upstox's
 * formatted {@code trading_symbol} ({@code "NIFTY FUT 28 JUL 26"}), and the weekly Kite encoding
 * cannot be parsed back unambiguously — so this re-resolves the leg to its STRUCTURED fields via
 * {@link InstrumentRepository#findByKey} (the same approach as {@code OpenAlgoHistoryClient}) and joins
 * the Upstox master on {@code (exchange, underlying, type, expiry, strike)} rather than any broker's
 * symbol string. A non-F&amp;O key, an unknown leg, or a contract the master doesn't hold returns
 * {@code null}; the caller then leaves it to the Kite path (purely ADDITIVE coverage).
 */
final class UpstoxFnoInstrumentKeys {

  private final InstrumentRepository instruments;
  private final UpstoxFnoMasterClient master;

  UpstoxFnoInstrumentKeys(InstrumentRepository instruments, UpstoxFnoMasterClient master) {
    this.instruments = instruments;
    this.master = master;
  }

  /**
   * The Upstox {@code instrument_key} for a FUT/option {@link InstrumentKey}, or {@code null} when the
   * leg is non-F&amp;O / unknown / not in the Upstox master.
   */
  String key(InstrumentKey instrument) {
    Instrument leg =
        instruments.findByKey(instrument.exchange(), instrument.tradingsymbol()).orElse(null);
    if (leg == null) {
      return null;
    }
    String type = leg.instrumentType();
    if (!"FUT".equals(type) && !"CE".equals(type) && !"PE".equals(type)) {
      return null; // cash / index — handled by the index+equity map, not here
    }
    return master.keyFor(leg.exchange(), leg.name(), type, leg.expiry(), leg.strike());
  }
}
