package in.arthayantra.backtest.replay.counterfactual;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.backtest.dispatch.JobCancelledException;
import in.arthayantra.backtest.dispatch.JobStreamDispatcher;
import in.arthayantra.backtest.dispatch.ProgressPublisher;
import in.arthayantra.backtest.jobs.Job;
import in.arthayantra.backtest.jobs.JobKind;
import in.arthayantra.backtest.jobs.JobRepository;
import in.arthayantra.backtest.replay.CandleReader;
import in.arthayantra.backtest.replay.CostConfig;
import in.arthayantra.backtest.replay.counterfactual.CounterfactualResult.VariantResult;
import in.arthayantra.backtest.replay.options.CandlePremiumReader;
import in.arthayantra.backtest.replay.options.PremiumExitEvaluator;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.strategyengine.fills.FillSimulator.Fill;
import in.arthayantra.strategyengine.fills.FillSimulator.FillRequest;
import in.arthayantra.strategyengine.fills.LtpSlippageV1;
import in.arthayantra.strategyengine.fills.Side;
import in.arthayantra.strategyengine.series.EngineCandle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The counterfactual-replay job type (EVO E3 item 9, §3.3.4) — the runbook §4.B "counterfactual band
 * grid" formalized. {@link #submit} validates + captured-data-guards + resolves lot sizes and pins a
 * self-contained COUNTERFACTUAL job onto the existing queue; {@link #run} (called from
 * {@code BacktestRunner} for a COUNTERFACTUAL job) replays each observed entry's captured premium under
 * every variant's exit knobs and persists the per-variant metrics.
 *
 * <p><b>Captured real premium only.</b> Each entry's premium PATH is read from the stored
 * {@code marketdata.candles} of the ACTUAL option contract (never fabricated, never the derived
 * OI/chain path); {@link CapturedPremiumGuard} refuses at submission (422) any leg/window without real
 * captured provenance. <b>Deterministic:</b> premium from stored candles, entry/qty from the request,
 * {@link PremiumExitEvaluator} + the shared {@code FillSimulator} — no wall-clock, no randomness, so the
 * same request yields byte-identical variant metrics. Intraday-scoped: each entry is replayed from its
 * {@code entryTime} to that day's {@link #SESSION_CLOSE} IST (the END_OF_DATA square-off backstop).
 */
@Service
public class CounterfactualService {

  private static final Logger log = LoggerFactory.getLogger(CounterfactualService.class);

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  /** The intraday square-off backstop — each entry's premium replays until this time on its own day. */
  static final LocalTime SESSION_CLOSE = LocalTime.of(15, 30);
  /** Fat-finger admission bounds (not policy limits — an offline evidence tool). */
  private static final int MAX_ENTRIES = 5000;
  private static final int MAX_VARIANTS = 64;

  private static final TypeReference<List<PinnedEntry>> ENTRY_LIST = new TypeReference<>() {};
  private static final TypeReference<List<CounterfactualRunRequest.Variant>> VARIANT_LIST =
      new TypeReference<>() {};

  private final JobRepository jobs;
  private final JobStreamDispatcher dispatcher;
  private final ProgressPublisher progress;
  private final CapturedPremiumGuard guard;
  private final CandleReader candleReader;
  private final CounterfactualRunRepository runs;
  private final ObjectMapper objectMapper;
  private final int maxQueued;

  /** The shared fill model — the SAME one the candle/premium paths + paper ledger use (paisa-parity). */
  private final LtpSlippageV1 fills = new LtpSlippageV1();

  /** Wires the collaborators. */
  public CounterfactualService(
      JobRepository jobs,
      JobStreamDispatcher dispatcher,
      ProgressPublisher progress,
      CapturedPremiumGuard guard,
      CandleReader candleReader,
      CounterfactualRunRepository runs,
      ObjectMapper objectMapper,
      @Value("${artha.backtest.max-queued:500}") int maxQueued) {
    this.jobs = jobs;
    this.dispatcher = dispatcher;
    this.progress = progress;
    this.guard = guard;
    this.candleReader = candleReader;
    this.runs = runs;
    this.objectMapper = objectMapper;
    this.maxQueued = maxQueued;
  }

  // ---- submission -----------------------------------------------------------------------------

  /**
   * Validates the request, captured-data-guards + lot-size-resolves every entry (fail-fast 422), pins a
   * self-contained COUNTERFACTUAL job (entries + variants in the request JSONB, no strategy version) and
   * dispatches it onto the existing backtest queue. Returns the queued job.
   */
  public Job submit(CounterfactualRunRequest req, String correlationId) {
    long queued = jobs.countQueued(JobKind.COUNTERFACTUAL);
    if (queued >= maxQueued) {
      throw new ApiException(
          429,
          ErrorCodes.RATE_LIMIT_QUEUE,
          "counterfactual queue is saturated (" + queued + " queued, cap " + maxQueued + ")");
    }

    List<CounterfactualRunRequest.Entry> entries = req.entries();
    List<CounterfactualRunRequest.Variant> variants = req.variants();
    if (entries == null || entries.isEmpty()) {
      throw badRequest("at least one entry is required");
    }
    if (entries.size() > MAX_ENTRIES) {
      throw badRequest("too many entries (" + entries.size() + "); cap " + MAX_ENTRIES);
    }
    if (variants == null || variants.isEmpty()) {
      throw badRequest("at least one variant is required");
    }
    if (variants.size() > MAX_VARIANTS) {
      throw badRequest("too many variants (" + variants.size() + "); cap " + MAX_VARIANTS);
    }
    OffsetDateTime from = parseBound(req.from(), "from");
    OffsetDateTime to = parseBound(req.to(), "to");
    if (!from.isBefore(to)) {
      throw badRequest("from must be strictly before to");
    }
    validateVariants(variants);

    List<PinnedEntry> pinned = new ArrayList<>(entries.size());
    for (int i = 0; i < entries.size(); i++) {
      pinned.add(validateAndGuard(entries.get(i), i, from, to));
    }

    JsonNode request =
        objectMapper.valueToTree(
            new PinnedRequest(true, from.toString(), to.toString(), pinned, variants));
    Job job = jobs.insertQueued(JobKind.COUNTERFACTUAL, null, null, request, correlationId, "owner");
    dispatcher.dispatchBacktest(job.id());
    progress.refreshSummary();
    return job;
  }

  private void validateVariants(List<CounterfactualRunRequest.Variant> variants) {
    java.util.Set<String> names = new java.util.HashSet<>();
    for (CounterfactualRunRequest.Variant v : variants) {
      if (v == null || v.name() == null || v.name().isBlank()) {
        throw badRequest("every variant needs a non-blank name");
      }
      if (!names.add(v.name())) {
        throw badRequest("duplicate variant name: " + v.name());
      }
      ExitKnobs k = v.exitKnobs();
      if (k == null) {
        throw badRequest("variant '" + v.name() + "' is missing exitKnobs");
      }
      requirePositive(k.takeProfitPct(), v.name(), "takeProfitPct");
      requirePositive(k.stopLossPct(), v.name(), "stopLossPct");
      requirePositive(k.trailActivatePct(), v.name(), "trailActivatePct");
      requirePositive(k.trailByPct(), v.name(), "trailByPct");
      if (k.timeStopBars() != null && k.timeStopBars() <= 0) {
        throw badRequest("variant '" + v.name() + "' timeStopBars must be > 0");
      }
    }
  }

  /** Validates one entry, guards its captured premium, resolves its lot size, and pins it. */
  private PinnedEntry validateAndGuard(
      CounterfactualRunRequest.Entry e, int idx, OffsetDateTime from, OffsetDateTime to) {
    String where = "entries[" + idx + "]";
    if (e.exchange() == null || e.exchange().isBlank()
        || e.tradingsymbol() == null || e.tradingsymbol().isBlank()) {
      throw badRequest(where + " needs exchange + tradingsymbol");
    }
    if (e.entryPremium() == null || e.entryPremium().signum() <= 0) {
      throw badRequest(where + " entryPremium must be > 0");
    }
    if (e.qty() <= 0) {
      throw badRequest(where + " qty must be > 0");
    }
    OffsetDateTime entryTime = parseBound(e.entryTime(), where + ".entryTime");
    if (entryTime.isBefore(from) || !entryTime.isBefore(to)) {
      throw badRequest(where + " entryTime " + entryTime + " is outside the window [" + from + ", " + to + ")");
    }
    OffsetDateTime sessionClose = sessionClose(entryTime);
    // captured-data guard: the premium over [entryTime, sessionClose) must be real captured candles.
    guard.assertCaptured(e.exchange(), e.tradingsymbol(), entryTime, sessionClose);
    int lotSize =
        guard
            .lotSize(e.exchange(), e.tradingsymbol())
            .orElseThrow(
                () ->
                    new ApiException(
                        422,
                        ErrorCodes.VALIDATION_FAILED,
                        "cannot resolve lot size for " + e.exchange() + ":" + e.tradingsymbol()
                            + " from the contract registry — counterfactual replay needs it to cost the"
                            + " trade with the shared fill model"));
    return new PinnedEntry(
        e.signalId(), e.exchange(), e.tradingsymbol(), entryTime.toString(), e.entryPremium(),
        e.qty(), lotSize);
  }

  // ---- worker replay --------------------------------------------------------------------------

  /**
   * Replays the pinned job: each entry's captured premium is read ONCE and evaluated against every
   * variant's exit knobs; per-variant metrics accrue and persist to {@code counterfactual_runs}. Called
   * from {@code BacktestRunner} on the worker thread. Throws {@link JobCancelledException} on cancel.
   */
  public void run(Job job, IntConsumer progressSink, BooleanSupplier cancelled) {
    JsonNode request = job.request();
    List<PinnedEntry> entries = convert(request.get("entries"), ENTRY_LIST);
    List<CounterfactualRunRequest.Variant> variants = convert(request.get("variants"), VARIANT_LIST);
    progressSink.accept(5);

    // deterministic trade sequence for the per-variant drawdown (chronological, stable tiebreak).
    List<PinnedEntry> ordered = new ArrayList<>(entries);
    ordered.sort(
        Comparator.comparing((PinnedEntry e) -> OffsetDateTime.parse(e.entryTime()).toInstant())
            .thenComparing(PinnedEntry::exchange)
            .thenComparing(PinnedEntry::tradingsymbol)
            .thenComparing(e -> e.entryPremium().toPlainString())
            .thenComparingLong(PinnedEntry::qty));

    List<Acc> accumulators = new ArrayList<>(variants.size());
    List<PremiumExitEvaluator.Rules> rules = new ArrayList<>(variants.size());
    for (CounterfactualRunRequest.Variant v : variants) {
      accumulators.add(new Acc(v.name()));
      rules.add(v.exitKnobs().toRules());
    }

    for (int i = 0; i < ordered.size(); i++) {
      if (cancelled.getAsBoolean()) {
        throw new JobCancelledException(job.id());
      }
      PinnedEntry entry = ordered.get(i);
      OffsetDateTime entryTime = OffsetDateTime.parse(entry.entryTime());
      OffsetDateTime sessionClose = sessionClose(entryTime);
      List<BigDecimal> premiums = premiumGrid(entry, entryTime, sessionClose);
      CostConfig cost = CostConfig.optionDefaults(entry.lotSize());
      for (int v = 0; v < variants.size(); v++) {
        PremiumExitEvaluator.Exit exit =
            PremiumExitEvaluator.evaluate(entry.entryPremium(), premiums, rules.get(v), premiums.size());
        accumulators.get(v).add(entry, exit, priceLeg(cost, entry, exit));
      }
      // smooth progress over the 5→95 band as entries process.
      progressSink.accept(5 + (int) ((90L * (i + 1)) / ordered.size()));
    }

    List<VariantResult> results = new ArrayList<>(accumulators.size());
    for (Acc acc : accumulators) {
      results.add(acc.toResult());
    }
    OffsetDateTime from = OffsetDateTime.parse(request.path("from").asText());
    OffsetDateTime to = OffsetDateTime.parse(request.path("to").asText());
    UUID runId = runs.insert(job.id(), from, to, entries.size(), results, job.createdBy());
    progressSink.accept(100);
    log.info(
        "counterfactual run {} completed: {} entries x {} variants",
        runId, entries.size(), variants.size());
  }

  /**
   * The per-minute premium grid for one entry from {@code entryTime} to session close: index 0 is the
   * REAL observed {@code entryPremium} (the exit levels anchor on it); each subsequent minute carries
   * the option's own captured 1m close forward (last-known when a minute did not trade). The index is
   * minutes-from-entry, so a variant's bar-count time stop counts wall-minutes. Refuses (422) if the
   * captured series vanished since submission (defensive — the submit guard is the primary gate).
   */
  private List<BigDecimal> premiumGrid(
      PinnedEntry entry, OffsetDateTime entryTime, OffsetDateTime sessionClose) {
    List<EngineCandle> bars =
        candleReader.read(entry.exchange(), entry.tradingsymbol(), "1m", entryTime, sessionClose);
    if (bars.isEmpty()) {
      throw new ApiException(
          422,
          ErrorCodes.DATA_GAP,
          "no captured premium candles for " + entry.exchange() + ":" + entry.tradingsymbol()
              + " over [" + entryTime + ", " + sessionClose + ") at replay time");
    }
    NavigableMap<OffsetDateTime, BigDecimal> series = new TreeMap<>();
    for (EngineCandle b : bars) {
      series.put(b.bucketStart(), b.close());
    }
    List<BigDecimal> premiums = new ArrayList<>();
    premiums.add(entry.entryPremium());
    BigDecimal last = entry.entryPremium();
    OffsetDateTime minute = entryTime.plusMinutes(1);
    while (minute.isBefore(sessionClose)) {
      BigDecimal p = CandlePremiumReader.premiumAt(series, minute);
      if (p == null || p.signum() <= 0) {
        p = last;
      } else {
        last = p;
      }
      premiums.add(p);
      minute = minute.plusMinutes(1);
    }
    return premiums;
  }

  /** One replayed leg's cost-inclusive PnL (Rs) + scale-free premium points, via the shared fill model. */
  private LegOutcome priceLeg(CostConfig cost, PinnedEntry entry, PremiumExitEvaluator.Exit exit) {
    Fill entryFill = fill(cost, Side.BUY, entry.qty(), entry.entryPremium());
    Fill exitFill = fill(cost, Side.SELL, entry.qty(), exit.premium());
    BigDecimal pnlInr = entryFill.netValue().add(exitFill.netValue()).setScale(2, RoundingMode.HALF_UP);
    BigDecimal points = exit.premium().subtract(entry.entryPremium());
    return new LegOutcome(pnlInr, points, exit.reason());
  }

  /** One premium-leg fill through the shared model (no quoted spread → the option 1-tick fallback). */
  private Fill fill(CostConfig cost, Side side, long qty, BigDecimal referencePrice) {
    return fills.simulate(
        new FillRequest(
            side, qty, cost.lotSize(), referencePrice, cost.instrumentClass(), cost.tickSize(),
            null, cost.slippage(), cost.brokerage(), cost.fees()),
        cost.slippageMultiplier());
  }

  // ---- helpers --------------------------------------------------------------------------------

  private static OffsetDateTime sessionClose(OffsetDateTime entryTime) {
    LocalDate day = entryTime.atZoneSameInstant(IST).toLocalDate();
    return day.atTime(SESSION_CLOSE).atZone(IST).toOffsetDateTime();
  }

  private static OffsetDateTime parseBound(String value, String field) {
    if (value == null || value.isBlank()) {
      throw badRequest(field + " is required");
    }
    String normalized = value.length() == 10 ? value + "T00:00:00+05:30" : value;
    try {
      return OffsetDateTime.parse(normalized);
    } catch (RuntimeException ex) {
      throw badRequest(field + " is not an ISO-8601 date/date-time: " + value);
    }
  }

  private static void requirePositive(BigDecimal value, String variant, String knob) {
    if (value != null && value.signum() <= 0) {
      throw badRequest("variant '" + variant + "' " + knob + " must be > 0");
    }
  }

  private static ApiException badRequest(String message) {
    return new ApiException(422, ErrorCodes.VALIDATION_FAILED, message);
  }

  private <T> T convert(JsonNode node, TypeReference<T> type) {
    return objectMapper.convertValue(node, type);
  }

  /** The pinned request JSONB shape (entries enriched with the resolved lot size). */
  private record PinnedRequest(
      boolean counterfactual,
      String from,
      String to,
      List<PinnedEntry> entries,
      List<CounterfactualRunRequest.Variant> variants) {}

  /** A validated + lot-size-resolved entry as stored in the job request JSONB. */
  private record PinnedEntry(
      String signalId,
      String exchange,
      String tradingsymbol,
      String entryTime,
      BigDecimal entryPremium,
      long qty,
      int lotSize) {}

  /** One replayed leg's outcome under a single variant. */
  private record LegOutcome(BigDecimal pnlInr, BigDecimal points, String reason) {}

  /** Mutable per-variant accumulator over the ordered entry sequence (drawdown needs the running sum). */
  private static final class Acc {
    private final String name;
    private int trades;
    private int wins;
    private BigDecimal netPnl = BigDecimal.ZERO;
    private BigDecimal grossPoints = BigDecimal.ZERO;
    private BigDecimal cum = BigDecimal.ZERO;
    private BigDecimal peak = BigDecimal.ZERO;
    private BigDecimal maxDd = BigDecimal.ZERO;
    private final Map<String, Integer> reasons = new TreeMap<>();

    Acc(String name) {
      this.name = name;
    }

    void add(PinnedEntry entry, PremiumExitEvaluator.Exit exit, LegOutcome outcome) {
      trades++;
      if (outcome.pnlInr().signum() > 0) {
        wins++;
      }
      netPnl = netPnl.add(outcome.pnlInr());
      grossPoints = grossPoints.add(outcome.points());
      reasons.merge(outcome.reason(), 1, Integer::sum);
      cum = cum.add(outcome.pnlInr());
      if (cum.compareTo(peak) > 0) {
        peak = cum;
      }
      BigDecimal dd = peak.subtract(cum);
      if (dd.compareTo(maxDd) > 0) {
        maxDd = dd;
      }
    }

    VariantResult toResult() {
      BigDecimal winRate =
          trades == 0
              ? BigDecimal.ZERO
              : BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(trades), 6, RoundingMode.HALF_UP);
      BigDecimal expectancy =
          trades == 0
              ? BigDecimal.ZERO
              : netPnl.divide(BigDecimal.valueOf(trades), 2, RoundingMode.HALF_UP);
      return new VariantResult(
          name,
          trades,
          netPnl.setScale(2, RoundingMode.HALF_UP),
          grossPoints.setScale(4, RoundingMode.HALF_UP),
          winRate,
          expectancy,
          maxDd.setScale(2, RoundingMode.HALF_UP),
          new TreeMap<>(reasons));
    }
  }
}
