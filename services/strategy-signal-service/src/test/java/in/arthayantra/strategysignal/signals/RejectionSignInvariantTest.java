package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
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

  private void record(String rail, BigDecimal operand, BigDecimal threshold, BigDecimal margin) {
    writer.record(
        UUID.randomUUID(), "slug", "NSE", "SIGTEST", "3m", "BUY", rail, operand, threshold, margin,
        "blocked", null, null, "{}", BAR, null);
  }

  private double contradictions() {
    return meters.counter("ay_signal_rejection_sign_contradiction_total").count();
  }

  @Test
  void aSelfContradictingRowIsCountedWarnedAndStillPersisted() {
    // The 2026-07-23 §2.3 id-7794 shape: confluence-composite blocked while its own operand
    // (0.6373) cleared the threshold (0.6000) — margin +0.0373 on a floor rail.
    record("confluence-composite", new BigDecimal("0.6373"), new BigDecimal("0.6000"),
        new BigDecimal("0.0373"));

    assertThat(contradictions()).isEqualTo(1.0);
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
        new BigDecimal("0.0001"));
    assertThat(contradictions()).isZero();
    assertThat(logs.list)
        .noneMatch(e -> e.getFormattedMessage().contains("self-contradicting"));
  }

  @Test
  void aVwapDistanceBlockClaimingANegativeMarginFlags() {
    // The ceiling mirror of the composite contradiction: a vwap-distance row whose margin says the
    // operand was INSIDE the band it was supposedly blocked for exceeding.
    record("vwap-distance", new BigDecimal("0.0030"), new BigDecimal("0.0040"),
        new BigDecimal("-0.0010"));
    assertThat(contradictions()).isEqualTo(1.0);
  }

  @Test
  void aCorrectNegativeMarginFloorBlockDoesNotFlag() {
    record("volume-floor", new BigDecimal("90000"), new BigDecimal("125000"),
        new BigDecimal("-35000"));
    assertThat(contradictions()).isZero();
  }

  @Test
  void nullMarginsUnsignedRailsAndUnknownRailsNeverFlagAndNeverThrow() {
    assertThatCode(
            () -> {
              record("confluence-composite", null, null, null); // decisive-leg block: margin null
              record("pct-price-move", new BigDecimal("2"), new BigDecimal("1"),
                  new BigDecimal("1")); // side-dependent rail: UNSIGNED
              record("some-future-rail", new BigDecimal("2"), new BigDecimal("1"),
                  new BigDecimal("1")); // unregistered: never a runtime throw on this seam
            })
        .doesNotThrowAnyException();
    assertThat(contradictions()).isZero();
  }
}
