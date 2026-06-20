package in.arthayantra.marketdata.bse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Port for the BSE daily equity bhavcopy (UDiFF {@code BhavCopy_BSE_CM_..._F_0000.CSV}). */
public interface BseBhavcopyFetcher {

  /** One cash-equity (FinInstrmTp=STK) security's EOD OHLCV for a trade date. */
  record BseBhavRow(
      LocalDate date,
      String scripCode, // FinInstrmId
      String ticker, // TckrSymb — the BSE tradingsymbol
      String isin, // ISIN
      String series, // SctySrs
      BigDecimal prevClose,
      BigDecimal open,
      BigDecimal high,
      BigDecimal low,
      BigDecimal last,
      BigDecimal close,
      Long totalTradedQty,
      BigDecimal turnover,
      Long noOfTrades) {}

  /**
   * The published BSE bhavcopy equity rows for a trade date, or an empty list when the file is not
   * published for that date. BSE answers a non-trading day with HTTP 200 + the SPA homepage HTML
   * (not a 404), so empty is decided by content-sniffing the body, not the status code.
   */
  List<BseBhavRow> fetchForDate(LocalDate date);
}
