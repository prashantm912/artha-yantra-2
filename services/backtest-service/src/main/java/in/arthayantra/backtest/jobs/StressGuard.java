package in.arthayantra.backtest.jobs;

import in.arthayantra.backtest.jobs.JobRepository.LineageWindow;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The S1C pre-publication stress-test contamination guard (§D.4 / §D.6). A stress test is an
 * ordinary {@code POST /backtests/run} job tagged {@code purpose: stress_test}; on submission the
 * requested window is validated against <b>every prior job of the strategy lineage — sweeps AND
 * manual backtests</b> (a manual quick-backtest leaks holdout information exactly as a sweep does).
 * Any overlap → <b>422 {@code WINDOW_CONTAMINATED}</b> listing the intersecting job ids.
 *
 * <p><b>Honest accounting, not tamper-proof prevention.</b> The owner is the leak; re-creating a
 * strategy under a new id resets lineage tracking. So the guard also maintains a <b>holdout-reuse
 * counter</b> per {@code (strategy, window)} (in Redis) — each repeated stress against the same
 * window is counted and surfaced, since each re-tune-after-failure cycle contaminates the holdout
 * through the owner's decisions. The clean-window suggestion is the day after the latest tested
 * {@code to}, through the latest cached candle.
 */
@Component
public class StressGuard {

  private static final String IST_OFFSET = "+05:30";
  private static final Duration REUSE_TTL = Duration.ofDays(365);

  private final JobRepository jobs;
  private final StringRedisTemplate redis;

  /** Wires the job repository + Redis (for the reuse counter). */
  public StressGuard(JobRepository jobs, StringRedisTemplate redis) {
    this.jobs = jobs;
    this.redis = redis;
  }

  /**
   * Validates a stress-test window against the strategy lineage and refuses the label on overlap.
   * Throws 422 {@code WINDOW_CONTAMINATED} with the intersecting job ids in {@code details.jobIds}.
   * The about-to-be-submitted job has not been inserted yet, so {@code excludeJobId} is {@code null}.
   *
   * <p><b>Exact-window repeats are NOT refused</b> — they are the {@link #recordReuse holdout-reuse}
   * case (a re-tune-after-failure cycle against the identical {@code (strategy, window)}), counted
   * and surfaced rather than blocked. The hard 422 is for overlap with a <b>different</b> prior
   * window (a sweep or manual backtest that tested an overlapping but non-identical range), which is
   * the contamination the owner cannot otherwise see.
   *
   * @param strategyId the strategy lineage
   * @param from the requested window start
   * @param to the requested window end (exclusive)
   */
  public void validateWindow(String strategyId, OffsetDateTime from, OffsetDateTime to) {
    List<String> offending = new ArrayList<>();
    for (LineageWindow w : jobs.findLineageWindows(strategyId, null)) {
      OffsetDateTime jobFrom = parse(w.fromRaw());
      OffsetDateTime jobTo = parse(w.toRaw());
      if (jobFrom == null || jobTo == null) {
        continue; // a job without a tested window cannot contaminate
      }
      if (from.isEqual(jobFrom) && to.isEqual(jobTo)) {
        continue; // exact-window repeat -> the reuse counter, never a hard refusal
      }
      // half-open overlap: [from,to) intersects [jobFrom,jobTo) iff from<jobTo AND jobFrom<to.
      if (from.isBefore(jobTo) && jobFrom.isBefore(to)) {
        offending.add(w.id().toString());
      }
    }
    if (!offending.isEmpty()) {
      throw new ApiException(
          422,
          ErrorCodes.WINDOW_CONTAMINATED,
          "stress-test window overlaps "
              + offending.size()
              + " prior tested window(s) for this strategy — the holdout is contaminated",
          Map.of(
              "strategyId", strategyId,
              "from", from.toString(),
              "to", to.toString(),
              "jobIds", offending));
    }
  }

  /**
   * Increments and returns the holdout-reuse count for a {@code (strategy, window)} (§D.6). The first
   * stress against a clean window returns {@code 1}; the Nth repeat returns {@code N} ("Nth stress
   * test against this window — treat as contaminated"). Best-effort: a Redis hiccup yields {@code 0}
   * rather than failing the submission (the authoritative overlap guard has already run).
   */
  public long recordReuse(String strategyId, OffsetDateTime from, OffsetDateTime to) {
    try {
      Long count = redis.opsForValue().increment(reuseKey(strategyId, from, to));
      redis.expire(reuseKey(strategyId, from, to), REUSE_TTL);
      return count == null ? 0L : count;
    } catch (RuntimeException e) {
      return 0L;
    }
  }

  /**
   * The suggested clean stress window for a strategy (§D.6): {@code from} = the day after the latest
   * tested {@code to} across the lineage; {@code to} = the latest cached candle date (the run window
   * end). When the strategy has never been tested, {@code from} is {@code null} (the whole cache is
   * clean). 404 when the strategy has no lineage rows at all is the caller's concern.
   *
   * @param strategyId the strategy lineage
   * @param latestCachedCandle the latest cached candle date-time (the suggested window end)
   * @return ordered {@code {from, to}} map (string ISO dates; {@code from} null when never tested)
   */
  public Map<String, Object> suggestCleanWindow(
      String strategyId, OffsetDateTime latestCachedCandle) {
    OffsetDateTime latestTo = null;
    boolean anyLineage = false;
    for (LineageWindow w : jobs.findLineageWindows(strategyId, null)) {
      anyLineage = true;
      OffsetDateTime jobTo = parse(w.toRaw());
      if (jobTo != null && (latestTo == null || jobTo.isAfter(latestTo))) {
        latestTo = jobTo;
      }
    }
    if (!anyLineage) {
      throw new NotFoundException(
          ErrorCodes.NOT_FOUND_RESOURCE, "no jobs for strategy: " + strategyId);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    if (latestTo == null) {
      out.put("from", null); // lineage exists but no tested windows — the cache is clean
    } else {
      LocalDate nextDay = latestTo.toLocalDate(); // 'to' is exclusive — the next clean day IS this date
      out.put("from", nextDay.toString());
    }
    out.put("to", latestCachedCandle == null ? null : latestCachedCandle.toLocalDate().toString());
    return out;
  }

  private static String reuseKey(String strategyId, OffsetDateTime from, OffsetDateTime to) {
    return "stress:reuse:"
        + strategyId
        + ":"
        + from.toLocalDate()
        + ":"
        + to.toLocalDate();
  }

  /** Normalizes a {@code request->>'from'/'to'} string (plain date or offset date-time) to IST. */
  static OffsetDateTime parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String normalized = raw.length() == 10 ? raw + "T00:00:00" + IST_OFFSET : raw;
    return OffsetDateTime.parse(normalized);
  }
}
