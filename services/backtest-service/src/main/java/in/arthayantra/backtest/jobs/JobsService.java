package in.arthayantra.backtest.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.backtest.client.StrategyVersionClient;
import in.arthayantra.backtest.dispatch.JobStreamDispatcher;
import in.arthayantra.backtest.dispatch.ProgressPublisher;
import in.arthayantra.backtest.dispatch.Streams;
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

  private final JobRepository repository;
  private final JobStreamDispatcher dispatcher;
  private final StrategyVersionClient versions;
  private final ProgressPublisher progress;
  private final PreflightCoverage preflight;
  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;

  /** Wires the collaborators. */
  public JobsService(
      JobRepository repository,
      JobStreamDispatcher dispatcher,
      StrategyVersionClient versions,
      ProgressPublisher progress,
      PreflightCoverage preflight,
      StringRedisTemplate redis,
      ObjectMapper objectMapper) {
    this.repository = repository;
    this.dispatcher = dispatcher;
    this.versions = versions;
    this.progress = progress;
    this.preflight = preflight;
    this.redis = redis;
    this.objectMapper = objectMapper;
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
    request.put("purpose", req.purpose() == null || req.purpose().isBlank() ? "backtest" : req.purpose());

    Job job =
        repository.insertQueued(JobKind.BACKTEST, null, v.strategyId(), request, correlationId);
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

  /** Paged listing filtered by optional status + strategyId. */
  public List<Job> list(String status, String strategyId, int limit, int offset) {
    return repository.list(parseStatus(status), strategyId, limit, offset);
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

  /** Coverage pre-flight (§D.6) for explicit single-instrument universes with a date window. */
  private void runPreflight(JsonNode config, BacktestRunRequest req) {
    if (config == null || !config.has("universe") || req.from() == null || req.to() == null) {
      return;
    }
    JsonNode instruments = config.path("universe").path("instruments");
    if (!instruments.isArray() || instruments.isEmpty()) {
      return; // index/options/futures universes resolve at submission in Stage F
    }
    JsonNode first = instruments.get(0);
    preflight.check(
        first.path("exchange").asText(),
        first.path("tradingsymbol").asText(),
        OffsetDateTime.parse(toDateTime(req.from())),
        OffsetDateTime.parse(toDateTime(req.to())));
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
