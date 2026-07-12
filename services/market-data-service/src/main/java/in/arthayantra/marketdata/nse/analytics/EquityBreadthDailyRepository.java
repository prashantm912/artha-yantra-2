package in.arthayantra.marketdata.nse.analytics;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads + materializes the daily market-breadth fold ({@code equity_breadth_daily}, V044). The fold
 * is self-contained in the NSE EQ-series bhavcopy: advance/decline from close-vs-prev-close, average
 * delivery%, and the above-50/200-SMA counts from a window over the SAME table (no cross-source
 * candle join). Idempotent upsert keyed by {@code trade_date}.
 */
@Repository
public class EquityBreadthDailyRepository {

  /** One materialized breadth day. Above-MA counts are null-capable (insufficient history early). */
  public record BreadthDay(
      LocalDate tradeDate,
      int advances,
      int declines,
      int unchanged,
      int total,
      BigDecimal avgDeliveryPct,
      Integer aboveSma50,
      Integer sma50Universe,
      Integer aboveSma200,
      Integer sma200Universe) {}

  // SMA warm-up: how many calendar days before the earliest wanted date to scan so a 200-session SMA
  // has its window (≈ 200 trading days ≈ 290 calendar days; 400 is a comfortable margin over holidays).
  private static final int SMA_WARMUP_DAYS = 400;

  // Folds breadth for EVERY EQ trade_date in [from, to] in one scan. The window functions warm up from
  // SMA_WARMUP_DAYS before `from`, then the outer WHERE restricts the emitted rows to the wanted range.
  // above_sma50/200 are counted only where the per-symbol window has >= the full lookback of rows
  // (n50/n200), so a short-history name is excluded from BOTH the numerator and the universe denominator.
  private static final String COMPUTE_SQL =
      """
      WITH hist AS (
        SELECT symbol, trade_date, close_price, prev_close, deliv_per,
               avg(close_price) OVER w50  AS sma50,  count(*) OVER w50  AS n50,
               avg(close_price) OVER w200 AS sma200, count(*) OVER w200 AS n200
        FROM nse_eod_bhavcopy
        WHERE series = 'EQ' AND trade_date > ? AND trade_date <= ?
        WINDOW w50  AS (PARTITION BY symbol ORDER BY trade_date ROWS 49  PRECEDING),
               w200 AS (PARTITION BY symbol ORDER BY trade_date ROWS 199 PRECEDING)
      )
      SELECT trade_date,
             count(*) FILTER (WHERE close_price > prev_close)::int AS advances,
             count(*) FILTER (WHERE close_price < prev_close)::int AS declines,
             count(*) FILTER (WHERE close_price = prev_close)::int AS unchanged,
             count(*)::int AS total,
             avg(deliv_per) AS avg_delivery_pct,
             count(*) FILTER (WHERE n50  >= 50  AND close_price > sma50 )::int AS above_sma50,
             count(*) FILTER (WHERE n50  >= 50)::int  AS sma50_universe,
             count(*) FILTER (WHERE n200 >= 200 AND close_price > sma200)::int AS above_sma200,
             count(*) FILTER (WHERE n200 >= 200)::int AS sma200_universe
      FROM hist
      WHERE trade_date >= ?
      GROUP BY trade_date
      ORDER BY trade_date
      """;

  private static final String UPSERT =
      """
      INSERT INTO equity_breadth_daily
        (trade_date, advances, declines, unchanged, total, avg_delivery_pct,
         above_sma50, sma50_universe, above_sma200, sma200_universe, computed_at)
      VALUES (?,?,?,?,?,?,?,?,?,?, now())
      ON CONFLICT (trade_date) DO UPDATE SET
        advances = EXCLUDED.advances, declines = EXCLUDED.declines,
        unchanged = EXCLUDED.unchanged, total = EXCLUDED.total,
        avg_delivery_pct = EXCLUDED.avg_delivery_pct,
        above_sma50 = EXCLUDED.above_sma50, sma50_universe = EXCLUDED.sma50_universe,
        above_sma200 = EXCLUDED.above_sma200, sma200_universe = EXCLUDED.sma200_universe,
        computed_at = now()
      """;

  private final JdbcTemplate jdbc;

  public EquityBreadthDailyRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Folds breadth from the EQ bhavcopy for every trading day in {@code [from, to]} (SMA-warmed). */
  public List<BreadthDay> compute(LocalDate from, LocalDate to) {
    Date warmupStart = Date.valueOf(from.minusDays(SMA_WARMUP_DAYS));
    return jdbc.query(
        COMPUTE_SQL, EquityBreadthDailyRepository::mapRow, warmupStart, Date.valueOf(to), Date.valueOf(from));
  }

  /** Upserts the materialized rows; returns the number of rows written. */
  public int upsertAll(List<BreadthDay> days) {
    if (days.isEmpty()) {
      return 0;
    }
    jdbc.batchUpdate(
        UPSERT,
        days,
        days.size(),
        (ps, d) -> {
          ps.setObject(1, d.tradeDate());
          ps.setInt(2, d.advances());
          ps.setInt(3, d.declines());
          ps.setInt(4, d.unchanged());
          ps.setInt(5, d.total());
          ps.setObject(6, d.avgDeliveryPct());
          ps.setObject(7, d.aboveSma50());
          ps.setObject(8, d.sma50Universe());
          ps.setObject(9, d.aboveSma200());
          ps.setObject(10, d.sma200Universe());
        });
    return days.size();
  }

  /** Reads the materialized breadth history for {@code [from, to]} (ascending by date). */
  public List<BreadthDay> history(LocalDate from, LocalDate to) {
    return jdbc.query(
        "SELECT trade_date, advances, declines, unchanged, total, avg_delivery_pct, "
            + " above_sma50, sma50_universe, above_sma200, sma200_universe "
            + "FROM equity_breadth_daily WHERE trade_date BETWEEN ? AND ? ORDER BY trade_date",
        EquityBreadthDailyRepository::mapRow,
        Date.valueOf(from), Date.valueOf(to));
  }

  /** The newest materialized trade date, or {@code null} when nothing has been materialized yet. */
  public LocalDate latestDate() {
    return jdbc.query(
        "SELECT max(trade_date) AS d FROM equity_breadth_daily",
        rs -> rs.next() ? rs.getObject("d", LocalDate.class) : null);
  }

  private static BreadthDay mapRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    return new BreadthDay(
        rs.getObject("trade_date", LocalDate.class),
        rs.getInt("advances"),
        rs.getInt("declines"),
        rs.getInt("unchanged"),
        rs.getInt("total"),
        rs.getBigDecimal("avg_delivery_pct"),
        (Integer) rs.getObject("above_sma50"),
        (Integer) rs.getObject("sma50_universe"),
        (Integer) rs.getObject("above_sma200"),
        (Integer) rs.getObject("sma200_universe"));
  }
}
