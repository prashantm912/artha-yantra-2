package in.arthayantra.marketdata.screener.manas;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persists + reads the daily Manas Arora screen ({@code manas_arora_screen_results}). Stores EVERY
 * scanned candidate (not just the passers) so a fail is explainable and queryable later;
 * {@code passes_all} feeds the fast list read. Mirrors the sibling {@code MinerviniScreenRepository}'s
 * batch-upsert shape. Writes the marketdata schema under the single-writer {@code artha} role.
 */
@Repository
public class ManasScreenRepository {

  private static final String UPSERT =
      """
      INSERT INTO manas_arora_screen_results
        (screen_date, symbol, exchange, close_price, sma50, sma200, high_52w, low_52w,
         avg_vol_20, avg_vol_50, turnover_50, within_high_pct, above_low_pct,
         within_high, above_sma50, liquid_volume, liquid_depth, low_cap,
         free_float_mcap_cr, free_float_pct,
         gate1, gate2, gate3, gate4, gate5, gate6, gates_passed, passes_all, rs_rank, computed_at)
      VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, now())
      ON CONFLICT (screen_date, symbol) DO UPDATE SET
        exchange = EXCLUDED.exchange, close_price = EXCLUDED.close_price,
        sma50 = EXCLUDED.sma50, sma200 = EXCLUDED.sma200,
        high_52w = EXCLUDED.high_52w, low_52w = EXCLUDED.low_52w,
        avg_vol_20 = EXCLUDED.avg_vol_20, avg_vol_50 = EXCLUDED.avg_vol_50,
        turnover_50 = EXCLUDED.turnover_50, within_high_pct = EXCLUDED.within_high_pct,
        above_low_pct = EXCLUDED.above_low_pct, within_high = EXCLUDED.within_high,
        above_sma50 = EXCLUDED.above_sma50, liquid_volume = EXCLUDED.liquid_volume,
        liquid_depth = EXCLUDED.liquid_depth, low_cap = EXCLUDED.low_cap,
        free_float_mcap_cr = EXCLUDED.free_float_mcap_cr, free_float_pct = EXCLUDED.free_float_pct,
        gate1 = EXCLUDED.gate1, gate2 = EXCLUDED.gate2, gate3 = EXCLUDED.gate3,
        gate4 = EXCLUDED.gate4, gate5 = EXCLUDED.gate5, gate6 = EXCLUDED.gate6,
        gates_passed = EXCLUDED.gates_passed, passes_all = EXCLUDED.passes_all,
        rs_rank = EXCLUDED.rs_rank, computed_at = now()
      """;

  private final JdbcTemplate jdbc;

  /** Wires the marketdata datasource. */
  public ManasScreenRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Batch-upserts the full scanned candidate set for a screen date. Returns rows written. */
  public int upsertAll(LocalDate screenDate, List<ManasCandidate> candidates) {
    Date d = Date.valueOf(screenDate);
    List<Object[]> batch = new ArrayList<>(candidates.size());
    for (ManasCandidate c : candidates) {
      batch.add(
          new Object[] {
            d, c.symbol(), c.exchange(), c.close(), c.sma50(), c.sma200(),
            c.high52w(), c.low52w(), c.avgVol20(), c.avgVol50(), c.turnover50(),
            c.withinHighPct(), c.aboveLowPct(), c.withinHigh(), c.aboveSma50(),
            c.liquidVolume(), c.liquidDepth(), c.lowCap(), c.freeFloatMcapCr(), c.freeFloatPct(),
            c.gate(1), c.gate(2), c.gate(3), c.gate(4), c.gate(5), c.gate(6),
            c.gatesPassed(), c.passesAll(), c.rsRank()
          });
    }
    int[] r = jdbc.batchUpdate(UPSERT, batch);
    return r.length;
  }

  /** Reads the persisted screen, filtered + ordered by symbol. */
  public List<ManasCandidate> latest(
      LocalDate screenDate, boolean passesAllOnly, int limit, int offset) {
    StringBuilder sql =
        new StringBuilder("SELECT * FROM manas_arora_screen_results WHERE screen_date = ?");
    List<Object> args = new ArrayList<>();
    args.add(Date.valueOf(screenDate));
    if (passesAllOnly) {
      sql.append(" AND passes_all = TRUE");
    }
    sql.append(" ORDER BY above_low_pct DESC NULLS LAST, symbol ASC LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(Math.max(0, offset));
    return jdbc.query(sql.toString(), ManasScreenRepository::mapRow, args.toArray());
  }

  /** Reads one persisted screen row (the analyzer fast path), or {@code null} if not scanned. */
  public ManasCandidate findOne(LocalDate screenDate, String symbol) {
    List<ManasCandidate> rows =
        jdbc.query(
            "SELECT * FROM manas_arora_screen_results WHERE screen_date = ? AND symbol = ?",
            ManasScreenRepository::mapRow,
            Date.valueOf(screenDate), symbol);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /**
   * The latest PERSISTED screen date — the read-side watermark. Read endpoints default to this,
   * NOT the bhavcopy watermark (which can point at a date with zero screen rows between the
   * bhavcopy landing and the screen running — the audit-H1 empty-funnel window). Null when no
   * screen has ever persisted.
   */
  public LocalDate latestScreenDate() {
    return jdbc.queryForObject(
        "SELECT max(screen_date) FROM manas_arora_screen_results", LocalDate.class);
  }

  /** How many symbols were scanned on a screen date (coverage). */
  public int coverage(LocalDate screenDate) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM manas_arora_screen_results WHERE screen_date = ?",
            Integer.class,
            Date.valueOf(screenDate));
    return n == null ? 0 : n;
  }

  private static ManasCandidate mapRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    boolean[] g =
        new boolean[] {
          rs.getBoolean("gate1"), rs.getBoolean("gate2"), rs.getBoolean("gate3"),
          rs.getBoolean("gate4"), rs.getBoolean("gate5"), rs.getBoolean("gate6")
        };
    return new ManasCandidate(
        rs.getString("symbol"), rs.getString("exchange"), rs.getBigDecimal("close_price"),
        rs.getBigDecimal("sma50"), rs.getBigDecimal("sma200"), rs.getBigDecimal("high_52w"),
        rs.getBigDecimal("low_52w"), rs.getBigDecimal("avg_vol_20"), rs.getBigDecimal("avg_vol_50"),
        rs.getBigDecimal("turnover_50"), rs.getBigDecimal("within_high_pct"),
        rs.getBigDecimal("above_low_pct"), rs.getBoolean("within_high"), rs.getBoolean("above_sma50"),
        rs.getBoolean("liquid_volume"), rs.getBoolean("liquid_depth"), rs.getBoolean("low_cap"),
        g, rs.getInt("gates_passed"), rs.getBoolean("passes_all"),
        rs.getBigDecimal("free_float_mcap_cr"), rs.getBigDecimal("free_float_pct"),
        rs.getBigDecimal("rs_rank"));
  }
}
