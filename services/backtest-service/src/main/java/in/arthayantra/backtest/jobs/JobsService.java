package in.arthayantra.backtest.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.backtest.client.MarketDataClient;
import in.arthayantra.backtest.client.StrategyVersionClient;
import in.arthayantra.backtest.dispatch.JobStreamDispatcher;
import in.arthayantra.backtest.dispatch.ProgressPublisher;
import in.arthayantra.backtest.dispatch.Streams;
import in.arthayantra.backtest.regime.BenchmarkSeries;
import in.arthayantra.backtest.regime.RegimeLabeler;
import in.arthayantra.backtest.regime.RegimePreflight;
import in.arthayantra.backtest.replay.PreflightCoverage;
import java.time.OffsetDateTime;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ConflictException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Submits, lists, reads and cancels backtest jobs against the authoritative {@code jobs} table. */
@Service
public class JobsService {

  // Calendar-day lookback for warming the benchmark daily series — generously covers the
  // WARMUP_SESSIONS trading sessions the regime pre-flight needs strictly before `from`.
  private static final int BENCHMARK_WARMUP_LOOKBACK_DAYS = RegimeLabeler.WARMUP_SESSIONS * 2;

  private final JobRepository repository;
  private final JobStreamDispatcher dispatcher;
  private final StrategyVersionClient versions;
  private final ProgressPublisher progress;
  private final PreflightCoverage preflight;
  private final RegimePreflight regimePreflight;
  private final StressGuard stressGuard;
  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final MarketDataClient marketData;

  /** Wires the collaborators. */
  public JobsService(
      JobRepository repository,
      JobStreamDispatcher dispatcher,
      StrategyVersionClient versions,
      ProgressPublisher progress,
      PreflightCoverage preflight,
      RegimePreflight regimePreflight,
      StressGuard stressGuard,
      StringRedisTemplate redis,
      ObjectMapper objectMapper,
      MarketDataClient marketData) {
    this.repository = repository;
    this.dispatcher = dispatcher;
    this.versions = versions;
    this.progress = progress;
    this.preflight = preflight;
    this.regimePreflight = regimePreflight;
    this.stressGuard = stressGuard;
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.marketData = marketData;
  }

  /** The outcome of a cancel request — 204 (was queued) vs 202 (running, observed at checkpoint). */
  public enum CancelOutcome {
    CANCELLED,
    CANCELLING
  }

  /** Validates the version, pins it into the request JSONB, inserts queued + dispatches (§D.14). */
  public Job submit(BacktestRunRequest req, String correlationId) {
    StrategyVersionClient.ResolvedVersion v =
        versions.resolve(req.strategyId(), req.strategyVersion());
    runPreflight(v.config(), req);

    String purpose = req.purpose() == null || req.purpose().isBlank() ? "backtest" : req.purpose();

    ObjectNode request = objectMapper.createObjectNode();
    request.put("strategyId", req.strategyId());
    request.put("strategyVersion", v.version());
    request.put("strategyChecksum", v.checksum());
    putIfPresent(request, "from", req.from());
    putIfPresent(request, "to", req.to());
    putIfPresent(request, "interval", req.interval());
    if (req.universeOverride() != null) {
      request.set("universeOverride", req.universeOverride());
    }
    if (req.initialCapital() != null) {
      request.put("initialCapital", req.initialCapital());
    }
    if (req.costs() != null) {
      request.set("costs", req.costs());
    }
    if (req.seed() != null) {
      request.put("seed", req.seed());
    }
    request.put("purpose", purpose);

    // Phase 44 (S8): resolve index_constituents / futures_of_underlying / futures_screener universes
    // ONCE, at submission, and PIN them by copy into the request JSONB — every sweep trial reuses this
    // embedded copy, so a mid-sweep constituent rebalance can never split a leaderboard.
    // B9 (EVO §13 row 19): the swing FUNNEL modes (manas_arora_funnel / minervini_funnel) join the
    // same pin-by-copy path so Manas/Minervini SIM_FIRST trials become runnable through the job
    // pipeline (they were 0-runnable — the runner threw "needs an explicit single-instrument
    // universe"). The strategy-signal resolver returns TODAY's screen (the live funnel endpoint is
    // NOT point-in-time), so a windowed funnel backtest runs a STATIC universe pinned as-of
    // submission — a documented divergence from the day-varying live funnel. The runner is
    // single-signal-instrument, so replay signals on the funnel's TOP-ranked pick (per-name
    // expectancy); portfolio/slot effects across the whole funnel are validated at the paper stage.
    String universeMode = v.config().path("universe").path("mode").asText("explicit");
    boolean funnelMode =
        "manas_arora_funnel".equals(universeMode) || "minervini_funnel".equals(universeMode);
    if ("index_constituents".equals(universeMode)
        || "futures_of_underlying".equals(universeMode)
        || "futures_screener".equals(universeMode)
        || funnelMode) {
      JsonNode universe = versions.resolveUniverse(v.strategyId(), v.version()).orElse(null);
      if (universe != null) {
        request.set("universe", universe.path("items"));
        request.put("universeChecksum", universe.path("checksum").asText());
      }
      // An empty funnel (off-hours / fresh screen / no passers this session) has nothing to signal
      // on — fail CLEAN at submission with a 4xx rather than letting the worker throw mid-replay
      // (which surfaces as a failed job, not a rejected submission). Scoped to funnel modes so
      // futures_screener's "pin empty, worker decides" behaviour stays byte-identical.
      if (funnelMode
          && (universe == null
              || !universe.path("items").isArray()
              || universe.path("items").isEmpty())) {
        throw new ApiException(
            422,
            ErrorCodes.STRATEGY_UNIVERSE_UNSUPPORTED,
            "funnel universe '"
                + universeMode
                + "' resolved 0 candidates — the screener funnel is empty (off-hours or no passers"
                + " this session); a funnel backtest needs a non-empty pinned universe");
      }
    }

    // S1C stress guard (§D.6): a stress test's window must not overlap ANY prior lineage job
    // (sweeps AND manual backtests both leak). The check runs BEFORE the row is inserted so a
    // refused stress never appears in lineage. The reuse counter is recorded on a clean window.
    if ("stress_test".equals(purpose) && req.from() != null && req.to() != null) {
      OffsetDateTime from = OffsetDateTime.parse(toDateTime(req.from()));
      OffsetDateTime to = OffsetDateTime.parse(toDateTime(req.to()));
      stressGuard.validateWindow(req.strategyId(), from, to);
      long reuse = stressGuard.recordReuse(req.strategyId(), from, to);
      request.put("holdoutReuseCount", reuse);
    }

    // Audit T3 / EVO §13 row 4: the /api/v1/backtests submission path is always the single owner
    // (one PHC login); the actor is derived from the write-site context, not an auth claim.
    Job job =
        repository.insertQueued(
            JobKind.BACKTEST, null, v.versionId(), request, correlationId, "owner");
    dispatcher.dispatchBacktest(job.id());
    progress.refreshSummary();
    return job;
  }

  /** A single job or 404. */
  public Job get(UUID id) {
    return repository
        .find(id)
        .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND_JOB, "job not found: " + id));
  }

  /**
   * Paged listing filtered by optional status + strategyId (+ a strategyIds CSV + a version-id CSV),
   * ordered by a key.
   */
  public List<Job> list(
      String status,
      String strategyId,
      String strategyIds,
      String currentVersions,
      int limit,
      int offset,
      String sortBy,
      String sortDir) {
    return repository.list(
        parseStatus(status), strategyId, strategyIds, currentVersions, limit, offset, sortBy, sortDir);
  }

  /** Cancels a job: queued → 204 cancelled now; running → 202 flag set for the checkpoint. */
  public CancelOutcome cancel(UUID id) {
    Job job = get(id);
    if (job.status().terminal()) {
      throw new ConflictException(
          ErrorCodes.CONFLICT_JOB_TERMINAL, "job already " + job.status().db());
    }
    if (repository.cancelIfQueued(id)) {
      progress.refreshSummary();
      return CancelOutcome.CANCELLED;
    }
    redis.opsForValue().set(Streams.cancelKey(id), "1", Duration.ofMinutes(30));
    return CancelOutcome.CANCELLING;
  }

  /**
   * Coverage pre-flight (§D.6) for explicit single-instrument universes with a date window — the
   * primary 1m series AND (guard 6, §D.4) the regime-attribution benchmark series including warm-up
   * depth, so a fold/stress run never attributes against partial benchmark coverage. The benchmark
   * gate uses {@code backtest.defaults.benchmark} (default {@code NSE:NIFTY 50}); it is skipped only
   * when the benchmark string is malformed (a schema concern, not a coverage one).
   */
  private void runPreflight(JsonNode config, BacktestRunRequest req) {
    if (config == null || !config.has("universe") || req.from() == null || req.to() == null) {
      return;
    }
    OffsetDateTime from = OffsetDateTime.parse(toDateTime(req.from()));
    OffsetDateTime to = OffsetDateTime.parse(toDateTime(req.to()));

    JsonNode instruments = config.path("universe").path("instruments");
    if (instruments.isArray() && !instruments.isEmpty()) {
      JsonNode first = instruments.get(0);
      String exch = first.path("exchange").asText();
      String sym = first.path("tradingsymbol").asText();
      // Auto-warm the primary 1m series into the shared store (cache-first, best-effort) so the
      // coverage check below passes on a fresh/uncovered window instead of 422-ing DATA_GAP —
      // replay always reads 1m for the primary instrument regardless of the strategy timeframe.
      marketData.warm(exch, sym, "1m", from, to);
      preflight.check(exch, sym, from, to);
    }
    // index/options/futures universes resolve at submission in Stage F (no primary check here), but
    // the benchmark gate runs for every windowed run with a resolvable benchmark.
    try {
      BenchmarkSeries benchmark = BenchmarkSeries.resolve(config);
      // Warm the benchmark daily series with enough lookback for the WARMUP_SESSIONS regime depth
      // strictly before `from`, then run the same gate the worker re-checks for fold attribution.
      marketData.warm(
          benchmark.exchange(),
          benchmark.tradingsymbol(),
          "1d",
          from.minusDays(BENCHMARK_WARMUP_LOOKBACK_DAYS),
          to);
      regimePreflight.check(benchmark, from);
    } catch (IllegalArgumentException malformedBenchmark) {
      // a malformed benchmark string is a schema-validation concern (rejected upstream), not a
      // coverage failure — never block submission on it here.
    }
  }

  private static String toDateTime(String value) {
    return value.length() == 10 ? value + "T00:00:00+05:30" : value;
  }

  private static void putIfPresent(ObjectNode node, String field, String value) {
    if (value != null && !value.isBlank()) {
      node.put(field, value);
    }
  }

  private static JobStatus parseStatus(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    try {
      return JobStatus.fromDb(status);
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "unknown status: " + status);
    }
  }
}
