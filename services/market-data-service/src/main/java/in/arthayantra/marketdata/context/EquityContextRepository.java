package in.arthayantra.marketdata.context;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Equity-context folds for the equity digest (intelligence-layer design 2026-07-10 §6.1.3) — SQL
 * over the EXISTING {@code nse_eod_bhavcopy} bhavcopy table, no new raw data (§13 row 4). Three of
 * these are the NEW folds §6.1.3 names (there is no such fold today): {@link #aboveMa} (universe-wide
 * above-20/50-DMA counts), {@link #deliveryZ} (universe-wide delivery-% z-score outliers), and
 * {@link #sectorSessionChange} (the per-session change read the sector-rotation fold parameterizes
 * by {@code date} — the existing sector fold hardwires the latest session). The rest ({@link
 * #advanceDecline}, {@link #advDecSeries}, {@link #returnBases}, {@link #indexMemberChange}) are the
 * bhavcopy reads the digest re-uses; they live here rather than injecting the {@code nse.analytics}
 * services because those are another module's internal types (cross-module deps target base-package
 * types only in this service).
 *
 * <p>Idioms match {@code nse.analytics}: plain {@link JdbcTemplate}, positional {@code ?} params,
 * {@code series = 'EQ'}, {@code java.sql.Date.valueOf} binds, IST-safe {@code (col AT TIME ZONE
 * 'Asia/Kolkata')::date} casts (never a bare {@code ::date}). Every windowed fold carries a lower
 * {@code trade_date} bound so the {@code ROW_NUMBER} scan is chunk-pruned, not full-table.
 */
@Repository
public class EquityContextRepository {

  private final JdbcTemplate jdbc;

  public EquityContextRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The latest EQ bhavcopy session (null when the table is empty). */
  public LocalDate latestSession() {
    return jdbc.queryForObject(
        "SELECT max(trade_date) FROM nse_eod_bhavcopy WHERE series = 'EQ'", LocalDate.class);
  }

  /** The EQ session strictly before {@code date} that has data (null when none). */
  public LocalDate priorSession(LocalDate date) {
    return jdbc.query(
            "SELECT max(trade_date) FROM nse_eod_bhavcopy WHERE series = 'EQ' AND trade_date < ?",
            (rs, n) -> rs.getObject(1, LocalDate.class),
            java.sql.Date.valueOf(date))
        .stream()
        .findFirst()
        .orElse(null);
  }

  /** Advances/declines/unchanged/total for one EQ session (0 total ⇒ no session). */
  public record AdCounts(int advances, int declines, int unchanged, int total) {}

  public AdCounts advanceDecline(LocalDate date) {
    return jdbc.queryForObject(
        "SELECT count(*) FILTER (WHERE close_price > prev_close) AS adv,"
            + " count(*) FILTER (WHERE close_price < prev_close) AS dec,"
            + " count(*) FILTER (WHERE close_price = prev_close) AS unch,"
            + " count(*) AS total"
            + " FROM nse_eod_bhavcopy WHERE trade_date = ? AND series = 'EQ' AND prev_close IS NOT NULL",
        (rs, n) -> new AdCounts(rs.getInt("adv"), rs.getInt("dec"), rs.getInt("unch"), rs.getInt("total")),
        java.sql.Date.valueOf(date));
  }

  /** One session's advance/decline counts (for the breadth-thrust moving average). */
  public record AdSession(LocalDate tradeDate, int advances, int declines) {}

  /** The last {@code sessions} EQ sessions on/before {@code date}, newest first. */
  public List<AdSession> advDecSeries(LocalDate date, int sessions) {
    return jdbc.query(
        "SELECT trade_date,"
            + " count(*) FILTER (WHERE close_price > prev_close) AS adv,"
            + " count(*) FILTER (WHERE close_price < prev_close) AS dec"
            + " FROM nse_eod_bhavcopy"
            + " WHERE series = 'EQ' AND prev_close IS NOT NULL AND trade_date <= ?"
            + " GROUP BY trade_date ORDER BY trade_date DESC LIMIT ?",
        (rs, n) -> new AdSession(rs.getObject("trade_date", LocalDate.class), rs.getInt("adv"), rs.getInt("dec")),
        java.sql.Date.valueOf(date),
        sessions);
  }

  /**
   * NEW fold (§6.1.3): universe-wide count of EQ symbols whose latest close on/before {@code date} is
   * above their 20- and 50-session simple moving average. Only symbols with a full window count into
   * the respective universe (n20 &ge; 20 / n50 &ge; 50). The 110-calendar-day lower bound comfortably
   * covers 50 trading sessions and chunk-prunes the scan.
   */
  public record AboveMaCounts(int universe20, int above20, int universe50, int above50) {}

  public AboveMaCounts aboveMa(LocalDate date) {
    return jdbc.queryForObject(
        "WITH windowed AS ("
            + "  SELECT symbol, close_price,"
            + "         ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY trade_date DESC) AS rn"
            + "  FROM nse_eod_bhavcopy"
            + "  WHERE series = 'EQ' AND trade_date <= ? AND trade_date > (?::date - INTERVAL '110 days')),"
            + " per_symbol AS ("
            + "  SELECT symbol,"
            + "         max(close_price) FILTER (WHERE rn = 1) AS last_close,"
            + "         avg(close_price) FILTER (WHERE rn <= 20) AS ma20,"
            + "         avg(close_price) FILTER (WHERE rn <= 50) AS ma50,"
            + "         count(*) FILTER (WHERE rn <= 20) AS n20,"
            + "         count(*) FILTER (WHERE rn <= 50) AS n50"
            + "  FROM windowed GROUP BY symbol)"
            + " SELECT"
            + "   count(*) FILTER (WHERE n20 >= 20) AS universe20,"
            + "   count(*) FILTER (WHERE n20 >= 20 AND last_close > ma20) AS above20,"
            + "   count(*) FILTER (WHERE n50 >= 50) AS universe50,"
            + "   count(*) FILTER (WHERE n50 >= 50 AND last_close > ma50) AS above50"
            + " FROM per_symbol",
        (rs, n) ->
            new AboveMaCounts(
                rs.getInt("universe20"), rs.getInt("above20"), rs.getInt("universe50"), rs.getInt("above50")),
        java.sql.Date.valueOf(date),
        java.sql.Date.valueOf(date));
  }

  /**
   * NEW fold (§6.1.3): universe-wide delivery-% z-score outliers. Per symbol, z = (latest deliv_per −
   * mean of the trailing 20 PRIOR sessions) / their sample stddev; returns the symbols at/above
   * {@code minZ}, largest z first. The baseline excludes the observation session (rn 2..21) so z
   * answers "is today's delivery unusual vs its own recent history". Bhavcopy has real depth, so no
   * forward-only confidence cap applies here.
   */
  public record DeliveryZRow(String symbol, BigDecimal deliveryPct, BigDecimal mean20, BigDecimal z) {}

  public List<DeliveryZRow> deliveryZ(LocalDate date, BigDecimal minZ, int limit) {
    return jdbc.query(
        "WITH windowed AS ("
            + "  SELECT symbol, deliv_per,"
            + "         ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY trade_date DESC) AS rn"
            + "  FROM nse_eod_bhavcopy"
            + "  WHERE series = 'EQ' AND deliv_per IS NOT NULL"
            + "    AND trade_date <= ? AND trade_date > (?::date - INTERVAL '45 days')),"
            + " stats AS ("
            + "  SELECT symbol,"
            + "         max(deliv_per) FILTER (WHERE rn = 1) AS latest,"
            + "         avg(deliv_per) FILTER (WHERE rn BETWEEN 2 AND 21) AS mean20,"
            + "         stddev_samp(deliv_per) FILTER (WHERE rn BETWEEN 2 AND 21) AS sd20,"
            + "         count(*) FILTER (WHERE rn BETWEEN 2 AND 21) AS n"
            + "  FROM windowed GROUP BY symbol)"
            + " SELECT symbol, latest,"
            + "        round(mean20, 2) AS mean20,"
            + "        round(((latest - mean20) / sd20)::numeric, 2) AS z"
            + " FROM stats"
            + " WHERE n >= 20 AND sd20 > 0 AND latest IS NOT NULL"
            + "   AND (latest - mean20) / sd20 >= ?"
            + " ORDER BY z DESC LIMIT ?",
        (rs, n) ->
            new DeliveryZRow(
                rs.getString("symbol"),
                rs.getBigDecimal("latest"),
                rs.getBigDecimal("mean20"),
                rs.getBigDecimal("z")),
        java.sql.Date.valueOf(date),
        java.sql.Date.valueOf(date),
        minZ,
        limit);
  }

  /**
   * NEW fold (§6.1.3): the per-symbol close/prev-close for ONE session, parameterized by {@code date}
   * (the existing sector fold hardwires the latest session). The sector-rotation service aggregates
   * this per sector for {@code date} and the prior session to compute day-over-day rank movement.
   */
  public record SessionChange(String symbol, BigDecimal close, BigDecimal prevClose) {}

  public List<SessionChange> sectorSessionChange(LocalDate date) {
    return jdbc.query(
        "SELECT symbol, close_price, prev_close FROM nse_eod_bhavcopy"
            + " WHERE trade_date = ? AND series = 'EQ' AND prev_close > 0",
        (rs, n) ->
            new SessionChange(
                rs.getString("symbol"), rs.getBigDecimal("close_price"), rs.getBigDecimal("prev_close")),
        java.sql.Date.valueOf(date));
  }

  /** One symbol's close now (rn 1) and one prior window (rn k) for a return calculation. */
  public record ReturnBase(String symbol, BigDecimal c0, BigDecimal cPrior) {}

  /**
   * Per-symbol close at rn 1 and rn {@code windowRn} on/before {@code date} (e.g. rn 6 ≈ 1-week). The
   * 45-calendar-day bound covers up to ~22 sessions; callers pass windowRn ≤ 22.
   */
  public List<ReturnBase> returnBases(LocalDate date, int windowRn) {
    return jdbc.query(
        "WITH ranked AS ("
            + "  SELECT symbol, close_price,"
            + "         ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY trade_date DESC) AS rn"
            + "  FROM nse_eod_bhavcopy"
            + "  WHERE series = 'EQ' AND trade_date <= ? AND trade_date > (?::date - INTERVAL '45 days'))"
            + " SELECT symbol,"
            + "        max(close_price) FILTER (WHERE rn = 1) AS c0,"
            + "        max(close_price) FILTER (WHERE rn = ?) AS c_prior"
            + " FROM ranked WHERE rn IN (1, ?) GROUP BY symbol"
            + " HAVING max(close_price) FILTER (WHERE rn = 1) IS NOT NULL"
            + "    AND max(close_price) FILTER (WHERE rn = ?) IS NOT NULL",
        (rs, n) -> new ReturnBase(rs.getString("symbol"), rs.getBigDecimal("c0"), rs.getBigDecimal("c_prior")),
        java.sql.Date.valueOf(date),
        java.sql.Date.valueOf(date),
        windowRn,
        windowRn,
        windowRn);
  }

  /** Per-symbol close/prev-close for a set of index members on one session (concentration fold). */
  public List<SessionChange> indexMemberChange(LocalDate date, List<String> members) {
    if (members.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(members.size(), "?"));
    List<Object> args = new java.util.ArrayList<>();
    args.add(java.sql.Date.valueOf(date));
    args.addAll(members);
    return jdbc.query(
        "SELECT symbol, close_price, prev_close FROM nse_eod_bhavcopy"
            + " WHERE trade_date = ? AND series = 'EQ' AND prev_close > 0 AND symbol IN ("
            + placeholders
            + ")",
        (rs, n) ->
            new SessionChange(
                rs.getString("symbol"), rs.getBigDecimal("close_price"), rs.getBigDecimal("prev_close")),
        args.toArray());
  }
}
