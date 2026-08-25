package in.arthayantra.strategysignal.signals;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import in.arthayantra.strategysignal.scalper.ScalperConfluenceGate.SentimentCounterfactual;
import in.arthayantra.strategysignal.scalper.SentimentLevelShadow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * V056: the exit-oracle shadow writer rides the PROTECTIVE EXIT path, so it must never let a DB
 * stall or a persistence failure reach the sole {@code signal-eval} thread. A position that cannot
 * be evaluated cannot be exited — this is the one place where blocking on I/O costs money rather
 * than latency. Saturation DROPS + counts; a failing insert is swallowed; {@code record()} always
 * returns promptly and never throws.
 *
 * <p>It also derives the one field the gate cannot: {@code shadow_flip}. The gate computes the
 * counterfactual SIDE — it does not know which side the position holds — so the flip is that side
 * judged against {@code heldSide} through the same {@code confluenceFlippedAgainst} predicate the
 * live exit uses.
 *
 * <p>Ported from {@link RiskSuppressionWriterTest} — same {@link BoundedAsyncWriter} seam.
 */
class ExitOracleShadowWriterTest {

  private static final OffsetDateTime BAR = OffsetDateTime.now(ZoneOffset.ofHoursMinutes(5, 30));
  /**
   * ⚠️ The raw {@code oiSlopeAgreeWouldPass} here (FALSE) deliberately DISAGREES with the tag-aware
   * {@code slopeGatePass} on the counterfactual below (TRUE). {@link SentimentLevelShadow} evaluates
   * oi-slope-agree unconditionally, while the gate's value is null when the tag is unarmed — so the
   * writer must read the GATE's. With both spellings set to the same value (as this fixture
   * originally had) every assertion below passes whichever source the writer reads, which is exactly
   * how the round-4 Major survived a green suite.
   */
  private static final SentimentLevelShadow SHADOW =
      new SentimentLevelShadow(
          new BigDecimal("0.00"), new BigDecimal("30"), true, false,
          SentimentLevelShadow.Reason.COMPUTED);
  /** The level operand would have fired a CE — against a held PE that is a flip. */
  private static final SentimentCounterfactual WOULD_FIRE_CE =
      new SentimentCounterfactual(
          CE, true, new BigDecimal("0.94"), new BigDecimal("0.6"), true, true, null);

  private static void record(ExitOracleShadowWriter writer) {
    writer.record(42L, "scalp-trending-oi-nifty", BAR, "PE", "CE", "CE", true, SHADOW, WOULD_FIRE_CE);
  }

  @Test
  void aStalledInsertNeverBlocksTheCallerAndSaturationDropsAndCounts() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    ExitOracleShadowRepository repo = mock(ExitOracleShadowRepository.class);
    CountDownLatch block = new CountDownLatch(1);
    // The single writer thread parks on the first insert; the bounded queue then fills and the rest
    // are dropped — the caller (the eval thread, mid-exit-evaluation) must still return in O(1).
    doAnswer(
            inv -> {
              block.await();
              return null;
            })
        .when(repo)
        .insert(
            anyLong(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), anyBoolean(),
            any(), any(), any(), any(), any(), any(), any(), any(), any());
    ExitOracleShadowWriter writer = new ExitOracleShadowWriter(repo, meters);
    try {
      int n = ExitOracleShadowWriter.QUEUE_CAPACITY * 8;
      long start = System.nanoTime();
      for (int i = 0; i < n; i++) {
        record(writer);
      }
      long elapsedMs = (System.nanoTime() - start) / 1_000_000;
      assertThat(elapsedMs)
          .as("the eval thread is never blocked by the exit-oracle write path (%d records)", n)
          .isLessThan(2_000L);
      Awaitility.await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> meters.counter("ay_exit_oracle_shadow_dropped_total").count() > 0);
    } finally {
      block.countDown();
      writer.drainAndShutdown(100L);
    }
  }

  /**
   * A failing insert is the writer thread's problem, never the caller's. The measurement losing a
   * row is the CORRECT failure here — the alternative is an exception propagating into a protective
   * exit evaluation.
   */
  @Test
  void aFailingInsertIsSwallowedAndNeverReachesTheCaller() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    ExitOracleShadowRepository repo = mock(ExitOracleShadowRepository.class);
    doThrow(new IllegalStateException("db down"))
        .when(repo)
        .insert(
            anyLong(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), anyBoolean(),
            any(), any(), any(), any(), any(), any(), any(), any(), any());
    ExitOracleShadowWriter writer = new ExitOracleShadowWriter(repo, meters);
    try {
      assertThatCode(() -> record(writer)).doesNotThrowAnyException();
      verify(repo, timeout(5_000))
          .insert(
              anyLong(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(),
              anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    } finally {
      writer.drainAndShutdown(500L);
    }
  }

  /**
   * VERIFY GOAL — the counterfactual DECISION is persisted, not merely operand disagreement. On a
   * held PE where the level operand would have fired a CE, the row records
   * {@code shadow_would_fire=true}, {@code shadow_oracle_side=CE} and — the field the whole
   * measurement turns on — {@code shadow_flip=TRUE}, alongside the composite/threshold that PROVE
   * the verdict without re-deriving it.
   */
  @Test
  void theCounterfactualDecisionAndItsProvingStateArePersisted() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    ExitOracleShadowRepository repo = mock(ExitOracleShadowRepository.class);
    ExitOracleShadowWriter writer = new ExitOracleShadowWriter(repo, meters);
    try {
      record(writer);
      verify(repo, timeout(5_000))
          .insert(
              42L, "scalp-trending-oi-nifty", BAR, "PE", "CE", "CE", true,
              new BigDecimal("0.00"), new BigDecimal("30"),
              true, true, "CE", true,
              new BigDecimal("0.94"), new BigDecimal("0.6"), true, null,
              true, true);
    } finally {
      writer.drainAndShutdown(500L);
    }
  }

  /**
   * The discriminating case for {@code shadow_flip}: the SAME counterfactual side is NOT a flip when
   * the position already holds it. A writer that copied {@code wouldFire} straight into
   * {@code shadow_flip} would pass the test above and fail here — which is precisely the
   * "operand disagreement mistaken for a decision change" error this column exists to prevent.
   */
  @Test
  void aCounterfactualFireOnTheHeldSideIsNotAFlip() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    ExitOracleShadowRepository repo = mock(ExitOracleShadowRepository.class);
    ExitOracleShadowWriter writer = new ExitOracleShadowWriter(repo, meters);
    try {
      // held CE, counterfactual confirms CE → the read AGREES with the position, so no exit.
      writer.record(9L, "slug", BAR, "CE", "CE", "CE", false, SHADOW, WOULD_FIRE_CE);
      verify(repo, timeout(5_000))
          .insert(
              9L, "slug", BAR, "CE", "CE", "CE", false,
              new BigDecimal("0.00"), new BigDecimal("30"),
              true, true, "CE", false,
              new BigDecimal("0.94"), new BigDecimal("0.6"), true, null,
              true, true);
    } finally {
      writer.drainAndShutdown(500L);
    }
  }

  /**
   * A counterfactual that would NOT fire cannot flip, and the sentiment-INDEPENDENT rail that
   * stopped it is recorded — so a reader can see the verdict was forced by something the operand
   * could never have influenced.
   */
  @Test
  void aNonFiringCounterfactualRecordsNoFlipAndNamesTheBlockingRail() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    ExitOracleShadowRepository repo = mock(ExitOracleShadowRepository.class);
    ExitOracleShadowWriter writer = new ExitOracleShadowWriter(repo, meters);
    try {
      SentimentCounterfactual blocked =
          new SentimentCounterfactual(
              null, false, new BigDecimal("0.94"), new BigDecimal("0.6"), true, true, "strike-pick");
      writer.record(11L, "slug", BAR, "PE", "CE", null, false, SHADOW, blocked);
      verify(repo, timeout(5_000))
          .insert(
              11L, "slug", BAR, "PE", "CE", null, false,
              new BigDecimal("0.00"), new BigDecimal("30"),
              true, false, null, false,
              new BigDecimal("0.94"), new BigDecimal("0.6"), true, "strike-pick",
              true, true);
    } finally {
      writer.drainAndShutdown(500L);
    }
  }

  /**
   * NOT EVALUABLE is its own state. A null counterfactual (no level operand, or the gate blocked
   * before any confluence was scored) writes {@code shadow_verdict_known=false} with EVERY shadow
   * column NULL — never {@code false}. An analysis filtering on {@code shadow_flip = false} without
   * checking {@code shadow_verdict_known} would otherwise count unmeasurable bars as agreement,
   * which is the exact bias this PR exists to avoid.
   */
  @Test
  void anUnevaluableCounterfactualIsRecordedAsUnknownNotAsNoChange() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    ExitOracleShadowRepository repo = mock(ExitOracleShadowRepository.class);
    ExitOracleShadowWriter writer = new ExitOracleShadowWriter(repo, meters);
    try {
      writer.record(
          7L, "slug", BAR, "CE", "CE", null, false,
          new SentimentLevelShadow(
              new BigDecimal("1.5"), null, null, null,
              SentimentLevelShadow.Reason.LEVEL_UNAVAILABLE),
          null);
      verify(repo, timeout(5_000))
          .insert(
              7L, "slug", BAR, "CE", "CE", null, false, new BigDecimal("1.5"), null,
              false, null, null, null, null, null, null, null, null, null);
    } finally {
      writer.drainAndShutdown(500L);
    }
  }

  /**
   * VERIFY GOAL (round-4 Major) — a strategy WITHOUT {@code oi-slope-agree} persists
   * {@code slope_gate_would_pass = NULL}, honouring the schema's "null ⇒ tag unarmed" contract.
   *
   * <p>Discriminating by construction: the raw {@link SentimentLevelShadow} verdict handed in is a
   * REAL boolean (it is computed unconditionally), so a writer persisting that instead of the gate's
   * tag-aware null writes {@code false} here and fails. Six of the twelve armed strategies are in
   * exactly this state, and for them the raw value is meaningless — a row reporting it would look
   * self-contradictory beside {@code shadow_would_fire = true}.
   */
  @Test
  void aStrategyWithoutTheSlopeTagPersistsNullNotTheAlwaysEvaluatedRawVerdict() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    ExitOracleShadowRepository repo = mock(ExitOracleShadowRepository.class);
    ExitOracleShadowWriter writer = new ExitOracleShadowWriter(repo, meters);
    try {
      // tag UNARMED ⇒ the gate reports slopeGatePass = null, while the raw shadow says false.
      SentimentCounterfactual unarmed =
          new SentimentCounterfactual(
              CE, true, new BigDecimal("0.94"), new BigDecimal("0.6"), true, null, null);
      writer.record(21L, "slug-no-slope-tag", BAR, "PE", "CE", "CE", true, SHADOW, unarmed);
      verify(repo, timeout(5_000))
          .insert(
              21L, "slug-no-slope-tag", BAR, "PE", "CE", "CE", true,
              new BigDecimal("0.00"), new BigDecimal("30"),
              true, true, "CE", true,
              new BigDecimal("0.94"), new BigDecimal("0.6"), true, null,
              true,
              null); // ← the contract: unarmed tag ⇒ NULL, never the raw false
    } finally {
      writer.drainAndShutdown(500L);
    }
  }

  /**
   * The other half: a strategy WITH the tag persists the gate's real verdict. The fixture's raw
   * shadow value is FALSE and the gate's is TRUE, so this pins that the persisted value comes from
   * the counterfactual — a writer reading the shadow would store {@code false} and fail here.
   */
  @Test
  void aStrategyWithTheSlopeTagPersistsTheGatesVerdictNotTheRawOne() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    ExitOracleShadowRepository repo = mock(ExitOracleShadowRepository.class);
    ExitOracleShadowWriter writer = new ExitOracleShadowWriter(repo, meters);
    try {
      record(writer); // WOULD_FIRE_CE carries slopeGatePass = TRUE; SHADOW's raw value is FALSE
      verify(repo, timeout(5_000))
          .insert(
              42L, "scalp-trending-oi-nifty", BAR, "PE", "CE", "CE", true,
              new BigDecimal("0.00"), new BigDecimal("30"),
              true, true, "CE", true,
              new BigDecimal("0.94"), new BigDecimal("0.6"), true, null,
              true,
              true); // ← the GATE's tag-aware verdict, not the shadow's raw false
    } finally {
      writer.drainAndShutdown(500L);
    }
  }
}
