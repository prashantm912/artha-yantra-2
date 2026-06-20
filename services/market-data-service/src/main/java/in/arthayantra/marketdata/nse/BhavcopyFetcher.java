package in.arthayantra.marketdata.nse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Port for the NSE daily security-wise bhavcopy + delivery ({@code sec_bhavdata_full}). */
public interface BhavcopyFetcher {

  /**
   * One security's EOD OHLCV + delivery for a trade date. {@code delivQty}/{@code delivPer} are null
   * for non-deliverable rows (the file carries {@code -}).
   */
  record BhavcopyRow(
      LocalDate date,
      String symbol,
      String series,
      BigDecimal prevClose,
      BigDecimal open,
      BigDecimal high,
      BigDecimal low,
      BigDecimal last,
      BigDecimal close,
      BigDecimal avgPrice,
      Long totalTradedQty,
      BigDecimal turnoverLacs,
      Long noOfTrades,
      Long delivQty,
      BigDecimal delivPer) {}

  /** The latest published bhavcopy rows (one per symbol+series). */
  List<BhavcopyRow> fetchLatest();

  /**
   * The published bhavcopy rows for a specific trade date, or an empty list when the file is not
   * published for that date (a 404 on a weekend/holiday, or not yet posted). Empty is the normal,
   * non-fatal signal the watermark catch-up uses to skip a non-trading day.
   */
  List<BhavcopyRow> fetchForDate(LocalDate date);
}
