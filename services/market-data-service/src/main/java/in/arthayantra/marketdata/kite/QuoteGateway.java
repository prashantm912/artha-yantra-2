package in.arthayantra.marketdata.kite;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Map;

/**
 * Port 3/5 (A.7a): batched spot quotes. Live = Kite quote API with the KITE_QUOTE_BATCH_SIZE
 * budget (Stage B); mock = the last-tick map.
 */
public interface QuoteGateway {

  /** A spot quote. */
  record Quote(InstrumentKey key, BigDecimal lastPrice, OffsetDateTime timestamp) {}

  /** Last-known quotes for the requested instruments; absent keys are omitted. */
  Map<InstrumentKey, Quote> quotes(Collection<InstrumentKey> keys);
}
