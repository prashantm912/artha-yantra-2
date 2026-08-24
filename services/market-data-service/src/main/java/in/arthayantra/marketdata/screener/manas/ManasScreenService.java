package in.arthayantra.marketdata.screener.manas;

import in.arthayantra.marketdata.equitydaily.AdjustedEquityDailySql;
import in.arthayantra.marketdata.equitydaily.CashEquityUniverse;
import in.arthayantra.marketdata.screener.ScreenCoverageFloor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Manas Arora selection screener (a simplified, India-adapted Minervini; long-only cash equities).
 * Pure parameterized SQL over the broad EOD equity universe {@code nse_eod_bhavcopy} (~2.2k EQ/BE
 * names with a full year) — the SAME source the sibling {@code TrendTemplateService} screen uses (NOT
 * the sparse {@code candles} store). Evaluates the §1.2 within-25%-of-52w-high universe filter, the
 * §4.1 six-criteria selection gates, the §4.3 liquidity checks (20-day-avg-volume veto + 10-week-avg
 * depth), and the (default-off, §4.4) low-cap/float gate.
 *
 * <p>All thresholds are config-tunable ({@code artha.manas-arora.*}). Timezone: {@code trade_date} is
 * already an IST calendar date (no tz cast). Free-float / market-cap inputs come from the shared
 * {@code equity_fundamentals} table (V032); the columns stay null when the feed has no row.
 */
@Service
public class ManasScreenService {

  /** A screen run: the as-of date, the count of symbols scanned, and the candidates. */
  public record ScreenResult(LocalDate screenDate, int coverage, List<ManasCandidate> candidates) {}

  private final JdbcTemplate jdbc;
  private final int minSessions;
  private final BigDecimal minPrice;
  private final BigDecimal within52wHigh; // e.g. 25 -> close >= high * 0.75
  private final BigDecimal pctAbove52wLow; // e.g. 100 -> close >= low * 2.00
  private final int sma200RisingSessions; // gate 2: 200d rising over N sessions (~3 months)
  private final int newHighLookbackSessions; // gate 6: new 52w high within last N sessions
  private final BigDecimal minAvgVolume; // §4.3 absolute veto: 20d avg volume must exceed this
  private final BigDecimal liquidityFloor; // §4.3 depth: 10wk avg vol >= multiple * intendedQty
  private final boolean lowCapGateEnabled; // §4.4 low-cap gate (off until fundamentals loaded)
  private final BigDecimal maxFreeFloatMcapCr; // < 5000 cr
  private final BigDecimal maxFreeFloatPct; // < 35 %
  private final int minCurrentCoveragePct; // coverage floor — see ScreenCoverageFloor

  /** Wires the marketdata datasource + the config-tunable Manas thresholds. */
  public ManasScreenService(
      JdbcTemplate jdbc,
      @Value("${artha.manas-arora.min-sessions:252}") int minSessions,
      @Value("${artha.manas-arora.min-price:30}") BigDecimal minPrice,
      @Value("${artha.manas-arora.within-52w-high:25}") BigDecimal within52wHigh,
      @Value("${artha.manas-arora.pct-above-52w-low:100}") BigDecimal pctAbove52wLow,
      @Value("${artha.manas-arora.sma200-rising-sessions:63}") int sma200RisingSessions,
      @Value("${artha.manas-arora.new-high-lookback-sessions:126}") int newHighLookbackSessions,
      @Value("${artha.manas-arora.min-avg-volume:5000}") BigDecimal minAvgVolume,
      @Value("${artha.manas-arora.intended-qty:100}") BigDecimal intendedQty,
      // Doc §4.3: 10-week average volume must be >= 50x (ideally 50-100x) the intended quantity —
      // the "you can (almost) always exit" structural-safety floor. The old 25x default (copied from
      // the Minervini screen's different turnover-in-rupees formula) admitted names with HALF the
      // required depth into the funnel + the ₹1.5L Manas paper book (audit M35).
      @Value("${artha.manas-arora.liquidity-multiple:50}") BigDecimal liquidityMultiple,
      @Value("${artha.manas-arora.lowcap-gate.enabled:false}") boolean lowCapGateEnabled,
      @Value("${artha.manas-arora.max-free-float-mcap-cr:5000}") BigDecimal maxFreeFloatMcapCr,
      @Value("${artha.manas-arora.max-free-float-pct:35}") BigDecimal maxFreeFloatPct,
      @Value("${artha.manas-arora.min-current-coverage-pct:80}") int minCurrentCoveragePct) {
    this.jdbc = jdbc;
    this.minSessions = minSessions;
    this.minPrice = minPrice;
    this.within52wHigh = within52wHigh;
    this.pctAbove52wLow = pctAbove52wLow;
    this.sma200RisingSessions = sma200RisingSessions;
    this.newHighLookbackSessions = newHighLookbackSessions;
    this.minAvgVolume = minAvgVolume;
    this.liquidityFloor = intendedQty.multiply(liquidityMultiple);
    this.lowCapGateEnabled = lowCapGateEnabled;
    this.maxFreeFloatMcapCr = maxFreeFloatMcapCr;
    this.maxFreeFloatPct = maxFreeFloatPct;
    this.minCurrentCoveragePct = minCurrentCoveragePct;
  }

  /** The latest daily bhavcopy trade date (IST calendar date). */
  public LocalDate latestScreenDate() {
    return jdbc.queryForObject(
        "SELECT max(trade_date) FROM nse_eod_bhavcopy WHERE " + CashEquityUniverse.SERIES_PREDICATE,
        LocalDate.class);
  }

  /** Interim raw row straight off the window query, before the gates are applied. */
  private record Raw(
      String symbol,
      BigDecimal close,
      BigDecimal sma50,
      BigDecimal sma200,
      BigDecimal sma200Ago,
      BigDecimal high52w,
      BigDecimal low52w,
      BigDecimal recentHigh, // max high over the last newHighLookback sessions (gate 6)
      BigDecimal avgVol20,
      BigDecimal avgVol50,
      BigDecimal turnover50,
      BigDecimal c63, // trailing closes for the §4.10 weighted RS (3/6/9/12-month)
      BigDecimal c126,
      BigDecimal c189,
      BigDecimal c252,
      BigDecimal ffMcap,
      BigDecimal ffPct,
      boolean current) {}

  // The new-high frame offset (§4.1 gate 6) is a config INT inlined as a SQL literal (Postgres frame
  // bounds take a constant, and the sibling screens keep every ROWS-BETWEEN bound a literal) — only
  // per-value data is bound with '?'. {@code %d} is substituted with newHighLookbackSessions-1. The
  // base CTE (prepended at build time) is the CA-adjusted shared price plane (AdjustedEquityDailySql,
  // audit H6 / FID P0-4): close/high/low back-adjusted for splits/bonuses so an action inside the
  // window no longer opens a false cliff under the MA/52w/RS gates; raw_close carries the unadjusted
  // close for the split-invariant rupee-turnover gate (turnover_50). The CALC/SELECT part carries the
  // {@code %d} literal, so the raw base CTE (which has no {@code %}) is concatenated OUTSIDE the format.
  private static final String CALC_AND_SELECT =
      """
      calc AS (
        SELECT symbol, bucket, close, volume,
          avg(close) OVER w50  AS sma50,
          avg(close) OVER w200 AS sma200,
          max(high)  OVER w252 AS high_52w,
          min(low)   OVER w252 AS low_52w,
          max(high)  OVER wnh  AS recent_high,
          avg(volume)         OVER w20 AS avg_vol_20,
          avg(volume)         OVER w50 AS avg_vol_50,
          avg(raw_close * volume) OVER w50 AS turnover_50,
          count(*) OVER (PARTITION BY symbol) AS sessions
        FROM base
        WINDOW
          w20  AS (PARTITION BY symbol ORDER BY bucket ROWS BETWEEN 19  PRECEDING AND CURRENT ROW),
          w50  AS (PARTITION BY symbol ORDER BY bucket ROWS BETWEEN 49  PRECEDING AND CURRENT ROW),
          w200 AS (PARTITION BY symbol ORDER BY bucket ROWS BETWEEN 199 PRECEDING AND CURRENT ROW),
          w252 AS (PARTITION BY symbol ORDER BY bucket ROWS BETWEEN 251 PRECEDING AND CURRENT ROW),
          wnh  AS (PARTITION BY symbol ORDER BY bucket ROWS BETWEEN %d PRECEDING AND CURRENT ROW)
      ),
      calc2 AS (
        SELECT calc.*,
          lag(sma200, ?) OVER (PARTITION BY symbol ORDER BY bucket) AS sma200_ago,
          -- Trailing closes for the IBD-style weighted RS (§4.1): 3/6/9/12-month lookbacks. The 420-day
          -- base window + the sessions>=252 filter make these present for essentially every name (a name
          -- with exactly 252 sessions lacks c252 — lag(252) needs 253 rows — but ManasGates.ret treats a
          -- null lag as 0, so that leg just drops out; never NPEs).
          lag(close,  63) OVER (PARTITION BY symbol ORDER BY bucket) AS c63,
          lag(close, 126) OVER (PARTITION BY symbol ORDER BY bucket) AS c126,
          lag(close, 189) OVER (PARTITION BY symbol ORDER BY bucket) AS c189,
          lag(close, 252) OVER (PARTITION BY symbol ORDER BY bucket) AS c252,
          row_number() OVER (PARTITION BY symbol ORDER BY bucket DESC) AS rn
        FROM calc
      )
      SELECT calc2.symbol, calc2.close, calc2.sma50, calc2.sma200, calc2.sma200_ago,
             calc2.high_52w, calc2.low_52w, calc2.recent_high,
             calc2.avg_vol_20, calc2.avg_vol_50, calc2.turnover_50,
             calc2.c63, calc2.c126, calc2.c189, calc2.c252,
             ef.free_float_mcap_cr AS ff_mcap, ef.free_float_pct AS ff_pct,
             -- Trailing-bar flag: the rn=1 row is the symbol's OWN latest bar, whatever date that
             -- is, but the result is labelled with latestScreenDate() = the UNIVERSE's max
             -- trade_date. A symbol that stopped printing (delisted/renamed/suspended) therefore
             -- emitted a full candidate row computed on a weeks-old close under today's badge, with
             -- the staleness neither carried on the row nor visible in the response. Same guard
             -- EquityReturnsService already applies to this table (its rn=1 HAVING).
             -- Comparing against base's OWN max — not a restated max(trade_date) subquery — keeps
             -- the series filter structurally identical to the population (base is series
             -- IN ('EQ','BE'), matching latestScreenDate(); EquityReturnsService says 'EQ' only
             -- because ITS population is 'EQ' only) and keeps the historical screen(asOf) replay
             -- correct: base is already bounded by asOf, so this reads "the universe's latest bar
             -- AS OF THE SCREEN DATE", not "as of today", which would drop every symbol on a replay.
             -- Carried as a FLAG rather than applied as a WHERE filter so one query yields both the
             -- surviving count and the eligible denominator the coverage floor needs, from a single
             -- snapshot (a second counting query would both double the cost and race this one).
             (calc2.bucket = (SELECT max(bucket) FROM base)) AS is_current
      FROM calc2
      LEFT JOIN equity_fundamentals ef ON ef.symbol = calc2.symbol
      WHERE calc2.rn = 1
        AND calc2.sessions >= ?
      """;

  /**
   * The latest bhavcopy trade date ON OR BEFORE {@code asOf} — the EFFECTIVE screen date. The
   * requested {@code asOf} must never become the label: the row selection compares each symbol
   * against {@code max(bucket) FROM base}, which is bounded by {@code asOf}, so an {@code asOf}
   * ahead of the data (e.g. POST /run?asOf=today before the evening bhavcopy lands) leaves every
   * symbol sitting on the last real bar, reports 100% coverage, sails through the coverage floor
   * and persists YESTERDAY's rows under TODAY's date — exactly the stale-close-under-a-current-badge
   * mislabelling this guard exists to prevent, arriving through the one door the floor cannot see.
   * Resolving the label to the real watermark keeps date and data in agreement by construction.
   */
  private LocalDate effectiveScreenDate(LocalDate asOf) {
    return asOf == null
        ? latestScreenDate()
        : jdbc.queryForObject(
            "SELECT max(trade_date) FROM nse_eod_bhavcopy WHERE " + CashEquityUniverse.SERIES_PREDICATE
                + " AND trade_date <= ?::date",
            LocalDate.class,
            java.sql.Date.valueOf(asOf));
  }

  /** Runs the screen as of {@code asOf} (default = latest). Computes the §4.1/§1.2/§4.3/§4.4 gates. */
  public ScreenResult screen(LocalDate asOf) {
    return screen(asOf, false);
  }

  /**
   * The same screen, optionally reading the LINEAGE-EXPANDED price plane (N2 / #1285): a renamed
   * symbol's pre-rename bars accrue to the ticker that carries the series today.
   *
   * <p>{@code lineageExpanded = false} builds the identical statement this method has always built —
   * {@link AdjustedEquityDailySql#screenerBaseCte} returns the untouched constant for {@code false}.
   * Every persisting caller uses the one-arg overload, so the published daily screen is unchanged.
   */
  public ScreenResult screen(LocalDate asOf, boolean lineageExpanded) {
    LocalDate date = effectiveScreenDate(asOf);
    if (date == null) {
      return new ScreenResult(null, 0, List.of());
    }
    java.sql.Date d = java.sql.Date.valueOf(date);
    // The new-high window is inclusive of the current row, so N-1 preceding rows cover N sessions.
    int newHighPreceding = Math.max(0, newHighLookbackSessions - 1);
    // The CA-adjusted base CTE (no '%' → safe to concatenate around the %d-bearing calc/select).
    String sql =
        "WITH base AS (\n"
            + AdjustedEquityDailySql.screenerBaseCte(lineageExpanded)
            + "\n),\n"
            + String.format(CALC_AND_SELECT, newHighPreceding);
    // ⚠️ See TrendTemplateService: the lineage plane binds a THIRD asOf, first. Arity is read from
    // AdjustedEquityDailySql so it can never drift out of step with the CTE it belongs to.
    Object[] args =
        new Object[AdjustedEquityDailySql.screenerBaseCteDateBinds(lineageExpanded) + 2];
    java.util.Arrays.fill(args, 0, args.length - 2, d);
    args[args.length - 2] = sma200RisingSessions;
    args[args.length - 1] = minSessions;
    List<Raw> raws =
        jdbc.query(
            sql,
            (rs, n) ->
                new Raw(
                    rs.getString("symbol"),
                    rs.getBigDecimal("close"),
                    rs.getBigDecimal("sma50"),
                    rs.getBigDecimal("sma200"),
                    rs.getBigDecimal("sma200_ago"),
                    rs.getBigDecimal("high_52w"),
                    rs.getBigDecimal("low_52w"),
                    rs.getBigDecimal("recent_high"),
                    rs.getBigDecimal("avg_vol_20"),
                    rs.getBigDecimal("avg_vol_50"),
                    rs.getBigDecimal("turnover_50"),
                    rs.getBigDecimal("c63"),
                    rs.getBigDecimal("c126"),
                    rs.getBigDecimal("c189"),
                    rs.getBigDecimal("c252"),
                    rs.getBigDecimal("ff_mcap"),
                    rs.getBigDecimal("ff_pct"),
                    rs.getBoolean("is_current")),
            args);

    // Trailing-bar guard + coverage floor. Applied FIRST — before the RS-rank — so the percentile is
    // computed over the surviving universe, exactly as when this was a WHERE filter.
    // ScreenCoverageFloor throws rather than publishing a gutted screen.
    int eligible = raws.size();
    raws = raws.stream().filter(Raw::current).toList();
    ScreenCoverageFloor.check("manas", date, raws.size(), eligible, minCurrentCoveragePct);

    // Cross-sectional RS-rank (§4.10, IBD-style): the weighted trailing relative strength as a 0..100
    // percentile across the screened universe. Sort ascending with a deterministic symbol tie-break
    // (ManasGates.ret returns 0 for a null/edge lag, so a whole block can tie at 0 — without the
    // tie-break two runs of the same screen date could hand tied names different ordinal percentiles).
    record Scored(Raw raw, BigDecimal rs) {}
    List<Scored> scored = new ArrayList<>(raws.size());
    for (Raw r : raws) {
      scored.add(
          new Scored(r, ManasGates.weightedRs(r.close(), r.c63(), r.c126(), r.c189(), r.c252())));
    }
    scored.sort(java.util.Comparator.comparing(Scored::rs).thenComparing(s -> s.raw().symbol()));
    int n = scored.size();
    List<ManasCandidate> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      BigDecimal rsRank =
          n <= 1
              ? BigDecimal.valueOf(100)
              : BigDecimal.valueOf(i)
                  .multiply(BigDecimal.valueOf(100))
                  .divide(BigDecimal.valueOf(n - 1L), 2, RoundingMode.HALF_UP);
      out.add(toCandidate(scored.get(i).raw(), rsRank));
    }
    return new ScreenResult(date, n, out);
  }

  private ManasCandidate toCandidate(Raw r, BigDecimal rsRank) {
    boolean[] g =
        ManasGates.gates(
            r.close(), r.sma50(), r.sma200(), r.sma200Ago(), r.high52w(), r.low52w(),
            r.recentHigh(), minPrice, pctAbove52wLow);
    int selectionPassed = ManasGates.passed(g);
    boolean withinHigh = ManasGates.withinHigh(r.close(), r.high52w(), within52wHigh);
    boolean liquidVolume = ManasGates.liquidVolume(r.avgVol20(), minAvgVolume);
    boolean liquidDepth = ManasGates.liquidDepth(r.avgVol50(), liquidityFloor);
    boolean aboveSma50 = ManasGates.aboveSma50(r.close(), r.sma50());
    boolean lowCap = ManasGates.lowCap(r.ffMcap(), r.ffPct(), maxFreeFloatMcapCr, maxFreeFloatPct);
    // passesAll = the 6 selection gates AND the universe gate AND both liquidity gates, plus (only
    // when the low-cap gate is enabled) the §4.4 float gate. Disabled → float gate never blocks.
    boolean passesAll =
        selectionPassed == 6
            && withinHigh
            && liquidVolume
            && liquidDepth
            && (!lowCapGateEnabled || lowCap);
    BigDecimal fromHigh =
        r.high52w() == null || r.high52w().signum() == 0
            ? null
            : r.close().subtract(r.high52w()).divide(r.high52w(), 4, RoundingMode.HALF_UP);
    BigDecimal aboveLow =
        r.low52w() == null || r.low52w().signum() == 0
            ? null
            : r.close().subtract(r.low52w()).divide(r.low52w(), 4, RoundingMode.HALF_UP);
    return new ManasCandidate(
        r.symbol(), "NSE", r.close(), r.sma50(), r.sma200(), r.high52w(), r.low52w(),
        r.avgVol20(), r.avgVol50(), r.turnover50(), fromHigh, aboveLow, withinHigh, aboveSma50,
        liquidVolume, liquidDepth, lowCap, g, selectionPassed, passesAll, r.ffMcap(), r.ffPct(),
        rsRank);
  }
}
