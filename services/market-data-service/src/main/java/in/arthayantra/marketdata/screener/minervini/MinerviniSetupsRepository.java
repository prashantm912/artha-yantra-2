package in.arthayantra.marketdata.screener.minervini;

import in.arthayantra.marketdata.screener.minervini.geometry.VcpFootprint;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists + reads per-candidate VCP / base geometry ({@code minervini_setups}, MV-5.4). One row per
 * {@code (screen_date, symbol)}, keyed the same as the screen table. Writes the marketdata schema
 * under the single-writer {@code artha} role.
 */
@Repository
public class MinerviniSetupsRepository {

  private static final String UPSERT =
      """
      INSERT INTO minervini_setups
        (screen_date, symbol, is_vcp, footprint, pivot, deepest_pct, tightest_pct,
         contraction_count, base_weeks, base_duration_days, volume_dry_up, shakeout, base_count,
         cheat_pivot, thrust, reject_reason, computed_at)
      VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, now())
      ON CONFLICT (screen_date, symbol) DO UPDATE SET
        is_vcp = EXCLUDED.is_vcp, footprint = EXCLUDED.footprint, pivot = EXCLUDED.pivot,
        deepest_pct = EXCLUDED.deepest_pct, tightest_pct = EXCLUDED.tightest_pct,
        contraction_count = EXCLUDED.contraction_count, base_weeks = EXCLUDED.base_weeks,
        base_duration_days = EXCLUDED.base_duration_days, volume_dry_up = EXCLUDED.volume_dry_up,
        shakeout = EXCLUDED.shakeout, base_count = EXCLUDED.base_count,
        cheat_pivot = EXCLUDED.cheat_pivot, thrust = EXCLUDED.thrust,
        reject_reason = EXCLUDED.reject_reason, computed_at = now()
      """;

  private final JdbcTemplate jdbc;

  /** Wires the marketdata datasource. */
  public MinerviniSetupsRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Atomically REPLACES geometry for a screen date (delete-by-date then write), so a setup row for
   * a symbol the guard has since dropped cannot survive a recompute. {@code geometry} maps symbol →
   * footprint; an EMPTY map is a valid input meaning "no passers today" and correctly clears the
   * date.
   */
  @Transactional
  public int replaceAll(LocalDate screenDate, Map<String, VcpFootprint> geometry) {
    Date d = Date.valueOf(screenDate);
    jdbc.update("DELETE FROM minervini_setups WHERE screen_date = ?", d);
    List<Object[]> batch = new ArrayList<>(geometry.size());
    for (Map.Entry<String, VcpFootprint> e : geometry.entrySet()) {
      VcpFootprint f = e.getValue();
      batch.add(
          new Object[] {
            d, e.getKey(), f.vcp(), f.footprint(), num(f.pivot(), f.vcp()),
            num(f.deepestPct(), f.vcp()), num(f.tightestPct(), f.vcp()),
            f.vcp() ? f.contractionCount() : null, f.vcp() ? f.baseWeeks() : null,
            f.vcp() ? f.baseDurationDays() : null, f.volumeDryUp(), f.shakeout(),
            f.vcp() ? f.baseCount() : null, num(f.cheatPivot(), f.vcp()), f.thrust(),
            f.rejectReason()
          });
    }
    int[] r = jdbc.batchUpdate(UPSERT, batch);
    return r.length;
  }

  /** Reads persisted geometry for one candidate, or {@code null} if none was computed. */
  public VcpFootprint find(LocalDate screenDate, String symbol) {
    List<VcpFootprint> rows =
        jdbc.query(
            """
            SELECT is_vcp, footprint, pivot, deepest_pct, tightest_pct, contraction_count,
                   base_weeks, base_duration_days, volume_dry_up, shakeout, base_count,
                   cheat_pivot, thrust, reject_reason
            FROM minervini_setups WHERE screen_date = ? AND symbol = ?
            """,
            (rs, n) ->
                new VcpFootprint(
                    rs.getBoolean("is_vcp"),
                    rs.getInt("contraction_count"),
                    dbl(rs.getBigDecimal("deepest_pct")),
                    dbl(rs.getBigDecimal("tightest_pct")),
                    rs.getInt("base_weeks"),
                    rs.getInt("base_duration_days"),
                    dbl(rs.getBigDecimal("pivot")),
                    dbl(rs.getBigDecimal("deepest_pct")),
                    rs.getBoolean("volume_dry_up"),
                    rs.getBoolean("shakeout"),
                    rs.getInt("base_count"),
                    dbl(rs.getBigDecimal("cheat_pivot")),
                    rs.getBoolean("thrust"),
                    rs.getString("footprint"),
                    rs.getString("reject_reason")),
            Date.valueOf(screenDate), symbol);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private static BigDecimal num(double v, boolean present) {
    return present ? BigDecimal.valueOf(v) : null;
  }

  private static double dbl(BigDecimal v) {
    return v == null ? 0.0 : v.doubleValue();
  }
}
