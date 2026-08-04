package in.arthayantra.marketdata.equitydaily;

/**
 * The ONE corporate-action-adjusted equity daily price plane the Minervini/Manas screeners and the
 * VCP geometry read, replacing three hand-rolled raw {@code nse_eod_bhavcopy} reads (audit H6 /
 * research-fidelity P0-4). The live screener plane used to read split/bonus-UNADJUSTED bhavcopy while
 * the deep-sim/hit-rate plane reads broker-adjusted {@code candles}@1d — so a split or bonus inside
 * the 420-session window opened a false price cliff that craters the trailing-return RS legs and
 * strands the pre-event 52-week high, silently dropping momentum leaders (where bonuses cluster) from
 * the funnel for up to a year. The two planes disagreed; the live funnel excluded names the backtest
 * happily traded.
 *
 * <p><b>Adjustment.</b> Each bar's close/high/low (and open, for geometry) is multiplied by the
 * cumulative product of every {@code eod_corporate_actions} ratio whose {@code ex_date} is AFTER the
 * bar — the exact multiplicative rule the read-time {@link in.arthayantra.marketdata.candles.EquitySplitBonusAdjuster}
 * (the chart-path adjuster) applies, expressed set-wise as {@code exp(sum(ln(ratio)))} over the
 * later-ex-date ratios. The factor is 1 for every bar dated on/after the last action (the vast
 * majority of the universe carries no action at all), so those names read byte-identically to the old
 * raw path. Prices are rounded to 4dp to match the Java adjuster's {@code setScale(4, HALF_UP)}
 * exactly. Volume is left raw (a split multiplies share count; the price plane, not the tape, is what
 * the gates read) and {@code raw_close} is carried unadjusted so the rupee-turnover liquidity gate
 * keeps its split-invariant true value.
 *
 * <p>The {@code eod_corporate_actions} PK {@code (exchange, tradingsymbol, ex_date)} indexes the
 * correlated lateral, so the probe is an empty index touch for the names with no action. No migration
 * is needed. The bhavcopy universe is NSE, so the exchange is the literal {@code 'NSE'} (the same
 * assumption the callers already bake into their {@code 'NSE'} candidate rows).
 */
public final class AdjustedEquityDailySql {

  private AdjustedEquityDailySql() {}

  /**
   * The CA-adjustment factor for one bar: the cumulative product of every action ratio with an
   * {@code ex_date} strictly after the bar's date, or 1 when there is none. {@code barAlias}/{@code
   * dateCol} name the outer bar the lateral correlates to.
   *
   * <p><b>PUBLIC so this is the SINGLE definition of the rule</b> (ledger §9-02). {@code
   * EquityReturnsService} used to inline a byte-identical copy of this lateral under a comment
   * promising it applied "the SAME multiplicative rule" — a promise nothing enforced, and exactly the
   * shape that drifts. Any new reader needing CA-adjusted prices must call this rather than paste it.
   */
  public static String factorLateral(String barAlias, String dateCol) {
    return """
        LEFT JOIN LATERAL (
          SELECT COALESCE(exp(sum(ln(ca.ratio))), 1) AS factor
          FROM eod_corporate_actions ca
          WHERE ca.exchange = 'NSE'
            AND ca.tradingsymbol = %1$s.symbol
            AND ca.ex_date > %1$s.%2$s
        ) caf ON true
        """
        .formatted(barAlias, dateCol);
  }

  /**
   * The multi-symbol screener {@code base} CTE body (no {@code base AS (...)} wrapper — the caller
   * names the CTE). Columns: {@code symbol, bucket, close, high, low, raw_close, volume}, where
   * close/high/low are CA-adjusted and {@code raw_close} is the unadjusted close for the turnover
   * gate. Binds two params in order: {@code asOf::date} (upper bound) and {@code asOf::date} (the
   * 420-session lower bound) — the SAME two the old raw base CTE bound, so caller bind order is
   * unchanged.
   */
  public static final String SCREENER_BASE_CTE =
      """
      SELECT b.symbol, b.bucket,
             round(b.close * caf.factor, 4) AS close,
             round(b.high  * caf.factor, 4) AS high,
             round(b.low   * caf.factor, 4) AS low,
             b.close AS raw_close,
             b.volume
      FROM (
        SELECT symbol, trade_date AS bucket, close_price AS close, high_price AS high,
               low_price AS low, ttl_trd_qnty AS volume
        FROM nse_eod_bhavcopy
        WHERE series IN ('EQ','BE')
          AND trade_date <= ?::date
          AND trade_date >  (?::date - 420)
      ) b
      """
          + factorLateral("b", "bucket");

  /**
   * The CA-adjustment factor for one bar on the LINEAGE-EXPANDED plane. Same multiplicative rule as
   * {@link #factorLateral}, but BOTH sides of the match are resolved through {@code terminal} first,
   * so the corporate-action plane is keyed exactly like the price plane.
   *
   * <p>WARNING: this exists because keying the plain lateral on the raw symbol reintroduces the
   * audit H6 / FID P0-4 price cliff the class was written to eliminate.
   * {@code eod_corporate_actions} records an action under the ticker that was live at its ex-date,
   * so a split dated AFTER a rename sits under the SUCCESSOR. Match on the raw symbol and the
   * predecessor's bars get factor 1 while the successor's get the ratio - measured on live data at
   * the {@code AARVEEDEN} to {@code VGL} join (2025-10-13 switch, VGL SPLIT 0.5 on 2026-03-02):
   * 151.4200 stays 151.4200 on the last predecessor bar while 147.0500 becomes 73.5250 on the first
   * successor bar. A ~51% one-day artifact inside the 420-day window, feeding SMA200, both 52-week
   * extremes and all four RS legs - and it fails NEGATIVE, so the successor reads as crashed and
   * fails the 52-week gates, which is precisely the "silently drops momentum leaders" signature.
   * {@code SABTNL} to {@code AQYLON} is the same shape at ratio 0.1, a 10x cliff.
   *
   * <p>Resolving both sides means every action anywhere in a lineage chain applies to every bar in
   * that chain, which is the correct reading of "one continuous instrument, one CA history". For a
   * symbol with no lineage row both {@code COALESCE}s collapse to the raw symbol and this reduces to
   * {@link #factorLateral} exactly.
   *
   * <p>Requires {@code terminal t} to be joined BEFORE this lateral - a LATERAL may only reference
   * relations earlier in the {@code FROM} list.
   */
  private static final String LINEAGE_FACTOR_LATERAL =
      """
      LEFT JOIN LATERAL (
        SELECT COALESCE(exp(sum(ln(ca.ratio))), 1) AS factor
        FROM eod_corporate_actions ca
        LEFT JOIN terminal tca ON tca.orig = ca.tradingsymbol
        WHERE ca.exchange = 'NSE'
          AND COALESCE(tca.cur, ca.tradingsymbol) = COALESCE(t.cur, b.symbol)
          AND ca.ex_date > b.bucket
      ) caf ON true
      """;

  /**
   * The LINEAGE-EXPANDED variant of {@link #SCREENER_BASE_CTE} - identical in every respect except
   * that each bar's {@code symbol} is resolved through {@code symbol_lineage} to the ticker that
   * carries the series TODAY, and the CA plane is resolved the same way. A renamed symbol's
   * pre-rename bars therefore accrue to its successor, which is what lets a successor clear the
   * 252-session gate on history that already exists in the database under a key nobody joins to
   * (N2 / #1285: 61 symbols invisible, 57 of them already past 252 sessions once merged).
   *
   * <p>OPT-IN ONLY. Callers reach this exclusively through {@link #screenerBaseCte(boolean)} with an
   * explicit {@code true}; every existing caller keeps passing through the untouched constant above
   * and is byte-identical. If a screen's numbers move without a reader asking for lineage, that is a
   * defect, not this feature.
   *
   * <p>Four details that are load-bearing:
   *
   * <ol>
   *   <li>The CA lateral is lineage-resolved on BOTH sides and is joined AFTER {@code terminal} so
   *       it can see it - see {@link #LINEAGE_FACTOR_LATERAL} for the measured cliff that keying on
   *       the raw symbol produces.
   *   <li>The walk is bounded by {@code asOf}. A link whose {@code switch_date} is AFTER the screen
   *       date has not happened yet from that date's point of view, so relabelling through it would
   *       report a symbol that did not exist - {@code ?asOf=2026-01-31} would surface {@code
   *       NIFTYAXIS}, first traded 2026-07-03, as a January candidate. 23 of the 65 active links
   *       have a switch date after 2026-01-31, so this is the common case on any replay, not an
   *       edge. A link with a NULL {@code switch_date} (a hand-seeded policy row the detector has
   *       not reached yet) is excluded by the same comparison.
   *   <li>A bar is relabelled only if it PREDATES its own switch ({@code b.bucket <
   *       t.first_switch}, the first hop out of that symbol), AND the result is de-duplicated by
   *       {@code DISTINCT ON (resolved symbol, bucket)}. <b>Both are needed and the first alone is
   *       not enough</b> — proven by {@code ScreenerLineageOptInIntegrationTest}, which reddened at
   *       29 duplicate rows with only the date guard in place. The date guard stops a bar from
   *       jumping FORWARD past its own switch; it cannot stop a hand-written link whose {@code
   *       switch_date} lands in the MIDDLE of the successor's existing run, which overlaps the two
   *       series directly. Duplicate {@code (symbol, bucket)} rows silently corrupt every window
   *       function downstream with no error anywhere, so this is closed structurally rather than
   *       trusted to the writer. The tie-break {@code (t.cur IS NULL) DESC} keeps a symbol's OWN bar
   *       over a predecessor bar stitched onto the same day, which is the only defensible
   *       precedence. Detected pairs never overlap, so this is inert on everything the detector
   *       writes; it exists because the WITHHELD escape hatch invites hand-written rows.
   *       <p>The one asymmetry: a predecessor bar dated ON or AFTER its switch keeps its own symbol
   *       while actions under that symbol resolve to the successor, so such a bar reads unadjusted.
   *       That fails toward raw prices rather than toward a cliff, and it cannot arise from a
   *       detected pair at all.
   *   <li>The walk is RECURSIVE and terminal - {@code A->B->C} lands A's bars on C, not on a B that
   *       no longer trades. No chain exists today (measured 2026-08-03: 66 pairs, all 1:1, max depth
   *       1), so this is insurance; depth is capped at 8 so a bad row cannot loop. Only {@code
   *       status = 'ACTIVE'} links are walked, and the walk does not traverse THROUGH a withheld
   *       link, so a demerger or a BSE-refuted amalgamation stops the stitch on both sides.
   * </ol>
   *
   * <p>WARNING: binds THREE {@code ?::date} params, not two - {@code asOf} for the lineage walk
   * (first, because the {@code asof} CTE is textually first), then the same two the plain CTE binds.
   * {@link #screenerBaseCteDateBinds(boolean)} is the single source of that arity; a caller that
   * hardcodes two silently shifts every later param by one.
   */
  public static final String LINEAGE_EXPANDED_SCREENER_BASE_CTE =
      """
      WITH RECURSIVE asof AS (SELECT ?::date AS d),
      lin AS (
          SELECT predecessor_symbol AS orig, successor_symbol AS cur, switch_date AS first_switch,
                 1 AS depth
          FROM symbol_lineage
          WHERE exchange = 'NSE' AND status = 'ACTIVE'
            AND switch_date <= (SELECT d FROM asof)
        UNION ALL
          SELECT l.orig, sl.successor_symbol, l.first_switch, l.depth + 1
          FROM lin l
          JOIN symbol_lineage sl ON sl.predecessor_symbol = l.cur
                                AND sl.exchange = 'NSE' AND sl.status = 'ACTIVE'
                                AND sl.switch_date <= (SELECT d FROM asof)
          WHERE l.depth < 8
      ),
      terminal AS (
        SELECT DISTINCT ON (orig) orig, cur, first_switch FROM lin ORDER BY orig, depth DESC
      )
      SELECT DISTINCT ON (COALESCE(t.cur, b.symbol), b.bucket)
             COALESCE(t.cur, b.symbol) AS symbol, b.bucket,
             round(b.close * caf.factor, 4) AS close,
             round(b.high  * caf.factor, 4) AS high,
             round(b.low   * caf.factor, 4) AS low,
             b.close AS raw_close,
             b.volume
      FROM (
        SELECT symbol, trade_date AS bucket, close_price AS close, high_price AS high,
               low_price AS low, ttl_trd_qnty AS volume
        FROM nse_eod_bhavcopy
        WHERE series IN ('EQ','BE')
          AND trade_date <= ?::date
          AND trade_date >  (?::date - 420)
      ) b
      LEFT JOIN terminal t ON t.orig = b.symbol AND b.bucket < t.first_switch
      """
          + LINEAGE_FACTOR_LATERAL
          + "ORDER BY COALESCE(t.cur, b.symbol), b.bucket, (t.cur IS NULL) DESC\n";

  /**
   * The screener {@code base} CTE body - plain by default, lineage-expanded when the caller asks.
   *
   * <p>{@code false} returns the {@link #SCREENER_BASE_CTE} constant ITSELF, not a rebuilt copy, so
   * "a reader that does not opt in behaves exactly as today" is true by object identity rather than
   * by inspection.
   */
  public static String screenerBaseCte(boolean lineageExpanded) {
    return lineageExpanded ? LINEAGE_EXPANDED_SCREENER_BASE_CTE : SCREENER_BASE_CTE;
  }

  /**
   * How many {@code asOf::date} params the chosen base CTE binds, in textual order, before any the
   * caller's own tail binds. Two for the plain plane; three for the lineage plane, whose walk needs
   * the screen date to exclude links that had not happened yet.
   *
   * <p>Exists so no caller hardcodes the arity: a positional bind list that is one short does not
   * fail loudly, it shifts every later parameter and silently screens on the wrong thresholds.
   */
  public static int screenerBaseCteDateBinds(boolean lineageExpanded) {
    return lineageExpanded ? 3 : 2;
  }

  /**
   * Single-symbol OHLCV read for the VCP geometry ({@link
   * in.arthayantra.marketdata.screener.minervini.geometry.DailyBarReader}). CA-adjusts OHLC, leaves
   * volume raw, oldest→newest. Binds four params in order: {@code symbol, asOf::date, asOf::date,
   * lookbackDays} — unchanged from the old raw read.
   */
  public static final String GEOMETRY_SYMBOL_SQL =
      """
      SELECT b.trade_date,
             round(b.open_price  * caf.factor, 4) AS open_price,
             round(b.high_price  * caf.factor, 4) AS high_price,
             round(b.low_price   * caf.factor, 4) AS low_price,
             round(b.close_price * caf.factor, 4) AS close_price,
             b.ttl_trd_qnty
      FROM nse_eod_bhavcopy b
      """
          + factorLateral("b", "trade_date")
          + """
      WHERE b.symbol = ?
        AND b.series IN ('EQ','BE')
        AND b.trade_date <= ?::date
        AND b.trade_date >  (?::date - ?)
      ORDER BY b.trade_date ASC
      """;
}
