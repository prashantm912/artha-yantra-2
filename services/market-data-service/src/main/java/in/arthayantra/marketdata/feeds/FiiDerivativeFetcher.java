package in.arthayantra.marketdata.feeds;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Port for FII daily net activity per F&amp;O derivative segment (ADR-0002 U6, oipulse "FII
 * Derivative Stats"). Unlike {@link FiiDiiFetcher} (cash, with an NSE source) there is NO NSE EOD
 * equivalent — the only source is Upstox Market-Information, so in {@code live} this port has a bean
 * ONLY when the Upstox analytics token is enabled; the scheduler treats its absence as "skip".
 */
public interface FiiDerivativeFetcher {

  /** One F&amp;O segment's net buy/sell for a trade date (values in ₹ crore). */
  record FiiDerivativeRow(
      LocalDate date, String segment, BigDecimal buy, BigDecimal sell, BigDecimal net) {}

  /** The latest published rows (one per segment per trade date in the source window). */
  List<FiiDerivativeRow> fetchLatest();
}
