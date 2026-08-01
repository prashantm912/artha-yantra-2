package in.arthayantra.marketdata.upstox;

import in.arthayantra.marketdata.kite.InstrumentKey;
import java.util.Map;

/**
 * Upstox edge mapper for GLOBAL indices — the domain {@link InstrumentKey} (canonical, Kite-grammar
 * {@code (exchange, tradingsymbol)}) → the Upstox {@code instrument_key} the world-indices quote call
 * needs. The same per-source-edge-mapper shape as {@code UpstoxQuoteInstrumentKeys} (indices via a
 * verified static map): the canonical key is NEVER rewritten, only translated at the wire edge.
 *
 * <p>The domain side is the pre-existing {@code GLOBAL_INDEX@DOWJONES} key the Connecting-Dots Dow
 * factor and {@code GET /api/v1/market/global/dow} already use — {@code DOWJONES} is OUR canonical
 * symbol, not an Upstox one. Upstox lists the Dow as {@code GLOBAL_INDEX|^DJI} (trading symbol
 * {@code ^DJI}, name {@code DOW JONES}), verified live against
 * {@code GET /api/v1/market/world-indices} on 2026-08-01.
 *
 * <p>Anything unmapped returns {@code null}; the caller then degrades to an empty quote (and counts
 * the degradation) rather than sending Upstox a key it would reject for the whole batch.
 */
final class UpstoxGlobalInstrumentKeys {

  /** The GLOBAL exchange in canonical (domain) form — the only exchange this mapper answers for. */
  private static final String GLOBAL = "GLOBAL_INDEX";

  /** Canonical global tradingsymbol → the Upstox {@code instrument_key} (the {@code |} request form). */
  private static final Map<String, String> KEYS = Map.of("DOWJONES", "GLOBAL_INDEX|^DJI");

  private UpstoxGlobalInstrumentKeys() {}

  /** The Upstox {@code instrument_key} for a canonical global index key, or {@code null} if unmapped. */
  static String key(InstrumentKey instrument) {
    if (instrument == null || !GLOBAL.equals(instrument.exchange())) {
      return null;
    }
    return KEYS.get(instrument.tradingsymbol());
  }
}
