package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.signals.SignalEngine.Outcome;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V043 {@code signal_eval_outcomes} against the REAL Flyway strategy lineage: the 3m rollup persists
 * the engine's entry-evaluation counters durably, a restart neither loses counts nor writes a
 * negative, and the canonical one-SELECT liveness query answers "was the engine evaluating on date
 * X, and what was the outcome mix" — the question the in-memory Micrometer counters cannot answer
 * once the process bounces.
 *
 * <p>The engine is MOCKED so counter values are explicit: this exercises persistence and delta
 * semantics, not the evaluation loop. (The real engine bean is absent here anyway — it and the
 * rollup job share the {@code artha.signals.engine-enabled} gate, which the IT harness turns off.)
 *
 * <p>Shared singleton DB with NO per-method cleanup: every method derives its own bucket window from
 * {@code now} — a different hour offset per method — and clears exactly that window first, so it is
 * deterministic across methods and across surefire reruns without touching another method's rows.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class SignalEvalOutcomeRollupIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private SignalEvalOutcomeRepository repository;
  @Autowired private ApplicationEventPublisher events;
  @Autowired private Environment environment;
  @Autowired private JdbcTemplate jdbc;

  /** A clock the test advances between ticks, so one job instance can take successive snapshots. */
  private static final class MutableClock extends Clock {
    private volatile Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void set(Instant next) {
      this.instant = next;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }

  private SignalEvalOutcomeRollupJob job(SignalEngine engine, Clock clock, int retentionDays) {
    return new SignalEvalOutcomeRollupJob(
        engine, repository, events, clock, environment, retentionDays);
  }

  /** A counter read: only the two outcomes this scenario moves; the rest are absent (i.e. zero). */
  private static Map<Outcome, Long> counters(long chartGateFailed, long fired) {
    Map<Outcome, Long> map = new EnumMap<>(Outcome.class);
    map.put(Outcome.CHART_GATE_FAILED, chartGateFailed);
    map.put(Outcome.FIRED, fired);
    return map;
  }

  private void clearRange(OffsetDateTime from, OffsetDateTime to) {
    jdbc.update(
        "DELETE FROM signal_eval_outcomes WHERE bucket_time >= ? AND bucket_time < ?", from, to);
  }

  private long sumFor(OffsetDateTime from, OffsetDateTime to, String outcome) {
    Long total =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(eval_count), 0) FROM signal_eval_outcomes"
                + " WHERE bucket_time >= ? AND bucket_time < ? AND outcome = ?",
            Long.class,
            from,
            to,
            outcome);
    return total == null ? 0 : total;
  }

  private boolean rowExists(OffsetDateTime bucket, String outcome) {
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM signal_eval_outcomes WHERE bucket_time = ? AND outcome = ?",
            Long.class,
            bucket,
            outcome);
    return count != null && count > 0;
  }

  @Test
  void aDaySumSurvivesARestartWithNoNegativeAndNoGap() {
    Instant t0 = Instant.now().minus(Duration.ofHours(1));
    OffsetDateTime from = SignalEvalOutcomeRollupJob.bucketFor(t0);
    OffsetDateTime to = from.plusMinutes(30);
    clearRange(from, to);

    // --- boot A: two snapshots three minutes apart, one job instance (one baseline) -----------
    MutableClock clock = new MutableClock(t0);
    SignalEngine engine = mock(SignalEngine.class);
    when(engine.outcomeCounts()).thenReturn(counters(40, 2), counters(100, 3));
    SignalEvalOutcomeRollupJob bootA = job(engine, clock, 180);

    assertThat(bootA.rollup())
        .as("first post-boot snapshot reports everything since boot")
        .isEqualTo(42L);
    clock.set(t0.plus(Duration.ofMinutes(3)));
    assertThat(bootA.rollup()).as("second snapshot reports only the growth").isEqualTo(61L);

    // --- restart: a NEW instance (fresh baseline) reading counters that reset to zero ---------
    SignalEngine rebooted = mock(SignalEngine.class);
    when(rebooted.outcomeCounts()).thenReturn(counters(25, 1));
    clock.set(t0.plus(Duration.ofMinutes(6)));
    assertThat(job(rebooted, clock, 180).rollup())
        .as("post-restart snapshot reports since-boot counts, never a negative")
        .isEqualTo(26L);

    // The counters alone could never answer this: 40+60+25 and 2+1+1 across a restart.
    assertThat(sumFor(from, to, "chart-gate-failed")).isEqualTo(125L);
    assertThat(sumFor(from, to, "fired")).isEqualTo(4L);

    Long negatives =
        jdbc.queryForObject(
            "SELECT count(*) FROM signal_eval_outcomes"
                + " WHERE bucket_time >= ? AND bucket_time < ? AND eval_count < 0",
            Long.class,
            from,
            to);
    assertThat(negatives).as("a counter reset never reads as negative activity").isZero();

    Long rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM signal_eval_outcomes WHERE bucket_time >= ? AND bucket_time < ?",
            Long.class,
            from,
            to);
    assertThat(rows)
        .as("every outcome recorded every bucket, zeros included (3 buckets x 7 outcomes)")
        .isEqualTo(3L * Outcome.values().length);
  }

  @Test
  void aMissedSnapshotIsAbsorbedByTheNextRatherThanLost() {
    Instant t0 = Instant.now().minus(Duration.ofHours(2));
    OffsetDateTime from = SignalEvalOutcomeRollupJob.bucketFor(t0);
    OffsetDateTime to = from.plusMinutes(30);
    clearRange(from, to);

    MutableClock clock = new MutableClock(t0);
    SignalEngine engine = mock(SignalEngine.class);
    // t0 read succeeds; the +3m tick's read THROWS (a stalled engine read stands in for any
    // failure); the +6m tick succeeds with counters that grew across BOTH windows.
    when(engine.outcomeCounts())
        .thenReturn(counters(10, 0))
        .thenThrow(new IllegalStateException("simulated rollup failure"))
        .thenReturn(counters(70, 0));
    SignalEvalOutcomeRollupJob job = job(engine, clock, 180);

    assertThat(job.rollup()).isEqualTo(10L);
    clock.set(t0.plus(Duration.ofMinutes(3)));
    assertThat(job.rollup()).as("a failed tick is fail-soft: returns 0, never throws").isZero();
    clock.set(t0.plus(Duration.ofMinutes(6)));
    assertThat(job.rollup()).as("the baseline did not advance — both windows roll in").isEqualTo(60L);

    assertThat(sumFor(from, to, "chart-gate-failed"))
        .as("nothing lost and nothing double-counted across the missed tick")
        .isEqualTo(70L);
    assertThat(rowExists(from.plusMinutes(3), "chart-gate-failed"))
        .as("the failed tick wrote no bucket")
        .isFalse();
  }

  @Test
  void conflictingWritesInOneBucketAddRatherThanReplace() {
    OffsetDateTime bucket =
        SignalEvalOutcomeRollupJob.bucketFor(Instant.now().minus(Duration.ofHours(5)));
    clearRange(bucket, bucket.plusMinutes(3));

    repository.upsertBucket(bucket, Map.of("fired", 2L));
    repository.upsertBucket(bucket, Map.of("fired", 3L));

    // Addition is the delta-correct merge for a blue/green overlap where both instances evaluated.
    assertThat(sumFor(bucket, bucket.plusMinutes(3), "fired")).isEqualTo(5L);
  }

  @Test
  void canonicalLivenessQueryReportsTheOutcomeMixForTheWindow() {
    OffsetDateTime bucket =
        SignalEvalOutcomeRollupJob.bucketFor(Instant.now().minus(Duration.ofHours(7)));
    clearRange(bucket, bucket.plusMinutes(3));
    repository.upsertBucket(
        bucket, Map.of("chart-gate-failed", 125L, "fired", 4L, "discipline-paused", 0L));

    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT outcome,"
                + " SUM(eval_count) AS evaluations,"
                + " COUNT(*) AS buckets_recorded,"
                + " COUNT(*) FILTER (WHERE eval_count > 0) AS buckets_active"
                + " FROM signal_eval_outcomes"
                + " WHERE bucket_time >= ? AND bucket_time < ?"
                + " GROUP BY outcome ORDER BY evaluations DESC",
            bucket,
            bucket.plusMinutes(3));

    assertThat(rows).hasSize(3);
    assertThat(rows.get(0)).containsEntry("outcome", "chart-gate-failed");
    assertThat(((Number) rows.get(0).get("evaluations")).longValue()).isEqualTo(125L);

    // The zero row is not noise: buckets_recorded proves the PROCESS was up even though this
    // outcome never occurred. Absence of rows is what would mean the process was down.
    Map<String, Object> paused =
        rows.stream()
            .filter(row -> "discipline-paused".equals(row.get("outcome")))
            .findFirst()
            .orElseThrow();
    assertThat(((Number) paused.get("buckets_recorded")).longValue()).isEqualTo(1L);
    assertThat(((Number) paused.get("buckets_active")).longValue()).isZero();
  }

  @Test
  void pruneDeletesAgedBucketsAndSparesRecentOnes() {
    OffsetDateTime aged =
        SignalEvalOutcomeRollupJob.bucketFor(Instant.now().minus(Duration.ofDays(200)));
    OffsetDateTime recent =
        SignalEvalOutcomeRollupJob.bucketFor(Instant.now().minus(Duration.ofDays(3)));
    repository.upsertBucket(aged, Map.of("fired", 1L));
    repository.upsertBucket(recent, Map.of("fired", 1L));

    int deleted = job(mock(SignalEngine.class), Clock.systemUTC(), 180).prune();

    assertThat(deleted).isGreaterThanOrEqualTo(1);
    assertThat(rowExists(aged, "fired")).as("aged bucket pruned").isFalse();
    assertThat(rowExists(recent, "fired")).as("recent bucket retained").isTrue();
  }

  @Test
  void nonPositiveHorizonIsGuardedAndDeletesNothing() {
    OffsetDateTime ancient =
        SignalEvalOutcomeRollupJob.bucketFor(Instant.now().minus(Duration.ofDays(500)));
    repository.upsertBucket(ancient, Map.of("fired", 1L));

    // A 0-day horizon would otherwise delete every row (cutoff == now()); the guard must skip.
    int deleted = job(mock(SignalEngine.class), Clock.systemUTC(), 0).prune();

    assertThat(deleted).isZero();
    assertThat(rowExists(ancient, "fired")).as("guard prevented the wipe").isTrue();

    repository.deleteOlderThanDays(180); // clean the ancient seed for later prune assertions
  }
}
