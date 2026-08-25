package in.arthayantra.marketdata.context;

import in.arthayantra.marketdata.equitydaily.AdjustedEquityDailySql;
import in.arthayantra.marketdata.equitydaily.CashEquityUniverse;
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
 * <p>⚠️ Every fold here reads the EQ+BE cash universe ({@link CashEquityUniverse}) — H24 PR-4,
 * which converted all NINE reads in one change. They move together on purpose: two of them
 * ({@link #latestSession}, {@link #priorSession}) are {@code max(trade_date)} PINS for the other
 * seven, and <b>widening a population without widening its pin is the mixed-watermark defect</b>
 * H24 names at {@code EquityBreadthEodJob:82} + {@code DataQualityEodJob:103}. Splitting this file
 * across PRs would have created that defect for the duration.
 *
 * <p>⚠️ <b>Every PRICE fold here reads the CORPORATE-ACTION-ADJUSTED plane</b>, through {@link
 * AdjustedEquityDailySql#factorLateral} — the SINGLE definition of that rule (audit H6 / ledger
 * §9-02), CALLED, never pasted — hand-rolling the cumulative-ratio product here is the drift this
 * seam exists to prevent. On raw closes a split or bonus ex-date collapsed the name against its prior
 * close, so the digest read the ex-date as a crash and the name stayed below its own MA for the next
 * 20/50 sessions. The factor is 1 for every symbol with no action after the bar — almost the whole
 * universe — so this is inert there.
 *
 * <p>They move TOGETHER for the same reason the H24 series sweep did: {@link EquityDigestService}
 * assembles {@link #advanceDecline}, {@link #aboveMa}, {@link #advDecSeries}, {@link
 * #sectorSessionChange}, {@link #indexMemberChange} and {@link #returnBases} into adjacent fields of
 * ONE response record. Adjusting some and not others would put two price planes in one payload with
 * nothing on the wire saying which is which — strictly worse for a reader than uniform-raw was.
 * <b>Do not adjust or un-adjust one of them alone.</b>
 *
 * <p><b>Raw ON PURPOSE, not missed:</b> {@code deliv_per} ({@link #deliveryZ}) is a percentage, not a
 * price. Volume and rupee turnover are read nowhere in this class, and where they ARE read they stay
 * raw for the reason {@link AdjustedEquityDailySql}'s javadoc gives — a split multiplies the share
 * count, so the tape is already on the post-split basis and adjusting it would double-count. The
 * {@code max(trade_date)} pins ({@link #latestSession}, {@link #priorSession}) are dates, not prices.
 *
 * <p>⚠️ The exchange's {@code prev_close} COLUMN is NOT on the adjusted basis and is no longer read
 * by any fold here — measured 2026-08-25 on the live DB, it equalled the prior session's RAW close on
 * all 21 ex-dates in 2026-06-01..2026-08-25 that carry a bhavcopy row (e.g. CORDELIA 2026-08-25,
 * ratio 0.10: {@code prev_close} 1043.60 against an ex-date close of 104.45). The prior close is a
 * rank-2 / {@code lag()} row on the adjusted plane instead, so both operands share one basis. That
 * swap is inert off the ex-date: {@code lag(close_price)} equalled {@code prev_close} on 103068 of
 * 103068 EQ+BE rows over 2026-07-01..2026-08-24. Its one cost is that a symbol with NO prior bar in
 * the window gets no advance/decline verdict — see {@link #PRIOR_BAR_LOOKBACK_DAYS}. Both figures are
 * re-derivable from the live DB, not constants: {@code nse_eod_bhavcopy} is retro-mutable.
 *
 * <p>Idioms match {@code nse.analytics}: plain {@link JdbcTemplate}, positional {@code ?} params,
 * {@code CashEquityUniverse.SERIES_PREDICATE}, {@code java.sql.Date.valueOf} binds, IST-safe {@code (col AT TIME ZONE
 * 'Asia/Kolkata')::date} casts (never a bare {@code ::date}). Every windowed fold carries a lower
 * {@code trade_date} bound so the {@code ROW_NUMBER} scan is chunk-pruned, not full-table.
 */
@Repository
public class EquityContextRepository {

  /**
   * Calendar days scanned back from the read date so every symbol trading on that date also has its
   * PRIOR bar in the window. The prior close is now a rank-2 row on the CA-adjusted plane, not the
   * exchange's {@code prev_close} column, so it has to be scanned for rather than read off the row.
   *
   * <p>45 matches the bound {@link #deliveryZ} and {@link #returnBases} already used, and comfortably
   * covers one session's gap. A symbol suspended LONGER than this loses its prior bar and therefore
   * its verdict — measured 2026-08-25 on the live DB for session 2026-08-24: 2 of 2872 EQ+BE symbols
   * had no prior bar in 45 days, and BOTH ({@code HORIZONIND}, {@code LALITHAA}) were listings whose
   * first-ever bar was that session. Re-derivable, not a constant.
   */
  private static final String PRIOR_BAR_LOOKBACK_DAYS = "45";

  private final JdbcTemplate jdbc;

  public EquityContextRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The latest cash-equity (EQ+BE) bhavcopy session (null when the table is empty). */
  public LocalDate latestSession() {
    return jdbc.queryForObject(
        "SELECT max(trade_date) FROM nse_eod_bhavcopy WHERE " + CashEquityUniverse.SERIES_PREDICATE,
        LocalDate.class);
  }

  /** The cash-equity session strictly before {@code date} that has data (null when none). */
  public LocalDate priorSession(LocalDate date) {
    return jdbc.query(
            "SELECT max(trade_date) FROM nse_eod_bhavcopy WHERE "
                + CashEquityUniverse.SERIES_PREDICATE
                + " AND trade_date < ?",
            (rs, n) -> rs.getObject(1, LocalDate.class),
            java.sql.Date.valueOf(date))
        .stream()
        .findFirst()
        .orElse(null);
  }

  /** Advances/declines/unchanged/total for one cash-equity session (0 total ⇒ no session). */
  public record AdCounts(int advances, int declines, int unchanged, int total) {}

  public AdCounts advanceDecline(LocalDate date) {
    return jdbc.queryForObject(
        "WITH windowed AS ("
            + "  SELECT b.symbol, b.trade_date,"
            + "         round(b.close_price * caf.factor, 4) AS close_adj,"
            + "         ROW_NUMBER() OVER (PARTITION BY b.symbol ORDER BY b.trade_date DESC) AS rn"
            + "  FROM nse_eod_bhavcopy b "
            + AdjustedEquityDailySql.factorLateral("b", "trade_date")
            + "  WHERE " + CashEquityUniverse.qualified("b")
            + "    AND b.trade_date <= ? AND b.trade_date > (?::date - INTERVAL '"
            + PRIOR_BAR_LOOKBACK_DAYS
            + " days')),"
            + " per_symbol AS ("
            + "  SELECT symbol,"
            + "         max(trade_date) FILTER (WHERE rn = 1) AS last_date,"
            + "         max(close_adj) FILTER (WHERE rn = 1) AS c0,"
            + "         max(close_adj) FILTER (WHERE rn = 2) AS c1"
            + "  FROM windowed GROUP BY symbol)"
            + " SELECT count(*) FILTER (WHERE c0 > c1) AS adv,"
            + "        count(*) FILTER (WHERE c0 < c1) AS dec,"
            + "        count(*) FILTER (WHERE c0 = c1) AS unch,"
            + "        count(*) AS total"
            + " FROM per_symbol WHERE last_date = ?::date",
        (rs, n) -> new AdCounts(rs.getInt("adv"), rs.getInt("dec"), rs.getInt("unch"), rs.getInt("total")),
        java.sql.Date.valueOf(date),
        java.sql.Date.valueOf(date),
        java.sql.Date.valueOf(date));
  }

  /** One session's advance/decline counts (for the breadth-thrust moving average). */
  public record AdSession(LocalDate tradeDate, int advances, int declines) {}

  /** The last {@code sessions} cash-equity sessions on/before {@code date}, newest first. */
  public List<AdSession> advDecSeries(LocalDate date, int sessions) {
    return jdbc.query(
        "WITH adj AS ("
            + "  SELECT b.symbol, b.trade_date,"
            + "         round(b.close_price * caf.factor, 4) AS close_adj"
            + "  FROM nse_eod_bhavcopy b "
            + AdjustedEquityDailySql.factorLateral("b", "trade_date")
            + "  WHERE " + CashEquityUniverse.qualified("b")
            + "    AND b.trade_date <= ?"
            + "    AND b.trade_date > (?::date - INTERVAL '1 day' * ?)),"
            + " lagged AS ("
            + "  SELECT trade_date, close_adj,"
            + "         lag(close_adj) OVER (PARTITION BY symbol ORDER BY trade_date) AS prev_adj"
            + "  FROM adj)"
            + " SELECT trade_date,"
            + "        count(*) FILTER (WHERE close_adj > prev_adj) AS adv,"
            + "        count(*) FILTER (WHERE close_adj < prev_adj) AS dec"
            + " FROM lagged"
            + " GROUP BY trade_date ORDER BY trade_date DESC LIMIT ?",
        (rs, n) -> new AdSession(rs.getObject("trade_date", LocalDate.class), rs.getInt("adv"), rs.getInt("dec")),
        java.sql.Date.valueOf(date),
        java.sql.Date.valueOf(date),
        seriesLookbackDays(sessions),
        sessions);
  }

  /**
   * Calendar days to scan so a {@code sessions}-long series has a prior bar for EVERY emitted date,
   * including the oldest one — the {@code lag()} of the oldest emitted session comes from a bar
   * OUTSIDE the emitted range, so the scan must be strictly wider than the output.
   *
   * <p>{@code 3x + 20}: a trading session costs ~1.4 calendar days, so 3x is already ~2x headroom
   * for weekends, and the flat +20 absorbs a holiday cluster at small {@code sessions}. Callers pass
   * {@code sessions} &le; 22 (the digest's breadth-thrust window defaults to 10). This also replaces
   * an UNBOUNDED scan — the previous form carried no lower {@code trade_date} bound at all, against
   * this class's own stated idiom.
   */
  private static int seriesLookbackDays(int sessions) {
    return sessions * 3 + 20;
  }

  /**
   * NEW fold (§6.1.3): universe-wide count of EQ symbols whose latest close on/before {@code date} is
   * above their 20- and 50-session simple moving average. Only symbols with a full window count into
   * the respective universe (n20 &ge; 20 / n50 &ge; 50). The 110-calendar-day lower bound comfortably
   * covers 50 trading sessions and chunk-prunes the scan.
   *
   * <p>⚠️ Closes are CORPORATE-ACTION-ADJUSTED via {@link AdjustedEquityDailySql#factorLateral} — the
   * SINGLE definition of that rule (ledger §9-02), CALLED rather than pasted. On raw bhavcopy closes a
   * split or bonus inside the window collapses the post-ex bars against the pre-ex ones, so the name
   * reads below its own MA for the next 20/50 sessions and is counted out of {@code above20}/{@code
   * above50} — a data artifact, not breadth. Same defect class as audit H6 / §9-02. The factor is 1 for
   * every symbol with no action after the bar, i.e. almost the whole universe, so this is inert there.
   *
   * <p>Every other price fold in this class reads the same adjusted plane — see the class javadoc for
   * why they move together and for what stays raw on purpose.
   */
  public record AboveMaCounts(int universe20, int above20, int universe50, int above50) {}

  public AboveMaCounts aboveMa(LocalDate date) {
    return jdbc.queryForObject(
        "WITH windowed AS ("
            + "  SELECT b.symbol, round(b.close_price * caf.factor, 4) AS close_price,"
            + "         ROW_NUMBER() OVER (PARTITION BY b.symbol ORDER BY b.trade_date DESC) AS rn"
            + "  FROM nse_eod_bhavcopy b "
            + AdjustedEquityDailySql.factorLateral("b", "trade_date")
            + "  WHERE " + CashEquityUniverse.qualified("b")
            + "    AND b.trade_date <= ? AND b.trade_date > (?::date - INTERVAL '110 days')),"
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
            + "  WHERE " + CashEquityUniverse.SERIES_PREDICATE + " AND deliv_per IS NOT NULL"
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
        sessionChangeSql(""),
        (rs, n) ->
            new SessionChange(
                rs.getString("symbol"), rs.getBigDecimal("close_price"), rs.getBigDecimal("prev_close")),
        java.sql.Date.valueOf(date),
        java.sql.Date.valueOf(date),
        java.sql.Date.valueOf(date));
  }

  /**
   * The shared per-symbol close/prior-close read for ONE session on the CA-adjusted plane, used by
   * BOTH {@link #sectorSessionChange} and {@link #indexMemberChange} so the two cannot drift apart.
   * {@code memberFilter} is an optional extra predicate inside the windowed CTE (the index-member
   * {@code IN} list); empty for the sector read.
   *
   * <p>Binds, in order: {@code date} (upper bound), {@code date} (lookback base), any binds inside
   * {@code memberFilter}, then {@code date} again (the emitted-session pin). A caller that gets that
   * order wrong does not fail loudly — it reads the wrong session — so both callers bind against
   * this one comment.
   *
   * <p>{@code c1 > 0} preserves the old {@code prev_close > 0} guard: a symbol with no prior bar in
   * the window has a NULL prior close, fails the predicate and is dropped, exactly as a NULL or zero
   * {@code prev_close} was dropped before.
   */
  private static String sessionChangeSql(String memberFilter) {
    return "WITH windowed AS ("
        + "  SELECT b.symbol, b.trade_date,"
        + "         round(b.close_price * caf.factor, 4) AS close_adj,"
        + "         ROW_NUMBER() OVER (PARTITION BY b.symbol ORDER BY b.trade_date DESC) AS rn"
        + "  FROM nse_eod_bhavcopy b "
        + AdjustedEquityDailySql.factorLateral("b", "trade_date")
        + "  WHERE " + CashEquityUniverse.qualified("b")
        + "    AND b.trade_date <= ? AND b.trade_date > (?::date - INTERVAL '"
        + PRIOR_BAR_LOOKBACK_DAYS
        + " days')"
        + memberFilter
        + "),"
        + " per_symbol AS ("
        + "  SELECT symbol,"
        + "         max(trade_date) FILTER (WHERE rn = 1) AS last_date,"
        + "         max(close_adj) FILTER (WHERE rn = 1) AS c0,"
        + "         max(close_adj) FILTER (WHERE rn = 2) AS c1"
        + "  FROM windowed GROUP BY symbol)"
        + " SELECT symbol, c0 AS close_price, c1 AS prev_close"
        + " FROM per_symbol WHERE last_date = ?::date AND c1 > 0";
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
            + "  SELECT b.symbol, round(b.close_price * caf.factor, 4) AS close_price,"
            + "         ROW_NUMBER() OVER (PARTITION BY b.symbol ORDER BY b.trade_date DESC) AS rn"
            + "  FROM nse_eod_bhavcopy b "
            + AdjustedEquityDailySql.factorLateral("b", "trade_date")
            + "  WHERE " + CashEquityUniverse.qualified("b")
            + "    AND b.trade_date <= ? AND b.trade_date > (?::date - INTERVAL '45 days'))"
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
    args.add(java.sql.Date.valueOf(date)); // upper bound
    args.add(java.sql.Date.valueOf(date)); // lookback base
    args.addAll(members); // the memberFilter binds sit INSIDE the windowed CTE
    args.add(java.sql.Date.valueOf(date)); // emitted-session pin
    return jdbc.query(
        sessionChangeSql("    AND b.symbol IN (" + placeholders + ")"),
        (rs, n) ->
            new SessionChange(
                rs.getString("symbol"), rs.getBigDecimal("close_price"), rs.getBigDecimal("prev_close")),
        args.toArray());
  }
}
