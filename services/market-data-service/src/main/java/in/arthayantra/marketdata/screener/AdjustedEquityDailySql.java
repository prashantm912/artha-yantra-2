package in.arthayantra.marketdata.screener;

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
