package in.arthayantra.marketdata.nse;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import in.arthayantra.marketdata.equitydaily.CashEquityUniverse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Idempotent upsert of NSE bhavcopy rows by {@code (trade_date, symbol, series)}. */
@Repository
public class NseEodBhavcopyRepository {

  private final JdbcTemplate jdbc;

  public NseEodBhavcopyRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Newest {@code trade_date} present in ANY series — the catch-up watermark; null when the table is
   * empty.
   *
   * <p>⚠️ Series-agnostic ON PURPOSE, and it must stay that way: the backfill catch-up
   * ({@code BhavcopyBackfillService:334,396}) asks "did we fetch this day at all", which is a
   * question about the FILE, not about the cash universe. A day whose file landed with only
   * non-cash rows was still fetched.
   *
   * <p>⚠️ But a CONSUMER that pairs this watermark with a cash-universe population has a
   * mixed-watermark defect by construction — H24 names {@code DataQualityEodJob:103} and
   * {@code EquityBreadthEodJob:82} as the two instances. Those want {@link #maxCashTradeDate()}.
   */
  public LocalDate maxTradeDate() {
    return jdbc.query(
        "SELECT max(trade_date) AS d FROM nse_eod_bhavcopy",
        rs -> rs.next() ? rs.getObject("d", LocalDate.class) : null);
  }

  /**
   * Newest {@code trade_date} carrying CASH-EQUITY rows ({@link CashEquityUniverse}) — the watermark
   * for any consumer whose population is the cash universe, so that watermark and population agree.
   *
   * <p>Measured 2026-08-18: identical to {@link #maxTradeDate()} on live, and there is no date in
   * the table's whole span carrying non-cash rows without cash rows. The two can only diverge on a
   * partial-file day — which is exactly the day a data-quality report must not silently score.
   */
  public LocalDate maxCashTradeDate() {
    return jdbc.query(
        "SELECT max(trade_date) AS d FROM nse_eod_bhavcopy WHERE " + CashEquityUniverse.SERIES_PREDICATE,
        rs -> rs.next() ? rs.getObject("d", LocalDate.class) : null);
  }

  /**
   * Trade dates already stored in {@code [from, to]} — the catch-up anti-joins against these so a
   * day missed by a transient fetch error (which looks identical to a holiday) is re-attempted on a
   * later run instead of being permanently skipped once the watermark moves past it.
   */
  public List<LocalDate> presentTradeDates(LocalDate from, LocalDate to) {
    return jdbc.query(
        "SELECT DISTINCT trade_date FROM nse_eod_bhavcopy WHERE trade_date BETWEEN ? AND ?",
        (rs, n) -> rs.getObject("trade_date", LocalDate.class),
        from, to);
  }

  /**
   * Cash-equity (EQ+BE) symbols present on one settled trade date, sorted for deterministic diff
   * output.
   *
   * <p>⚠️ Was {@code eqSymbolsOn}, EQ-only, until H24 PR-5. Its one consumer diffs today's set
   * against the prior day's to report symbols "absent vs prior day", so an EQ-only read counted an
   * NSE surveillance move EQ→BE as a DISAPPEARANCE — the symbol is present and trading, merely
   * reclassified. Measured on the latest session pair: <b>3 reported drops, of which 2 were
   * migrations, not absences</b>, and 413 symbols changed between EQ and BE in the trailing 120
   * days. It fails in the ALARMING direction, and it also depresses the {@code bhavcopy_eq}
   * coverage ratio against its 0.98 floor.
   */
  public Set<String> cashSymbolsOn(LocalDate date) {
    return new TreeSet<>(
        jdbc.query(
            "SELECT symbol FROM nse_eod_bhavcopy WHERE trade_date = ? AND "
                + CashEquityUniverse.SERIES_PREDICATE,
            (rs, n) -> rs.getString("symbol"),
            java.sql.Date.valueOf(date)));
  }

  /** Previous settled trade date with any bhavcopy rows, or null on cold start. */
  public LocalDate prevTradeDate(LocalDate before) {
    return jdbc.query(
        "SELECT max(trade_date) AS d FROM nse_eod_bhavcopy WHERE trade_date < ?",
        rs -> rs.next() ? rs.getObject("d", LocalDate.class) : null,
        java.sql.Date.valueOf(before));
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
