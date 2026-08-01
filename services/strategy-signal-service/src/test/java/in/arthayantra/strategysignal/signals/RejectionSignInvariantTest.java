package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import in.arthayantra.strategysignal.scalper.RailMarginSign;
import in.arthayantra.strategysignal.scalper.ScalperConfluenceGate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * G17 / T14 sign-aware margin invariant at the {@code signal_rejections} persist seam. A persisted
 * first-block whose margin sits STRICTLY on the passing side of its own rail's operator
 * (2026-07-20 §6.3: 7 composite rows with a POSITIVE margin) is counted
 * ({@code ay_signal_rejection_sign_contradiction_total}) and WARNed — and STILL persisted, because
 * the row is the evidence. A correct block never flags: {@code vwap-distance} legitimately blocks
 * with a POSITIVE margin (2026-07-23 §2.3 — the refutation of the naive {@code margin < 0} guard),
 * a floor rail with a negative one. The flag can never throw into the eval thread.
 */
class RejectionSignInvariantTest {

  private static final OffsetDateTime BAR = OffsetDateTime.now(ZoneOffset.ofHoursMinutes(5, 30));

  private SimpleMeterRegistry meters;
  private SignalRejectionRepository repo;
  private ShadowBookService shadow;
  private RejectionWriter writer;
  private ListAppender<ILoggingEvent> logs;
  private Logger writerLog;

  @BeforeEach
  void setUp() {
    meters = new SimpleMeterRegistry();
    repo = mock(SignalRejectionRepository.class);
    shadow = mock(ShadowBookService.class);
    writer = new RejectionWriter(repo, shadow, meters);
    writerLog = (Logger) LoggerFactory.getLogger(RejectionWriter.class);
    logs = new ListAppender<>();
    logs.start();
    writerLog.addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    writerLog.detachAppender(logs);
    writer.shutdown();
  }

  /**
   * The sign now rides the diagnostic, stamped upstream by the gate function that compared the
   * operand — so the seam is exercised the way production feeds it, not through a name lookup.
   */
  private void record(
      String rail, BigDecimal operand, BigDecimal threshold, BigDecimal margin,
      RailMarginSign sign) {
    writer.record(
        UUID.randomUUID(), "slug", "NSE", "SIGTEST", "3m", "BUY", rail, operand, threshold, margin,
        "blocked", null, null, "{}", BAR, diagnostic(rail, operand, threshold, margin, sign));
  }

  private static ScalperConfluenceGate.RejectionDiagnostic diagnostic(
      String rail, BigDecimal operand, BigDecimal threshold, BigDecimal margin,
      RailMarginSign sign) {
    return new ScalperConfluenceGate.RejectionDiagnostic(
        rail, null, operand, threshold, margin, "blocked", null, null, List.of(), null, null, null,
        null, null, null, sign);
  }

  private double contradictions() {
    return meters.counter("ay_signal_rejection_sign_contradiction_total").count();
  }

  /**
   * Deterministic barrier for the ASYNC half of the writer — call it before EVERY assertion on
   * {@code logs.list}. {@link RejectionWriter#record} ends in {@code queue.submit(...)} and the WARN
   * is emitted on the writer thread ({@code RejectionWriter:102-109}), so a log assertion made
   * straight after {@code record()} races the writer and is unreliable in BOTH directions: an
   * {@code anyMatch} fails intermittently under full-suite load, and a {@code noneMatch} passes
   * VACUOUSLY (measured — a genuinely contradicting row left {@code noneMatch} green while the
   * synchronous counter proved the WARN had already been emitted).
   *
   * <p>This is the barrier the sibling writer tests already use for exactly this assertion shape
   * ({@code RejectionWriterTest:255}, {@code CompositeRejectionWriterTest:198},
   * {@code BoundedAsyncWriterTest:132}). The drain returns as soon as the queue empties and the
   * executor terminates, which both guarantees the append HAPPENED and establishes the
   * happens-before edge that makes reading the appender's plain {@code ArrayList} from the test
   * thread safe. The budget matches the {@code timeout(2_000)} convention in this class.
   *
   * <p>⚠️ Do NOT "simplify" this away and assert on {@code logs.list} directly. The WARN was moved
   * onto the writer thread deliberately (review round 1, see
   * {@link #theCounterIsSynchronousButTheWarnIsEmittedOnTheWriterThread}) because logging is I/O and
   * the sole {@code signal-eval} thread must never park on it — the fix belongs in the test, not in
   * the writer. It also SHUTS THE WRITER DOWN, so call it once, after the last {@code record()}.
   */
  private void drainWriter() {
    writer.drainAndShutdown(2_000);
  }

  @Test
  void aSelfContradictingRowIsCountedWarnedAndStillPersisted() {
    // The 2026-07-23 §2.3 id-7794 shape: confluence-composite blocked while its own operand
    // (0.6373) cleared the threshold (0.6000) — margin +0.0373 on a floor rail.
    record("confluence-composite", new BigDecimal("0.6373"), new BigDecimal("0.6000"),
        new BigDecimal("0.0373"), RailMarginSign.NEGATIVE_WHEN_BLOCKED);

    assertThat(contradictions()).isEqualTo(1.0);
    // The WARN rides the writer thread — wait for the drain before reading the appender.
    drainWriter();
    assertThat(logs.list)
        .as("the contradiction WARNs with the full row identity")
        .anyMatch(
            e ->
                e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("self-contradicting rejection row")
                    && e.getFormattedMessage().contains("confluence-composite"));
    // The flag must never suppress the write — the contradicting row IS the evidence.
    verify(repo, timeout(2_000))
        .insert(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any());
  }

  @Test
  void aCorrectPositiveMarginVwapDistanceBlockDoesNotFlag() {
    // 2026-07-23 §2.3: vwap-distance is a CEILING — operand 0.0041 > threshold 0.0040 with margin
    // +0.0001 is the semantically CORRECT failure signature. This is the case that killed the
    // naive blanket `blocking_margin < 0` invariant.
    record("vwap-distance", new BigDecimal("0.0041"), new BigDecimal("0.0040"),
        new BigDecimal("0.0001"), RailMarginSign.POSITIVE_WHEN_BLOCKED);
    assertThat(contradictions()).isZero();
    // Without this the noneMatch is VACUOUS — it would pass simply because the writer thread has
    // not appended yet, not because no WARN was emitted.
    drainWriter();
    assertThat(logs.list)
        .noneMatch(e -> e.getFormattedMessage().contains("self-contradicting"));
  }

  @Test
  void aVwapDistanceBlockClaimingANegativeMarginFlags() {
    // The ceiling mirror of the composite contradiction: a vwap-distance row whose margin says the
    // operand was INSIDE the band it was supposedly blocked for exceeding.
    record("vwap-distance", new BigDecimal("0.0030"), new BigDecimal("0.0040"),
        new BigDecimal("-0.0010"), RailMarginSign.POSITIVE_WHEN_BLOCKED);
    assertThat(contradictions()).isEqualTo(1.0);
  }

  @Test
  void aCorrectNegativeMarginFloorBlockDoesNotFlag() {
    record("volume-floor", new BigDecimal("90000"), new BigDecimal("125000"),
        new BigDecimal("-35000"), RailMarginSign.NEGATIVE_WHEN_BLOCKED);
    assertThat(contradictions()).isZero();
  }

  @Test
  void nullMarginsUnsignedRailsAndAbsentDiagnosticsNeverFlagAndNeverThrow() {
    assertThatCode(
            () -> {
              // decisive-leg composite block: compositeMargin() records NULL, so nothing to judge
              record("confluence-composite", null, null, null,
                  RailMarginSign.NEGATIVE_WHEN_BLOCKED);
              // side-dependent rail: the gate declares UNSIGNED, so both block signs are legitimate
              record("pct-price-move", new BigDecimal("2"), new BigDecimal("1"),
                  new BigDecimal("1"), RailMarginSign.UNSIGNED);
              // a gate that declared nothing at all defaults to UNSIGNED
              record("some-future-rail", new BigDecimal("2"), new BigDecimal("1"),
                  new BigDecimal("1"), null);
              // and a caller with NO diagnostic at all (the pre-G17 builders) asserts nothing
              writer.record(
                  UUID.randomUUID(), "slug", "NSE", "SIGTEST", "3m", "BUY", "volume-floor",
                  new BigDecimal("2"), new BigDecimal("1"), new BigDecimal("1"), "blocked", null,
                  null, "{}", BAR, null);
            })
        .doesNotThrowAnyException();
    assertThat(contradictions()).isZero();
  }

  /**
   * Review round 1 (Major): the WARN used to run synchronously on the sole {@code signal-eval}
   * thread before the enqueue. Logging is I/O and that thread must never park on it — the reason
   * this writer exists. The COUNTER stays on the caller (it must survive a saturated queue that
   * drops the record); the WARN now rides the bounded async path, so it appears only once the
   * writer thread has picked the record up.
   */
  @Test
  void theCounterIsSynchronousButTheWarnIsEmittedOnTheWriterThread() {
    String callerThread = Thread.currentThread().getName();

    record("confluence-composite", new BigDecimal("0.6373"), new BigDecimal("0.6000"),
        new BigDecimal("0.0373"), RailMarginSign.NEGATIVE_WHEN_BLOCKED);

    // The COUNTER is synchronous — it must survive a saturated queue that drops the record, so it
    // cannot ride the async path. This is already true the instant record() returns.
    assertThat(contradictions())
        .as("the counter is incremented on the caller, before the enqueue")
        .isEqualTo(1.0);

    // The WARN is not. Assert the emitting THREAD rather than racing on whether it has happened
    // yet: the writer picks the task up immediately, so a "not yet logged" check would be a
    // timing race, while the thread name is a durable fact about where the I/O ran. The drain (not
    // a poll) is what makes the read deterministic — see drainWriter().
    drainWriter();
    ILoggingEvent warn =
        logs.list.stream()
            .filter(e -> e.getFormattedMessage().contains("self-contradicting"))
            .findFirst()
            .orElseThrow();
    assertThat(warn.getThreadName())
        .as("the WARN is I/O and must NOT run on the sole signal-eval (caller) thread")
        .isNotEqualTo(callerThread)
        .isEqualTo("signal-rejection-writer");
  }
}
