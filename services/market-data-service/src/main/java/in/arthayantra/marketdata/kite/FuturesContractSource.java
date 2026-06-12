package in.arthayantra.marketdata.kite;

import java.time.LocalDate;
import java.util.List;

/**
 * Monthly FUT contracts of an underlying, expiry-sorted (B-18). Implemented by the instruments
 * module over the synced master — declared here so kite-side consumers (the Phase-15A pinner)
 * never reach into instruments internals.
 */
public interface FuturesContractSource {

  /** One listed contract. */
  record FutContract(InstrumentKey key, LocalDate expiry) {}

  /** Active FUT contracts for {@code underlying} expiring on/after {@code onOrAfter}, sorted. */
  List<FutContract> monthlyFutures(String underlying, LocalDate onOrAfter);
}
