package in.arthayantra.marketdata.feeds;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Port for the daily FII/DII cash activity (B-1b; NSE-primary, Upstox swap-out). */
public interface FiiDiiFetcher {

  /** One category's cash buy/sell/net for a trade date (values in ₹ crore). */
  record FiiDiiRow(LocalDate date, String category, BigDecimal buy, BigDecimal sell, BigDecimal net) {}

  /** The latest published FII/DII rows (one per category). */
  List<FiiDiiRow> fetchLatest();
}
