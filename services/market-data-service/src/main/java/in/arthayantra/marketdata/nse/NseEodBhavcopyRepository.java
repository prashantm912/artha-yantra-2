package in.arthayantra.marketdata.nse;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Idempotent upsert of NSE bhavcopy rows by {@code (trade_date, symbol, series)}. */
@Repository
public class NseEodBhavcopyRepository {

  private final JdbcTemplate jdbc;

  public NseEodBhavcopyRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void upsertAll(List<BhavcopyFetcher.BhavcopyRow> rows) {
    jdbc.batchUpdate(
        """
        INSERT INTO nse_eod_bhavcopy (
          trade_date, symbol, series,
          prev_close, open_price, high_price, low_price, last_price, close_price, avg_price,
          ttl_trd_qnty, turnover_lacs, no_of_trades, deliv_qty, deliv_per, fetched_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
        ON CONFLICT (trade_date, symbol, series) DO UPDATE SET
          prev_close = EXCLUDED.prev_close,
          open_price = EXCLUDED.open_price,
          high_price = EXCLUDED.high_price,
          low_price = EXCLUDED.low_price,
          last_price = EXCLUDED.last_price,
          close_price = EXCLUDED.close_price,
          avg_price = EXCLUDED.avg_price,
          ttl_trd_qnty = EXCLUDED.ttl_trd_qnty,
          turnover_lacs = EXCLUDED.turnover_lacs,
          no_of_trades = EXCLUDED.no_of_trades,
          deliv_qty = EXCLUDED.deliv_qty,
          deliv_per = EXCLUDED.deliv_per,
          fetched_at = now()
        """,
        rows,
        rows.size(),
        (ps, r) -> {
          ps.setObject(1, r.date());
          ps.setString(2, r.symbol());
          ps.setString(3, r.series());
          ps.setObject(4, r.prevClose());
          ps.setObject(5, r.open());
          ps.setObject(6, r.high());
          ps.setObject(7, r.low());
          ps.setObject(8, r.last());
          ps.setObject(9, r.close());
          ps.setObject(10, r.avgPrice());
          ps.setObject(11, r.totalTradedQty());
          ps.setObject(12, r.turnoverLacs());
          ps.setObject(13, r.noOfTrades());
          ps.setObject(14, r.delivQty());
          ps.setObject(15, r.delivPer());
        });
  }
}
