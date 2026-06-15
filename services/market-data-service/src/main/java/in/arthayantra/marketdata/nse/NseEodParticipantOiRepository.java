package in.arthayantra.marketdata.nse;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Idempotent upsert of NSE participant-OI rows by {@code (trade_date, client_type)}. */
@Repository
public class NseEodParticipantOiRepository {

  private final JdbcTemplate jdbc;

  public NseEodParticipantOiRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void upsertAll(List<ParticipantOiFetcher.ParticipantOiRow> rows) {
    jdbc.batchUpdate(
        """
        INSERT INTO nse_eod_participant_oi (
          trade_date, client_type,
          future_index_long, future_index_short, future_stock_long, future_stock_short,
          option_index_call_long, option_index_put_long, option_index_call_short, option_index_put_short,
          option_stock_call_long, option_stock_put_long, option_stock_call_short, option_stock_put_short,
          total_long_contracts, total_short_contracts, fetched_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
        ON CONFLICT (trade_date, client_type) DO UPDATE SET
          future_index_long = EXCLUDED.future_index_long,
          future_index_short = EXCLUDED.future_index_short,
          future_stock_long = EXCLUDED.future_stock_long,
          future_stock_short = EXCLUDED.future_stock_short,
          option_index_call_long = EXCLUDED.option_index_call_long,
          option_index_put_long = EXCLUDED.option_index_put_long,
          option_index_call_short = EXCLUDED.option_index_call_short,
          option_index_put_short = EXCLUDED.option_index_put_short,
          option_stock_call_long = EXCLUDED.option_stock_call_long,
          option_stock_put_long = EXCLUDED.option_stock_put_long,
          option_stock_call_short = EXCLUDED.option_stock_call_short,
          option_stock_put_short = EXCLUDED.option_stock_put_short,
          total_long_contracts = EXCLUDED.total_long_contracts,
          total_short_contracts = EXCLUDED.total_short_contracts,
          fetched_at = now()
        """,
        rows,
        rows.size(),
        (ps, r) -> {
          ps.setObject(1, r.date());
          ps.setString(2, r.clientType());
          ps.setLong(3, r.futureIndexLong());
          ps.setLong(4, r.futureIndexShort());
          ps.setLong(5, r.futureStockLong());
          ps.setLong(6, r.futureStockShort());
          ps.setLong(7, r.optionIndexCallLong());
          ps.setLong(8, r.optionIndexPutLong());
          ps.setLong(9, r.optionIndexCallShort());
          ps.setLong(10, r.optionIndexPutShort());
          ps.setLong(11, r.optionStockCallLong());
          ps.setLong(12, r.optionStockPutLong());
          ps.setLong(13, r.optionStockCallShort());
          ps.setLong(14, r.optionStockPutShort());
          ps.setLong(15, r.totalLongContracts());
          ps.setLong(16, r.totalShortContracts());
        });
  }
}
