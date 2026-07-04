package in.arthayantra.marketdata.screener.minervini;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Minervini SEPA daily Trend-Template screener (Track-1, price-based). Pure parameterized SQL over
 * the broad EOD equity universe {@code nse_eod_bhavcopy} (~2.2k EQ/BE names with a full year) — NOT
 * the native {@code candles} store, whose dense recent-year history only covers the ~100
 * subscribed/backfilled names (bhavcopy is DO-NOTHING on the candle PK, so it never fills the year
 * there); mirrors the {@code ScreenerService} "SQL only" discipline. Evaluates the 8
 * Trend-Template gates (§4.2), the cross-sectional IBD-style RS-rank (§4.10), the derived Stage
 * label (§4.1), and the owner's price + liquidity universe gates. Free-float / market-cap low-cap
 * gates (§ ADR-0005) are layered later by the Upstox fundamentals feed; the columns stay null here.
 *
 * <p>All thresholds are config-tunable ({@code artha.minervini.*}) — "tuning rides DB/config, never
 * Java" (CLAUDE.md). Timezone: the daily bucket is normalised to the IST calendar date
 * ({@code AT TIME ZONE 'Asia/Kolkata'}) to avoid the UTC off-by-one across IST midnight.
 */
@Service
public class TrendTemplateService {

  /** A screen run: the as-of date, the count of symbols scanned, and the ranked candidates. */
  public record ScreenResult(LocalDate screenDate, int coverage, List<TrendCandidate> candidates) {}

  private final JdbcTemplate jdbc;
  private final int minSessions;
  private final BigDecimal minPrice;
  private final BigDecimal rsMin;
  private final BigDecimal pctAbove52wLow; // e.g. 25 -> close >= low * 1.25
  private final BigDecimal within52wHigh; // e.g. 25 -> close >= high * 0.75
  private final int sma200RisingSessions; // gate 3: 200d rising over N sessions (~1 month)
  private final BigDecimal liquidityThreshold; // capital * maxNamePct * multiple
  private final boolean lowCapGateEnabled; // ADR-0005 low-cap universe gate (off until fundamentals loaded)
  private final BigDecimal maxFreeFloatMcapCr; // < 5000 cr
  private final BigDecimal maxFreeFloatPct; // < 35 %

  /** Wires the marketdata datasource + the config-tunable Minervini thresholds. */
  public TrendTemplateService(
      JdbcTemplate jdbc,
      @Value("${artha.minervini.min-sessions:252}") int minSessions,
      @Value("${artha.minervini.min-price:30}") BigDecimal minPrice,
      @Value("${artha.minervini.rs-min:70}") BigDecimal rsMin,
      @Value("${artha.minervini.pct-above-52w-low:25}") BigDecimal pctAbove52wLow,
      @Value("${artha.minervini.within-52w-high:25}") BigDecimal within52wHigh,
      @Value("${artha.minervini.sma200-rising-sessions:21}") int sma200RisingSessions,
      @Value("${artha.minervini.capital:150000}") BigDecimal capital,
      @Value("${artha.minervini.max-name-pct:0.25}") BigDecimal maxNamePct,
      @Value("${artha.minervini.liquidity-multiple:100}") BigDecimal liquidityMultiple,
      @Value("${artha.minervini.lowcap-gate.enabled:false}") boolean lowCapGateEnabled,
      @Value("${artha.minervini.max-free-float-mcap-cr:5000}") BigDecimal maxFreeFloatMcapCr,
      @Value("${artha.minervini.max-free-float-pct:35}") BigDecimal maxFreeFloatPct) {
    this.jdbc = jdbc;
    this.minSessions = minSessions;
    this.minPrice = minPrice;
    this.rsMin = rsMin;
    this.pctAbove52wLow = pctAbove52wLow;
    this.within52wHigh = within52wHigh;
    this.sma200RisingSessions = sma200RisingSessions;
    this.liquidityThreshold = capital.multiply(maxNamePct).multiply(liquidityMultiple);
    this.lowCapGateEnabled = lowCapGateEnabled;
    this.maxFreeFloatMcapCr = maxFreeFloatMcapCr;
    this.maxFreeFloatPct = maxFreeFloatPct;
  }

  /** The latest daily bhavcopy trade date (IST calendar date). */
  public LocalDate latestScreenDate() {
    return jdbc.queryForObject(
        "SELECT max(trade_date) FROM nse_eod_bhavcopy WHERE series IN ('EQ','BE')",
        LocalDate.class);
  }

  /** Interim raw row straight off the window query, before RS percentile + gates are applied. */
  private record Raw(
      String symbol,
      BigDecimal close,
      BigDecimal sma50,
      BigDecimal sma150,
      BigDecimal sma200,
      BigDecimal sma200Ago,
      BigDecimal high52w,
      BigDecimal low52w,
      BigDecimal avgTurnover50,
      BigDecimal c63,
      BigDecimal c126,
      BigDecimal c189,
      BigDecimal c252,
      BigDecimal ffMcap,
      BigDecimal ffPct) {}

  private static final String SQL =
      """
      WITH base AS (
        -- The BROAD equity universe = the daily bhavcopy (nse_eod_bhavcopy, ~2.2k EQ/BE names with a
        -- full year), NOT native candles@1d (whose dense recent-year history only covers the ~100
        -- subscribed/backfilled names — bhavcopy is DO-NOTHING on the candle PK, so it never fills
        -- the year there). trade_date is already an IST calendar date (no tz cast). Master-plan §13.2.
        SELECT symbol, trade_date AS bucket, close_price AS close, high_price AS high,
               low_price AS low, ttl_trd_qnty AS volume
        FROM nse_eod_bhavcopy
        WHERE series IN ('EQ','BE')
          AND trade_date <= ?::date
          AND trade_date >  (?::date - 420)
      ),
      calc AS (
        SELECT symbol, bucket, close, volume,
          avg(close) OVER w50  AS sma50,
          avg(close) OVER w150 AS sma150,
          avg(close) OVER w200 AS sma200,
          max(high)  OVER w252 AS high_52w,
          min(low)   OVER w252 AS low_52w,
          avg(close * volume) OVER w50 AS avg_turnover_50,
          lag(close, 63)  OVER pw AS c63,
          lag(close, 126) OVER pw AS c126,
          lag(close, 189) OVER pw AS c189,
          lag(close, 252) OVER pw AS c252,
          count(*) OVER (PARTITION BY symbol) AS sessions
        FROM base
        WINDOW
          pw   AS (PARTITION BY symbol ORDER BY bucket),
          w50  AS (PARTITION BY symbol ORDER BY bucket ROWS BETWEEN 49  PRECEDING AND CURRENT ROW),
          w150 AS (PARTITION BY symbol ORDER BY bucket ROWS BETWEEN 149 PRECEDING AND CURRENT ROW),
          w200 AS (PARTITION BY symbol ORDER BY bucket ROWS BETWEEN 199 PRECEDING AND CURRENT ROW),
          w252 AS (PARTITION BY symbol ORDER BY bucket ROWS BETWEEN 251 PRECEDING AND CURRENT ROW)
      ),
      calc2 AS (
        SELECT calc.*,
          lag(sma200, ?) OVER (PARTITION BY symbol ORDER BY bucket) AS sma200_ago,
          row_number() OVER (PARTITION BY symbol ORDER BY bucket DESC) AS rn
        FROM calc
      )
      SELECT calc2.symbol, calc2.close, calc2.sma50, calc2.sma150, calc2.sma200, calc2.sma200_ago,
             calc2.high_52w, calc2.low_52w, calc2.avg_turnover_50,
             calc2.c63, calc2.c126, calc2.c189, calc2.c252,
             ef.free_float_mcap_cr AS ff_mcap, ef.free_float_pct AS ff_pct
      FROM calc2
      LEFT JOIN equity_fundamentals ef ON ef.symbol = calc2.symbol
      WHERE calc2.rn = 1
        AND calc2.sessions >= ?
        AND calc2.close >= ?
        AND calc2.avg_turnover_50 >= ?
      """;

  /** Runs the screen as of {@code asOf} (default = latest). Computes gates + RS-rank + Stage. */
  public ScreenResult screen(LocalDate asOf) {
    LocalDate date = asOf != null ? asOf : latestScreenDate();
    if (date == null) {
      return new ScreenResult(null, 0, List.of());
    }
    java.sql.Date d = java.sql.Date.valueOf(date);
    List<Raw> raws =
        jdbc.query(
            SQL,
            (rs, n) ->
                new Raw(
                    rs.getString("symbol"),
                    rs.getBigDecimal("close"),
                    rs.getBigDecimal("sma50"),
                    rs.getBigDecimal("sma150"),
                    rs.getBigDecimal("sma200"),
                    rs.getBigDecimal("sma200_ago"),
                    rs.getBigDecimal("high_52w"),
                    rs.getBigDecimal("low_52w"),
                    rs.getBigDecimal("avg_turnover_50"),
                    rs.getBigDecimal("c63"),
                    rs.getBigDecimal("c126"),
                    rs.getBigDecimal("c189"),
                    rs.getBigDecimal("c252"),
                    rs.getBigDecimal("ff_mcap"),
                    rs.getBigDecimal("ff_pct")),
            d, d, sma200RisingSessions, minSessions, minPrice, liquidityThreshold);

    if (lowCapGateEnabled) {
      // Low-cap universe gate (ADR-0005): drop names with a KNOWN large free-float cap or a high
      // free-float %; keep low-caps AND unknowns (unknown → owner manual-checklist, never a silent
      // drop). Applied BEFORE the RS-rank so the percentile is over the low-cap universe.
      raws =
          raws.stream()
              .filter(
                  r ->
                      r.ffMcap() == null
                          || (r.ffMcap().compareTo(maxFreeFloatMcapCr) < 0
                              && (r.ffPct() == null || r.ffPct().compareTo(maxFreeFloatPct) < 0)))
              .toList();
    }

    // Cross-sectional RS-rank: weighted trailing relative strength, percentile 0..100 across the
    // whole liquid universe (IBD-style, §4.10). Compute raw RS, rank ascending, then percentile.
    record Scored(Raw raw, BigDecimal rs) {}
    List<Scored> scored = new ArrayList<>(raws.size());
    for (Raw r : raws) {
      scored.add(new Scored(r, weightedRs(r)));
    }
    scored.sort(Comparator.comparing(Scored::rs));
    int n = scored.size();
    List<TrendCandidate> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      Scored s = scored.get(i);
      BigDecimal rsRank =
          n <= 1
              ? BigDecimal.valueOf(100)
              : BigDecimal.valueOf(i)
                  .multiply(BigDecimal.valueOf(100))
                  .divide(BigDecimal.valueOf(n - 1L), 2, RoundingMode.HALF_UP);
      out.add(toCandidate(s.raw(), s.rs(), rsRank));
    }
    out.sort(Comparator.comparing(TrendCandidate::rsRank).reversed());
    return new ScreenResult(date, n, out);
  }

  private BigDecimal weightedRs(Raw r) {
    BigDecimal rs = BigDecimal.ZERO;
    rs = rs.add(ret(r.close(), r.c63()).multiply(new BigDecimal("0.4")));
    rs = rs.add(ret(r.close(), r.c126()).multiply(new BigDecimal("0.2")));
    rs = rs.add(ret(r.close(), r.c189()).multiply(new BigDecimal("0.2")));
    rs = rs.add(ret(r.close(), r.c252()).multiply(new BigDecimal("0.2")));
    return rs;
  }

  private static BigDecimal ret(BigDecimal now, BigDecimal past) {
    if (past == null || past.signum() == 0) {
      return BigDecimal.ZERO;
    }
    return now.subtract(past).divide(past, 6, RoundingMode.HALF_UP);
  }

  private TrendCandidate toCandidate(Raw r, BigDecimal rsRaw, BigDecimal rsRank) {
    BigDecimal lowGate = r.low52w() == null ? null : r.low52w().multiply(onePlus(pctAbove52wLow));
    BigDecimal highGate = r.high52w() == null ? null : r.high52w().multiply(oneMinus(within52wHigh));
    boolean[] g = new boolean[8];
    g[0] = gt(r.close(), r.sma150()) && gt(r.close(), r.sma200());
    g[1] = gt(r.sma150(), r.sma200());
    g[2] = gt(r.sma200(), r.sma200Ago());
    g[3] = gt(r.sma50(), r.sma150()) && gt(r.sma50(), r.sma200());
    g[4] = gt(r.close(), r.sma50());
    g[5] = lowGate != null && r.close().compareTo(lowGate) >= 0;
    g[6] = highGate != null && r.close().compareTo(highGate) >= 0;
    g[7] = rsRank.compareTo(rsMin) >= 0;
    int passed = 0;
    for (boolean b : g) {
      if (b) {
        passed++;
      }
    }
    boolean all = passed == 8;
    BigDecimal fromHigh =
        r.high52w() == null || r.high52w().signum() == 0
            ? null
            : r.close().subtract(r.high52w()).divide(r.high52w(), 4, RoundingMode.HALF_UP);
    BigDecimal aboveLow =
        r.low52w() == null || r.low52w().signum() == 0
            ? null
            : r.close().subtract(r.low52w()).divide(r.low52w(), 4, RoundingMode.HALF_UP);
    return new TrendCandidate(
        r.symbol(), "NSE", r.close(), r.sma50(), r.sma150(), r.sma200(), r.high52w(), r.low52w(),
        fromHigh, aboveLow, r.avgTurnover50(), rsRaw, rsRank, g, passed, all,
        computeStage(r), r.ffMcap(), r.ffPct());
  }

  /**
   * MV-2.9 Stage 1-4 (§4.1), a cheap derived label off the moving-average structure: Stage 2 =
   * above a rising 200-day and above the 50-day (accumulation/advance); Stage 4 = below a falling
   * 200-day (decline); Stage 3 = above the 200-day but the 200-day has stopped rising (topping);
   * else Stage 1 (neglect/consolidation).
   */
  private int computeStage(Raw r) {
    boolean above200 = gt(r.close(), r.sma200());
    boolean rising200 = gt(r.sma200(), r.sma200Ago());
    if (!above200 && !rising200) {
      return 4;
    }
    if (above200 && rising200 && gt(r.close(), r.sma50())) {
      return 2;
    }
    if (above200 && !rising200) {
      return 3;
    }
    return 1;
  }

  private static boolean gt(BigDecimal a, BigDecimal b) {
    return a != null && b != null && a.compareTo(b) > 0;
  }

  private static BigDecimal onePlus(BigDecimal pct) {
    return BigDecimal.ONE.add(pct.movePointLeft(2));
  }

  private static BigDecimal oneMinus(BigDecimal pct) {
    return BigDecimal.ONE.subtract(pct.movePointLeft(2));
  }
}
