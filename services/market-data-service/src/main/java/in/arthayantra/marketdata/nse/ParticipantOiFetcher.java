package in.arthayantra.marketdata.nse;

import java.time.LocalDate;
import java.util.List;

/** Port for NSE daily participant-wise OI (FII/DII/Pro/Client futures + options long/short). */
public interface ParticipantOiFetcher {

  /**
   * One participant category's open interest (number of contracts) for a trade date. Columns mirror
   * the NSE {@code fao_participant_oi} CSV in source order.
   */
  record ParticipantOiRow(
      LocalDate date,
      String clientType,
      long futureIndexLong,
      long futureIndexShort,
      long futureStockLong,
      long futureStockShort,
      long optionIndexCallLong,
      long optionIndexPutLong,
      long optionIndexCallShort,
      long optionIndexPutShort,
      long optionStockCallLong,
      long optionStockPutLong,
      long optionStockCallShort,
      long optionStockPutShort,
      long totalLongContracts,
      long totalShortContracts) {}

  /** The most recent published participant-OI rows (one per client type). */
  List<ParticipantOiRow> fetchLatest();
}
