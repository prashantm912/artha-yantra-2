package in.arthayantra.marketdata.options;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * The flag-selected per-strike LTP+OI+spot source for the live OI snapshot (Wave U1). The DEFAULT
 * live path keeps reading per-strike quotes from the Kite {@code QuoteGateway} inside
 * {@link OptionsChainService}; when {@code artha.marketdata.source.optionchain=upstox} an
 * implementation backed by the direct-Upstox {@code /v2/option/chain} call (1-yr analytics token,
 * login-free) is bound instead. It replaces ONLY the raw quote fetch — the greeks/IV pipeline
 * (black76) downstream is unchanged, so a captured snapshot stays parity-comparable regardless of
 * source.
 */
public interface OptionChainQuoteSource {

  /** One strike side's live market data: LTP/bid/ask/volume + OI and the prev-pass OI. */
  record Leg(
      BigDecimal ltp,
      BigDecimal bid,
      BigDecimal ask,
      Long volume,
      Long oi,
      Long prevOi) {}

  /** The whole chain for one (underlying, expiry): spot + a CE/PE {@link Leg} per strike. */
  record ChainQuotes(BigDecimal spot, Map<BigDecimal, Leg> ce, Map<BigDecimal, Leg> pe) {}

  /**
   * Fetches the live chain market data for {@code (underlying, expiry)} in one call. {@code empty}
   * means the source has nothing for that pair (e.g. an unmapped underlying) — the caller then leaves
   * the chain to its default behaviour rather than producing an empty snapshot.
   */
  Optional<ChainQuotes> fetch(String underlying, LocalDate expiry);
}
