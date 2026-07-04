package in.arthayantra.marketdata.screener.minervini;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persists + reads the daily Minervini screen. Stores EVERY scanned candidate (not just the
 * passers) so a fail is explainable and queryable later (the pass/fail-with-reasoning requirement);
 * {@code passes_all}/{@code rs_rank} feed the fast list read. Mirrors {@code NseEodBhavcopyRepository}'s
 * batch upsert shape. Writes the marketdata schema under the single-writer {@code artha} role.
 */
@Repository
public class MinerviniScreenRepository {

  private static final String UPSERT =
      """
      INSERT INTO minervini_screen_results
        (screen_date, symbol, exchange, close_price, sma50, sma150, sma200, high_52w, low_52w,
         pct_from_high, pct_above_low, rs_rank, avg_turnover_50, free_float_mcap_cr, free_float_pct,
         gate1, gate2, gate3, gate4, gate5, gate6, gate7, gate8, gates_passed, passes_all, stage,
         computed_at)
      VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, now())
      ON CONFLICT (screen_date, symbol) DO UPDATE SET
        exchange = EXCLUDED.exchange, close_price = EXCLUDED.close_price,
        sma50 = EXCLUDED.sma50, sma150 = EXCLUDED.sma150, sma200 = EXCLUDED.sma200,
        high_52w = EXCLUDED.high_52w, low_52w = EXCLUDED.low_52w,
        pct_from_high = EXCLUDED.pct_from_high, pct_above_low = EXCLUDED.pct_above_low,
        rs_rank = EXCLUDED.rs_rank, avg_turnover_50 = EXCLUDED.avg_turnover_50,
        free_float_mcap_cr = EXCLUDED.free_float_mcap_cr, free_float_pct = EXCLUDED.free_float_pct,
        gate1 = EXCLUDED.gate1, gate2 = EXCLUDED.gate2, gate3 = EXCLUDED.gate3,
        gate4 = EXCLUDED.gate4, gate5 = EXCLUDED.gate5, gate6 = EXCLUDED.gate6,
        gate7 = EXCLUDED.gate7, gate8 = EXCLUDED.gate8,
        gates_passed = EXCLUDED.gates_passed, passes_all = EXCLUDED.passes_all,
        stage = EXCLUDED.stage, computed_at = now()
      """;

  private final JdbcTemplate jdbc;

  /** Wires the marketdata datasource. */
  public MinerviniScreenRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Batch-upserts the full scanned candidate set for a screen date. Returns rows written. */
  public int upsertAll(LocalDate screenDate, List<TrendCandidate> candidates) {
    Date d = Date.valueOf(screenDate);
    List<Object[]> batch = new ArrayList<>(candidates.size());
    for (TrendCandidate c : candidates) {
      batch.add(
          new Object[] {
            d, c.symbol(), c.exchange(), c.close(), c.sma50(), c.sma150(), c.sma200(),
            c.high52w(), c.low52w(), c.pctFromHigh(), c.pctAboveLow(), c.rsRank(), c.avgTurnover50(),
            c.freeFloatMcapCr(), c.freeFloatPct(),
            c.gate(1), c.gate(2), c.gate(3), c.gate(4), c.gate(5), c.gate(6), c.gate(7), c.gate(8),
            c.gatesPassed(), c.passesAll(), c.stage()
          });
    }
    int[] r = jdbc.batchUpdate(UPSERT, batch);
    return r.length;
  }

  /** Reads the persisted screen, filtered + ranked by RS-rank desc. */
  public List<TrendCandidate> latest(
      LocalDate screenDate, boolean passesAllOnly, BigDecimal minRsRank, int limit, int offset) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT * FROM minervini_screen_results WHERE screen_date = ?");
    List<Object> args = new ArrayList<>();
    args.add(Date.valueOf(screenDate));
    if (passesAllOnly) {
      sql.append(" AND passes_all = TRUE");
    }
    if (minRsRank != null) {
      sql.append(" AND rs_rank >= ?");
      args.add(minRsRank);
    }
    sql.append(" ORDER BY rs_rank DESC NULLS LAST LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(Math.max(0, offset));
    return jdbc.query(
        sql.toString(),
        (rs, n) -> {
          boolean[] g =
              new boolean[] {
                rs.getBoolean("gate1"), rs.getBoolean("gate2"), rs.getBoolean("gate3"),
                rs.getBoolean("gate4"), rs.getBoolean("gate5"), rs.getBoolean("gate6"),
                rs.getBoolean("gate7"), rs.getBoolean("gate8")
              };
          Integer stage = rs.getObject("stage", Integer.class);
          return new TrendCandidate(
              rs.getString("symbol"), rs.getString("exchange"), rs.getBigDecimal("close_price"),
              rs.getBigDecimal("sma50"), rs.getBigDecimal("sma150"), rs.getBigDecimal("sma200"),
              rs.getBigDecimal("high_52w"), rs.getBigDecimal("low_52w"),
              rs.getBigDecimal("pct_from_high"), rs.getBigDecimal("pct_above_low"),
              rs.getBigDecimal("avg_turnover_50"), null, rs.getBigDecimal("rs_rank"),
              g, rs.getInt("gates_passed"), rs.getBoolean("passes_all"), stage,
              rs.getBigDecimal("free_float_mcap_cr"), rs.getBigDecimal("free_float_pct"));
        },
        args.toArray());
  }

  /** Reads one persisted screen row (the analyzer fast path), or {@code null} if not scanned. */
  public TrendCandidate findOne(LocalDate screenDate, String symbol) {
    List<TrendCandidate> rows =
        jdbc.query(
            "SELECT * FROM minervini_screen_results WHERE screen_date = ? AND symbol = ?",
            (rs, n) -> {
              boolean[] g =
                  new boolean[] {
                    rs.getBoolean("gate1"), rs.getBoolean("gate2"), rs.getBoolean("gate3"),
                    rs.getBoolean("gate4"), rs.getBoolean("gate5"), rs.getBoolean("gate6"),
                    rs.getBoolean("gate7"), rs.getBoolean("gate8")
                  };
              Integer stage = rs.getObject("stage", Integer.class);
              return new TrendCandidate(
                  rs.getString("symbol"), rs.getString("exchange"), rs.getBigDecimal("close_price"),
                  rs.getBigDecimal("sma50"), rs.getBigDecimal("sma150"), rs.getBigDecimal("sma200"),
                  rs.getBigDecimal("high_52w"), rs.getBigDecimal("low_52w"),
                  rs.getBigDecimal("pct_from_high"), rs.getBigDecimal("pct_above_low"),
                  rs.getBigDecimal("avg_turnover_50"), null, rs.getBigDecimal("rs_rank"),
                  g, rs.getInt("gates_passed"), rs.getBoolean("passes_all"), stage,
                  rs.getBigDecimal("free_float_mcap_cr"), rs.getBigDecimal("free_float_pct"));
            },
            Date.valueOf(screenDate), symbol);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** How many symbols were scanned on a screen date (coverage). */
  public int coverage(LocalDate screenDate) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM minervini_screen_results WHERE screen_date = ?",
            Integer.class,
            Date.valueOf(screenDate));
    return n == null ? 0 : n;
  }
}
