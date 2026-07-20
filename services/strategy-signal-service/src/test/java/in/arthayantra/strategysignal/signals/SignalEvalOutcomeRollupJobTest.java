package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.strategysignal.signals.SignalEngine.Outcome;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

/**
 * The delta, bucketing and scheduling semantics of the V043 rollup, tested as pure functions — no
 * clock, no engine, no database. These are the properties the retroactive-liveness record depends
 * on: every outcome recorded every bucket including zeros, a restart that reads as neither negative
 * activity nor a gap, and a cron that actually parses.
 *
 * <p>The idempotency guarantees (no double count on a lost acknowledgement, no loss across a
 * baseline reset) are proven against a real database in
 * {@code SignalEvalOutcomeRollupIntegrationTest} — they are properties of the DURABLE checkpoint, so
 * they cannot be demonstrated in-memory.
 */
class SignalEvalOutcomeRollupJobTest {

  private static Map<Outcome, Long> counts(Object... pairs) {
    Map<Outcome, Long> map = new EnumMap<>(Outcome.class);
    for (int i = 0; i < pairs.length; i += 2) {
      map.put((Outcome) pairs[i], ((Number) pairs[i + 1]).longValue());
    }
    return map;
  }

  private static long sum(Map<String, Long> deltas) {
    return deltas.values().stream().mapToLong(Long::longValue).sum();
  }

  @Test
  void everyOutcomeIsRecordedEveryBucketIncludingZeros() {
    Map<String, Long> cumulative = SignalEvalOutcomeRollupJob.byTag(counts(Outcome.FIRED, 3));

    // A zero row is the evidence that the process was alive but the eval loop produced nothing —
    // omitting it would reproduce the very ambiguity this table exists to close.
    assertThat(cumulative).hasSize(Outcome.values().length);
    assertThat(cumulative.keySet())
        .containsExactlyInAnyOrder(
            "fired",
            "confluence-blocked",
            "confluence-gate-absent",
            "discipline-paused",
            "composite-below-threshold",
            "chart-gate-failed",
            "unscoreable-indicators-warming");
    assertThat(cumulative.get("fired")).isEqualTo(3L);
    assertThat(cumulative.get("chart-gate-failed")).isZero();
  }

  @Test
  void deltaIsTheGrowthSinceTheDurableCheckpoint() {
    Map<String, Long> checkpoint = Map.of("fired", 10L, "chart-gate-failed", 200L);
    Map<String, Long> cumulative =
        SignalEvalOutcomeRollupJob.byTag(counts(Outcome.FIRED, 12, Outcome.CHART_GATE_FAILED, 263));

    Map<String, Long> deltas = SignalEvalOutcomeRollupJob.deltas(checkpoint, cumulative);

    assertThat(deltas.get("fired")).isEqualTo(2L);
    assertThat(deltas.get("chart-gate-failed")).isEqualTo(63L);
  }

  @Test
  void aBootWithNoCheckpointReportsEverythingSinceBootAndNeverGoesNegative() {
    // A restart yields BOTH zeroed counters and a fresh epoch, so the new boot finds no checkpoint
    // of its own and differences against 0. That is the whole restart contract: no negative, no gap.
    Map<String, Long> afterRestart =
        SignalEvalOutcomeRollupJob.byTag(
            counts(Outcome.FIRED, 4, Outcome.COMPOSITE_BELOW_THRESHOLD, 91));

    Map<String, Long> deltas = SignalEvalOutcomeRollupJob.deltas(Map.of(), afterRestart);

    assertThat(deltas.values()).allSatisfy(v -> assertThat(v).isNotNegative());
    assertThat(deltas.get("fired")).isEqualTo(4L);
    assertThat(deltas.get("composite-below-threshold")).isEqualTo(91L);
  }

  @Test
  void aDaySumSurvivesARestartWithoutLossOrDoubleCount() {
    // 09:15-12:00 on one boot, then a restart, then 12:00-15:30 on the next. Summing the persisted
    // deltas must equal the true evaluation total — the counters themselves cannot answer this.
    long preRestart =
        sum(
            SignalEvalOutcomeRollupJob.deltas(
                Map.of(),
                SignalEvalOutcomeRollupJob.byTag(counts(Outcome.CHART_GATE_FAILED, 4000))));
    long postRestart =
        sum(
            SignalEvalOutcomeRollupJob.deltas(
                Map.of(),
                SignalEvalOutcomeRollupJob.byTag(counts(Outcome.CHART_GATE_FAILED, 3875))));

    assertThat(preRestart + postRestart).isEqualTo(7875L);
  }

  @Test
  void aMissedSnapshotIsAbsorbedByTheNextRatherThanLost() {
    // The checkpoint only moves when a write commits, so a failed/delayed tick leaves it behind and
    // the next delta covers both windows. Only 3m resolution degrades; no count is lost.
    Map<String, Long> checkpoint = Map.of("fired", 5L);
    Map<String, Long> afterTwoWindows = SignalEvalOutcomeRollupJob.byTag(counts(Outcome.FIRED, 9));

    assertThat(SignalEvalOutcomeRollupJob.deltas(checkpoint, afterTwoWindows).get("fired"))
        .isEqualTo(4L);
  }

  /**
   * The literal default out of the {@code @Scheduled} annotation, so these assertions cannot drift
   * from the value that actually ships: {@code ${key:default}} → {@code default}.
   */
  private static String defaultCron(String method) throws NoSuchMethodException {
    String placeholder =
        SignalEvalOutcomeRollupJob.class
            .getDeclaredMethod(method)
            .getAnnotation(Scheduled.class)
            .cron();
    assertThat(placeholder).startsWith("${").endsWith("}");
    return placeholder.substring(placeholder.indexOf(':') + 1, placeholder.length() - 1);
  }

  @Test
  void bothScheduledCronsParse() throws NoSuchMethodException {
    // Nothing else parses these: the job bean is gated on artha.signals.engine-enabled, which every
    // IT turns off, so a malformed cron would otherwise surface for the first time at LIVE boot.
    assertThat(CronExpression.parse(defaultCron("scheduledRollup"))).isNotNull();
    assertThat(CronExpression.parse(defaultCron("scheduledPrune"))).isNotNull();
  }

  @Test
  void theRollupCronFiresEveryThreeMinutesAcrossTheSessionWindow() throws NoSuchMethodException {
    CronExpression cron = CronExpression.parse(defaultCron("scheduledRollup"));
    // A Monday, from just before the 09:00 window opens.
    LocalDateTime cursor = LocalDateTime.parse("2026-07-20T08:59:00");

    LocalDateTime first = cron.next(cursor);
    LocalDateTime second = cron.next(first);
    int ticks = 0;
    for (LocalDateTime t = cron.next(cursor);
        t != null && t.toLocalDate().equals(cursor.toLocalDate());
        t = cron.next(t)) {
      ticks++;
    }

    assertThat(first).isEqualTo(LocalDateTime.parse("2026-07-20T09:00:00"));
    assertThat(Duration.between(first, second)).isEqualTo(Duration.ofMinutes(3));
    // 7 hours (09-15) x 20 ticks/h = 140 buckets/day; x 7 outcomes = 980 rows/day, the sizing claim.
    assertThat(ticks).isEqualTo(140);
    assertThat(ticks * Outcome.values().length).isEqualTo(980);
  }

  @Test
  void theRollupCronSkipsWeekends() throws NoSuchMethodException {
    CronExpression cron = CronExpression.parse(defaultCron("scheduledRollup"));

    // Saturday 2026-07-25 -> the next fire is Monday's first bucket.
    assertThat(cron.next(LocalDateTime.parse("2026-07-25T10:00:00")))
        .isEqualTo(LocalDateTime.parse("2026-07-27T09:00:00"));
  }

  @Test
  void bucketFloorsToAThreeMinuteBoundaryInIst() {
    OffsetDateTime bucket =
        SignalEvalOutcomeRollupJob.bucketFor(
            OffsetDateTime.parse("2026-07-20T09:20:59.750+05:30").toInstant());

    assertThat(bucket).isEqualTo(OffsetDateTime.parse("2026-07-20T09:18:00+05:30"));
    assertThat(bucket.getOffset()).isEqualTo(Ist.OFFSET);
  }

  @Test
  void theSessionOpenIsItselfABucketBoundary() {
    // IST is +05:30 = 19800s = 110 * 180, so 3m boundaries coincide in UTC and IST and the 09:15
    // open never straddles a bucket.
    Instant open = OffsetDateTime.parse("2026-07-20T09:15:00+05:30").toInstant();

    assertThat(SignalEvalOutcomeRollupJob.bucketFor(open))
        .isEqualTo(OffsetDateTime.parse("2026-07-20T09:15:00+05:30"));
  }
}
