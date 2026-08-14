package in.arthayantra.marketdata.canary;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.alerts.NtfyClient;
import in.arthayantra.marketdata.constituents.StaticIndexConstituents;
import in.arthayantra.marketdata.kite.GapBackfiller;
import in.arthayantra.marketdata.kite.InstrumentKey;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * Bhavcopy-close vs Kite-1d-close data-quality canary (app-platform audit 2026-07-10 §8 V8). The
 * corporate-action job diffs Kite-vs-Kite closes only and excludes bhavcopy-only names, so a
 * divergence between the NSE EOD bhavcopy close and the Kite-captured 1d close for a symbol that has
 * BOTH — an unadjusted corporate action, or a bad print in one feed — is never surfaced. This
 * standalone canary compares the two closes for the latest bhavcopy trade date (only symbols whose
 * {@code candles} 1d bar carries {@code source='KITE'}, i.e. genuinely dual-sourced) and alerts on the
 * count exceeding the threshold. Read-only; it deliberately does NOT touch the corporate-action plane.
 *
 * <p>Keying: bhavcopy close is {@code nse_eod_bhavcopy.close_price} (PK {@code trade_date, symbol,
 * series}); the Kite close is a {@code candles} 1d row (PK excludes {@code source}) filtered to {@code
 * source='KITE'} and matched on the IST calendar date of the bucket. Live-only sweep + fail-soft
 * alerting; {@link #evaluate(LocalDate)} is side-effect-free so a GET / a test can call it any time.
 *
 * <p><b>The comparison population is this canary's OWN, and it used to be BORROWED.</b> Until
 * 2026-08-13 this canary never fetched a Kite bar — it compared whatever {@code source='KITE'} 1d
 * rows some other job happened to leave in {@code candles}, which was the swing batch fetching bars
 * for the names it screened at 20:00 ({@code MinerviniSwingScheduler}'s javadoc documents the same
 * dependency from the other side). #1333 moved that batch to a 16:00 EXITS-ONLY settle, so it began
 * fetching only the symbols the book HOLDS. Measured: symbols compared went 171 / 159 / 160 / 164 on
 * 08-05..08-10 and then <b>14 / 14</b> on 08-11 / 08-12 — against an EQ bhavcopy universe of ~2 450.
 * On 2026-08-11 this canary reported <b>GREEN on 14 symbols</b>: catalogue trap #14, an armed gate
 * whose operand is structurally near-zero, reporting success.
 *
 * <p>#1367 added {@code artha.bhavcopy-close.min-compared} so that could not recur silently — below
 * the floor the verdict is never GREEN. That was the ALARM. {@link #prefetchPopulation()} is the
 * CURE: a 16:05 IST pass that fetches the 1d bar for a fixed reference list, so the population is
 * one this canary controls rather than another job's leftovers. The floor stays, and now measures
 * something that can actually satisfy it.
 *
 * <p><b>⚠️ What this canary certifies, and what it does NOT.</b> The population is a SAMPLE — the
 * {@code NIFTY 200} reference list, 202 large-cap NSE names, plus whatever residue other jobs leave
 * behind (measured 215 compared against a 202-symbol seed) — not the ~2 450-name EQ bhavcopy
 * universe. That is a real limit and naming it is the whole point of the coverage work: a partial
 * check that reads as complete is how this gate got into trouble in the first place.
 *
 * <ul>
 *   <li><b>Caught:</b> SYSTEMIC divergence between the two close feeds — a session-date off-by-one,
 *       a feed-wide price-scale or currency error, a global split/bonus adjustment regression, a
 *       bhavcopy parse fault, a stale Kite session serving yesterday's closes. Those show
 *       identically on 202 names as on 2 450, so the sample costs nothing against them.
 *   <li><b>MISSED:</b> IDIOSYNCRATIC divergence in the ~2 250 names outside the sample — a bad Kite
 *       print, an unadjusted corporate action, a symbol-rename/lineage mismatch on one stock. This
 *       is not a small residue: corporate actions and thin-book bad prints are MORE frequent per
 *       name among small/mid caps, so the sample is biased AWAY from where per-symbol corruption
 *       actually lives. Anything correlated with market cap, series, or listing segment (an SME or
 *       BE-series mapping break, say) is invisible here by construction.
 * </ul>
 *
 * <p>So: this certifies that the two close feeds AGREE AS FEEDS. It does not, and with a sample
 * cannot, certify per-symbol correctness across the EQ universe. A full sweep would — and is not
 * affordable: 2 450 symbols at the {@code kite-historical} 3 req/s limiter is ~14 min (measured
 * 2.84 calls/s over 40 sequential calls, 2026-08-13), which does not fit the 18:45→18:58 evening
 * window at all, let alone before the 19:00 machine-off. 202 symbols is ~71 s.
 */
@Component
public class BhavcopyCloseCanary {

  private static final Logger log = LoggerFactory.getLogger(BhavcopyCloseCanary.class);
  private static final String GREEN = "GREEN";
  private static final String YELLOW = "YELLOW";
  private static final String RED = "RED";

  /** One symbol whose bhavcopy close diverges from its Kite 1d close beyond the threshold. */
  public record CloseMismatch(
      String symbol, @Schema(type = "string") BigDecimal bhavClose, @Schema(type = "string") BigDecimal kiteClose, @Schema(type = "string") BigDecimal relDiffPct) {}

  /**
   * The report for a trade date: compared count, divergent count, and the worst offenders.
   *
   * <p>{@code minCompared} is the coverage floor in force, echoed so the report explains its own
   * verdict: a {@code YELLOW} carrying {@code divergent=0} is a COVERAGE failure, and without the
   * floor beside the count a reader cannot tell that from a clean run.
   */
  // ⚠️ `compared` is NOT the size of the seeded sample. It counts every EQ bhavcopy row that has a
  // source='KITE' 1d bar on that date — the symbols prefetchPopulation() seeds PLUS whatever other
  // jobs happen to leave behind (the swing settle's holdings, an owner browsing charts). Measured
  // 215 against a 202-symbol seed. So the seed is a FLOOR on this number, not its definition, and a
  // count above the seed size is expected rather than a counting bug.
  public record BhavcopyCloseReport(
      @Schema(types = {"string", "null"}) LocalDate tradeDate,
      String status,
      int compared,
      int divergent,
      int minCompared,
      @Schema(type = "string") BigDecimal thresholdPct,
      List<CloseMismatch> offenders) {}

  // Symbols whose 1d bar is genuinely Kite-captured (source='KITE'); a bhavcopy-projected bar
  // (source='BHAVCOPY') would compare against itself. Matches the two closes on the IST session date.
  //
  // ⚠️ `b.series = 'EQ'` is KEPT, deliberately, and it was checked rather than assumed — an EQ-only
  // filter elsewhere in this repo once hid every BE-series symbol and manufactured a fake outage.
  // Measured over 2026-08-05..08-12, this filter drops exactly ZERO rows from the comparison: the
  // same join counted EQ-only, EQ+BE, and series-agnostic returns an identical
  // 171 / 159 / 160 / 164 / 14 / 14 on every one of the six dates, because every symbol carrying a
  // Kite 1d bar is EQ. So it is not what narrowed the population and widening it would buy nothing
  // today. It IS a latent narrowing: the moment a BE-series name is held (the bars come from the
  // swing book), its close would silently stop being compared. Left as-is because changing the
  // divergence population is not this change's business, and the coverage floor below now makes any
  // future shrink from this cause visible instead of silent.
  private static final String COMPARE_SQL =
      """
      SELECT b.symbol,
             b.close_price AS bhav_close,
             c.close AS kite_close,
             abs(b.close_price - c.close) / c.close AS rel_diff
      FROM nse_eod_bhavcopy b
      JOIN candles c
        ON c.exchange = 'NSE' AND c.tradingsymbol = b.symbol AND c.interval = '1d'
       AND c.source = 'KITE'
       AND (c.bucket AT TIME ZONE 'Asia/Kolkata')::date = b.trade_date
      WHERE b.series = 'EQ' AND b.trade_date = ?
        AND b.close_price IS NOT NULL AND c.close IS NOT NULL AND c.close > 0
        AND abs(b.close_price - c.close) / c.close > ?
      ORDER BY rel_diff DESC
      """;

  private final JdbcTemplate jdbc;
  private final NtfyClient ntfy;
  private final Clock clock;
  private final StaticIndexConstituents constituents;
  private final GapBackfiller backfiller;
  private final MarketCalendar calendar;
  private final Counter divergenceCounter;
  private final Counter emptyPopulationCounter;
  private final String prefetchCron;

  /**
   * The prefetch cron, parsed, so {@link #catchUpPopulation()} can ask "was today's pass already
   * due?" from the SAME property the schedule uses rather than a second copy of the literal that
   * could drift away from it. {@code null} when the job is disabled Spring's documented way
   * ({@code cron = "-"}), in which case there is no scheduled pass and so nothing to catch up.
   */
  private final CronExpression prefetchSchedule;

  private final boolean live;
  private final boolean enabled;
  private final boolean alertsEnabled;
  private final BigDecimal threshold;
  private final int redFloor;
  private final int sampleLimit;
  private final String populationIndex;

  /**
   * The smallest comparison population this canary will certify. Below it the verdict is never
   * GREEN.
   *
   * <p><b>Why 100.</b> Measured {@code compared} counts: 171 (08-05), 159 (08-06), 160 (08-07), 164
   * (08-10), then 14 (08-11), 14 (08-12). 100 sits ~1.6× below the healthy minimum — so an ordinary
   * shrink in whatever job leaves the bars behind does not cry wolf — and ~7× above the collapsed
   * value, so the collapse cannot hide under it. The exact number is not delicate: the two
   * populations are an order of magnitude apart, so anything from ~30 to ~150 separates them
   * identically, and 100 is simply the round middle of that range.
   *
   * <p><b>It is deliberately NOT re-sized to the owned population.</b> {@link
   * #prefetchPopulation()} now supplies 202 symbols, so 100 leaves ~102 names of erosion headroom
   * before the alarm fires — generous, and that generosity is the trade: a SLOW erosion (index
   * rebalances retiring names from the static list over years) stays invisible until it has halved
   * the population. Raising the deployed {@code ARTHA_BHAVCOPY_CLOSE_MIN_COMPARED} to ~150 once
   * this has run a few sessions would tighten that, and it is a one-line env change; it is not made
   * here because the floor's own defect class is being set TOO HIGH on a bad night, and this change
   * has no live evidence yet for what the owned population's normal floor looks like.
   *
   * <p><b>⚠️ EFFECTIVE, not configured — this field is CLAMPED at construction.</b> The configured
   * value is raised to at least {@code max(1, redFloor)}, because a floor below {@code redFloor}
   * would certify a population on which RED is arithmetically unreachable: {@code divergent} can
   * never exceed {@code compared}, so at {@code compared < redFloor} the top severity cannot fire
   * however corrupt the feed is. That is precisely the defect this property exists to remove, and
   * without the clamp the property reintroduces it inside its own knob — trap #14 one level up, in
   * the fix for trap #14. {@code min-compared=0} is the worst case: every SQL count is
   * non-negative, so the condition can never fire and an EMPTY population reads GREEN again.
   *
   * <p><b>Why clamp-and-warn rather than refuse to start.</b> Three options, and the choice is
   * deliberate:
   *
   * <ul>
   *   <li><b>Fail fast</b> — a bean that refuses to construct takes market-data, and with it the
   *       LIVE FEED, down over a misconfigured canary threshold. This package is fail-soft
   *       throughout ({@link #sweep()} swallows a RuntimeException, alerting never throws), and
   *       trading the feed for a canary knob inverts that priority.
   *   <li><b>Silent clamp</b> — a quiet lie. An operator who set 0 would see 20 and never learn
   *       why.
   *   <li><b>Clamp + WARN + publish the effective value</b> — chosen. The clamp direction is
   *       always toward ALARMING, never toward certifying, so the safety property holds whether or
   *       not anyone reads the log; and the report carries this effective value on the wire, so
   *       the clamp is visible in the ARTIFACT rather than only in a startup line that scrolls
   *       away.
   * </ul>
   *
   * <p>To silence this canary use {@code artha.bhavcopy-close.enabled=false} (stops the sweep) or
   * {@code alerts-enabled=false} (stops the page), never a low floor. Note {@code enabled=false}
   * deliberately does NOT change the GET: the endpoint reports what is true regardless.
   */
  private final int minCompared;

  public BhavcopyCloseCanary(
      JdbcTemplate jdbc,
      NtfyClient ntfy,
      Clock clock,
      StaticIndexConstituents constituents,
      GapBackfiller backfiller,
      MarketCalendar calendar,
      MeterRegistry meterRegistry,
      Environment environment,
      @Value("${artha.bhavcopy-close.enabled:true}") boolean enabled,
      @Value("${artha.bhavcopy-close.alerts-enabled:true}") boolean alertsEnabled,
      @Value("${artha.bhavcopy-close.threshold:0.01}") BigDecimal threshold,
      @Value("${artha.bhavcopy-close.red-floor:20}") int redFloor,
      @Value("${artha.bhavcopy-close.sample-limit:25}") int sampleLimit,
      @Value("${artha.bhavcopy-close.min-compared:100}") int minCompared,
      @Value("${artha.bhavcopy-close.population-index:NIFTY 200}") String populationIndex,
      @Value("${artha.bhavcopy-close.prefetch-cron:0 5 16 * * MON-FRI}") String prefetchCron) {
    this.jdbc = jdbc;
    this.ntfy = ntfy;
    this.clock = clock;
    this.constituents = constituents;
    this.backfiller = backfiller;
    this.calendar = calendar;
    this.populationIndex = populationIndex;
    this.prefetchCron = prefetchCron;
    this.prefetchSchedule =
        Scheduled.CRON_DISABLED.equals(prefetchCron) ? null : CronExpression.parse(prefetchCron);
    this.divergenceCounter = meterRegistry.counter("ay_bhavcopy_close_divergence_total");
    this.emptyPopulationCounter =
        meterRegistry.counter("ay_bhavcopy_close_population_empty_total");
    this.live = environment.matchesProfiles("live");
    this.enabled = enabled;
    this.alertsEnabled = alertsEnabled;
    this.threshold = threshold;
    this.redFloor = redFloor;
    this.sampleLimit = sampleLimit;
    // ⚠️ Clamped, not trusted — see the field javadoc. A floor under redFloor certifies populations
    // on which RED cannot arithmetically fire. redFloor itself is NOT clamped (it is tuned, and
    // this change does not own it); max(1, ...) only stops a non-positive redFloor from dragging
    // the floor back to zero and reopening the empty-population GREEN.
    this.minCompared = Math.max(minCompared, Math.max(1, redFloor));
    if (this.minCompared != minCompared) {
      log.warn(
          "artha.bhavcopy-close.min-compared={} would certify a population on which RED is"
              + " arithmetically unreachable (red-floor={}); raised to {}. To silence this canary"
              + " use artha.bhavcopy-close.enabled=false, not a low floor.",
          minCompared, redFloor, this.minCompared);
    }
  }

  /**
   * Fetches this canary's OWN comparison population — the 1d Kite bar for every symbol in the
   * {@code populationIndex} reference list — at 16:05 IST. Live-only, trading-days-only.
   *
   * <p><b>⚠️ Why 16:05 and not "just before the 18:45 ingest".</b> Because the pass has no upper
   * bound on its duration and the deadline it must beat is {@link #sweep()} at 18:58, not the
   * ingest. It is ~202 SEQUENTIAL Kite fetches; nominal is ~71 s (below), but each fetch retries up
   * to 4 times over a 60 s read timeout, so a Kite brown-out stretches the pass arbitrarily. Five
   * minutes of slack in front of the ingest is therefore not slack at all, while 16:05 buys 2 h
   * 53 m for a pass that normally needs 71 s — a badly degraded pass still lands in time. Getting
   * that wrong would also fail QUIETLY: a pass that has not finished when the sweep reads the
   * population is indistinguishable from an eroded index, both surfacing as a coverage YELLOW.
   *
   * <p><b>It is NOT scheduled early to beat the bhavcopy projection to these buckets.</b> That was
   * the original rationale and it is FALSE, measured. {@code upsertAuthoritativeAll}'s
   * keep-the-existing-source branch requires {@code oi} to match as well as OHLCV, and the two 1d
   * producers encode "no open interest" differently — {@code BhavcopyCandles} writes {@code null},
   * Kite historical returns a literal {@code 0} for cash equities — so a Kite write over a bhavcopy
   * bar ALWAYS takes {@code source='KITE'} and the bar stays in this population. Bhavcopy-first is
   * safe; {@code CandleCaggIntegrationTest#kiteOverBhavcopyTakesTheKiteSourceBecauseOnlyOneSideEncodesNoOiAsZero}
   * pins it, because that safety turns on an encoding difference neither producer promises.
   *
   * <p><b>The 16:05 bar is already final</b> — this was measured, not assumed, because fetching a
   * partial bar would manufacture divergence out of nothing. Three symbols' 1d bars captured at
   * 16:00 IST on 2026-08-13 were byte-identical in close AND volume when re-fetched at 17:43, and
   * the value-identical write kept {@code source='KITE'}.
   *
   * <p><b>Timing and margin.</b> 202 symbols, one Kite call each (the B-4 recency rule makes
   * today's in-progress 1d bucket always re-fetch, so exactly one page per symbol), paced by the
   * {@code kite-historical} limiter at 3 req/s. Measured 2.84 calls/s over 40 sequential calls
   * against the live gateway on 2026-08-13 → <b>~71 s</b>, i.e. done by ~16:06 against a 17:52
   * earliest-publish and a 18:58 evaluation.
   *
   * <p><b>Why this cannot starve the live feed.</b> The pass is strictly SEQUENTIAL, so it holds at
   * most ONE of the limiter's 3 permits/s at any instant — a competing caller waits ~333 ms, far
   * inside the limiter's 5 s queue, and never sees {@code RATE_LIMIT_LOCAL}. It also runs 35 min
   * after the close, so the live WS ticker (which consumes no REST HISTORICAL permits in any case)
   * is idle, and the neighbouring {@code kite-historical} consumers are done: the 15:45
   * {@code EodBackfillJob} and the 16:00–16:02 swing settle both precede it, and the 16:30
   * corporate-action job follows. The market being shut weakens the concern but does not remove it
   * — an owner browsing charts shares this limiter — which is why the sequential property, not the
   * hour, is the actual guarantee.
   *
   * <p>Per-symbol failures are counted and stepped over, never re-raised: a Kite outage or one
   * delisted name should SHRINK the population, not abort the pass, because a short population is
   * precisely what the coverage floor is built to report. This pass is therefore self-checking — if
   * it quietly achieves nothing, {@link #sweep()} says so 18 minutes later.
   *
   * <p><b>A cron alone is not enough, and that is what {@link #catchUpPopulation()} is for.</b> This
   * fires only while the service is UP at 16:05. The machine is off overnight and the stack has
   * been down for a whole afternoon before (2026-08-10: no batch 08:29→18:47 IST), so a boot after
   * 16:05 skips this pass entirely for that session and the evening's population falls back to
   * whatever other jobs left behind — measured 14, against a 202-symbol seed and a floor of 100.
   * The catch-up replays the missed pass.
   */
  // ⚠️ `cron` and `zone` stay on ONE line: CronPassthroughParityTest matches the @Scheduled site by
  // a single-line `cron = "${...:` needle and then asserts THAT line carries the IST zone. Split
  // across lines, the site is still found and the zone assertion fails on a correct annotation.
  @Scheduled(cron = "${artha.bhavcopy-close.prefetch-cron:0 5 16 * * MON-FRI}", zone = "Asia/Kolkata",
      scheduler = "closeCanaryTaskScheduler")
  public void prefetchPopulation() {
    if (!live || !enabled) {
      return;
    }
    LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
    if (!isTradingDaySafe(today)) {
      return;
    }
    prefetchNow(today);
  }

  /**
   * Replays a MISSED {@link #prefetchPopulation()} at boot, synchronously, and returns the symbols
   * attempted (0 when there was nothing to replay). Called by {@code BhavcopyStartupCatchup} ahead
   * of the bhavcopy pull, under a HARD DEADLINE — see that class for why the call is a direct one
   * rather than two ordered {@code ApplicationReadyEvent} listeners, and why the caller stops
   * waiting on this method after 90 s rather than letting a slow pass hold up the feed.
   *
   * <p><b>Why this is not optional polish.</b> {@link #prefetchPopulation()} is cron-only, so it
   * fires only while the service is up at 16:05 IST. On a boot after that hour the pass is simply
   * never run for that session, so the evening's population is back to whatever other jobs left
   * behind — measured 14 against a 202-symbol seed, with the canary reporting GREEN on those 14.
   * It is not a rare shape: this machine is off overnight, and the live stack has already spent an
   * entire afternoon down (2026-08-10, no batch 08:29→18:47 IST).
   *
   * <p><b>Due, not "always on boot".</b> Replaying on an 08:00 boot would fetch 202 IN-PROGRESS 1d
   * bars — 71 s of {@code kite-historical} budget for a partial bar that the 16:05 pass then
   * re-fetches anyway (B-4 always refreshes the in-progress bucket). So the catch-up runs only when
   * today's scheduled fire time has already passed, read from {@link #prefetchSchedule} — the same
   * property the annotation uses, so retuning the cron moves both.
   *
   * <p>Note the catch-up is still LATE by construction: it happens whenever the service comes up,
   * which on a bad night is after the evening chain has already started. What it guarantees is that
   * the session gets a seeded population at all; what it cannot guarantee is that the population is
   * complete before {@link #sweep()} reads it at 18:58. A boot at 18:57 seeds nothing the sweep can
   * see — the pass takes ~71 s — and that is a coverage YELLOW, correctly reported.
   */
  public int catchUpPopulation() {
    if (!live || !enabled) {
      return 0;
    }
    ZonedDateTime now = ZonedDateTime.now(clock.withZone(Ist.ZONE));
    LocalDate today = now.toLocalDate();
    if (!isTradingDaySafe(today) || !scheduledPassIsAlreadyPast(now)) {
      return 0;
    }
    log.info(
        "bhavcopy-close population pass missed its scheduled slot (cron '{}', service not up) —"
            + " replaying it for {} now so tonight's comparison population is this canary's own",
        prefetchCron, today);
    return prefetchNow(today);
  }

  /** Whether the prefetch cron's fire time for {@code now}'s IST date is at or before {@code now}. */
  private boolean scheduledPassIsAlreadyPast(ZonedDateTime now) {
    if (prefetchSchedule == null) {
      return false;
    }
    ZonedDateTime firstToday =
        prefetchSchedule.next(now.toLocalDate().atStartOfDay(Ist.ZONE).minusNanos(1));
    // Not-today covers the cron simply not firing on this date at all (a MON-FRI cron on a Saturday
    // that some future calendar counts as a trading day) — there is no missed pass to replay.
    return firstToday != null
        && firstToday.toLocalDate().equals(now.toLocalDate())
        && !firstToday.isAfter(now);
  }

  /**
   * One synchronous population pass for {@code tradeDate}; returns the symbols attempted. Callable
   * from tests and by hand — the guards live in {@link #prefetchPopulation()}, so this always runs.
   */
  public int prefetchNow(LocalDate tradeDate) {
    List<String> symbols = constituents.symbols(populationIndex);
    if (symbols.isEmpty()) {
      // ⚠️ This branch must emit something NOTHING ELSE emits, or it is not a guard at all. Delete
      // it and the loop below runs zero times, returns the same 0, and raises no interaction — so a
      // test written against the return value or the backfiller passes IDENTICALLY with and without
      // it: catalogue trap #14, the guard that enumerates zero items and reports success, inside the
      // fix for trap #14. The counter is what the test can pin; the alert is what an operator
      // actually sees. Both, deliberately: a metric nothing scrapes is loud only in principle.
      //
      // It does NOT wait for the 18:58 sweep to notice. The sweep's coverage YELLOW says "too few
      // symbols were comparable" — true, but it cannot name the cause, and the causes are wildly
      // different repairs (a typo in population-index vs. a Kite outage vs. index erosion). This
      // fires 2h53m earlier and names it.
      emptyPopulationCounter.increment();
      String message =
          "bhavcopy-close population index '" + populationIndex + "' has no constituents — the"
              + " canary fetched NOTHING, so tonight's comparison population is whatever other jobs"
              + " leave behind. Check the index name against reference/index-constituents.json";
      log.error("bhavcopy-close population pass fetched nothing: {}", message);
      sendAlert("ArthaYantra bhavcopy-close population empty", "urgent", message);
      return 0;
    }
    // Today only: one 1d bucket per symbol, so one Kite page each. A wider window would re-fetch
    // closed bars the cache already owns (GapDetector's 10-min recency rule only ever forces the
    // in-progress bucket), multiplying the cost for nothing the canary reads.
    Instant from = tradeDate.atStartOfDay(Ist.ZONE).toInstant();
    Instant to = clock.instant();
    long startedAt = System.currentTimeMillis();
    int failed = 0;
    for (String symbol : symbols) {
      try {
        backfiller.prefetch(new InstrumentKey("NSE", symbol), "1d", from, to);
      } catch (RuntimeException e) {
        // Defence in depth, and it is NOT redundant with GapBackfillService's own catch. That is one
        // implementation of the GapBackfiller PORT; the port promises nothing about swallowing, so
        // relying on it makes a whole session's population hostage to a collaborator's internals.
        // The failure direction matters: unhandled, one delisted or renamed symbol early in an
        // alphabetical list would abort the pass and leave the canary short every night, looking
        // exactly like the borrowed-population collapse this change exists to end.
        failed++;
        log.debug("bhavcopy-close population fetch failed for {}: {}", symbol, e.toString());
      }
    }
    // "attempted", not "fetched": GapBackfillService swallows its own fetch failures, so a symbol
    // that raised nothing here may still have stored no bar. Claiming a fetch count this cannot
    // know would make this line read like the coverage evidence it is not — the sweep's `compared`
    // is the only honest measure of what actually landed.
    log.info(
        "bhavcopy-close population pass: {} '{}' symbols attempted for {} in {} ms ({} raised);"
            + " the sweep's compared count is the authority on what landed",
        symbols.size(), populationIndex, tradeDate,
        System.currentTimeMillis() - startedAt, failed);
    return symbols.size();
  }

  /** Trading-day check that treats a year outside the bundled calendar as a non-trading day. */
  private boolean isTradingDaySafe(LocalDate day) {
    try {
      return calendar.isTradingDay(day);
    } catch (IllegalArgumentException uncoveredYear) {
      return false;
    }
  }

  /**
   * Daily sweep, after the evening's bhavcopy lands. Live-only.
   *
   * <p>⚠️ Sweeps the LATEST bhavcopy date, which is not necessarily today's. NSE's publish time
   * varies — measured 17:52, 17:59, 18:47 and 19:31 across four days — so on a late night the file
   * has not landed when this fires, and without the guard below the canary silently re-evaluates
   * YESTERDAY's session and alerts on it as if it were tonight's: an operator message naming the
   * wrong day, repeated every late night — and a confidently wrong alert trains the owner to ignore
   * the channel.
   *
   * <p>⚠️ A skipped comparison is PERMANENTLY missed for that session, not deferred.
   * {@code BhavcopyStartupCatchup} starts the backfill on the next boot, so the DATA arrives — but
   * this canary has no completion listener and only fires on its own cron, and by the time it next
   * fires that session is no longer today, so the guard below skips it again. Forever. Skipping
   * still trades a missed check for a WRONG one, which is the right trade; it does not make the
   * check free, and the loss is a whole session's close comparison rather than a delay.
   *
   * <p><b>20:10 → 18:58, and this LOSES coverage. Stated because it is a real cost of moving the
   * chain inside the 19:00 machine-off boundary, not a wash.</b> Against the four measured publish
   * times, 20:10 caught all four; 18:58 catches three (17:52, 17:59, 18:47) and misses 19:31. It is
   * the last free minute before shutdown, so it is the most coverage the window allows — 18:52,
   * seven minutes after an ASYNCHRONOUS 18:45 submit, would have caught only two.
   *
   * <p>The 19:31 case needs a bhavcopy-completion listener, and that was BUILT AND WITHDRAWN from
   * the schedule PR rather than shipped half-right. Three review rounds found successive defects in
   * it: routed through the date guard it was meant to bypass, so it recovered nothing; then a
   * dedupe that covered only the rare trigger order; then — decisively — the watermark is
   * in-memory, so the daily JVM restart replays the startup catch-up with a null watermark and
   * re-alerts yesterday's divergence every morning. Doing it properly needs a PERSISTED compared-
   * session watermark, which is its own change with its own migration.
   */
  @Scheduled(cron = "${artha.bhavcopy-close.cron:0 58 18 * * MON-FRI}", zone = "Asia/Kolkata")
  public void sweep() {
    if (!live || !enabled) {
      return;
    }
    LocalDate latest = latestTradeDate();
    if (latest == null) {
      return;
    }
    LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
    if (latest.isBefore(today)) {
      // Neutral wording on purpose: this also fires on a weekday NSE holiday, where there is no
      // file to wait for and "has not landed yet" would be a small lie in the log every holiday.
      log.info(
          "bhavcopy-close canary skipped — the newest bhavcopy is {} but today is {}; there is no"
              + " bar for today (a late publish, or a non-trading weekday), and comparing an older"
              + " session would alert on the wrong day",
          latest, today);
      return;
    }
    BhavcopyCloseReport report;
    try {
      report = evaluate(latest);
    } catch (RuntimeException e) {
      log.warn("bhavcopy-close canary failed: {}", e.getMessage());
      return;
    }
    publish(report);
  }

  /** The newest EQ bhavcopy trade date, or {@code null} when the table is empty. */
  public LocalDate latestTradeDate() {
    return jdbc.query(
        "SELECT max(trade_date) AS d FROM nse_eod_bhavcopy WHERE series = 'EQ'",
        rs -> rs.next() ? rs.getObject("d", LocalDate.class) : null);
  }

  /** Compares the two closes for {@code tradeDate}. Side-effect-free (the GET + tests call this). */
  public BhavcopyCloseReport evaluate(LocalDate tradeDate) {
    if (tradeDate == null) {
      // Zero compared symbols is the smallest population there is, so it goes through the same
      // floor as any other: an empty table is the one case where "nothing diverged" is guaranteed
      // and means nothing at all. This used to return GREEN.
      return report(null, List.of(), 0);
    }
    List<CloseMismatch> offenders =
        jdbc.query(
            COMPARE_SQL,
            (rs, n) ->
                new CloseMismatch(
                    rs.getString("symbol"),
                    rs.getBigDecimal("bhav_close"),
                    rs.getBigDecimal("kite_close"),
                    rs.getBigDecimal("rel_diff")
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, java.math.RoundingMode.HALF_UP)),
            java.sql.Date.valueOf(tradeDate),
            threshold);
    Integer compared =
        jdbc.queryForObject(
            "SELECT count(*) FROM nse_eod_bhavcopy b "
                + "JOIN candles c ON c.exchange='NSE' AND c.tradingsymbol=b.symbol "
                + " AND c.interval='1d' AND c.source='KITE' "
                + " AND (c.bucket AT TIME ZONE 'Asia/Kolkata')::date = b.trade_date "
                + "WHERE b.series='EQ' AND b.trade_date=? AND b.close_price IS NOT NULL "
                + " AND c.close IS NOT NULL AND c.close > 0",
            Integer.class,
            java.sql.Date.valueOf(tradeDate));
    return report(tradeDate, offenders, compared == null ? 0 : compared);
  }

  /**
   * Builds the report, applying the divergence rule first and the coverage floor on top of it.
   *
   * <p>⚠️ The floor is applied as a ONE-WAY escalation, never as a replacement. Computing the
   * status as "short coverage ⇒ YELLOW, else the divergence rule" reads equivalent and is not: it
   * would DOWNGRADE a RED to a YELLOW exactly when the population is small, i.e. silence the worst
   * verdict in the situation the floor exists to flag. Coverage may only turn GREEN into YELLOW.
   */
  private BhavcopyCloseReport report(
      LocalDate tradeDate, List<CloseMismatch> offenders, int comparedCount) {
    int divergent = offenders.size();
    String byDivergence = divergent == 0 ? GREEN : divergent >= redFloor ? RED : YELLOW;
    String status =
        comparedCount < minCompared && GREEN.equals(byDivergence) ? YELLOW : byDivergence;
    List<CloseMismatch> sample =
        offenders.size() > sampleLimit ? offenders.subList(0, sampleLimit) : offenders;
    return new BhavcopyCloseReport(
        tradeDate,
        status,
        comparedCount,
        divergent,
        minCompared,
        threshold.multiply(BigDecimal.valueOf(100)).setScale(2, java.math.RoundingMode.HALF_UP),
        List.copyOf(sample));
  }

  private void publish(BhavcopyCloseReport report) {
    if (GREEN.equals(report.status())) {
      // Names the population on purpose. "202 symbols compared, none diverge" invites the reader to
      // hear "the close feed is clean" — but this is a large-cap SAMPLE, and an idiosyncratic bad
      // print outside it is exactly what a GREEN here cannot speak to (see the class javadoc).
      //
      // "after seeding", not "of": `compared` is every dual-sourced EQ row (see BhavcopyCloseReport),
      // which is the seeded sample PLUS other jobs' leftovers — measured 215 against a 202-symbol
      // seed. "215 of the NIFTY 200 sample" would be arithmetically impossible and would read as a
      // bug in the count rather than as the residual it is.
      log.info(
          "bhavcopy-close canary GREEN for {} — {} symbols compared after seeding the '{}' list,"
              + " none diverge > {}% (systemic agreement only; per-symbol correctness outside the"
              + " sample is unchecked)",
          report.tradeDate(), report.compared(), populationIndex, report.thresholdPct());
      return;
    }
    divergenceCounter.increment(report.divergent());
    if (report.divergent() == 0) {
      // Coverage is the ONLY reason this is not GREEN, so the operator message must say so.
      // Reusing the divergence wording here would page about "0 of 14 symbols diverge", which
      // describes a clean run and buries the actual finding.
      String message =
          "only " + report.compared() + " symbols had both a bhavcopy row and a Kite 1d bar on "
              + report.tradeDate() + " (floor " + report.minCompared() + ") — nothing diverged, but"
              + " that population is too small to certify the close feed, so this is NOT a clean"
              + " run";
      log.warn("bhavcopy-close canary YELLOW (coverage): {}", message);
      sendAlert("ArthaYantra bhavcopy-close coverage", "default", message);
      return;
    }
    boolean coverageShort = report.compared() < report.minCompared();
    String samples =
        report.offenders().stream()
            .map(o -> o.symbol() + " (bhav " + o.bhavClose() + " vs kite " + o.kiteClose() + ", " + o.relDiffPct() + "%)")
            .reduce((a, b) -> a + ", " + b)
            .orElse("");
    String message =
        report.divergent() + " of " + report.compared() + " symbols diverge > "
            + report.thresholdPct() + "% on " + report.tradeDate() + ": " + samples
            + (coverageShort
                ? " — and only " + report.compared() + " symbols were comparable (floor "
                    + report.minCompared() + "), so this rate is measured on a population too small"
                    + " to certify the feed either way"
                : "");
    if (RED.equals(report.status())) {
      log.error("bhavcopy-close canary RED: {}", message);
      sendAlert("ArthaYantra bhavcopy-close divergence", "urgent", message);
    } else {
      log.warn("bhavcopy-close canary YELLOW: {}", message);
      sendAlert("ArthaYantra bhavcopy-close divergence", "default", message);
    }
  }

  private void sendAlert(String title, String priority, String message) {
    if (alertsEnabled) {
      ntfy.send(title, priority, message);
    }
  }
}
