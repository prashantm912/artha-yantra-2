package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.signals.SignalEngine.Outcome;
import in.arthayantra.strategysignal.signals.SignalEngine.OutcomeSnapshot;
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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V043 {@code signal_eval_outcomes} against the REAL Flyway strategy lineage.
 *
 * <p>The load-bearing cases here are the two the cross-vendor review identified in the earlier
 * in-memory-checkpoint revision, because both are properties of the DURABLE checkpoint and cannot be
 * demonstrated without a database:
 *
 * <ul>
 *   <li>{@link #aLostAcknowledgementDoesNotDoubleCount()} — Postgres commits, the acknowledgement is
 *       lost. An in-memory baseline would re-cover those evaluations on the next tick.
 *   <li>{@link #aFailedWriteFollowedByABaselineResetLosesNothing()} — a failed write, then the
 *       writer object dies and is rebuilt (the process-restart shape) while the counters keep their
 *       epoch. An in-memory baseline would take the unrecorded window down with it.
 * </ul>
 *
 * <p>The engine is MOCKED so counter values and the epoch are explicit. (The real engine bean is
 * absent here anyway — it and the rollup job share the {@code artha.signals.engine-enabled} gate,
 * which the IT harness turns off.)
 *
 * <p>Shared singleton DB with NO per-method cleanup: every method uses its OWN random {@code boot_id}
 * and derives a private bucket window from {@code now}, so methods cannot collide with each other or
 * with a surefire rerun.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class SignalEvalOutcomeRollupIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private SignalEvalOutcomeRepository repository;
  @Autowired private ApplicationEventPublisher events;
  @Autowired private Environment environment;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private javax.sql.DataSource dataSource;

  /**
   * A PLAIN repository instance for the failure-injection tests. The injected bean is a Spring AOP
   * proxy (persistence exception translation), which Mockito cannot spy — "Failed to unwrap proxied
   * object". This is the same class wired the same way, just not proxied.
   */
  private SignalEvalOutcomeRepository unproxiedRepository() {
    return new SignalEvalOutcomeRepository(dataSource);
  }

  /** A clock the test advances between ticks. */
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

  private SignalEvalOutcomeRollupJob job(
      SignalEngine engine, SignalEvalOutcomeRepository repo, Clock clock, int retentionDays) {
    return new SignalEvalOutcomeRollupJob(engine, repo, events, clock, environment, retentionDays);
  }

  /** An engine whose counters return the given values in succession, all under one epoch. */
  private static SignalEngine engineAt(UUID epoch, Map<Outcome, Long>... successiveReads) {
    SignalEngine engine = mock(SignalEngine.class);
    OutcomeSnapshot[] snapshots = new OutcomeSnapshot[successiveReads.length];
    for (int i = 0; i < successiveReads.length; i++) {
      snapshots[i] = new OutcomeSnapshot(epoch, successiveReads[i]);
    }
    OutcomeSnapshot[] rest = new OutcomeSnapshot[snapshots.length - 1];
    System.arraycopy(snapshots, 1, rest, 0, rest.length);
    when(engine.outcomeSnapshot()).thenReturn(snapshots[0], rest);
    return engine;
  }

  private static Map<Outcome, Long> counters(long chartGateFailed, long fired) {
    Map<Outcome, Long> map = new EnumMap<>(Outcome.class);
    map.put(Outcome.CHART_GATE_FAILED, chartGateFailed);
    map.put(Outcome.FIRED, fired);
    return map;
  }

  private long sumFor(UUID bootId, String outcome) {
    Long total =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(eval_count), 0) FROM signal_eval_outcomes"
                + " WHERE boot_id = ? AND outcome = ?",
            Long.class,
            bootId,
            outcome);
    return total == null ? 0 : total;
  }

  private boolean rowExists(OffsetDateTime bucket, UUID bootId, String outcome) {
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM signal_eval_outcomes"
                + " WHERE bucket_time = ? AND boot_id = ? AND outcome = ?",
            Long.class,
            bucket,
            bootId,
            outcome);
    return count != null && count > 0;
  }

  @Test
  void aDaySumSurvivesARestartWithNoNegativeAndNoGap() {
    Instant t0 = Instant.now().minus(Duration.ofHours(1));
    UUID bootA = UUID.randomUUID();
    UUID bootB = UUID.randomUUID();
    MutableClock clock = new MutableClock(t0);

    // --- boot A: two snapshots three minutes apart -------------------------------------------
    SignalEngine engineA = engineAt(bootA, counters(40, 2), counters(100, 3));
    SignalEvalOutcomeRollupJob jobA = job(engineA, repository, clock, 180);

    assertThat(jobA.rollup())
        .as("first snapshot of a boot reports everything since boot")
        .isEqualTo(42L);
    clock.set(t0.plus(Duration.ofMinutes(3)));
    assertThat(jobA.rollup()).as("second snapshot reports only the growth").isEqualTo(61L);

    // --- restart: new epoch, counters back to zero, a brand-new job object --------------------
    SignalEngine engineB = engineAt(bootB, counters(25, 1));
    clock.set(t0.plus(Duration.ofMinutes(6)));
    assertThat(job(engineB, repository, clock, 180).rollup())
        .as("post-restart snapshot reports since-boot counts, never a negative")
        .isEqualTo(26L);

    // The counters alone could never answer this: 40+60 on boot A and 25 on boot B.
    assertThat(sumFor(bootA, "chart-gate-failed")).isEqualTo(100L);
    assertThat(sumFor(bootB, "chart-gate-failed")).isEqualTo(25L);
    assertThat(sumFor(bootA, "fired") + sumFor(bootB, "fired")).isEqualTo(4L);

    Long negatives =
        jdbc.queryForObject(
            "SELECT count(*) FROM signal_eval_outcomes"
                + " WHERE boot_id IN (?, ?) AND eval_count < 0",
            Long.class,
            bootA,
            bootB);
    assertThat(negatives).as("a counter reset never reads as negative activity").isZero();

    Long rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM signal_eval_outcomes WHERE boot_id IN (?, ?)",
            Long.class,
            bootA,
            bootB);
    assertThat(rows)
        .as("every outcome recorded every bucket, zeros included (3 buckets x 7 outcomes)")
        .isEqualTo(3L * Outcome.values().length);
  }

  @Test
  void aLostAcknowledgementDoesNotDoubleCount() {
    // Postgres COMMITS but the caller never learns it. With an in-memory baseline the next tick
    // would re-cover the committed evaluations and the day SUM would count them twice; the durable
    // checkpoint means the next tick SEES the commit and reports only the true increment.
    Instant t0 = Instant.now().minus(Duration.ofHours(2));
    UUID boot = UUID.randomUUID();
    MutableClock clock = new MutableClock(t0);

    SignalEvalOutcomeRepository flaky = spy(unproxiedRepository());
    doAnswer(
            invocation -> {
              invocation.callRealMethod(); // the write really does commit...
              throw new RuntimeException("acknowledgement lost after commit"); // ...ack never lands
            })
        .when(flaky)
        .upsertBucket(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(boot),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());

    SignalEngine engine = engineAt(boot, counters(30, 0));
    assertThat(job(engine, flaky, clock, 180).rollup())
        .as("fail-soft: the lost ack surfaces as 0, never a throw")
        .isZero();
    assertThat(sumFor(boot, "chart-gate-failed")).as("the write did commit").isEqualTo(30L);

    // Next tick against the REAL repository, counters now at 45.
    clock.set(t0.plus(Duration.ofMinutes(3)));
    SignalEngine grown = engineAt(boot, counters(45, 0));
    assertThat(job(grown, repository, clock, 180).rollup())
        .as("only the 15 since the committed checkpoint — not all 45")
        .isEqualTo(15L);

    assertThat(sumFor(boot, "chart-gate-failed"))
        .as("45 total, counted exactly once — a double count would read 75")
        .isEqualTo(45L);
  }

  @Test
  void aFailedWriteFollowedByABaselineResetLosesNothing() {
    // A write genuinely FAILS (nothing commits), then the writer object is destroyed and rebuilt —
    // the process-restart shape, which is exactly what kills an in-memory baseline. The counters
    // keep their epoch (the engine outlived the write failure), so the rebuilt writer must recover
    // the unrecorded window from the durable checkpoint rather than lose it.
    Instant t0 = Instant.now().minus(Duration.ofHours(3));
    UUID boot = UUID.randomUUID();
    MutableClock clock = new MutableClock(t0);

    // Tick 1 succeeds: checkpoint lands at 10.
    assertThat(job(engineAt(boot, counters(10, 0)), repository, clock, 180).rollup()).isEqualTo(10L);

    // Tick 2 FAILS outright — nothing commits.
    SignalEvalOutcomeRepository broken = spy(unproxiedRepository());
    doAnswer(
            invocation -> {
              throw new RuntimeException("write failed");
            })
        .when(broken)
        .upsertBucket(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(boot),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    clock.set(t0.plus(Duration.ofMinutes(3)));
    assertThat(job(engineAt(boot, counters(40, 0)), broken, clock, 180).rollup()).isZero();
    assertThat(rowExists(
            SignalEvalOutcomeRollupJob.bucketFor(t0.plus(Duration.ofMinutes(3))),
            boot,
            "chart-gate-failed"))
        .as("the failed tick wrote no bucket")
        .isFalse();

    // Baseline reset: a BRAND-NEW job object, holding no memory of anything. Tick 3 succeeds.
    clock.set(t0.plus(Duration.ofMinutes(6)));
    assertThat(job(engineAt(boot, counters(70, 0)), repository, clock, 180).rollup())
        .as("the rebuilt writer recovers the whole unrecorded window from the durable checkpoint")
        .isEqualTo(60L);

    assertThat(sumFor(boot, "chart-gate-failed"))
        .as("70 evaluations, all accounted for across a failed write AND a baseline reset")
        .isEqualTo(70L);
  }

  @Test
  void theShutdownFlushClosesTheFinalWindow() {
    // The one window the protocol cannot otherwise cover: evaluations since the last successful
    // write live only in memory. A graceful stop must persist them — this is the 2026-07-20 shape,
    // a deliberate restart of a healthy engine, which previously destroyed the session's evidence.
    Instant t0 = Instant.now().minus(Duration.ofHours(4));
    UUID boot = UUID.randomUUID();
    MutableClock clock = new MutableClock(t0);
    SignalEvalOutcomeRollupJob job =
        job(engineAt(boot, counters(12, 0), counters(19, 0)), repository, clock, 180);

    job.rollup(); // scheduled tick: 12 recorded
    clock.set(t0.plus(Duration.ofMinutes(3)));
    job.flushOnShutdown(); // 7 more arrived before the stop

    assertThat(sumFor(boot, "chart-gate-failed"))
        .as("a graceful shutdown loses nothing")
        .isEqualTo(19L);
  }

  @Test
  void repeatedWritesIntoOneBucketAddRatherThanReplace() {
    OffsetDateTime bucket =
        SignalEvalOutcomeRollupJob.bucketFor(Instant.now().minus(Duration.ofHours(5)));
    UUID boot = UUID.randomUUID();

    repository.upsertBucket(bucket, boot, Map.of("fired", 2L), Map.of("fired", 2L));
    repository.upsertBucket(bucket, boot, Map.of("fired", 3L), Map.of("fired", 5L));

    // Safe because deltas are derived from the durable checkpoint, so a second write into the same
    // (bucket, boot) is always a genuine further increment.
    assertThat(sumFor(boot, "fired")).isEqualTo(5L);
    Long checkpoint =
        jdbc.queryForObject(
            "SELECT cumulative_count FROM signal_eval_outcomes"
                + " WHERE bucket_time = ? AND boot_id = ? AND outcome = 'fired'",
            Long.class,
            bucket,
            boot);
    assertThat(checkpoint).as("cumulative REPLACES so the checkpoint stays absolute").isEqualTo(5L);
  }

  @Test
  void theCheckpointReadIsScopedToOneBoot() {
    OffsetDateTime bucket =
        SignalEvalOutcomeRollupJob.bucketFor(Instant.now().minus(Duration.ofHours(6)));
    UUID mine = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    repository.upsertBucket(bucket, other, Map.of("fired", 999L), Map.of("fired", 999L));

    assertThat(repository.lastCumulativeForBoot(mine))
        .as("a fresh boot sees no checkpoint, so its first delta is everything since boot")
        .isEmpty();
    assertThat(repository.lastCumulativeForBoot(other)).containsEntry("fired", 999L);
  }

  @Test
  void canonicalLivenessQueryReportsTheOutcomeMixForTheWindow() {
    OffsetDateTime bucket =
        SignalEvalOutcomeRollupJob.bucketFor(Instant.now().minus(Duration.ofHours(7)));
    UUID boot = UUID.randomUUID();
    Map<String, Long> values =
        Map.of("chart-gate-failed", 125L, "fired", 4L, "discipline-paused", 0L);
    repository.upsertBucket(bucket, boot, values, values);

    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT outcome,"
                + " SUM(eval_count) AS evaluations,"
                + " COUNT(*) AS buckets_recorded,"
                + " COUNT(*) FILTER (WHERE eval_count > 0) AS buckets_active,"
                + " COUNT(DISTINCT boot_id) AS boots"
                + " FROM signal_eval_outcomes"
                + " WHERE bucket_time >= ? AND bucket_time < ?"
                + " GROUP BY outcome ORDER BY evaluations DESC",
            bucket,
            bucket.plusMinutes(3));

    assertThat(rows).hasSize(3);
    assertThat(rows.get(0)).containsEntry("outcome", "chart-gate-failed");
    assertThat(((Number) rows.get(0).get("evaluations")).longValue()).isEqualTo(125L);
    assertThat(((Number) rows.get(0).get("boots")).longValue()).isEqualTo(1L);

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
    UUID boot = UUID.randomUUID();
    repository.upsertBucket(aged, boot, Map.of("fired", 1L), Map.of("fired", 1L));
    repository.upsertBucket(recent, boot, Map.of("fired", 1L), Map.of("fired", 2L));

    int deleted = job(mock(SignalEngine.class), repository, Clock.systemUTC(), 180).prune();

    assertThat(deleted).isGreaterThanOrEqualTo(1);
    assertThat(rowExists(aged, boot, "fired")).as("aged bucket pruned").isFalse();
    assertThat(rowExists(recent, boot, "fired")).as("recent bucket retained").isTrue();
  }

  @Test
  void nonPositiveHorizonIsGuardedAndDeletesNothing() {
    OffsetDateTime ancient =
        SignalEvalOutcomeRollupJob.bucketFor(Instant.now().minus(Duration.ofDays(500)));
    UUID boot = UUID.randomUUID();
    repository.upsertBucket(ancient, boot, Map.of("fired", 1L), Map.of("fired", 1L));

    // A 0-day horizon would otherwise delete every row — including every live boot's checkpoint.
    int deleted = job(mock(SignalEngine.class), repository, Clock.systemUTC(), 0).prune();

    assertThat(deleted).isZero();
    assertThat(rowExists(ancient, boot, "fired")).as("guard prevented the wipe").isTrue();

    repository.deleteOlderThanDays(180); // clean the ancient seed for later prune assertions
  }
}
