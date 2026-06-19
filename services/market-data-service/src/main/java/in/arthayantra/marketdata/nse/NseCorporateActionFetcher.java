package in.arthayantra.marketdata.nse;

import java.time.LocalDate;
import java.util.List;

/** Port for the NSE corporate-actions feed (splits/bonuses/dividends; the ratio source for Phase C). */
public interface NseCorporateActionFetcher {

  /** One announced corporate action. {@code subject} is the free text parsed into a ratio. */
  record CaRecord(String symbol, String isin, LocalDate exDate, String subject) {}

  /** Announced actions with an ex-date in {@code [from, to]} (one cookie-seeded API call). */
  List<CaRecord> fetchRecent(LocalDate from, LocalDate to);
}
