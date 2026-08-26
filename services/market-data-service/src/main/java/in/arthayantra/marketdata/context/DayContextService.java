package in.arthayantra.marketdata.context;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.canary.IngestHealthBoard;
import in.arthayantra.marketdata.candles.Candle;
import in.arthayantra.marketdata.candles.CandleQueryService;
import in.arthayantra.marketdata.instruments.UnderlyingRef;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import in.arthayantra.marketdata.kite.VixQuoteCache;
import in.arthayantra.marketdata.options.OptionsDigestService;
import in.arthayantra.marketdata.upstox.UpstoxGlobalInstrumentsClient;
import in.arthayantra.marketdata.upstox.WorldIndex;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Day-context digest (intelligence-layer design 2026-07-10 §6.1.5) — the dashboard one-call the Focus
 * panel and the day-context strip render. It BUNDLES the market-data-native context plane for I1: the
 * options-digest headline (§6.1.1) for the primary index, the INDIA VIX level/Δ/band, the index
 * price-action read (gap-open / day-range vs its 20-session average / direction, off the existing
 * daily candles), the overnight global cues (top-mover world indices), session phase + holiday
 * proximity ({@link MarketCalendar}), and the ingest-trust summary (the {@code ingest_runs} batch-
 * source oracle via {@link IngestHealthBoard}).
 *
 * <p><b>Scope (I1).</b> The futures / equity / FII headline blocks (§6.1.2–4) are I2 and are NOT
 * bundled here — day-context carries only the blocks whose folds exist today, never a fabricated one
 * (§6.5). Every live-quote-backed block (VIX, overnight cues) is fail-soft: it degrades to a null/empty
 * value with a {@code notes} entry off-hours or when Upstox analytics is off, never a 5xx.
 *
 * <p><b>Trust.</b> The {@code ingestTrust} block is the batch-source ledger read (the §7 trust oracle's
 * ingest half). The full cross-service {@code TrustService} composition (adding live-capture health,
 * dot-health, and the market calendar) is the insights module's job in a later increment.
 */
@Service
public class DayContextService {

  /** INDIA VIX quote (level + change vs prev close + a coarse regime band). Null when unavailable. */
  public record Vix(
      @Schema(type = "string") BigDecimal level,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal change,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal changePct,
      String band,
      @Schema(types = {"string", "null"}) OffsetDateTime asOf) {}

  /** One overnight global-index cue (top mover by |%change|). */
  public record GlobalCue(
      @Schema(types = {"string", "null"}) String name,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal ltp,
      @Schema(type = "string") BigDecimal changePct) {}

  /** Primary-index price action off the daily candles — the intraday/overnight structure read. */
  public record IndexPriceAction(
      String symbol,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal gapOpenPct,
      @Schema(type = "string") BigDecimal dayRange,
      @Schema(type = "string") BigDecimal avgRange20,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal rangeVsAvg,
      String direction,
      @Schema(types = {"string", "null"}) String rangeState,
      LocalDate asOfDate) {}

  /** Holiday proximity: whether today is a listed holiday and the next one (name + days away). */
  public record HolidayProximity(
      boolean holidayToday,
      @Schema(types = {"string", "null"}) LocalDate nextHoliday,
      @Schema(types = {"string", "null"}) String nextHolidayName,
      @Schema(types = {"integer", "null"}) Integer daysToNextHoliday) {}

  /** One batch source's trust cell (mapped from the ingest health board). */
  public record SourceTrust(
      String source,
      String status,
      @Schema(types = {"string", "null"}) String lastRunStatus,
      @Schema(types = {"string", "null"}) OffsetDateTime lastRunAt,
      boolean stale) {}

  /** The ingest-trust summary: worst-of overall + per-source cells (OK / DEGRADED / BLOCKED). */
  public record IngestTrust(String overall, List<SourceTrust> sources) {}

  /**
   * The EXPENSIVE half of a day context — everything that reaches an upstream (options digest, VIX
   * quote, Upstox world indices, the daily-candle read, the ingest-health board) plus the {@code
   * notes} those reads produced, stamped with the instant it was assembled.
   *
   * <p>⚠️ Deliberately holds NOTHING now-dependent. {@code tradeDate}, {@code sessionPhase},
   * {@code holiday} and {@code asOf} are recomputed on EVERY request — see {@link #dayContext()}.
   */
  private record Heavy(
      OptionsDigestService.OptionsDigest options,
      Vix vix,
      List<GlobalCue> cues,
      IndexPriceAction indexPriceAction,
      IngestTrust ingestTrust,
      List<String> notes,
      Instant computedAt) {}

  /** The bundled day context. */
  public record DayContext(
      LocalDate tradeDate,
      String sessionPhase,
      HolidayProximity holiday,
      @Schema(types = {"object", "null"}) OptionsDigestService.OptionsDigest options,
      @Schema(types = {"object", "null"}) Vix vix,
      List<GlobalCue> overnightCues,
      @Schema(types = {"object", "null"}) IndexPriceAction indexPriceAction,
      IngestTrust ingestTrust,
      OffsetDateTime asOf,
      List<String> notes) {}

  private static final Logger log = LoggerFactory.getLogger(DayContextService.class);
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  // Coarse INDIA-VIX regime bands (heuristic defaults, config-overridable). Labels only — never a gate.
  private static final BigDecimal RANGE_WIDE = BigDecimal.valueOf(1.2);
  private static final BigDecimal RANGE_NARROW = BigDecimal.valueOf(0.8);
  // H31 snapshot meters. SECONDARY to the INFO lines: a flat hit counter cannot tell "served
  // from cache" apart from "nobody called", the ambiguity that let H31 pass a live-verify once.
  private static final String HIT_TOTAL = "ay_day_context_snapshot_hit_total";
  private static final String INLINE_TOTAL = "ay_day_context_snapshot_inline_total";
  private static final String UNCACHED_TOTAL = "ay_day_context_uncached_total";
  private static final String REFRESH_TOTAL = "ay_day_context_snapshot_refresh_total";
  private static final String REFRESH_FAILED_TOTAL = "ay_day_context_snapshot_refresh_failed_total";

  private final OptionsDigestService optionsDigest;
  private final VixQuoteCache vixQuotes;
  private final ObjectProvider<UpstoxGlobalInstrumentsClient> worldIndices;
  private final CandleQueryService candles;
  private final IngestHealthBoard healthBoard;
  private final MarketCalendar calendar;
  private final Clock clock;
  private final MeterRegistry meterRegistry;
  private final InstrumentKey vixKey;
  private final String optionsName;
  private final String indexExchange;
  private final String indexSymbol;
  private final int overnightTopN;
  private final int trustLookback;
  private final BigDecimal vixNormal;
  private final BigDecimal vixElevated;
  private final BigDecimal vixHigh;
  private final Duration snapshotMaxAge;

  /**
   * The latest intraday snapshot of the expensive half, or null before the first refresh. Written
   * only by the scheduled refresher, read by every request — {@code volatile} is sufficient because
   * {@link Heavy} is immutable and publication is a single reference store.
   */
  private volatile Heavy snapshot;

  /** Wires the reused digest + the market-data-native context ports and the display/config knobs. */
  public DayContextService(
      OptionsDigestService optionsDigest,
      VixQuoteCache vixQuotes,
      ObjectProvider<UpstoxGlobalInstrumentsClient> worldIndices,
      CandleQueryService candles,
      IngestHealthBoard healthBoard,
      MarketCalendar calendar,
      Clock clock,
      MeterRegistry meterRegistry,
      @Value("${artha.context.options-name:NIFTY 50}") String optionsName,
      @Value("${artha.context.index-exchange:NSE}") String indexExchange,
      @Value("${artha.context.index-symbol:NIFTY 50}") String indexSymbol,
      @Value("${artha.context.vix-instrument:INDIA VIX}") String vixSymbol,
      @Value("${artha.context.overnight-top-n:5}") int overnightTopN,
      @Value("${artha.context.trust-lookback-days:5}") int trustLookback,
      @Value("${artha.context.vix-normal:13}") BigDecimal vixNormal,
      @Value("${artha.context.vix-elevated:17}") BigDecimal vixElevated,
      @Value("${artha.context.vix-high:22}") BigDecimal vixHigh,
      @Value("${artha.context.day-context-snapshot-max-age-seconds:300}") long snapshotMaxAgeSeconds) {
    this.optionsDigest = optionsDigest;
    this.vixQuotes = vixQuotes;
    this.worldIndices = worldIndices;
    this.candles = candles;
    this.healthBoard = healthBoard;
    this.calendar = calendar;
    this.clock = clock;
    this.meterRegistry = meterRegistry;
    this.vixKey = new InstrumentKey("NSE", vixSymbol);
    // ⚠️ NORMALISED, not trusted. The default was a bare `NIFTY` for the whole life of this
    // feature, which is not a canonical instrument key: OptionsDigestService answered "no option
    // expiries for NIFTY", dayContext() fail-softed it into a note, and market_context_days
    // persisted 26 ROWS spanning 2026-07-13..2026-08-19 with expiry/pcr/max_pain/atm_straddle/
    // atm_iv ALL NULL while the job logged "persisted ... (1 row)" on each of those nights.
    // ⚠️ NOT "26 consecutive trading days" -- the range holds 28, and 2026-07-17 and 2026-08-12
    // have no row AND no MARKET_CONTEXT_DAY run at all (neither is an NSE holiday). That is a
    // SECOND, separate hole the tidier phrasing concealed (review) -- traced the same evening and
    // ALREADY FIXED: the whole evening chain is missing both nights, both dates pre-date #1358, and
    // before it the chain ran 19:30-21:15, outside the hours the machine is up.
    // Fixing only the default would leave the next person free to write the alias again.
    this.optionsName = UnderlyingRef.canonical(optionsName);
    this.indexExchange = indexExchange;
    this.indexSymbol = indexSymbol;
    this.overnightTopN = overnightTopN;
    this.trustLookback = trustLookback;
    this.vixNormal = vixNormal;
    this.vixElevated = vixElevated;
    this.vixHigh = vixHigh;
    this.snapshotMaxAge = Duration.ofSeconds(snapshotMaxAgeSeconds);
  }

  /**
   * Assemble the day-context one-call for the configured primary index: FRESH now-dependent values
   * composed with the (possibly cached) expensive half. This is the CONTROLLER's entry point.
   *
   * <p><b>H31.</b> The whole assembly cost ~1.9 s server-side against {@code ContextClient}'s 2000 ms
   * read budget, so {@code InsightSweeper}'s 15-minute sweep intermittently timed out and DISCARDED
   * work the server had already finished (measured 2026-08-26: 09:00 and 09:15 succeeded, 09:30
   * refreshed 0, 09:45:02 logged "Read timed out"). The expensive half is now precomputed by
   * {@link #refreshSnapshot()} on its own schedule so no caller pays the queued read inline.
   *
   * <p>⚠️ <b>{@code phase}, {@code nowIst}, {@code tradeDate} and {@code holiday} are NEVER served
   * from the snapshot.</b> The sweep fires at exactly 09:15:01; a cached phase would report a stale
   * {@code PRE_OPEN} across the opening bell — a wrong regime label handed to every downstream
   * insight. Only the upstream-backed values are cached.
   *
   * <p>A caller that must NOT see a cached upstream read calls {@link #freshDayContext()} instead.
   */
  public DayContext dayContext() {
    return compose(false);
  }

  /**
   * The day context with the expensive half ALWAYS computed inline — never served from the snapshot.
   *
   * <p><b>Why this exists rather than relying on the max age.</b> {@link MarketContextEodJob} writes
   * ONE {@code market_context_days} row per session and it must hold the day's CLOSING context. The
   * arithmetic happens to work today — the refresher's window ends at 15:58, the job runs at 18:49,
   * so the snapshot is ~171 minutes old and blows past any sane max age — but that is a coincidence
   * of two INDEPENDENTLY CONFIGURABLE crons plus a third knob. Widening
   * {@code artha.context.day-context-refresh-cron} past 18:xx, or raising
   * {@code artha.context.day-context-snapshot-max-age-seconds}, would silently start persisting a
   * mid-afternoon context as the day's close — with no commit to the job, no deploy of it, and no
   * review of it. That is the behaviour-that-arms-itself shape: a config-gated rule that changes
   * what gets written on a date nobody chose. Making the EOD path structurally uncached removes the
   * dependency instead of documenting it.
   */
  public DayContext freshDayContext() {
    return compose(true);
  }

  /** Compose fresh now-dependent values with either a cache-eligible or a forced-fresh heavy half. */
  private DayContext compose(boolean forceFresh) {
    OffsetDateTime nowIst = OffsetDateTime.now(clock).withOffsetSameInstant(Ist.OFFSET);
    LocalDate today = nowIst.toLocalDate();
    List<String> notes = new ArrayList<>();

    boolean tradingDay;
    LocalDate tradeDate;
    try {
      tradingDay = calendar.isTradingDay(today);
      tradeDate = tradingDay ? today : calendar.previousTradingDay(today);
    } catch (IllegalArgumentException uncoveredYear) {
      tradingDay = false;
      tradeDate = today;
      notes.add("trading calendar does not cover " + today);
    }
    String phase = sessionPhase(tradingDay, nowIst.toLocalTime());
    HolidayProximity holiday = holidayProximity(today);

    Heavy heavy =
        forceFresh
            ? inlineHeavy(nowIst, "caller requires an uncached read", UNCACHED_TOTAL)
            : heavy(nowIst, notes);
    notes.addAll(heavy.notes());

    return new DayContext(
        tradeDate,
        phase,
        holiday,
        heavy.options(),
        heavy.vix(),
        heavy.cues(),
        heavy.indexPriceAction(),
        heavy.ingestTrust(),
        nowIst,
        List.copyOf(notes));
  }

  /**
   * The held snapshot when it is younger than {@code artha.context.day-context-snapshot-max-age-
   * seconds}, else a fresh inline compute — byte-for-byte what {@code dayContext()} did before H31.
   *
   * <p>Every inline fallback is logged at INFO with its REASON. That is deliberate and it is the
   * primary live-verify signal for this change: the inline path is the BAD case, so the gate fires
   * on an EVENT rather than on elapsed time. H31 was once marked DONE on a measurement taken in the
   * one regime where the defect could not occur; "did the 09:15 sweep read a snapshot or compute
   * inline?" must be answerable from the log, not inferred from the sweep having succeeded.
   *
   * <p>⚠️ <b>A cache hit adds a {@code notes} entry carrying the age, and that is not decoration.</b>
   * {@code compose()} stamps {@code asOf = nowIst}, so on the cached path {@code asOf} describes the
   * REQUEST, not the VIX band or the index range beside it — and those are exactly what
   * {@code MarketStructureGenerator:57,64} cites as {@code s.asOf()} on the evidence line of a
   * PERSISTED insight. Without the note, an insight would claim second-fresh evidence for a reading
   * up to {@code max-age} old. {@code notes} is the established provenance channel here (the
   * fail-soft blocks already use it), and the EOD row goes through {@code forceFresh}, so nothing
   * downstream shifts.
   */
  private Heavy heavy(OffsetDateTime nowIst, List<String> notes) {
    Heavy held = this.snapshot;
    if (held == null) {
      return inlineHeavy(nowIst, "no snapshot has been refreshed yet", INLINE_TOTAL);
    }
    if (snapshotMaxAge.isZero() || snapshotMaxAge.isNegative()) {
      return inlineHeavy(
          nowIst,
          "snapshot caching disabled (max-age " + snapshotMaxAge.toSeconds() + "s)",
          INLINE_TOTAL);
    }
    Duration age = Duration.between(held.computedAt(), clock.instant());
    if (age.isNegative()) {
      return inlineHeavy(
          nowIst, "snapshot computedAt is in the future (clock moved back)", INLINE_TOTAL);
    }
    if (age.compareTo(snapshotMaxAge) > 0) {
      return inlineHeavy(
          nowIst,
          "snapshot age " + age.toSeconds() + "s exceeds max-age " + snapshotMaxAge.toSeconds() + "s",
          INLINE_TOTAL);
    }
    meterRegistry.counter(HIT_TOTAL).increment();
    notes.add("heavy half computed " + age.toSeconds() + "s ago");
    return held;
  }

  /** Compute the heavy half inline, timing it and saying WHY the snapshot was not used. */
  private Heavy inlineHeavy(OffsetDateTime nowIst, String reason, String counter) {
    long startedAt = System.nanoTime();
    Heavy fresh = computeHeavy(nowIst);
    meterRegistry.counter(counter).increment();
    log.info(
        "day-context INLINE compute ({}) — this caller paid the upstream reads, took {} ms",
        reason,
        (System.nanoTime() - startedAt) / 1_000_000L);
    return fresh;
  }

  /** Every upstream-backed block of a day context, exactly as {@code dayContext()} computed them. */
  private Heavy computeHeavy(OffsetDateTime nowIst) {
    List<String> notes = new ArrayList<>();

    OptionsDigestService.OptionsDigest options = null;
    try {
      options = optionsDigest.digest(optionsName, null, null);
    } catch (RuntimeException e) {
      notes.add("options digest unavailable for " + optionsName + ": " + e.getMessage());
    }

    Vix vix = vix(notes);
    List<GlobalCue> cues = overnightCues(notes);
    IndexPriceAction ipa = indexPriceAction(nowIst, notes);
    IngestTrust trust = ingestTrust();

    return new Heavy(options, vix, cues, ipa, trust, List.copyOf(notes), clock.instant());
  }

  /**
   * Precompute the expensive half so the 15-minute insight sweep never pays it inline (H31).
   *
   * <p><b>Why :13,:28,:43,:58 and not "every N minutes".</b> The consumer sweeps at :00/:15/:30/:45,
   * so refreshing two minutes ahead of each puts a &le;2-minute-old snapshot in front of every sweep
   * for <b>four extra upstream passes a session</b>: this cron runs 4/hr over hours 8–15 = <b>32</b>,
   * against the <b>28</b> the sweep alone used to cost (4/hr over hours 9–15), before any inline
   * fallback. ⚠️ An earlier cut of this javadoc — and of the compose comment beside the env var —
   * claimed the SAME number of calls. That was simply wrong arithmetic, not a design change; the
   * extra four buy the pre-open warm-up that stops the 09:00 sweep computing inline. A faster
   * refresh would TRIPLE Upstox load — and {@link #overnightCues} is an UNCACHED Upstox call with 429
   * backoff up to 16 s, i.e. the leading suspect for the residual latency. Refreshing harder would
   * worsen the cause while treating the symptom.
   *
   * <p>Fail-soft on purpose: a failed refresh leaves the PREVIOUS snapshot in place and never
   * propagates, so one bad upstream minute degrades freshness rather than the endpoint.
   *
   * <p>⚠️ <b>The success line here is what proves the refresher RAN</b>, independently of whether
   * anything read the result. {@code ay_day_context_snapshot_hit_total} alone CANNOT distinguish
   * "the sweep was served from cache" from "the sweep never ran" — both leave it flat, and that
   * exact ambiguity is why H31's first live-verify gate passed on a defect that was still live. The
   * two INFO lines (this one and the inline-fallback one) are the primary signal; the counters are
   * secondary, for trend only.
   *
   * <p>⚠️ <b>Bound to {@code dayContextTaskScheduler}, and that binding is load-bearing.</b> The
   * default pool is a SINGLE thread shared with {@code OptionsSnapshotService.scheduledSnapshot}
   * (every 2 min, ~70 s per pass), so a refresh queued behind one could land after the :15 sweep and
   * reproduce H31 with every test still green. See that bean's javadoc.
   */
  @Scheduled(
      // ⚠️ `cron` and `zone` must stay on ONE line: CronPassthroughParityTest matches the
      // @Scheduled site PER LINE (activeCronSites) and asserts the zone on that same slice, so
      // wrapping zone onto the next line reads to it as an unzoned job. Same rule, same reason, as
      // EveningChainCanary — the comment lives in both files so neither can be "tidied" back alone.
      cron = "${artha.context.day-context-refresh-cron:0 13,28,43,58 8-15 * * MON-FRI}", zone = "Asia/Kolkata",
      scheduler = "dayContextTaskScheduler")
  public void refreshSnapshot() {
    long startedAt = System.nanoTime();
    try {
      OffsetDateTime nowIst = OffsetDateTime.now(clock).withOffsetSameInstant(Ist.OFFSET);
      // MON-FRI still fires on a weekday NSE holiday; without this the day costs 32 pointless
      // upstream passes. Fail-soft on an uncovered calendar year: refresh rather than skip, since
      // skipping would silently disable the whole precompute past the bundled horizon.
      if (!tradingDaySafe(nowIst.toLocalDate())) {
        // Logged, not silent: without this line "skipped, holiday" and "never fired at all" are
        // indistinguishable in the log — the same read-the-absence ambiguity this class's own
        // javadoc criticises the hit counter for.
        log.info(
            "day-context snapshot refresh skipped — {} is not a trading day", nowIst.toLocalDate());
        return;
      }
      this.snapshot = computeHeavy(nowIst);
      meterRegistry.counter(REFRESH_TOTAL).increment();
      log.info(
          "day-context snapshot refreshed in {} ms (max-age {}s)",
          (System.nanoTime() - startedAt) / 1_000_000L,
          snapshotMaxAge.toSeconds());
    } catch (RuntimeException failed) {
      meterRegistry.counter(REFRESH_FAILED_TOTAL).increment();
      log.warn(
          "day-context snapshot refresh failed (keeping the previous snapshot): {}",
          failed.getMessage());
    }
  }

  /** Trading-day check that degrades to TRUE past the bundled calendar horizon (never skips). */
  private boolean tradingDaySafe(LocalDate day) {
    try {
      return calendar.isTradingDay(day);
    } catch (IllegalArgumentException uncoveredYear) {
      return true;
    }
  }

  private static String sessionPhase(boolean tradingDay, LocalTime now) {
    if (!tradingDay) {
      return "HOLIDAY";
    }
    if (now.isBefore(MarketCalendar.SESSION_OPEN)) {
      return "PRE_OPEN";
    }
    if (!now.isBefore(MarketCalendar.SESSION_CLOSE)) {
      return "POST_CLOSE";
    }
    return "OPEN";
  }

  private HolidayProximity holidayProximity(LocalDate today) {
    List<MarketCalendar.Holiday> all = calendar.holidayList();
    boolean holidayToday = all.stream().anyMatch(h -> h.date().equals(today));
    MarketCalendar.Holiday next =
        all.stream()
            .filter(h -> h.date().isAfter(today))
            .min(Comparator.comparing(MarketCalendar.Holiday::date))
            .orElse(null);
    return new HolidayProximity(
        holidayToday,
        next == null ? null : next.date(),
        next == null ? null : next.name(),
        next == null ? null : (int) ChronoUnit.DAYS.between(today, next.date()));
  }

  private Vix vix(List<String> notes) {
    try {
      // H31: the VIX read is the single largest cost in this assembly -- it queues behind the
      // 1/s kite-quote limiter, measured at ~1.5 s of a ~1.9 s day-context against the insight
      // sweep's 2000 ms read timeout. Served from the short-TTL cache instead of a fresh call.
      QuoteGateway.Quote q = vixQuotes.quote(vixKey).orElse(null);
      if (q == null) {
        notes.add("INDIA VIX quote unavailable (off-hours / analytics off)");
        return null;
      }
      QuoteGateway.Quote.Ohlc o = q.ohlc();
      BigDecimal prevClose = o == null ? null : o.close();
      BigDecimal change = prevClose == null ? null : q.lastPrice().subtract(prevClose);
      BigDecimal changePct =
          prevClose == null || prevClose.signum() == 0
              ? null
              : change.multiply(HUNDRED).divide(prevClose, 4, RoundingMode.HALF_UP);
      return new Vix(q.lastPrice(), change, changePct, vixBand(q.lastPrice()), q.timestamp());
    } catch (RuntimeException e) {
      notes.add("INDIA VIX read failed: " + e.getMessage());
      return null;
    }
  }

  /** Coarse regime label from the VIX level (LOW / NORMAL / ELEVATED / HIGH) — a display band only. */
  private String vixBand(BigDecimal level) {
    if (level.compareTo(vixNormal) < 0) {
      return "LOW";
    }
    if (level.compareTo(vixElevated) < 0) {
      return "NORMAL";
    }
    if (level.compareTo(vixHigh) < 0) {
      return "ELEVATED";
    }
    return "HIGH";
  }

  private List<GlobalCue> overnightCues(List<String> notes) {
    UpstoxGlobalInstrumentsClient client = worldIndices.getIfAvailable();
    if (client == null) {
      notes.add("overnight global cues unavailable (upstox analytics off)");
      return List.of();
    }
    return client.worldIndices().stream()
        .filter(w -> w.changePct() != null)
        .sorted(Comparator.comparing((WorldIndex w) -> w.changePct().abs()).reversed())
        .limit(overnightTopN)
        .map(w -> new GlobalCue(w.name(), w.ltp(), w.changePct()))
        .toList();
  }

  private IndexPriceAction indexPriceAction(OffsetDateTime nowIst, List<String> notes) {
    try {
      CandleQueryService.CandleRead read =
          candles.read(indexExchange, indexSymbol, "1d", nowIst.minusDays(45), nowIst);
      List<Candle> bars = read.items();
      if (bars.size() < 2) {
        notes.add("index price action unavailable (fewer than 2 daily bars for " + indexSymbol + ")");
        return null;
      }
      Candle todayBar = bars.get(bars.size() - 1);
      Candle priorBar = bars.get(bars.size() - 2);
      BigDecimal prevClose = priorBar.close();
      BigDecimal gapOpenPct =
          prevClose == null || prevClose.signum() == 0
              ? null
              : todayBar.open().subtract(prevClose).multiply(HUNDRED).divide(prevClose, 2, RoundingMode.HALF_UP);
      BigDecimal dayRange = todayBar.high().subtract(todayBar.low());
      int window = Math.min(20, bars.size() - 1);
      BigDecimal sum = BigDecimal.ZERO;
      for (int i = bars.size() - 1 - window; i < bars.size() - 1; i++) {
        Candle b = bars.get(i);
        sum = sum.add(b.high().subtract(b.low()));
      }
      BigDecimal avgRange20 =
          window == 0 ? null : sum.divide(BigDecimal.valueOf(window), 4, RoundingMode.HALF_UP);
      BigDecimal rangeVsAvg =
          avgRange20 == null || avgRange20.signum() == 0
              ? null
              : dayRange.divide(avgRange20, 2, RoundingMode.HALF_UP);
      int dir = todayBar.close().compareTo(todayBar.open());
      String direction = dir > 0 ? "UP" : dir < 0 ? "DOWN" : "FLAT";
      String rangeState =
          rangeVsAvg == null
              ? null
              : rangeVsAvg.compareTo(RANGE_WIDE) > 0
                  ? "WIDE"
                  : rangeVsAvg.compareTo(RANGE_NARROW) < 0 ? "NARROW" : "NORMAL";
      return new IndexPriceAction(
          indexSymbol,
          gapOpenPct,
          dayRange,
          avgRange20,
          rangeVsAvg,
          direction,
          rangeState,
          todayBar.bucket().atZoneSameInstant(Ist.ZONE).toLocalDate());
    } catch (RuntimeException e) {
      log.warn("day-context index price action read failed for {}: {}", indexSymbol, e.getMessage());
      notes.add("index price action read failed: " + e.getMessage());
      return null;
    }
  }

  private IngestTrust ingestTrust() {
    IngestHealthBoard.BoardReport board = healthBoard.board(trustLookback);
    List<SourceTrust> sources = new ArrayList<>();
    String overall = "OK";
    for (IngestHealthBoard.SourceHealth s : board.sources()) {
      String status = toTrust(s.status());
      overall = worse(overall, status);
      IngestHealthBoard.LastRun lr = s.lastRun();
      OffsetDateTime lastRunAt =
          lr == null || lr.startedAt() == null
              ? null
              : OffsetDateTime.ofInstant(lr.startedAt(), Ist.ZONE);
      sources.add(
          new SourceTrust(
              s.source(), status, lr == null ? null : lr.status(), lastRunAt, lr != null && lr.stale()));
    }
    return new IngestTrust(overall, List.copyOf(sources));
  }

  /** Map the health board's GREEN/YELLOW/RED verdict to the layer's OK/DEGRADED/BLOCKED trust states. */
  private static String toTrust(String boardStatus) {
    return switch (boardStatus) {
      case "GREEN" -> "OK";
      case "YELLOW" -> "DEGRADED";
      default -> "BLOCKED"; // RED / unknown
    };
  }

  /** The worse of two trust states (BLOCKED > DEGRADED > OK). */
  private static String worse(String a, String b) {
    return rank(b) > rank(a) ? b : a;
  }

  private static int rank(String trust) {
    return switch (trust) {
      case "OK" -> 0;
      case "DEGRADED" -> 1;
      default -> 2; // BLOCKED
    };
  }
}
