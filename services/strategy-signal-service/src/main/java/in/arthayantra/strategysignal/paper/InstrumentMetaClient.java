package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategyengine.fills.InstrumentClass;
import java.math.BigDecimal;

/**
 * Resolves the instrument class / tick / lot a paper fill needs — over market-data REST (this
 * service holds NO {@code marketdata} grant, D7/D10), exactly like the registry's context-instrument
 * check. A TRANSPORT miss (market-data unreachable, 404) still degrades to an equity proxy so a
 * paper order is never blocked on a blip. A row we DID read whose {@code lot_size} is missing on a
 * DERIVATIVE is different and reports {@code lotSize == 0} — "unknown", never a fabricated 1 — which
 * the entry paths refuse; see {@code RestInstrumentMetaClient#lotOf}.
 */
public interface InstrumentMetaClient {

  /** Class + tick + lot for a stable key. */
  record InstrumentMeta(InstrumentClass instrumentClass, BigDecimal tickSize, long lotSize) {}

  /** Resolves metadata for {@code (exchange, tradingsymbol)}. */
  InstrumentMeta meta(String exchange, String tradingsymbol);
}
