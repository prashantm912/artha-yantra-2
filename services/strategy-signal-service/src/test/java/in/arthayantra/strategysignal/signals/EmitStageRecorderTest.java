package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * G8 / T26: the emit-path stage timers must TELESCOPE — five stages computed from the same clock
 * stamps, summing EXACTLY to the same-boundary total — and must be impossible to half-record: a
 * clock-driven emit with no bar trace (the BTST pre-close path) or a partially-walked trace
 * records NOTHING, never lying zeros. Instrumentation only: no hook may ever throw into the emit
 * path.
 */
class EmitStageRecorderTest {

  private SimpleMeterRegistry meters;
  private EmitStageRecorder recorder;

  @BeforeEach
  void setUp() {
    meters = new SimpleMeterRegistry();
    recorder = new EmitStageRecorder(meters);
  }

  private Timer stage(String stage, String direction) {
    return meters
        .find("ay_signal_emit_stage_seconds")
        .tag("stage", stage)
        .tag("direction", direction)
        .timer();
  }

  private Timer total(String direction) {
    return meters.find("ay_signal_emit_total_seconds").tag("direction", direction).timer();
  }

  @Test
  void everyStageAndTotalTimerIsPreRegisteredAtZeroSoAMissingSeriesIsNeverAmbiguous() {
    for (String direction : new String[] {"entry", "exit"}) {
      for (String s : EmitStageRecorder.STAGES) {
        assertThat(stage(s, direction)).as("%s/%s pre-registered", s, direction).isNotNull();
        assertThat(stage(s, direction).count()).isZero();
      }
      assertThat(total(direction)).isNotNull();
      assertThat(total(direction).count()).isZero();
    }
  }

  @Test
  void aWalkedEmitRecordsEveryStagePositiveAndTheStagesSumExactlyToTheTotal() {
    // bar received at 1_000; eval starts 1_010 (pre_eval 10ms); emit entered 1_030 (gate_eval 20);
    // tx starts 1_060 (leg_resolve 30); tx ends 1_100 (persist 40); complete 1_150 (publish 50).
    recorder.beginEvaluation(1_000L, 1_010L);
    recorder.markEmitStart(1_030L);
    recorder.markPersistStart(1_060L);
    recorder.markPersistEnd(1_100L);
    recorder.recordEmitComplete("entry", 1_150L);

    double sum = 0;
    for (String s : EmitStageRecorder.STAGES) {
      assertThat(stage(s, "entry").count()).as("stage %s recorded once", s).isEqualTo(1L);
      assertThat(stage(s, "entry").totalTime(TimeUnit.MILLISECONDS))
          .as("stage %s duration > 0", s)
          .isGreaterThan(0.0);
      sum += stage(s, "entry").totalTime(TimeUnit.MILLISECONDS);
    }
    assertThat(stage("pre_eval", "entry").totalTime(TimeUnit.MILLISECONDS)).isEqualTo(10.0);
    assertThat(stage("gate_eval", "entry").totalTime(TimeUnit.MILLISECONDS)).isEqualTo(20.0);
    assertThat(stage("leg_resolve", "entry").totalTime(TimeUnit.MILLISECONDS)).isEqualTo(30.0);
    assertThat(stage("persist", "entry").totalTime(TimeUnit.MILLISECONDS)).isEqualTo(40.0);
    assertThat(stage("publish", "entry").totalTime(TimeUnit.MILLISECONDS)).isEqualTo(50.0);
    assertThat(sum)
        .as("the five stages telescope to the same-boundary total (150ms), exactly")
        .isEqualTo(total("entry").totalTime(TimeUnit.MILLISECONDS))
        .isEqualTo(150.0);
    // Direction isolation: nothing leaked into the exit tag.
    for (String s : EmitStageRecorder.STAGES) {
      assertThat(stage(s, "exit").count()).isZero();
    }
    assertThat(total("exit").count()).isZero();
  }

  @Test
  void anExitEmitRecordsUnderTheExitTag() {
    recorder.beginEvaluation(2_000L, 2_001L);
    recorder.markEmitStart(2_002L);
    recorder.markPersistStart(2_003L);
    recorder.markPersistEnd(2_004L);
    recorder.recordEmitComplete("exit", 2_005L);
    for (String s : EmitStageRecorder.STAGES) {
      assertThat(stage(s, "exit").count()).isEqualTo(1L);
      assertThat(stage(s, "entry").count()).isZero();
    }
    assertThat(total("exit").totalTime(TimeUnit.MILLISECONDS)).isEqualTo(5.0);
  }

  @Test
  void aClockDrivenEmitWithNoBarTraceRecordsNothing() {
    // The BTST pre-close path: no bar receipt stamp -> beginEvaluation(0, ...) opens NO trace, and
    // an emit walked without any beginEvaluation at all is equally silent. Never a lying zero.
    recorder.beginEvaluation(0L, 3_000L);
    recorder.markEmitStart(3_001L);
    recorder.markPersistStart(3_002L);
    recorder.markPersistEnd(3_003L);
    assertThatCode(() -> recorder.recordEmitComplete("entry", 3_004L)).doesNotThrowAnyException();
    recorder.endEvaluation();

    recorder.markEmitStart(4_001L); // no beginEvaluation at all
    assertThatCode(() -> recorder.recordEmitComplete("exit", 4_002L)).doesNotThrowAnyException();

    for (String direction : new String[] {"entry", "exit"}) {
      for (String s : EmitStageRecorder.STAGES) {
        assertThat(stage(s, direction).count()).isZero();
      }
      assertThat(total(direction).count()).isZero();
    }
  }

  @Test
  void aPartiallyWalkedTraceRecordsNothing() {
    // An emit that early-returned (risk veto / unroutable leg) never reaches recordEmitComplete;
    // the converse guard: recordEmitComplete with missing marks must record nothing, not zeros.
    recorder.beginEvaluation(1_000L, 1_010L);
    recorder.markEmitStart(1_030L); // no tx marks — the emit refused before persisting
    recorder.recordEmitComplete("entry", 1_050L);
    for (String s : EmitStageRecorder.STAGES) {
      assertThat(stage(s, "entry").count()).isZero();
    }
    assertThat(total("entry").count()).isZero();
  }

  @Test
  void aConsumedTraceCannotDoubleRecord() {
    recorder.beginEvaluation(1_000L, 1_010L);
    recorder.markEmitStart(1_030L);
    recorder.markPersistStart(1_060L);
    recorder.markPersistEnd(1_100L);
    recorder.recordEmitComplete("exit", 1_150L);
    recorder.recordEmitComplete("exit", 1_200L); // stale second call: trace already consumed
    for (String s : EmitStageRecorder.STAGES) {
      assertThat(stage(s, "exit").count()).isEqualTo(1L);
    }
    assertThat(total("exit").count()).isEqualTo(1L);
  }

  @Test
  void aClockStepBackClampsToZeroAndNeverThrows() {
    recorder.beginEvaluation(5_000L, 4_990L); // receipt AFTER eval start (clock stepped back)
    recorder.markEmitStart(4_980L);
    recorder.markPersistStart(4_970L);
    recorder.markPersistEnd(4_960L);
    assertThatCode(() -> recorder.recordEmitComplete("entry", 4_950L)).doesNotThrowAnyException();
    for (String s : EmitStageRecorder.STAGES) {
      assertThat(stage(s, "entry").count()).isEqualTo(1L);
      assertThat(stage(s, "entry").totalTime(TimeUnit.MILLISECONDS)).isZero();
    }
  }

  /**
   * Review round 1 (Major): the trace used to open INSIDE the evaluation, so
   * {@code evaluateCoarsePrimary}'s synchronous {@code refreshFromRest} calls — that strategy's OWN
   * REST latency — landed in {@code pre_eval}, the bucket documented as queue-drain plus EARLIER
   * strategies. A slow refresh would have been read as queueing: exactly the misdiagnosis T26
   * exists to prevent. The scope now opens in {@code onClosedBar}'s per-strategy loop, ahead of all
   * per-strategy work. This pins the placement structurally so a later refactor cannot quietly push
   * it back down (source scan, the {@code RailPolicyTableTest} pattern).
   */
  @Test
  void theTraceScopeOpensInOnClosedBarAheadOfAnyPerStrategyWork() throws IOException {
    String src = Files.readString(engineSource());

    assertThat(src.split("emitStages\\.beginEvaluation\\(", -1).length - 1)
        .as("exactly ONE trace scope exists — a second would reset evalStart and hide refresh cost")
        .isEqualTo(1);

    int onClosedBar = src.indexOf("private void onClosedBar(");
    int nextMethod = src.indexOf("private void evaluateAtBarClose(", onClosedBar);
    int begin = src.indexOf("emitStages.beginEvaluation(");
    assertThat(onClosedBar).as("onClosedBar located").isGreaterThan(-1);
    assertThat(nextMethod).as("the method after onClosedBar located").isGreaterThan(onClosedBar);
    assertThat(begin)
        .as("beginEvaluation must sit INSIDE onClosedBar, not inside the evaluation methods")
        .isBetween(onClosedBar, nextMethod);

    // ...and specifically ahead of the coarse dispatch, which is what performs the REST refreshes.
    assertThat(begin)
        .as("the scope opens BEFORE the evaluateCoarsePrimary dispatch (its refreshes must bill to"
            + " gate_eval, never to pre_eval)")
        .isLessThan(src.indexOf("evaluateCoarsePrimary(strategy", onClosedBar));
  }

  private static Path engineSource() {
    Path cwd = Path.of("").toAbsolutePath();
    Path relative =
        Path.of("src/main/java/in/arthayantra/strategysignal/signals/SignalEngine.java");
    if (Files.exists(cwd.resolve(relative))) {
      return cwd.resolve(relative);
    }
    Path fromRepoRoot = cwd.resolve("services/strategy-signal-service").resolve(relative);
    assertThat(fromRepoRoot).as("engine source locatable").exists();
    return fromRepoRoot;
  }

  @Test
  void endEvaluationClearsTheScopeSoTheNextEvaluationStartsClean() {
    recorder.beginEvaluation(1_000L, 1_010L);
    recorder.markEmitStart(1_030L);
    recorder.endEvaluation(); // strategy evaluated, nothing emitted
    recorder.markPersistStart(9_000L); // stray late hook after the scope closed: no-op
    recorder.recordEmitComplete("entry", 9_001L);
    for (String s : EmitStageRecorder.STAGES) {
      assertThat(stage(s, "entry").count()).isZero();
    }
  }
}
