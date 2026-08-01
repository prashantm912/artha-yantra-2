package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.strategysignal.signals.SignalEngine.Outcome;
import in.arthayantra.strategysignal.signals.SignalEngine.OutcomeSnapshot;
import in.arthayantra.strategysignal.signals.SignalEngine.StrategyEvalKey;
import in.arthayantra.strategysignal.signals.SignalEngine.StrategyEvalSnapshot;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V053 {@code strategy_eval_denominator} against the REAL Flyway strategy lineage — the write
 * protocol that gives per-strategy rates a denominator.
 *
 * <p>The load-bearing cases are the ones the design exists for, and all three are properties of
 * <b>cumulative values REPLACED</b> rather than deltas added, so none can be shown without a database:
 *
 * <ul>
 *   <li>{@link #flushingTwiceLeavesTheRowUnchanged()} — the anti-double-count guarantee. Nothing on
 *       the database side adds, so re-running a flush rewrites the identical absolute number.
 *   <li>{@link #aRestartMidDayKeepsBothBootsTotals()} — a restart zeroes the adders and mints a new
 *       epoch; {@code boot_id} in the key is what stops the smaller new totals from overwriting the
 *       old ones, and the day total is their SUM.
 *   <li>{@link #countsLandOnTheBarsSessionDateEvenWhenFlushedTheNextDay()} — the session date travels
 *       in the key, so a late flush cannot mis-attribute a session (V045's cross-date ambiguity has
 *       no analogue here).
 * </ul>
 *
 * <p>Shared singleton DB with NO per-method cleanup: every method uses its OWN random {@code boot_id}
 * and its OWN random slug, so methods cannot collide with each other or with a surefire rerun.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class StrategyEvalDenominatorIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private StrategyEvalDenominatorRepository denominators;
  @Autowired private SignalEvalOutcomeRepository outcomes;
  @Autowired private ApplicationEventPublisher events;
  @Autowired private Environment environment;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private javax.sql.DataSource dataSource;

  /** Today in IST — the flush evicts sessions strictly older than this, so "live" rows use it. */
  private static LocalDate istToday() {
    return OffsetDateTime.now(Ist.OFFSET).toLocalDate();
  }

  private static String uniqueSlug(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  /**
   * A PLAIN repository instance for the failure-injection test. The injected bean is a Spring AOP
   * proxy (persistence exception translation), which Mockito cannot spy.
   */
  private StrategyEvalDenominatorRepository unproxiedDenominators() {
    return new StrategyEvalDenominatorRepository(dataSource);
  }

  private SignalEvalOutcomeRollupJob job(
      SignalEngine engine, StrategyEvalDenominatorRepository repo, int retentionDays) {
    return new SignalEvalOutcomeRollupJob(
        engine, outcomes, repo, events, Clock.systemUTC(), environment, retentionDays);
  }

  /**
   * An engine whose per-strategy counters return the given maps in succession under one epoch. The
   * fleet snapshot is stubbed empty so the V045 half of the tick is a harmless no-op here.
   */
  @SafeVarargs
  private static SignalEngine engineAt(UUID epoch, Map<StrategyEvalKey, Long>... successiveReads) {
    SignalEngine engine = mock(SignalEngine.class);
    StrategyEvalSnapshot[] snapshots = new StrategyEvalSnapshot[successiveReads.length];
    for (int i = 0; i < successiveReads.length; i++) {
      snapshots[i] = new StrategyEvalSnapshot(epoch, successiveReads[i]);
    }
    StrategyEvalSnapshot[] rest = new StrategyEvalSnapshot[snapshots.length - 1];
    System.arraycopy(snapshots, 1, rest, 0, rest.length);
    when(engine.strategyEvalSnapshot()).thenReturn(snapshots[0], rest);
    when(engine.outcomeSnapshot())
        .thenReturn(new OutcomeSnapshot(epoch, new EnumMap<>(Outcome.class)));
    return engine;
  }

  private static Map<StrategyEvalKey, Long> counts(Object... keyThenCount) {
    Map<StrategyEvalKey, Long> map = new LinkedHashMap<>();
    for (int i = 0; i < keyThenCount.length; i += 2) {
      map.put((StrategyEvalKey) keyThenCount[i], ((Number) keyThenCount[i + 1]).longValue());
    }
    return map;
  }

  private static StrategyEvalKey key(LocalDate session, String slug, Outcome outcome) {
    return new StrategyEvalKey(session, slug, outcome);
  }

  private Long storedCount(LocalDate session, UUID boot, String slug, Outcome outcome) {
    return jdbc.queryForObject(
        "SELECT eval_count FROM strategy_eval_denominator"
            + " WHERE session_date = ? AND boot_id = ? AND strategy_slug = ? AND outcome = ?",
        Long.class,
        session,
        boot,
        slug,
        outcome.tag());
  }

  /** The canonical denominator query from the V053 header, scoped to one slug for isolation. */
  private long denominatorFor(LocalDate session, String slug) {
    Long total =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(eval_count), 0) FROM strategy_eval_denominator"
                + " WHERE session_date = ? AND strategy_slug = ?",
            Long.class,
            session,
            slug);
    return total == null ? 0 : total;
  }

  @Test
  void twoStrategiesEvaluatingDifferentBarCountsGetDistinctPersistedTotals() {
    // The whole point: "scalp-a evaluated 412 bars today, scalp-b evaluated 137" is now a row, not
    // an inference off the 3m grid or a fleet total shared by every strategy.
    LocalDate session = istToday();
    UUID boot = UUID.randomUUID();
    String slugA = uniqueSlug("scalp-denom-a");
    String slugB = uniqueSlug("scalp-denom-b");

    job(
            engineAt(
                boot,
                counts(
                    key(session, slugA, Outcome.CHART_GATE_FAILED), 412L,
                    key(session, slugA, Outcome.FIRED), 3L,
                    key(session, slugB, Outcome.CHART_GATE_FAILED), 137L)),
            denominators,
            180)
        .flushStrategyDenominators();

    assertThat(denominatorFor(session, slugA)).isEqualTo(415L);
    assertThat(denominatorFor(session, slugB)).isEqualTo(137L);
    assertThat(storedCount(session, boot, slugA, Outcome.FIRED)).isEqualTo(3L);
  }

  @Test
  void flushingTwiceLeavesTheRowUnchanged() {
    // THE ANTI-DOUBLE-COUNT GUARANTEE. A delta protocol would need a durable checkpoint to survive a
    // lost acknowledgement or a retry; here the value is an absolute cumulative read off one
    // in-memory adder and the upsert ASSIGNS it, so a second flush of the same state is a no-op by
    // construction. TODAY's session date on purpose — the flush evicts strictly older sessions, so
    // a past-dated key would vanish after the first flush and the second flush would write nothing,
    // which would pass this test for the wrong reason.
    LocalDate session = istToday();
    UUID boot = UUID.randomUUID();
    String slug = uniqueSlug("scalp-idempotent");
    Map<StrategyEvalKey, Long> unchanged =
        counts(key(session, slug, Outcome.COMPOSITE_BELOW_THRESHOLD), 250L);

    SignalEvalOutcomeRollupJob job = job(engineAt(boot, unchanged, unchanged), denominators, 180);

    assertThat(job.flushStrategyDenominators()).as("first flush writes the row").isEqualTo(1);
    assertThat(job.flushStrategyDenominators())
        .as("second flush touches the same single row")
        .isEqualTo(1);

    assertThat(storedCount(session, boot, slug, Outcome.COMPOSITE_BELOW_THRESHOLD))
        .as("unchanged — an ADD-on-conflict protocol would read 500 here")
        .isEqualTo(250L);
    assertThat(denominatorFor(session, slug)).isEqualTo(250L);
  }

  @Test
  void aGrownCounterOverwritesRatherThanAccumulates() {
    // The other half of REPLACE: a later flush of a LARGER absolute total must land as that total,
    // not as total + previous.
    LocalDate session = istToday();
    UUID boot = UUID.randomUUID();
    String slug = uniqueSlug("scalp-grows");

    SignalEvalOutcomeRollupJob job =
        job(
            engineAt(
                boot,
                counts(key(session, slug, Outcome.CHART_GATE_FAILED), 100L),
                counts(key(session, slug, Outcome.CHART_GATE_FAILED), 163L)),
            denominators,
            180);
    job.flushStrategyDenominators();
    job.flushStrategyDenominators();

    assertThat(storedCount(session, boot, slug, Outcome.CHART_GATE_FAILED))
        .as("163, not 263")
        .isEqualTo(163L);
  }

  @Test
  void aRestartMidDayKeepsBothBootsTotals() {
    // A restart zeroes the adders AND mints a fresh epoch. boot_id in the key is what makes the day
    // survive: the second boot writes its OWN rows, so its smaller totals can never overwrite the
    // first boot's, and the day's true denominator is the SUM across boots.
    LocalDate session = istToday();
    UUID bootA = UUID.randomUUID();
    UUID bootB = UUID.randomUUID();
    String slug = uniqueSlug("scalp-restart");

    job(
            engineAt(bootA, counts(key(session, slug, Outcome.CHART_GATE_FAILED), 300L)),
            denominators,
            180)
        .flushStrategyDenominators();
    // ...restart: counters back to zero under a new epoch, then 120 more evaluations.
    job(
            engineAt(bootB, counts(key(session, slug, Outcome.CHART_GATE_FAILED), 120L)),
            denominators,
            180)
        .flushStrategyDenominators();

    assertThat(storedCount(session, bootA, slug, Outcome.CHART_GATE_FAILED)).isEqualTo(300L);
    assertThat(storedCount(session, bootB, slug, Outcome.CHART_GATE_FAILED)).isEqualTo(120L);
    assertThat(denominatorFor(session, slug))
        .as("the day total is exact across the restart — 300 + 120")
        .isEqualTo(420L);
    Long boots =
        jdbc.queryForObject(
            "SELECT count(DISTINCT boot_id) FROM strategy_eval_denominator"
                + " WHERE session_date = ? AND strategy_slug = ?",
            Long.class,
            session,
            slug);
    assertThat(boots).as("the restart is visible, not silently merged").isEqualTo(2L);
  }

  @Test
  void countsLandOnTheBarsSessionDateEvenWhenFlushedTheNextDay() {
    // The weekend/late-flush path. Bars closing after the 15:57 tick are counted but not flushed
    // until the next trading morning; because the session date is IN THE KEY they still land on
    // their own day. V045 cannot do this — its counters carry a total and no timing, so a recovered
    // span there is genuinely unattributable and has to be marked as such.
    LocalDate friday = istToday().minusDays(3);
    UUID boot = UUID.randomUUID();
    String slug = uniqueSlug("scalp-late-flush");

    // One flush, running TODAY, carrying Friday's counts.
    job(engineAt(boot, counts(key(friday, slug, Outcome.FIRED), 7L)), denominators, 180)
        .flushStrategyDenominators();

    assertThat(denominatorFor(friday, slug)).as("attributed to Friday").isEqualTo(7L);
    assertThat(denominatorFor(istToday(), slug)).as("today gets none of it").isZero();
  }

  @Test
  void theSnapshotIsPersistedBeforeStaleSessionsAreEvicted() {
    // Ordering is the correctness argument for eviction: the row carrying an evicted key's FINAL
    // value must already be committed. Verified against the real engine seam, not a mock.
    LocalDate stale = istToday().minusDays(2);
    UUID boot = UUID.randomUUID();
    String slug = uniqueSlug("scalp-evict-order");
    SignalEngine engine = engineAt(boot, counts(key(stale, slug, Outcome.FIRED), 9L));

    job(engine, denominators, 180).flushStrategyDenominators();

    assertThat(denominatorFor(stale, slug))
        .as("the stale session's final value is durable before its key is dropped")
        .isEqualTo(9L);
    verify(engine).evictStrategyEvalCountsBefore(istToday());
  }

  @Test
  void aFailingDenominatorFlushIsFailSoftAndLeavesTheV045RecordIntact() {
    // Additive means additive: the new record must never be able to break or delay the fleet-level
    // one. scheduledRollup() runs V045 FIRST, then this — and this cannot throw.
    UUID boot = UUID.randomUUID();
    SignalEngine engine =
        engineAt(boot, counts(key(istToday(), uniqueSlug("scalp-failsoft"), Outcome.FIRED), 1L));
    StrategyEvalDenominatorRepository broken = spy(unproxiedDenominators());
    doThrow(new RuntimeException("denominator write failed")).when(broken).upsertCounts(any(), any());

    SignalEvalOutcomeRollupJob job = job(engine, broken, 180);
    assertThatCode(job::scheduledRollup).doesNotThrowAnyException();

    // The V045 tick still wrote its seven rows for this boot.
    Long v045Rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM signal_eval_outcomes WHERE boot_id = ?", Long.class, boot);
    assertThat(v045Rows)
        .as("the fleet record is unaffected by a failing denominator flush")
        .isEqualTo((long) Outcome.values().length);
    // Nothing was evicted, so the next flush still carries the full absolute totals.
    verify(engine, never()).evictStrategyEvalCountsBefore(any());
  }

  @Test
  void pruneDeletesAgedSessionsAndSparesRecentOnes() {
    LocalDate aged = istToday().minusDays(200);
    LocalDate recent = istToday().minusDays(3);
    UUID boot = UUID.randomUUID();
    String slug = uniqueSlug("scalp-prune");
    denominators.upsertCounts(
        boot,
        counts(
            key(aged, slug, Outcome.FIRED), 1L,
            key(recent, slug, Outcome.FIRED), 1L));

    job(mock(SignalEngine.class), denominators, 180).prune();

    assertThat(denominatorFor(aged, slug)).as("aged session pruned").isZero();
    assertThat(denominatorFor(recent, slug)).as("recent session retained").isEqualTo(1L);
  }

  @Test
  void anEmptySnapshotWritesNothingRatherThanFailing() {
    // Before the first bar of a session — and all weekend — the map is empty. A zero-row statement
    // would be a syntax error, so the repository short-circuits.
    assertThat(denominators.upsertCounts(UUID.randomUUID(), Map.of())).isZero();
  }
}
