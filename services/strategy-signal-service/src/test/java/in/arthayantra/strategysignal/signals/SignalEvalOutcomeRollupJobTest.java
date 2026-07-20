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
 * The delta + bucketing semantics of the V043 rollup, tested as pure functions — no clock, no
 * engine, no database. These are the properties the retroactive-liveness record depends on: a
 * restart must never read as negative activity or as a gap, and every outcome must be recorded every
 * bucket including zeros.
 */
class SignalEvalOutcomeRollupJobTest {

  private static Map<Outcome, Long> counts(Object... pairs) {
    Map<Outcome, Long> map = new EnumMap<>(Outcome.class);
    for (int i = 0; i < pairs.length; i += 2) {
      map.put((Outcome) pairs[i], ((Number) pairs[i + 1]).longValue());
    }
    return map;
  }

  @Test
  void everyOutcomeIsRecordedEveryBucketIncludingZeros() {
    Map<String, Long> deltas =
        SignalEvalOutcomeRollupJob.deltas(Map.of(), counts(Outcome.FIRED, 3));

    // A zero row is the evidence that the process was alive but the eval loop produced nothing —
    // omitting it would reproduce the very ambiguity this table exists to close.
    assertThat(deltas).hasSize(Outcome.values().length);
    assertThat(deltas.keySet())
        .containsExactlyInAnyOrder(
            "fired",
            "confluence-blocked",
            "confluence-gate-absent",
            "discipline-paused",
            "composite-below-threshold",
            "chart-gate-failed",
            "unscoreable-indicators-warming");
    assertThat(deltas.get("fired")).isEqualTo(3L);
    assertThat(deltas.get("chart-gate-failed")).isZero();
  }

  @Test
  void deltaIsTheGrowthSinceTheLastSuccessfulSnapshot() {
    Map<Outcome, Long> baseline = counts(Outcome.FIRED, 10, Outcome.CHART_GATE_FAILED, 200);
    Map<Outcome, Long> current = counts(Outcome.FIRED, 12, Outcome.CHART_GATE_FAILED, 263);

    Map<String, Long> deltas = SignalEvalOutcomeRollupJob.deltas(baseline, current);

    assertThat(deltas.get("fired")).isEqualTo(2L);
    assertThat(deltas.get("chart-gate-failed")).isEqualTo(63L);
  }

  @Test
  void firstSnapshotAfterBootReportsEverythingSinceBootAndNeverGoesNegative() {
    // A restart resets the Micrometer counters AND the in-memory baseline together — they share a
    // JVM lifetime. So the post-restart baseline is empty and the first snapshot reports the
    // post-restart counts in full. This is the whole restart contract: no negative, no gap.
    Map<Outcome, Long> afterRestart = counts(Outcome.FIRED, 4, Outcome.COMPOSITE_BELOW_THRESHOLD, 91);

    Map<String, Long> deltas = SignalEvalOutcomeRollupJob.deltas(new EnumMap<>(Outcome.class), afterRestart);

    assertThat(deltas.values()).allSatisfy(v -> assertThat(v).isNotNegative());
    assertThat(deltas.get("fired")).isEqualTo(4L);
    assertThat(deltas.get("composite-below-threshold")).isEqualTo(91L);
  }

  @Test
  void aDaySumSurvivesARestartWithoutLossOrDoubleCount() {
    // 09:15-12:00 on one boot, then a restart, then 12:00-15:30 on the next. Summing the persisted
    // deltas must equal the true evaluation total — the counters themselves cannot answer this.
    long preRestart =
        SignalEvalOutcomeRollupJob.deltas(Map.of(), counts(Outcome.CHART_GATE_FAILED, 4000))
            .values()
            .stream()
            .mapToLong(Long::longValue)
            .sum();
    long postRestart =
        SignalEvalOutcomeRollupJob.deltas(
                new EnumMap<>(Outcome.class), counts(Outcome.CHART_GATE_FAILED, 3875))
            .values()
            .stream()
            .mapToLong(Long::longValue)
            .sum();

    assertThat(preRestart + postRestart).isEqualTo(7875L);
  }

  @Test
  void aMissedSnapshotIsAbsorbedByTheNextOneRatherThanLost() {
    // The baseline advances only after a DURABLE write, so a failed/delayed tick leaves it behind
    // and the next successful delta covers both windows. Only 3m resolution degrades; no count is
    // lost and none is written twice.
    Map<Outcome, Long> baseline = counts(Outcome.FIRED, 5);
    Map<Outcome, Long> afterTwoWindows = counts(Outcome.FIRED, 9); // tick at +3m failed, +6m succeeds

    assertThat(SignalEvalOutcomeRollupJob.deltas(baseline, afterTwoWindows).get("fired"))
        .isEqualTo(4L);
  }

  /**
   * The literal default out of the {@code @Scheduled} annotation, so these assertions cannot drift
   * from the value that actually ships: {@code ${key:default}} → {@code default}.
   */
  private static String defaultCron(String method) throws NoSuchMethodException {
    String placeholder =
        SignalEvalOutcomeRollupJob.class.getDeclaredMethod(method).getAnnotation(Scheduled.class).cron();
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
