package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategyengine.fills.InstrumentClass;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Resolves the contract metadata the expiry-settlement lifecycle needs — expiry, strike, the spot
 * symbol to settle against, and whether the underlying is an index (cash-settled) vs a stock
 * (physical). Over market-data REST (no {@code marketdata} grant). Kept separate from
 * {@link InstrumentMetaClient} so the lighter meta lookups stay a single-method functional interface.
 */
public interface ContractInfoClient {

  /** A derivative contract's settlement-relevant facts. */
  record ContractInfo(
      LocalDate expiry,
      String spotExchange,
      String spotSymbol,
      BigDecimal strike,
      String optionType,
      InstrumentClass instrumentClass,
      boolean indexUnderlying) {}

  /** Contract info for a stable key; empty for a non-derivative or an unknown instrument. */
  Optional<ContractInfo> contract(String exchange, String tradingsymbol);
}
