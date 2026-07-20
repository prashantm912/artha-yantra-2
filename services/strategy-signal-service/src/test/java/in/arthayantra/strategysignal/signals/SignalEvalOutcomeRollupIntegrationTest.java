package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import in.arthayantra.common.web.time.Ist;
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
 * V045 {@code signal_eval_outcomes} against the REAL Flyway strategy lineage.
 *
 * <p>The load-bearing cases are the three cross-vendor review identified across two rounds, because
 * all three are properties of the DURABLE checkpoint and cannot be shown without a database:
 *
 * <ul>
 *   <li>{@link #aLostAcknowledgementDoesNotDoubleCount()} — Postgres commits, the acknowledgement is
 *       lost. An in-memory baseline would re-cover those evaluations on the next tick.
 *   <li>{@link #aFailedWriteFollowedByABaselineResetLosesNothing()} — a failed write, then the
 *       writer object dies and is rebuilt (the process-restart shape) while the counters keep their
 *       epoch. An in-memory baseline would take the unrecorded window down with it.
 *   <li>{@link #aRecoverySpanningIstDatesIsNotCountedAsCurrentDayActivity()} — the weekend path,
 *       where a recovered window would otherwise be attributed wholesale to the current date and
 *       silently corrupt BOTH days' outcome mix.
 * </ul>
 *
 * <p>The engine is MOCKED so counter values and the epoch are explicit. (The real engine bean is
 * absent here anyway — it and the rollup job share the {@code artha.signals.engine-enabled} gate,
 * which the IT harness turns off.)
 *
 * <p>Shared singleton DB with NO per-method cleanup: every method uses its OWN random
 * {@code boot_id} and derives a private time window from {@code now}, so methods cannot collide with
 * each other or with a surefire rerun.
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
  @SafeVarargs
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

  /**
   * The CANONICAL liveness query from the V045 header — its SELECT aggregates and its span-overlap
   * WHERE verbatim — for one IST calendar date. The extra {@code boot_id}/{@code outcome} predicates
   * are test isolation only (the shared singleton DB holds other methods' rows) and do not weaken
   * the span logic under test.
   */
  private Map<String, Object> livenessFor(OffsetDateTime istDay, UUID bootId, String outcome) {
    OffsetDateTime from =
        istDay.withOffsetSameInstant(Ist.OFFSET).toLocalDate().atStartOfDay().atOffset(Ist.OFFSET);
    OffsetDateTime to = from.plusDays(1);
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT outcome,"
                + " COALESCE(SUM(eval_count) FILTER (WHERE recovered_from IS NULL), 0)"
                + "   AS evaluations,"
                + " COALESCE(SUM(eval_count) FILTER (WHERE recovered_from IS NOT NULL), 0)"
                + "   AS unattributable,"
                + " COUNT(*) FILTER (WHERE recovered_from IS NULL) AS buckets_recorded"
                + " FROM signal_eval_outcomes"
                + " WHERE bucket_time >= ?"
                + "   AND COALESCE(recovered_from, bucket_time) < ?"
                + "   AND boot_id = ? AND outcome = ?"
                + " GROUP BY outcome",
            from,
            to,
            bootId,
            outcome);
    return rows.isEmpty() ? Map.of() : rows.get(0);
  }

  private static long asLong(Map<String, Object> row, String column) {
    Object value = row.get(column);
    return value == null ? 0 : ((Number) value).longValue();
  }

  @Test
  void theFirstTickOfANewTradingDayIsNotMarkedWhenNothingWasRecovered() {
    // THE NORMAL MORNING, end to end. Yesterday's last tick is ~15:57 and today's first is 09:00,
    // so the IST dates always differ — but nothing evaluates overnight, so the counters are
    // unchanged. These seven rows must stay ORDINARY liveness rows: unmarked, no alert, and
    // counting toward buckets_recorded. They are the evidence that the engine came up today.
    OffsetDateTime yesterdayClose =
        OffsetDateTime.now(Ist.OFFSET)
            .minusDays(8)
            .withHour(15)
            .withMinute(57)
            .withSecond(0)
            .withNano(0);
    OffsetDateTime todayOpen = yesterdayClose.plusDays(1).withHour(9).withMinute(0);
    UUID boot = UUID.randomUUID();
    MutableClock clock = new MutableClock(yesterdayClose.toInstant());

    // Yesterday's closing tick.
    job(engineAt(boot, counters(4000, 12)), repository, clock, 180).rollup();
    // This morning's first tick — counters IDENTICAL, because nothing ran overnight.
    clock.set(todayOpen.toInstant());
    assertThat(job(engineAt(boot, counters(4000, 12)), repository, clock, 180).rollup())
        .as("an idle overnight window recovers nothing")
        .isZero();

    OffsetDateTime openingBucket = SignalEvalOutcomeRollupJob.bucketFor(todayOpen.toInstant());
    Long marked =
        jdbc.queryForObject(
            "SELECT count(*) FROM signal_eval_outcomes"
                + " WHERE bucket_time = ? AND boot_id = ? AND recovered_from IS NOT NULL",
            Long.class,
            openingBucket,
            boot);
    assertThat(marked).as("no row of the session's first bucket is marked").isZero();

    // And they count as process-alive evidence, which is the whole point of the zero rows.
    Map<String, Object> todayLiveness = livenessFor(todayOpen, boot, "chart-gate-failed");
    assertThat(asLong(todayLiveness, "buckets_recorded"))
        .as("the opening bucket is counted as liveness evidence, not excluded")
        .isEqualTo(1L);
    assertThat(asLong(todayLiveness, "unattributable")).isZero();
    assertThat(asLong(todayLiveness, "evaluations")).isZero();

    // Yesterday keeps its own totals, undisturbed.
    assertThat(asLong(livenessFor(yesterdayClose, boot, "chart-gate-failed"), "evaluations"))
        .isEqualTo(4000L);
  }

  @Test
  void aRecoverySpanningIstDatesIsNotCountedAsCurrentDayActivity() {
    // THE WEEKEND PATH. The process runs continuously (boot_id stable), writes succeed on date D at
    // 15:00, then fail. No ticks fire until date D+3. Without the cross-date mark, D's final counts
    // would be recorded as D+3 activity: D under-reports, D+3 over-reports, both mixes wrong.
    // Ten days back keeps these rows well inside the 180d retention horizon.
    OffsetDateTime dayD =
        OffsetDateTime.now(Ist.OFFSET)
            .minusDays(10)
            .withHour(15)
            .withMinute(0)
            .withSecond(0)
            .withNano(0);
    OffsetDateTime dayD3 = dayD.plusDays(3).withHour(9).withMinute(0);
    UUID boot = UUID.randomUUID();
    MutableClock clock = new MutableClock(dayD.toInstant());

    // Date D: 100 evaluations recorded normally.
    assertThat(job(engineAt(boot, counters(100, 0)), repository, clock, 180).rollup())
        .isEqualTo(100L);

    // Date D+3: counters have grown to 130. Those 30 happened at an unknown point in the span.
    clock.set(dayD3.toInstant());
    assertThat(job(engineAt(boot, counters(130, 0)), repository, clock, 180).rollup())
        .as("the 30 are recorded — never lost")
        .isEqualTo(30L);

    OffsetDateTime recoveryBucket = SignalEvalOutcomeRollupJob.bucketFor(dayD3.toInstant());
    Long marked =
        jdbc.queryForObject(
            "SELECT count(*) FROM signal_eval_outcomes"
                + " WHERE bucket_time = ? AND boot_id = ? AND recovered_from IS NOT NULL",
            Long.class,
            recoveryBucket,
            boot);
    assertThat(marked)
        .as("ONLY chart-gate-failed moved, so only it is marked — the six zero outcomes stay"
            + " ordinary liveness rows")
        .isEqualTo(1L);
    Long unmarkedZeroRows =
        jdbc.queryForObject(
            "SELECT count(*) FROM signal_eval_outcomes"
                + " WHERE bucket_time = ? AND boot_id = ? AND recovered_from IS NULL",
            Long.class,
            recoveryBucket,
            boot);
    assertThat(unmarkedZeroRows).isEqualTo((long) Outcome.values().length - 1);

    // THE ASSERTION THE REVIEW ASKED FOR: the recovered evaluations do NOT land on D+3's total.
    Map<String, Object> dayD3Liveness = livenessFor(dayD3, boot, "chart-gate-failed");
    assertThat(asLong(dayD3Liveness, "evaluations"))
        .as("D+3 attributable activity is ZERO — the 30 are not its own")
        .isZero();
    assertThat(asLong(dayD3Liveness, "unattributable"))
        .as("D+3 surfaces them separately instead of absorbing them")
        .isEqualTo(30L);

    // And date D keeps its own 100 while ALSO surfacing the overlapping recovery, so a reader of D
    // sees that its total may be understated rather than being quietly misled.
    Map<String, Object> dayDLiveness = livenessFor(dayD, boot, "chart-gate-failed");
    assertThat(asLong(dayDLiveness, "evaluations")).isEqualTo(100L);
    assertThat(asLong(dayDLiveness, "unattributable"))
        .as("the span overlaps D, so D is told about it too")
        .isEqualTo(30L);

    // Nothing was lost: 130 counters == 100 attributable + 30 unattributable.
    assertThat(sumFor(boot, "chart-gate-failed")).isEqualTo(130L);
  }

  @Test
  void aRecoveryWithinOneDayStaysAttributableToThatDay() {
    // The contrast case: a failed stretch recovered on the SAME IST date is NOT marked, because the
    // day total remains exact — only the 3m shape of that stretch is approximate.
    OffsetDateTime morning =
        OffsetDateTime.now(Ist.OFFSET)
            .minusDays(9)
            .withHour(9)
            .withMinute(0)
            .withSecond(0)
            .withNano(0);
    UUID boot = UUID.randomUUID();
    MutableClock clock = new MutableClock(morning.toInstant());

    job(engineAt(boot, counters(50, 0)), repository, clock, 180).rollup();
    clock.set(morning.withHour(15).withMinute(57).toInstant());
    job(engineAt(boot, counters(4000, 0)), repository, clock, 180).rollup();

    Map<String, Object> liveness = livenessFor(morning, boot, "chart-gate-failed");
    assertThat(asLong(liveness, "evaluations"))
        .as("a same-day recovery keeps the day total exact")
        .isEqualTo(4000L);
    assertThat(asLong(liveness, "unattributable")).isZero();
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
        .upsertBucket(any(), eq(boot), any(), any(), any());

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
        .upsertBucket(any(), eq(boot), any(), any(), any());
    clock.set(t0.plus(Duration.ofMinutes(3)));
    assertThat(job(engineAt(boot, counters(40, 0)), broken, clock, 180).rollup()).isZero();
    assertThat(
            rowExists(
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

    repository.upsertBucket(bucket, boot, Map.of("fired", 2L), Map.of("fired", 2L), Map.of());
    repository.upsertBucket(bucket, boot, Map.of("fired", 3L), Map.of("fired", 5L), Map.of());

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
  void anUnattributableMarkIsNeverErasedByALaterWriteIntoTheSameBucket() {
    OffsetDateTime bucket =
        SignalEvalOutcomeRollupJob.bucketFor(Instant.now().minus(Duration.ofHours(8)));
    OffsetDateTime origin = bucket.minusDays(3);
    UUID boot = UUID.randomUUID();

    repository.upsertBucket(bucket, boot, Map.of("fired", 5L), Map.of("fired", 5L), Map.of("fired", origin));
    repository.upsertBucket(bucket, boot, Map.of("fired", 1L), Map.of("fired", 6L), Map.of());

    OffsetDateTime stored =
        jdbc.queryForObject(
            "SELECT recovered_from FROM signal_eval_outcomes"
                + " WHERE bucket_time = ? AND boot_id = ? AND outcome = 'fired'",
            OffsetDateTime.class,
            bucket,
            boot);
    assertThat(stored)
        .as("a bucket that once held an unattributable recovery stays marked")
        .isEqualTo(origin);
  }

  @Test
  void theCheckpointReadIsScopedToOneBootAndCarriesItsBucket() {
    OffsetDateTime bucket =
        SignalEvalOutcomeRollupJob.bucketFor(Instant.now().minus(Duration.ofHours(6)));
    UUID mine = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    repository.upsertBucket(bucket, other, Map.of("fired", 999L), Map.of("fired", 999L), Map.of());

    assertThat(repository.lastCumulativeForBoot(mine).absent())
        .as("a fresh boot sees no checkpoint, so its first delta is everything since boot")
        .isTrue();
    SignalEvalOutcomeRepository.Checkpoint checkpoint = repository.lastCumulativeForBoot(other);
    assertThat(checkpoint.cumulative()).containsEntry("fired", 999L);
    assertThat(checkpoint.bucketTime())
        .as("the checkpoint carries WHEN it was taken — the cross-date test depends on it")
        .isEqualTo(bucket);
  }

  @Test
  void canonicalLivenessQueryReportsTheOutcomeMixForTheWindow() {
    OffsetDateTime bucket =
        SignalEvalOutcomeRollupJob.bucketFor(Instant.now().minus(Duration.ofHours(7)));
    UUID boot = UUID.randomUUID();
    Map<String, Long> values =
        Map.of("chart-gate-failed", 125L, "fired", 4L, "discipline-paused", 0L);
    repository.upsertBucket(bucket, boot, values, values, Map.of());

    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT outcome,"
                + " SUM(eval_count) FILTER (WHERE recovered_from IS NULL) AS evaluations,"
                + " COUNT(*) FILTER (WHERE recovered_from IS NULL) AS buckets_recorded,"
                + " COUNT(*) FILTER (WHERE recovered_from IS NULL AND eval_count > 0)"
                + "   AS buckets_active,"
                + " COUNT(DISTINCT boot_id) AS boots"
                + " FROM signal_eval_outcomes"
                + " WHERE bucket_time >= ? AND COALESCE(recovered_from, bucket_time) < ?"
                + "   AND boot_id = ?"
                + " GROUP BY outcome ORDER BY evaluations DESC",
            bucket,
            bucket.plusMinutes(3),
            boot);

    assertThat(rows).hasSize(3);
    assertThat(rows.get(0)).containsEntry("outcome", "chart-gate-failed");
    assertThat(asLong(rows.get(0), "evaluations")).isEqualTo(125L);
    assertThat(asLong(rows.get(0), "boots")).isEqualTo(1L);

    // The zero row is not noise: buckets_recorded proves the PROCESS was up even though this
    // outcome never occurred. Absence of rows is what would mean the process was down.
    Map<String, Object> paused =
        rows.stream()
            .filter(row -> "discipline-paused".equals(row.get("outcome")))
            .findFirst()
            .orElseThrow();
    assertThat(asLong(paused, "buckets_recorded")).isEqualTo(1L);
    assertThat(asLong(paused, "buckets_active")).isZero();
  }

  @Test
  void pruneDeletesAgedBucketsAndSparesRecentOnes() {
    OffsetDateTime aged =
        SignalEvalOutcomeRollupJob.bucketFor(Instant.now().minus(Duration.ofDays(200)));
    OffsetDateTime recent =
        SignalEvalOutcomeRollupJob.bucketFor(Instant.now().minus(Duration.ofDays(3)));
    UUID boot = UUID.randomUUID();
    repository.upsertBucket(aged, boot, Map.of("fired", 1L), Map.of("fired", 1L), Map.of());
    repository.upsertBucket(recent, boot, Map.of("fired", 1L), Map.of("fired", 2L), Map.of());

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
    repository.upsertBucket(ancient, boot, Map.of("fired", 1L), Map.of("fired", 1L), Map.of());

    // A 0-day horizon would otherwise delete every row — including every live boot's checkpoint.
    int deleted = job(mock(SignalEngine.class), repository, Clock.systemUTC(), 0).prune();

    assertThat(deleted).isZero();
    assertThat(rowExists(ancient, boot, "fired")).as("guard prevented the wipe").isTrue();

    repository.deleteOlderThanDays(180); // clean the ancient seed for later prune assertions
  }
}
