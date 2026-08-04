package in.arthayantra.marketdata.lineage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Derives predecessor→successor ticker-change links from the EOD tape and upserts them into {@code
 * symbol_lineage}. The seed and the ongoing refresh are the SAME code path — there is no
 * hand-transcribed pair list anywhere in this change.
 *
 * <h2>The rule</h2>
 *
 * A pair {@code (P, S)} on NSE is recorded iff, inside the trailing {@code windowDays} EQ/BE
 * bhavcopy window ending at the table's latest trade date:
 *
 * <ol>
 *   <li><b>Succession.</b> {@code P}'s last bar sits at trading-day index {@code d_P}, {@code S}'s
 *       first at {@code d_S}, with {@code 1 <= d_S - d_P <= maxGapSessions}.
 *   <li><b>Price continuity.</b> {@code S}'s first-bar {@code prev_close} equals {@code P}'s
 *       last-bar {@code close_price} EXACTLY at the stored 4dp. NSE carries the series across a
 *       symbol change, so the successor's day-1 {@code prev_close} is the predecessor's day-0
 *       close. A genuine new listing also carries a non-zero {@code prev_close} (the issue price),
 *       so non-nullness proves nothing — only the match against a symbol that JUST STOPPED does.
 *   <li><b>Real boundaries.</b> {@code S}'s first bar is strictly after the table's earliest trade
 *       date (a series that merely begins at the data floor is an ingestion artifact, not a new
 *       listing) and {@code P}'s last bar is strictly before the latest (P really stopped).
 *   <li><b>1:1 only.</b> {@code S} matches exactly one {@code P} and {@code P} exactly one {@code
 *       S} in the window. Ambiguity is DROPPED, never guessed.
 * </ol>
 *
 * <p>Confidence comes from a signal that is completely independent of the NSE price rule: BSE's
 * {@code scrip_code} is a stable per-listing identifier, so a BSE rename is directly observable as
 * ONE {@code scrip_code} carrying BOTH tickers. Agreement ⇒ {@code confirmed}; price continuity
 * alone ⇒ {@code inferred}. Any {@code symbol_rename_events} row for the successor near the switch
 * (the primary-source NSE "Change in Name" feed) is appended to the evidence string.
 *
 * <h2>Which way it is biased, and why</h2>
 *
 * <p><b>A wrong pair merges two unrelated companies' price histories</b> into an owner-facing
 * screen; a missed pair merely reproduces today's behaviour. So every ambiguity resolves to DROP,
 * and the rule is deliberately tuned toward FALSE NEGATIVES.
 *
 * <p><b>False-positive directions</b> (what could wrongly pair):
 *
 * <ul>
 *   <li><b>Coincidental price collision</b> — two unrelated symbols sharing an exact 4dp boundary
 *       price within the gap cap. Concentrated at penny prices; a measured placebo (below) puts the
 *       rate near zero at gap 1 and the gap cap is what holds it there.
 *   <li><b>A demerger or amalgamation the exchange carried the series through.</b> This is the
 *       irreducible one: {@code TATAMOTORS→TMPV} is a demerger, {@code CREATIVE→CNL} and {@code
 *       WORTH→WORTHPERI} are amalgamations into new legal entities, and NSE's {@code prev_close},
 *       BSE's {@code scrip_code} and (for the demerger) the ISIN all say "continue". NO signal
 *       available to this platform separates them from a pure rename. The escape hatch is data, not
 *       code: {@code status = 'WITHHELD'} on the pair, which this detector never overwrites.
 * </ul>
 *
 * <p><b>False-negative directions</b> (what it will miss, accepted):
 *
 * <ul>
 *   <li>A switch across a suspension longer than {@code maxGapSessions}. {@code PEL→PIRAMALFIN} sat
 *       at gap 31 and is missed by construction — widening the cap admits penny collisions.
 *   <li>Ambiguous matches, dropped by rule 4.
 *   <li>A rename whose two tickers TRADE CONCURRENTLY — structurally invisible to a
 *       "stopped, then started" rule.
 *   <li>Anything before the bhavcopy floor (2025-06-20); that history does not exist here.
 *   <li>Series outside EQ/BE (SME/Emerge boards) — outside the screened universe anyway.
 *   <li>A rename NSE did not carry {@code prev_close} through. None observed; cannot be excluded.
 *   <li>A subject that announces a name change AND a face-value split in one line: {@link
 *       in.arthayantra.marketdata.bhavcopy.CorporateActionSubjectParser} claims it as a split first,
 *       so the rename-event capture never sees it. The price rule still catches the pair.
 * </ul>
 *
 * <h2>Measured, 2026-08-03, live {@code artha}</h2>
 *
 * <p>66 pairs over the 276 sessions that exist; 65 at gap 1, one at gap 4; zero ambiguity (every
 * match 1:1). Placebo by gap band — 65 matches at gap 1, 1 across gaps 2–5, 2 across 6–40, 2 across
 * 41–200 — i.e. ~5000× concentration per session-of-gap at the rule core, so it is not matching
 * price noise. Independent BSE cross-check: 58 of the 60 BSE-checkable pairs confirmed, and running
 * the BSE detector on its own found 58 of 58 transitions the NSE rule was eligible to see, missing
 * none.
 *
 * <h2>Cost</h2>
 *
 * <p>Bounded aggregates only — {@code DISTINCT ON} for the per-symbol first/last bar, a hash
 * {@code DISTINCT} for the BSE ticker map, both pruned to the same trailing window. No per-session
 * join across the hypertable: the wide-join form OOM'd this DB during the investigation, and the DB
 * is memory-constrained.
 */
@Service
public class SymbolLineageDetector {

  private static final Logger log = LoggerFactory.getLogger(SymbolLineageDetector.class);
  private static final String EXCHANGE = "NSE";
  private static final String SOURCE = "nse-price-continuity";

  /** One detection pass: what the rule found and what it changed. */
  public record DetectionResult(
      LocalDate asOf, int detected, int inserted, int refreshed, int confirmed, int inferred) {}

  /**
   * The rule, verbatim.
   *
   * <p>{@code cal} numbers the trading sessions so "next session" is expressible without a calendar
   * table. {@code m} is the candidate match set and {@code uniq} enforces 1:1 with window counts
   * rather than correlated sub-selects (one pass, and it cannot re-scan an inlined CTE). The BSE
   * map is a bounded {@code DISTINCT} over the SAME window, so it prunes to the same chunks.
   *
   * <p>Binds, in order: {@code windowDays}, {@code windowDays}, {@code maxGapSessions}.
   */
  private static final String DETECT_SQL =
      """
      WITH cal AS (
        SELECT trade_date, row_number() OVER (ORDER BY trade_date) AS dn
        FROM (SELECT DISTINCT trade_date FROM nse_eod_bhavcopy WHERE series IN ('EQ','BE')) d
      ),
      bounds AS (SELECT min(trade_date) AS floor_d, max(trade_date) AS latest_d FROM cal),
      w AS (
        SELECT b.symbol, b.trade_date, b.prev_close, b.close_price, c.dn
        FROM nse_eod_bhavcopy b
        JOIN cal c USING (trade_date)
        WHERE b.series IN ('EQ','BE')
          AND b.trade_date > (SELECT latest_d FROM bounds) - ?::int
      ),
      firstrow AS (
        SELECT DISTINCT ON (symbol) symbol, trade_date AS first_d, dn AS first_dn, prev_close AS pc
        FROM w ORDER BY symbol, trade_date ASC
      ),
      lastrow AS (
        SELECT DISTINCT ON (symbol) symbol, trade_date AS last_d, dn AS last_dn, close_price AS lc
        FROM w ORDER BY symbol, trade_date DESC
      ),
      m AS (
        SELECT p.symbol AS predecessor, s.symbol AS successor, s.first_d AS switch_date,
               s.first_dn - p.last_dn AS gap_sessions, p.lc AS boundary_price
        FROM firstrow s
        JOIN lastrow p ON p.last_dn < s.first_dn
                      AND s.first_dn - p.last_dn <= ?::int
                      AND s.pc = p.lc
        WHERE s.first_d > (SELECT floor_d FROM bounds)
          AND p.last_d  < (SELECT latest_d FROM bounds)
      ),
      uniq AS (
        SELECT m.*,
               count(*) OVER (PARTITION BY successor)   AS succ_n,
               count(*) OVER (PARTITION BY predecessor) AS pred_n
        FROM m
      ),
      bse AS (
        SELECT DISTINCT scrip_code, ticker
        FROM bse_eod_bhavcopy
        WHERE ticker IS NOT NULL
          AND trade_date > (SELECT latest_d FROM bounds) - ?::int
      )
      SELECT u.predecessor, u.successor, u.switch_date, u.gap_sessions, u.boundary_price,
             EXISTS (SELECT 1 FROM bse b1 JOIN bse b2 USING (scrip_code)
                     WHERE b1.ticker = u.predecessor AND b2.ticker = u.successor) AS bse_confirmed,
             EXISTS (SELECT 1 FROM symbol_rename_events e
                     WHERE e.exchange = 'NSE' AND e.symbol = u.successor
                       AND e.ex_date BETWEEN u.switch_date - 30 AND u.switch_date + 30) AS ca_confirmed
      FROM uniq u
      WHERE u.succ_n = 1 AND u.pred_n = 1
      ORDER BY u.switch_date, u.successor
      """;

  private record Match(
      String predecessor,
      String successor,
      LocalDate switchDate,
      int gapSessions,
      BigDecimal boundaryPrice,
      boolean bseConfirmed,
      boolean caConfirmed) {}

  private final JdbcTemplate jdbc;
  private final SymbolLineageRepository repo;
  private final int windowDays;
  private final int maxGapSessions;

  /**
   * @param windowDays the trailing window the rule looks back over — the SAME 420 days the screener
   *     base CTE bounds itself to, so a pair the screens could use is a pair this can see.
   * @param maxGapSessions the succession gap cap. 5 is the measured precision/recall knee: 65 of 66
   *     pairs land at exactly 1, and the two matches in the 6–40 band are penny-price collisions.
   */
  public SymbolLineageDetector(
      JdbcTemplate jdbc,
      SymbolLineageRepository repo,
      @Value("${artha.symbol-lineage.window-days:420}") int windowDays,
      @Value("${artha.symbol-lineage.max-gap-sessions:5}") int maxGapSessions) {
    this.jdbc = jdbc;
    this.repo = repo;
    this.windowDays = windowDays;
    this.maxGapSessions = maxGapSessions;
  }

  /** Runs the rule and upserts every match. Existing {@code status} verdicts are preserved. */
  public DetectionResult detect() {
    List<Match> matches =
        jdbc.query(
            DETECT_SQL,
            (rs, n) ->
                new Match(
                    rs.getString("predecessor"),
                    rs.getString("successor"),
                    rs.getObject("switch_date", LocalDate.class),
                    rs.getInt("gap_sessions"),
                    rs.getBigDecimal("boundary_price"),
                    rs.getBoolean("bse_confirmed"),
                    rs.getBoolean("ca_confirmed")),
            windowDays,
            maxGapSessions,
            windowDays);

    int inserted = 0;
    int confirmed = 0;
    for (Match m : matches) {
      String confidence = m.bseConfirmed() ? "confirmed" : "inferred";
      if (m.bseConfirmed()) {
        confirmed++;
      }
      if (repo.upsertDetected(
          EXCHANGE,
          m.predecessor(),
          m.successor(),
          m.switchDate(),
          m.gapSessions(),
          m.boundaryPrice(),
          confidence,
          evidence(m),
          SOURCE)) {
        inserted++;
      }
    }
    LocalDate asOf =
        jdbc.queryForObject(
            "SELECT max(trade_date) FROM nse_eod_bhavcopy WHERE series IN ('EQ','BE')",
            LocalDate.class);
    DetectionResult result =
        new DetectionResult(
            asOf,
            matches.size(),
            inserted,
            matches.size() - inserted,
            confirmed,
            matches.size() - confirmed);
    log.info(
        "symbol-lineage detect asOf={}: {} pairs ({} confirmed / {} inferred), {} new, {} refreshed",
        asOf, result.detected(), confirmed, result.inferred(), inserted, result.refreshed());
    return result;
  }

  private static String evidence(Match m) {
    StringBuilder sb = new StringBuilder();
    sb.append("nse prev_close continuity at ")
        .append(m.boundaryPrice())
        .append(", gap ")
        .append(m.gapSessions())
        .append(" session(s)");
    if (m.bseConfirmed()) {
      sb.append("; bse scrip_code carried both tickers");
    }
    if (m.caConfirmed()) {
      sb.append("; nse change-in-name corporate action within 30d");
    }
    return sb.toString();
  }
}
