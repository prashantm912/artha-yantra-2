package in.arthayantra.marketdata.screener.minervini;

import in.arthayantra.marketdata.equitydaily.AdjustedEquityDailySql;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Reports which Minervini funnel candidates are being read off TWO different price planes.
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
 * <p><b>Two things it deliberately does NOT do.</b> It changes neither plane's prices — teaching a
 * plane about dividends is a separate, owner-gated decision. And <b>it does not page</b>: see "why
 * there is no alert" below. That is a measured decision, not an unfinished one.
 *
 * <p><b>What it measures.</b> For one screen date, over the SAME 420-day window {@link
 * AdjustedEquityDailySql#SCREENER_BASE_CTE} binds, it recomputes both planes for every 8-gate passer
 * and reports the largest relative close divergence per symbol. Plane A is the screener plane
 * (bhavcopy, CA-adjusted through the shared {@link AdjustedEquityDailySql#factorLateral}); plane B is
 * the chart/engine plane ({@code candles}@1d with the source-aware split/bonus rule {@code
 * EquitySplitBonusAdjuster} applies — only {@code source='BHAVCOPY'} bars are scaled).
 *
 * <p><b>⚠️ As-of honesty: both source tables are RETRO-MUTABLE.</b> {@code nse_eod_bhavcopy} gains
 * rows for months-old trade dates and {@code candles} series are rewritten wholesale by {@code
 * CorporateActionJob} days after the sessions they describe (measured: ABSLAMC's 2026-07-21 bar
 * carries {@code fetched_at 2026-07-23}). A probe that compared today's bytes against a screen
 * persisted weeks ago would report a divergence the screen never saw — the exact trap that produced
 * four false flips in #1272 and dissolved under this same gate. Every bar pair is therefore gated on
 * <b>both</b> sides' {@code fetched_at} being at or before the screen's own {@code computed_at}, and
 * the bars that gate excludes are counted and reported rather than silently dropped. On the live
 * nightly path the cutoff is minutes old and excludes nothing; the gate is what makes a historical
 * read <b>as-of-bounded with explicit exclusions</b>.
 *
 * <p>⚠️ That is deliberately weaker than <i>reproducible</i>, and the difference matters. {@code
 * fetched_at} is an UPSERT timestamp, so it soundly answers "was this row rewritten after the screen
 * ran" — it cannot recover what the row HELD then, and a row written before the screen and rewritten
 * again before it reads clean either way. A symbol whose every shared bar was rewritten is reported
 * as unjudgeable, never as clean. Exact history would need the nightly report persisted rather than
 * recomputed; the completion marker records only that the probe ran, not what it saw.
 *
 * <p><b>Why a candidate and not merely a divergence.</b> A divergence that never reaches the served
 * funnel cannot move money, and there are always some — 197 divergent passer name-dates over the 22
 * screen dates measured, on every one of the 22. {@code divergentCandidates} counts those the funnel
 * actually serves as immediately-buyable or on-deck. Bucket membership is asked of {@link
 * MinerviniFunnelService} itself rather than re-derived, so the two can never drift.
 *
 * <p><b>Why there is no alert.</b> A page keyed on "a divergent symbol is a served candidate" was
 * built, measured and removed. Two independent reasons, both measured:
 *
 * <ul>
 *   <li><b>Wrong frequency.</b> Divergent candidates occur on 20 of 22 evenings. The scheduler
 *       aggregates a date into ONE notification, so that is a page on ~91% of evenings. A pager that
 *       fires almost nightly trains its reader to ignore it.
 *   <li><b>Wrong target — the decisive reason.</b> The only measured case where the split changed a
 *       screen outcome on a served candidate is ICEMAKE 2026-07-28, and <b>ICEMAKE is not
 *       divergent</b>. It was displaced across the {@code rs>=70} cut by another symbol's error,
 *       through the universe-relative RS percentile. Any predicate over the divergent symbol is
 *       structurally blind to it. An honest detector has to come from a whole-universe two-plane
 *       outcome rerank, which is a separate build.
 * </ul>
 *
 * So this surface answers "which names are read two ways, how far apart, and did any reach the
 * funnel" — the visibility gap that actually existed — and does not pretend to be a detector.
 */
@Service
public class PlaneDivergenceProbe {

  /** One passer whose two price planes disagree, and how far into the funnel it got. */
  @Schema(name = "MinerviniPlaneDivergentName")
  public record DivergentName(
      String symbol,
      @Schema(type = "string") BigDecimal maxDivergencePct,
      @Schema(types = {"string", "null"}) LocalDate worstBar,
      int sharedBars,
      int barsExcludedAsOf,
      boolean candidate) {}

  /**
   * The probe result for one screen date.
   *
   * <p>{@code thresholdPct} is the single REPORT floor — everything at or above it lands in {@code
   * names}, the log line and the endpoint. There is no second, alerting floor; see the class javadoc
   * for why the page was removed rather than tuned.
   *
   * <p>{@code asOfCutoff} is the screen's own {@code computed_at}. {@code barsExcludedAsOf} and
   * {@code symbolsWithNoHonestBars} are what that gate cost: a symbol whose every shared bar was
   * rewritten after the screen ran cannot be judged, and is counted here rather than reported clean.
   */
  @Schema(name = "MinerviniPlaneDivergence")
  public record Report(
      @Schema(types = {"string", "null"}) LocalDate screenDate,
      @Schema(types = {"string", "null"}) OffsetDateTime asOfCutoff,
      int passersChecked,
      int barsCompared,
      int barsExcludedAsOf,
      int symbolsWithNoHonestBars,
      int divergentPassers,
      int divergentCandidates,
      @Schema(type = "string") BigDecimal thresholdPct,
      int lookbackDays,
      List<DivergentName> names) {}

  // Plane A = the screener plane, verbatim through the ONE shared definition of the CA rule.
  // Plane B = candles@1d under EquitySplitBonusAdjuster's source-aware rule (BHAVCOPY bars only).
  // The candles side is wrapped so its `tradingsymbol`/`bucket` present as `symbol`/`d` and the
  // same factorLateral applies — pasting a second copy of the lateral is what its javadoc forbids.
  // Both planes join on the bhavcopy session date, so the session sequence is held fixed and only
  // the price plane varies.
  //
  // NO `HAVING`: every passer's aggregate comes back and the report floor is applied in Java. A
  // HAVING would drop exactly the rows that prove the as-of gate cost something — a symbol below
  // the floor, or one with no as-of-honest bars at all.
  private static final String SQL =
      """
      WITH cand AS (
        SELECT symbol FROM minervini_screen_results WHERE screen_date = ?::date AND passes_all
      ),
      a AS (
        SELECT b.symbol, b.trade_date AS d, round(b.close_price * caf.factor, 4) AS a_close,
               b.fetched_at
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
              SELECT cb.symbol, cb.d, cb.fetched_at,
                     CASE WHEN cb.source = 'BHAVCOPY' THEN round(cb.close * caf.factor, 4)
                          ELSE cb.close END AS b_close
              FROM (
                SELECT c.tradingsymbol AS symbol,
                       (c.bucket AT TIME ZONE 'Asia/Kolkata')::date AS d,
                       c.close, c.source, c.fetched_at
                FROM candles c
                WHERE c.exchange = 'NSE' AND c."interval" = '1d'
                  AND c.tradingsymbol IN (SELECT symbol FROM cand)
                  AND c.bucket >  (?::date - ?)::timestamp AT TIME ZONE 'Asia/Kolkata'
                  AND c.bucket <  (?::date + 1)::timestamp AT TIME ZONE 'Asia/Kolkata'
              ) cb
            """
          + AdjustedEquityDailySql.factorLateral("cb", "d")
          + """
            ),
            j AS (
              SELECT a.symbol, a.d,
                     abs(1 - bp.b_close / a.a_close) * 100 AS div_pct,
                     (a.fetched_at <= ? AND bp.fetched_at <= ?) AS as_of_ok
              FROM a JOIN bp ON bp.symbol = a.symbol AND bp.d = a.d
              WHERE a.a_close > 0
            )
            SELECT j.symbol,
                   count(*) FILTER (WHERE j.as_of_ok)     AS shared_bars,
                   count(*) FILTER (WHERE NOT j.as_of_ok) AS excluded_bars,
                   round(max(j.div_pct) FILTER (WHERE j.as_of_ok), 4) AS max_div_pct,
                   (array_agg(j.d ORDER BY j.div_pct DESC, j.d DESC)
                      FILTER (WHERE j.as_of_ok))[1] AS worst_bar
            FROM j
            GROUP BY j.symbol
            """;

  /** One symbol's aggregate straight off the query, before the report floor is applied. */
  private record Agg(
      String symbol, int sharedBars, int excludedBars, BigDecimal maxDivPct, LocalDate worstBar) {}

  private final JdbcTemplate jdbc;
  private final MinerviniFunnelService funnelService;
  private final BigDecimal minDivergencePct;
  private final int lookbackDays;

  /** Wires the marketdata datasource + the funnel (the single definition of "served candidate"). */
  public PlaneDivergenceProbe(
      JdbcTemplate jdbc,
      MinerviniFunnelService funnelService,
      @Value("${artha.minervini.plane-divergence.min-pct:0.5}") BigDecimal minDivergencePct,
      @Value("${artha.minervini.plane-divergence.lookback-days:420}") int lookbackDays) {
    this.jdbc = jdbc;
    this.funnelService = funnelService;
    this.minDivergencePct = minDivergencePct;
    this.lookbackDays = lookbackDays;
  }

  /** Probes one screen date. Returns an empty report for a date that was never screened. */
  public Report probe(LocalDate screenDate) {
    if (screenDate == null) {
      return empty(null, null);
    }
    java.sql.Date d = java.sql.Date.valueOf(screenDate);
    // The as-of cutoff is the screen's OWN persistence time — the moment the decision was made.
    OffsetDateTime cutoff =
        jdbc.queryForObject(
            "SELECT max(computed_at) FROM minervini_screen_results WHERE screen_date=?",
            OffsetDateTime.class,
            d);
    if (cutoff == null) {
      return empty(screenDate, null);
    }
    Integer passers =
        jdbc.queryForObject(
            "SELECT count(*) FROM minervini_screen_results WHERE screen_date=? AND passes_all",
            Integer.class,
            d);
    List<Agg> aggs =
        jdbc.query(
            SQL,
            (rs, n) -> {
              java.sql.Date worst = rs.getDate("worst_bar");
              return new Agg(
                  rs.getString("symbol"),
                  rs.getInt("shared_bars"),
                  rs.getInt("excluded_bars"),
                  rs.getBigDecimal("max_div_pct"),
                  worst == null ? null : worst.toLocalDate());
            },
            d, d, d, lookbackDays, d, lookbackDays, d, cutoff, cutoff);

    Set<String> served = servedSymbols(screenDate);
    List<DivergentName> names = new ArrayList<>();
    int barsCompared = 0;
    int barsExcluded = 0;
    int noHonestBars = 0;
    for (Agg g : aggs) {
      barsCompared += g.sharedBars();
      barsExcluded += g.excludedBars();
      if (g.sharedBars() == 0) {
        noHonestBars++; // judged on nothing — counted, never reported as clean
        continue;
      }
      if (g.maxDivPct() != null && g.maxDivPct().compareTo(minDivergencePct) >= 0) {
        names.add(
            new DivergentName(
                g.symbol(), g.maxDivPct(), g.worstBar(), g.sharedBars(), g.excludedBars(),
                served.contains(g.symbol())));
      }
    }
    names.sort((x, y) -> y.maxDivergencePct().compareTo(x.maxDivergencePct()));
    int candidates = (int) names.stream().filter(DivergentName::candidate).count();
    return new Report(
        screenDate,
        cutoff,
        passers == null ? 0 : passers,
        barsCompared,
        barsExcluded,
        noHonestBars,
        names.size(),
        candidates,
        minDivergencePct,
        lookbackDays,
        names);
  }

  /**
   * {@code canary_runs.canary} key for the per-screen-date completion marker. Reuses the existing
   * per-IST-day marker table (V052) rather than adding one — no migration.
   */
  public static final String CANARY_KEY = "MINERVINI_PLANE_DIVERGENCE";

  /** True once this screen date's reading has been recorded. Drives the scheduler's retry. */
  public boolean alreadyReported(LocalDate screenDate) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM canary_runs WHERE canary=? AND run_day=? AND state='DONE'",
            Integer.class,
            CANARY_KEY,
            java.sql.Date.valueOf(screenDate));
    return n != null && n > 0;
  }

  /** Records that this screen date has been reported, so later doors stop retrying it. */
  public void markReported(LocalDate screenDate) {
    jdbc.update(
        "INSERT INTO canary_runs(canary, run_day, state, source, claimed_at, completed_at)"
            + " VALUES(?,?, 'DONE', 'MINERVINI_SCHEDULER', now(), now())"
            + " ON CONFLICT (canary, run_day) DO UPDATE SET state='DONE', completed_at=now()",
        CANARY_KEY,
        java.sql.Date.valueOf(screenDate));
  }

  private Report empty(LocalDate screenDate, OffsetDateTime cutoff) {
    return new Report(
        screenDate, cutoff, 0, 0, 0, 0, 0, 0, minDivergencePct, lookbackDays, List.of());
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
