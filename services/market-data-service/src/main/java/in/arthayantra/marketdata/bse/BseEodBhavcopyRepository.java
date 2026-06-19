package in.arthayantra.marketdata.bse;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Idempotent upsert of BSE bhavcopy rows by {@code (trade_date, scrip_code)}. */
@Repository
public class BseEodBhavcopyRepository {

  private final JdbcTemplate jdbc;

  public BseEodBhavcopyRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Newest {@code trade_date} present — the catch-up watermark; null when the table is empty. */
  public LocalDate maxTradeDate() {
    return jdbc.query(
        "SELECT max(trade_date) AS d FROM bse_eod_bhavcopy",
        rs -> rs.next() ? rs.getObject("d", LocalDate.class) : null);
  }

  /** Trade dates already stored in {@code [from, to]} — the catch-up anti-joins against these so a
   * day missed by a transient fetch error (indistinguishable from a holiday) is re-attempted later. */
  public List<LocalDate> presentTradeDates(LocalDate from, LocalDate to) {
    return jdbc.query(
        "SELECT DISTINCT trade_date FROM bse_eod_bhavcopy WHERE trade_date BETWEEN ? AND ?",
        (rs, n) -> rs.getObject("trade_date", LocalDate.class),
        from, to);
  }

  /** Most-recent BSE ticker for a scrip code — joins a corporate action (keyed by scrip) to the
   * candle key (ticker). */
  public String tickerForScrip(String scripCode) {
    if (scripCode == null || scripCode.isBlank()) {
      return null;
    }
    List<String> hit =
        jdbc.query(
            "SELECT ticker FROM bse_eod_bhavcopy WHERE scrip_code = ? AND ticker IS NOT NULL"
                + " ORDER BY trade_date DESC LIMIT 1",
            (rs, n) -> rs.getString(1),
            scripCode);
    return hit.isEmpty() ? null : hit.get(0);
  }

  public void upsertAll(List<BseBhavcopyFetcher.BseBhavRow> rows) {
    jdbc.batchUpdate(
        """
        INSERT INTO bse_eod_bhavcopy (
          trade_date, scrip_code, ticker, isin, series,
          prev_close, open_price, high_price, low_price, last_price, close_price,
          ttl_trd_qnty, turnover_rs, no_of_trades, fetched_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
        ON CONFLICT (trade_date, scrip_code) DO UPDATE SET
          ticker = EXCLUDED.ticker,
          isin = EXCLUDED.isin,
          series = EXCLUDED.series,
          prev_close = EXCLUDED.prev_close,
          open_price = EXCLUDED.open_price,
          high_price = EXCLUDED.high_price,
          low_price = EXCLUDED.low_price,
          last_price = EXCLUDED.last_price,
          close_price = EXCLUDED.close_price,
          ttl_trd_qnty = EXCLUDED.ttl_trd_qnty,
          turnover_rs = EXCLUDED.turnover_rs,
          no_of_trades = EXCLUDED.no_of_trades,
          fetched_at = now()
        """,
        rows,
        rows.size(),
        (ps, r) -> {
          ps.setObject(1, r.date());
          ps.setString(2, r.scripCode());
          ps.setString(3, r.ticker());
          ps.setString(4, r.isin());
          ps.setString(5, r.series());
          ps.setObject(6, r.prevClose());
          ps.setObject(7, r.open());
          ps.setObject(8, r.high());
          ps.setObject(9, r.low());
          ps.setObject(10, r.last());
          ps.setObject(11, r.close());
          ps.setObject(12, r.totalTradedQty());
          ps.setObject(13, r.turnover());
          ps.setObject(14, r.noOfTrades());
        });
  }
}
