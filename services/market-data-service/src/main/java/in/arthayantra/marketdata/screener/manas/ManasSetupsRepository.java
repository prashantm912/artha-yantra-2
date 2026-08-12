package in.arthayantra.marketdata.screener.manas;

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
 * Persists + reads per-candidate Manas base geometry ({@code manas_arora_setups}). TWO rows per
 * candidate — one per setup type ({@code vcp} / {@code breakout}) — keyed
 * {@code (screen_date, symbol, setup_type)}. Populated only for the day's passers (the geometry scan
 * is per-symbol and expensive). Mirrors the sibling {@code MinerviniSetupsRepository}. Writes the
 * marketdata schema under the single-writer {@code artha} role.
 */
@Repository
public class ManasSetupsRepository {

  private static final String UPSERT =
      """
      INSERT INTO manas_arora_setups
        (screen_date, symbol, setup_type, is_valid, footprint, pivot, base_weeks, reject_reason,
         computed_at)
      VALUES (?,?,?,?,?,?,?,?, now())
      ON CONFLICT (screen_date, symbol, setup_type) DO UPDATE SET
        is_valid = EXCLUDED.is_valid, footprint = EXCLUDED.footprint, pivot = EXCLUDED.pivot,
        base_weeks = EXCLUDED.base_weeks, reject_reason = EXCLUDED.reject_reason, computed_at = now()
      """;

  private final JdbcTemplate jdbc;

  /** Wires the marketdata datasource. */
  public ManasSetupsRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Atomically REPLACES geometry for a screen date (delete-by-date then write), so a setup row for
   * a symbol the guard has since dropped cannot survive a recompute. {@code geometry} maps symbol →
   * its list of setups; an EMPTY map is a valid input meaning "no passers today" and correctly
   * clears the date.
   */
  @Transactional
  public int replaceAll(LocalDate screenDate, Map<String, List<ManasSetup>> geometry) {
    Date d = Date.valueOf(screenDate);
    jdbc.update("DELETE FROM manas_arora_setups WHERE screen_date = ?", d);
    List<Object[]> batch = new ArrayList<>(geometry.size() * 2);
    for (Map.Entry<String, List<ManasSetup>> e : geometry.entrySet()) {
      for (ManasSetup s : e.getValue()) {
        batch.add(
            new Object[] {
              d, e.getKey(), s.setupType(), s.valid(), s.footprint(),
              s.valid() ? BigDecimal.valueOf(s.pivot()) : null,
              s.valid() ? s.baseWeeks() : null, s.rejectReason()
            });
      }
    }
    int[] r = jdbc.batchUpdate(UPSERT, batch);
    return r.length;
  }

  /** Reads persisted geometry (both setups) for one candidate; empty when none was computed. */
  public List<ManasSetup> find(LocalDate screenDate, String symbol) {
    return jdbc.query(
        """
        SELECT setup_type, is_valid, footprint, pivot, base_weeks, reject_reason
        FROM manas_arora_setups WHERE screen_date = ? AND symbol = ? ORDER BY setup_type
        """,
        (rs, n) ->
            new ManasSetup(
                rs.getString("setup_type"),
                rs.getBoolean("is_valid"),
                rs.getString("footprint"),
                dbl(rs.getBigDecimal("pivot")),
                rs.getInt("base_weeks"),
                rs.getString("reject_reason")),
        Date.valueOf(screenDate), symbol);
  }

  private static double dbl(BigDecimal v) {
    return v == null ? 0.0 : v.doubleValue();
  }
}
