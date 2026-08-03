package in.arthayantra.marketdata.screener.minervini;

import in.arthayantra.marketdata.equitydaily.AdjustedEquityDailySql;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Tells the owner when a Minervini funnel candidate is being read off TWO different price planes.
 *
 * <p><b>Why this exists.</b> {@code candles}@1d is dividend-back-adjusted and {@code
 * nse_eod_bhavcopy} is not, by two deliberate and opposite decisions: {@code
 * BhavcopyBackfillService} records a dividend into {@code marketdata.dividends} and then {@code
 * continue}s without writing {@code eod_corporate_actions} (whose CHECK constraint admits only
 * {@code SPLIT}/{@code BONUS} anyway), while {@code CorporateActionJob} re-fetches whole series from
 * Kite, whose history <em>is</em> dividend-adjusted. {@code eod_corporate_actions} is the sole input
 * to BOTH adjusters, so the bhavcopy plane can never learn about a dividend the candles plane has
 * already applied. 32 of 1,813 screened symbols sat in that state on 2026-08-03, by 2–12%. Nothing
 * in the platform would have told anyone (docs/signal-analysis/2026-08-03-rs-plane-divergence.md).
 *
 * <p><b>What it does NOT do.</b> It changes neither plane's prices — teaching a plane about
 * dividends is a separate, owner-gated decision. This is pure instrumentation.
 *
 * <p><b>What it measures.</b> For one screen date, over the SAME 420-day window {@link
 * AdjustedEquityDailySql#SCREENER_BASE_CTE} binds, it recomputes both planes for every 8-gate passer
 * and reports the largest relative close divergence per symbol. Plane A is the screener plane
 * (bhavcopy, CA-adjusted through the shared {@link AdjustedEquityDailySql#factorLateral}); plane B is
 * the chart/engine plane ({@code candles}@1d with the source-aware split/bonus rule {@code
 * EquitySplitBonusAdjuster} applies — only {@code source='BHAVCOPY'} bars are scaled).
 *
 * <p><b>Why a candidate and not merely a divergence.</b> A divergence that never reaches the served
 * funnel cannot move money, and there are always some — 197 divergent passer name-dates over the 22
 * screen dates measured, on every one of the 22. The alarm therefore fires on {@code
 * alertingCandidates > 0}: a symbol {@link MinerviniFunnelService} actually serves as
 * immediately-buyable or on-deck AND whose divergence clears the separate page floor (see {@link
 * Report}). Bucket membership is asked of the funnel service itself rather than re-derived, so the
 * two can never drift.
 *
 * <p><b>Blind spot, deliberate and measured.</b> RS-rank is universe-relative, so a divergent
 * symbol's price error also displaces the percentile of names that are NOT divergent — measured at
 * 3 of 38,868 name-dates changing {@code passes_all} purely by displacement, one of which
 * (ICEMAKE 2026-07-28) was a served candidate. This probe reports the DIVERGENT symbol, so a
 * displacement casualty is invisible to it. Catching that needs a two-plane rerank of the whole
 * universe, not a per-candidate probe.
 */
@Service
public class PlaneDivergenceProbe {

  /** One passer whose two price planes disagree, and how far into the funnel it got. */
  @Schema(name = "MinerviniPlaneDivergentName")
  public record DivergentName(
      String symbol,
      /** max |1 − planeB/planeA| over the window, in percent. */
      @Schema(type = "string") BigDecimal maxDivergencePct,
      @Schema(types = {"string", "null"}) LocalDate worstBar,
      int sharedBars,
      /** true iff {@link MinerviniFunnelService} serves it as immediately-buyable or on-deck. */
      boolean candidate) {}

  /**
   * The probe result for one screen date.
   *
   * <p>TWO floors, because they answer different questions and the measured rates are two orders of
   * magnitude apart. {@code thresholdPct} is the REPORT floor — everything at or above it lands in
   * {@code names}, the log line and the endpoint, which is the visibility gap this closes. {@code
   * alertPct} is the PAGE floor. Measured over the 22 screen dates 2026-07-03…08-03: at the 0.5%
   * report floor a served candidate diverges on 103 name-dates across 20 of 22 sessions (~4.7 per
   * evening) — a page on that is noise, not a signal. At the 5% default page floor it is 7
   * name-dates on 7 sessions, two symbols (INDOBORAX, MAHLOG), both special-dividend scale.
   */
  @Schema(name = "MinerviniPlaneDivergence")
  public record Report(
      @Schema(types = {"string", "null"}) LocalDate screenDate,
      int passersChecked,
      int divergentPassers,
      int divergentCandidates,
      int alertingCandidates,
      @Schema(type = "string") BigDecimal thresholdPct,
      @Schema(type = "string") BigDecimal alertPct,
      int lookbackDays,
      List<DivergentName> names) {

    /** True for a served candidate whose divergence clears the PAGE floor — the alarm predicate. */
    public boolean isAlerting(DivergentName n) {
      return n.candidate() && n.maxDivergencePct().compareTo(alertPct) >= 0;
    }
  }

  // Plane A = the screener plane, verbatim through the ONE shared definition of the CA rule.
  // Plane B = candles@1d under EquitySplitBonusAdjuster's source-aware rule (BHAVCOPY bars only).
  // The candles side is wrapped so its `tradingsymbol`/`bucket` present as `symbol`/`d` and the
  // same factorLateral applies — pasting a second copy of the lateral is exactly what its javadoc
  // forbids. Both planes are joined on the bhavcopy session date, so the session sequence is held
  // fixed and only the price plane varies.
  private static final String SQL =
      """
      WITH cand AS (
        SELECT symbol FROM minervini_screen_results WHERE screen_date = ?::date AND passes_all
      ),
      a AS (
        SELECT b.symbol, b.trade_date AS d, round(b.close_price * caf.factor, 4) AS a_close
        FROM nse_eod_bhavcopy b
      """
          + AdjustedEquityDailySql.factorLateral("b", "trade_date")
          + """
              WHERE b.symbol IN (SELECT symbol FROM cand)
                AND b.series IN ('EQ','BE')
                AND b.trade_date <= ?::date
                AND b.trade_date >  (?::date - ?)
            ),
            bp AS (
              SELECT cb.symbol, cb.d,
                     CASE WHEN cb.source = 'BHAVCOPY' THEN round(cb.close * caf.factor, 4) ELSE cb.close END
                       AS b_close
              FROM (
                SELECT c.tradingsymbol AS symbol,
                       (c.bucket AT TIME ZONE 'Asia/Kolkata')::date AS d,
                       c.close, c.source
                FROM candles c
                WHERE c.exchange = 'NSE' AND c."interval" = '1d'
                  AND c.tradingsymbol IN (SELECT symbol FROM cand)
                  AND c.bucket >  (?::date - ?)::timestamp AT TIME ZONE 'Asia/Kolkata'
                  AND c.bucket <  (?::date + 1)::timestamp AT TIME ZONE 'Asia/Kolkata'
              ) cb
            """
          + AdjustedEquityDailySql.factorLateral("cb", "d")
          + """
            )
            SELECT a.symbol,
                   count(*) AS shared_bars,
                   round(max(abs(1 - bp.b_close / a.a_close)) * 100, 4) AS max_div_pct,
                   (array_agg(a.d ORDER BY abs(1 - bp.b_close / a.a_close) DESC, a.d DESC))[1] AS worst_bar
            FROM a JOIN bp ON bp.symbol = a.symbol AND bp.d = a.d
            WHERE a.a_close > 0
            GROUP BY a.symbol
            HAVING max(abs(1 - bp.b_close / a.a_close)) * 100 >= ?
            ORDER BY max_div_pct DESC
            """;

  private final JdbcTemplate jdbc;
  private final MinerviniFunnelService funnelService;
  private final BigDecimal minDivergencePct;
  private final BigDecimal alertPct;
  private final int lookbackDays;

  /** Wires the marketdata datasource + the funnel (the single definition of "served candidate"). */
  public PlaneDivergenceProbe(
      JdbcTemplate jdbc,
      MinerviniFunnelService funnelService,
      @Value("${artha.minervini.plane-divergence.min-pct:0.5}") BigDecimal minDivergencePct,
      @Value("${artha.minervini.plane-divergence.alert-pct:5.0}") BigDecimal alertPct,
      @Value("${artha.minervini.plane-divergence.lookback-days:420}") int lookbackDays) {
    this.jdbc = jdbc;
    this.funnelService = funnelService;
    this.minDivergencePct = minDivergencePct;
    this.alertPct = alertPct;
    this.lookbackDays = lookbackDays;
  }

  /** Probes one screen date. Never throws on an empty date — returns an empty report. */
  public Report probe(LocalDate screenDate) {
    if (screenDate == null) {
      return new Report(null, 0, 0, 0, 0, minDivergencePct, alertPct, lookbackDays, List.of());
    }
    java.sql.Date d = java.sql.Date.valueOf(screenDate);
    Integer passers =
        jdbc.queryForObject(
            "SELECT count(*) FROM minervini_screen_results WHERE screen_date=? AND passes_all",
            Integer.class,
            d);
    Set<String> served = servedSymbols(screenDate);
    List<DivergentName> names =
        jdbc.query(
            SQL,
            (rs, n) -> {
              String symbol = rs.getString("symbol");
              java.sql.Date worst = rs.getDate("worst_bar");
              return new DivergentName(
                  symbol,
                  rs.getBigDecimal("max_div_pct"),
                  worst == null ? null : worst.toLocalDate(),
                  rs.getInt("shared_bars"),
                  served.contains(symbol));
            },
            d, d, d, lookbackDays, d, lookbackDays, d, minDivergencePct);
    int candidates = (int) names.stream().filter(DivergentName::candidate).count();
    Report shell =
        new Report(screenDate, 0, 0, 0, 0, minDivergencePct, alertPct, lookbackDays, List.of());
    int alerting = (int) names.stream().filter(shell::isAlerting).count();
    return new Report(
        screenDate,
        passers == null ? 0 : passers,
        names.size(),
        candidates,
        alerting,
        minDivergencePct,
        alertPct,
        lookbackDays,
        names);
  }

  /** The set the doctrine actually SERVES — buyable + on-deck. {@code watch} is not a candidate. */
  private Set<String> servedSymbols(LocalDate screenDate) {
    MinerviniFunnelService.Funnel f = funnelService.funnel(screenDate);
    Set<String> out = new HashSet<>();
    f.immediatelyBuyable().forEach(r -> out.add(r.symbol()));
    f.onDeck().forEach(r -> out.add(r.symbol()));
    return out;
  }
}
