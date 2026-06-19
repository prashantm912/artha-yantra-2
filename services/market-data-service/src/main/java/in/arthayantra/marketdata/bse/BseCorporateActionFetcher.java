package in.arthayantra.marketdata.bse;

import java.time.LocalDate;
import java.util.List;

/** Port for the BSE corporate-actions feed (the ratio source for BSE-only listings, Phase C). */
public interface BseCorporateActionFetcher {

  /**
   * One BSE corporate action. BSE keys by scrip code (no ISIN in this feed); {@code shortName} is the
   * BSE ticker (our BSE tradingsymbol). {@code purpose} is the free text parsed into a ratio.
   */
  record BseCaRecord(String scripCode, String shortName, LocalDate exDate, String purpose) {}

  /** Announced actions with an ex-date in {@code [from, to]} (one JSON call, not paginated). */
  List<BseCaRecord> fetchRecent(LocalDate from, LocalDate to);
}
