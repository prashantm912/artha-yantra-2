package in.arthayantra.marketdata.nse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Port for the NSE daily FII/DII cash activity (B-1b). */
public interface FiiDiiFetcher {

  /** One category's cash buy/sell/net for a trade date (values in ₹ crore). */
  record FiiDiiRow(LocalDate date, String category, BigDecimal buy, BigDecimal sell, BigDecimal net) {}

  /** The latest published FII/DII rows (one per category). */
  List<FiiDiiRow> fetchLatest();
}
