package in.arthayantra.marketdata.nse.analytics;

import in.arthayantra.marketdata.equitydaily.AdjustedEquityDailySql;
import in.arthayantra.marketdata.equitydaily.CashEquityUniverse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads + materializes the daily market-breadth fold ({@code equity_breadth_daily}, V044). The fold
 * is self-contained in the NSE cash (EQ+BE) bhavcopy: advance/decline from close-vs-prior-close, average
 * delivery%, and the above-50/200-SMA counts from a window over the SAME table (no cross-source
 * candle join). Idempotent upsert keyed by {@code trade_date}.
 *
 * <p>All price operands are CORPORATE-ACTION-ADJUSTED through {@link
 * in.arthayantra.marketdata.equitydaily.AdjustedEquityDailySql#factorLateral} — see the comment on
 * {@code COMPUTE_SQL} for why, and for why the prior close is a {@code lag()} over the adjusted
 * series rather than the exchange's own {@code prev_close} column.
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
      @Schema(type = "string", types = {"string", "null"}) BigDecimal avgDeliveryPct,
      @Schema(types = {"integer", "null"}) Integer aboveSma50,
      @Schema(types = {"integer", "null"}) Integer sma50Universe,
      @Schema(types = {"integer", "null"}) Integer aboveSma200,
      @Schema(types = {"integer", "null"}) Integer sma200Universe) {}

  // SMA warm-up: how many calendar days before the earliest wanted date to scan so a 200-session SMA
  // has its window (≈ 200 trading days ≈ 290 calendar days; 400 is a comfortable margin over holidays).
  private static final int SMA_WARMUP_DAYS = 400;

  // Folds breadth for EVERY cash trade_date in [from, to] in one scan. The window functions warm up from
  // SMA_WARMUP_DAYS before `from`, then the outer WHERE restricts the emitted rows to the wanted range.
  // above_sma50/200 are counted only where the per-symbol window has >= the full lookback of rows
  // (n50/n200), so a short-history name is excluded from BOTH the numerator and the universe denominator.
  //
  // ⚠️ EVERY price operand here is CORPORATE-ACTION-ADJUSTED via AdjustedEquityDailySql.factorLateral —
  // the SINGLE definition of the rule (ledger §9-02), called rather than pasted. Raw bhavcopy closes made
  // a split or bonus ex-date read as a market event: the name collapsed against its unadjusted prior close
  // (counted a DECLINE) and dropped below its own SMA (counted out of above_sma50/200), and the SMA
  // windows carried that cliff for the next 50 and 200 sessions. Same defect class as audit H6 / §9-02.
  //
  // WHY lag(), NOT the bhavcopy `prev_close` COLUMN. `prev_close` is the exchange's own previous close and
  // is NOT on the adjusted basis, so comparing it to an adjusted close would swap one basis mismatch for
  // another — measured 2026-08-25 on the live DB across all 21 ex-dates in 2026-06-01..2026-08-25 that have
  // a bhavcopy row: `prev_close` equalled the prior session's RAW close in 21 of 21 (e.g. CORDELIA
  // 2026-08-25, ratio 0.10: prev_close 1043.60 against an ex-date close of 104.45). Taking both operands
  // from the one adjusted plane is the only way the comparison is basis-consistent at the ex-date.
  // The swap is inert everywhere else: over 2026-07-01..2026-08-24, lag(close_price) equalled `prev_close`
  // on 103068 of 103068 EQ+BE rows that had both. The one behavioural difference is a symbol whose FIRST
  // in-window bar is an emitted day — it has no prior adjusted close, so it counts into `total` but into
  // neither advances nor declines (previously the exchange column gave it a verdict). Measured on the same
  // live DB with the production 400-day warm-up: 211 of 103288 emitted rows, 0.20%, all new listings. That
  // is the honest reading — we cannot state an adjusted move for a session we have no adjusted base for —
  // and it fails toward "no opinion", never toward a fabricated advance or decline.
  private static final String COMPUTE_SQL =
      """
      WITH adj AS (
        SELECT b.symbol, b.trade_date, b.deliv_per,
               round(b.close_price * caf.factor, 4) AS close_adj
        FROM nse_eod_bhavcopy b
      """
          + AdjustedEquityDailySql.factorLateral("b", "trade_date")
          + """
        WHERE %s AND b.trade_date > ? AND b.trade_date <= ?
      ), hist AS (
        SELECT symbol, trade_date, deliv_per, close_adj,
               lag(close_adj) OVER (PARTITION BY symbol ORDER BY trade_date) AS prev_close_adj,
               avg(close_adj) OVER w50  AS sma50,  count(*) OVER w50  AS n50,
               avg(close_adj) OVER w200 AS sma200, count(*) OVER w200 AS n200
        FROM adj
        WINDOW w50  AS (PARTITION BY symbol ORDER BY trade_date ROWS 49  PRECEDING),
               w200 AS (PARTITION BY symbol ORDER BY trade_date ROWS 199 PRECEDING)
      )
      SELECT trade_date,
             count(*) FILTER (WHERE close_adj > prev_close_adj)::int AS advances,
             count(*) FILTER (WHERE close_adj < prev_close_adj)::int AS declines,
             count(*) FILTER (WHERE close_adj = prev_close_adj)::int AS unchanged,
             count(*)::int AS total,
             avg(deliv_per) AS avg_delivery_pct,
             count(*) FILTER (WHERE n50  >= 50  AND close_adj > sma50 )::int AS above_sma50,
             count(*) FILTER (WHERE n50  >= 50)::int  AS sma50_universe,
             count(*) FILTER (WHERE n200 >= 200 AND close_adj > sma200)::int AS above_sma200,
             count(*) FILTER (WHERE n200 >= 200)::int AS sma200_universe
      FROM hist
      WHERE trade_date >= ?
      GROUP BY trade_date
      ORDER BY trade_date
      """
              .formatted(CashEquityUniverse.qualified("b"));

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

  /** Folds breadth from the EQ+BE cash bhavcopy for every trading day in {@code [from, to]} (SMA-warmed). */
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
