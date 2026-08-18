package in.arthayantra.marketdata.lineage;

import java.math.BigDecimal;
import in.arthayantra.marketdata.equitydaily.CashEquityUniverse;
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
 *   <li><b>Real boundaries.</b> {@code S}'s first bar is strictly after the clipped window floor
 *       (a series that merely begins at the data floor is an ingestion artifact, not a new listing)
 *       and {@code P}'s last bar is strictly before the latest (P really stopped).
 *   <li><b>1:1 only.</b> {@code S} matches exactly one {@code P} and {@code P} exactly one {@code
 *       S} in the window. Ambiguity is DROPPED, never guessed.
 * </ol>
 *
 * <h2>Corroboration, and the refutation tier</h2>
 *
 * <p>Confidence comes from a signal completely independent of the NSE price rule: BSE's {@code
 * scrip_code} is a stable per-listing identifier, so a BSE rename is directly observable as ONE
 * scrip_code carrying BOTH tickers. Three outcomes, and the third is the one that matters:
 *
 * <ul>
 *   <li>{@code confirmed} — one BSE scrip carried both tickers. Inserted {@code ACTIVE}.
 *   <li>{@code inferred} — at least one ticker is absent from BSE or has conflicting/missing ISIN
 *       evidence, so there is nothing safe to refute. Inserted {@code ACTIVE} on price continuity
 *       alone.
 *   <li><b>{@code refuted}</b> — BOTH tickers are on BSE with one unambiguous nonblank ISIN each,
 *       and NO scrip_code carried both. BSE is not silent here, it actively CONTRADICTS continuity.
 *       Inserted {@code WITHHELD}. A ticker with conflicting or missing ISINs is treated as silent.
 * </ul>
 *
 * <p><b>Refutation is measured, not assumed.</b> The statistics in this class were measured
 * <b>pre-clip, un-remeasured</b>. Across all 66 pairs on 2026-08-03: 58 confirmed, 6 unlistable, and
 * exactly <b>2 refuted</b> — {@code CREATIVE→CNL} (scrip 539527→544631) and {@code WORTH→WORTHPERI}
 * (538451→544577). Zero false positives, because the tier only fires when both sides are observable.
 * Two further signals agree on those two and on nothing else: the ISIN changes at the boundary, and
 * — decisively — <b>the predecessor never stopped</b>. CREATIVE and WORTH still print on BSE through
 * 2026-08-03; they delisted from NSE while continuing to trade there, which is precisely the
 * concurrent-trading shape listed below as NOT a rename. The NSE-only view cannot see that, which is
 * why the BSE check earns its place.
 *
 * <p>Any {@code symbol_rename_events} row for the successor near the switch (the primary-source NSE
 * "Change in Name" feed) is appended to the evidence string.
 *
 * <p><b>Status is a one-way ratchet.</b> {@link SymbolLineageRepository#upsertDetected} sets {@code
 * status} on INSERT only, and separately demotes {@code ACTIVE → WITHHELD} when a later run refutes
 * a pair. It NEVER promotes. So an owner's WITHHELD verdict is permanent, and a pair that BSE data
 * later contradicts stops being stitched without anyone having to notice.
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
 *       price within the gap cap. Concentrated at penny prices; a measured placebo (pre-clip,
 *       un-remeasured) puts the rate near zero at gap 1 and the gap cap is what holds it there.
 *   <li><b>An amalgamation into a new legal entity.</b> {@code CREATIVE→CNL} and {@code
 *       WORTH→WORTHPERI} carry NSE {@code prev_close} straight through, so the price rule pairs
 *       them — but they are <b>mechanically refutable</b>, and the detector now refutes them (see
 *       the refutation tier below). No owner ruling needed.
 *   <li><b>A demerger the exchange carried through on the SAME listing.</b> This is the irreducible
 *       one. {@code TATAMOTORS→TMPV} keeps its BSE {@code scrip_code} AND its ISIN AND stops
 *       printing on the switch date — every signal this platform has says "same listing, renamed",
 *       and structurally that is true; what changed is the ASSET MIX behind it (the CV arm was spun
 *       out). Nothing in market data can see that. The escape hatch is data, not code: {@code status
 *       = 'WITHHELD'} on the pair, seeded by the migration, which this detector never promotes.
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
 * <p><b>Measured pre-clip, un-remeasured.</b> 66 pairs over the 276 sessions that exist; 65 at gap
 * 1, one at gap 4; zero ambiguity (every match 1:1). Placebo by gap band — 65 matches at gap 1, 1
 * across gaps 2–5, 2 across 6–40, 2 across 41–200 — i.e. ~5000× concentration per session-of-gap
 * at the rule core, so it is not matching price noise. Independent BSE cross-check: 58 of the 60
 * BSE-checkable pairs confirmed, and running the BSE detector on its own found 58 of 58 transitions
 * the NSE rule was eligible to see, missing none.
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
      LocalDate asOf,
      int detected,
      int inserted,
      int refreshed,
      int confirmed,
      int inferred,
      int refuted) {}

  /**
   * The rule, verbatim.
   *
   * <p>{@code cal} numbers the trading sessions so "next session" is expressible without a calendar
   * table. {@code m} is the candidate match set and {@code uniq} enforces 1:1 with window counts
   * rather than correlated sub-selects (one pass, and it cannot re-scan an inlined CTE). The BSE
   * map is a bounded {@code DISTINCT} over the SAME window, so it prunes to the same chunks.
   *
   * <p>Binds, in order: {@code windowDays} for the clipped floor, {@code maxGapSessions}, then
   * {@code windowDays} for the BSE slice.
   */
  private static final String DETECT_SQL =
      """
      WITH cal AS (
        SELECT trade_date, row_number() OVER (ORDER BY trade_date) AS dn
        FROM (SELECT DISTINCT trade_date FROM nse_eod_bhavcopy WHERE %s) d
      ),
      bounds AS (
        SELECT GREATEST(min(trade_date), max(trade_date) - ?::int) AS floor_d,
               max(trade_date) AS latest_d
        FROM cal
      ),
      w AS (
        SELECT b.symbol, b.trade_date, b.prev_close, b.close_price, c.dn
        FROM nse_eod_bhavcopy b
        JOIN cal c USING (trade_date)
        WHERE %s
          AND b.trade_date >= (SELECT floor_d FROM bounds)
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
        SELECT DISTINCT scrip_code, ticker, NULLIF(trim(isin), '') AS isin
        FROM bse_eod_bhavcopy
        WHERE ticker IS NOT NULL
          AND trade_date > (SELECT latest_d FROM bounds) - ?::int
      ),
      bse_unambiguous AS (
        SELECT ticker
        FROM bse
        GROUP BY ticker
        HAVING count(*) FILTER (WHERE isin IS NULL) = 0
           AND count(DISTINCT isin) = 1
      )
      SELECT u.predecessor, u.successor, u.switch_date, u.gap_sessions, u.boundary_price,
             EXISTS (SELECT 1 FROM bse b1 JOIN bse b2 USING (scrip_code)
                     WHERE b1.ticker = u.predecessor AND b2.ticker = u.successor) AS bse_confirmed,
             -- BOTH sides observable on BSE with unambiguous ISINs is what makes a non-match a
             -- REFUTATION rather than silence. Conflicting or missing ISIN evidence is silent.
             (EXISTS (SELECT 1 FROM bse_unambiguous WHERE ticker = u.predecessor)
              AND EXISTS (SELECT 1 FROM bse_unambiguous WHERE ticker = u.successor)) AS both_on_bse,
             EXISTS (SELECT 1 FROM symbol_rename_events e
                     WHERE e.exchange = 'NSE' AND e.symbol = u.successor
                       AND e.ex_date BETWEEN u.switch_date - 30 AND u.switch_date + 30) AS ca_confirmed
      FROM uniq u
      WHERE u.succ_n = 1 AND u.pred_n = 1
      ORDER BY u.switch_date, u.successor
      """
              .formatted(CashEquityUniverse.SERIES_PREDICATE, CashEquityUniverse.qualified("b"));

  private record Match(
      String predecessor,
      String successor,
      LocalDate switchDate,
      int gapSessions,
      BigDecimal boundaryPrice,
      boolean bseConfirmed,
      boolean bothOnBse,
      boolean caConfirmed) {

    /** BSE can see both tickers and says they are DIFFERENT listings — a contradiction, not silence. */
    boolean refuted() {
      return bothOnBse && !bseConfirmed;
    }

    String confidence() {
      return bseConfirmed ? "confirmed" : refuted() ? "refuted" : "inferred";
    }
  }

  private final JdbcTemplate jdbc;
  private final SymbolLineageRepository repo;
  private final int windowDays;
  private final int maxGapSessions;

  /**
   * @param windowDays the trailing window the rule looks back over — the SAME 420 days the screener
   *     base CTE bounds itself to, so a pair the screens could use is a pair this can see.
   * @param maxGapSessions the succession gap cap. 5 is the measured pre-clip, un-remeasured
   *     precision/recall knee: 65 of 66 pairs land at exactly 1, and the two matches in the 6–40
   *     band are penny-price collisions.
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
                    rs.getBoolean("both_on_bse"),
                    rs.getBoolean("ca_confirmed")),
            windowDays,
            maxGapSessions,
            windowDays);

    int inserted = 0;
    int confirmed = 0;
    int refuted = 0;
    for (Match m : matches) {
      if (m.bseConfirmed()) {
        confirmed++;
      }
      if (m.refuted()) {
        refuted++;
      }
      if (repo.upsertDetected(
          EXCHANGE,
          m.predecessor(),
          m.successor(),
          m.switchDate(),
          m.gapSessions(),
          m.boundaryPrice(),
          m.confidence(),
          evidence(m),
          SOURCE,
          m.refuted())) {
        inserted++;
      }
    }
    LocalDate asOf =
        jdbc.queryForObject(
            "SELECT max(trade_date) FROM nse_eod_bhavcopy WHERE " + CashEquityUniverse.SERIES_PREDICATE,
            LocalDate.class);
    DetectionResult result =
        new DetectionResult(
            asOf,
            matches.size(),
            inserted,
            matches.size() - inserted,
            confirmed,
            matches.size() - confirmed - refuted,
            refuted);
    log.info(
        "symbol-lineage detect asOf={}: {} pairs ({} confirmed / {} inferred / {} REFUTED->withheld),"
            + " {} new, {} refreshed",
        asOf, result.detected(), confirmed, result.inferred(), refuted, inserted, result.refreshed());
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
    } else if (m.refuted()) {
      sb.append("; REFUTED — both tickers on bse under DIFFERENT scrip_codes");
    }
    if (m.caConfirmed()) {
      sb.append("; nse change-in-name corporate action within 30d");
    }
    return sb.toString();
  }
}
