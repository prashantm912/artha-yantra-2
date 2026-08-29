package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.signals.TakeAdmission;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * H44 at the ADMISSION port, which is a different gate from the writer's and exists for one reason:
 * a refusal raised inside {@code PaperService} arrives TOO LATE on the take path.
 *
 * <p>{@code PaperSignalListener} catches the writer's 422, compensates the stranded anchor, and
 * {@code SignalsController} still answers 200 with a detail body — so an armed gate would report
 * SUCCESS for a take that opened nothing. Found in cross-vendor review round 2, after the writer-side
 * gate was already green. The verdict therefore has to be reached BEFORE the ACTIVE-&gt;TAKEN CAS,
 * which is what this port does.
 */
class InstrumentTakeAdmissionH44Test {

  /** A reader whose Redis is unreachable — the fail-closed case, not a "no tick" case. */
  private static final class ThrowingTickReader extends LastTickReader {
    ThrowingTickReader() {
      super(null, null, Clock.systemUTC());
    }

    @Override
    public Optional<TickView> lastTick(String exchange, String tradingsymbol) {
      throw new IllegalStateException("redis down");
    }
  }

  /** A reader that answers honestly: nothing has ever ticked. */
  private static final class SilentTickReader extends LastTickReader {
    SilentTickReader() {
      super(null, null, Clock.systemUTC());
    }

    @Override
    public Optional<TickView> lastTick(String exchange, String tradingsymbol) {
      return Optional.empty();
    }
  }

  private static SignalRepository.SignalRow row() {
    return new SignalRepository.SignalRow(
        7L, UUID.randomUUID(), "NFO", "NIFTY24JUN24000CE", "3m", "ENTRY", "BUY",
        new BigDecimal("100"), null, null, new BigDecimal("0.7"), null, "ACTIVE",
        null, null, new BigDecimal("75"), null, null, null, null, null, null);
  }

  private static SignalRepository store() {
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.find(7L)).thenReturn(Optional.of(row()));
    return signals;
  }

  private static InstrumentMetaClient instruments(InstrumentClass klass) {
    InstrumentMetaClient instruments = mock(InstrumentMetaClient.class);
    when(instruments.meta("NFO", "NIFTY24JUN24000CE"))
        .thenReturn(new InstrumentMeta(klass, new BigDecimal("0.05"), 75));
    return instruments;
  }

  private static InstrumentTakeAdmission admission(
      InstrumentClass klass, LastTickReader ticks, boolean armed) {
    return new InstrumentTakeAdmission(
        store(), instruments(klass), new SimpleMeterRegistry(), "paper", ticks, armed,
        mock(PaperOrderRejectionRecorder.class));
  }

  @Test
  void anArmedTakeOfANeverTickedOptionIsRefusedBeforeTheCas() {
    TakeAdmission.Verdict verdict =
        admission(InstrumentClass.OPTION, new SilentTickReader(), true).admit(7L, 75);

    assertThat(verdict.admitted()).isFalse();
    assertThat(verdict.code()).isEqualTo(ErrorCodes.DATA_GAP);
    assertThat(verdict.reason()).contains("no tick has ever been seen");
  }

  /**
   * ⚠️ THE CRITICAL FROM ROUND 2, and it REVERSED an earlier decision. The first cut let the fill
   * through when the probe could not answer, on the reasoning that a diagnostic must never break what
   * it observes. That is correct for a diagnostic and wrong for a SAFETY GATE: allowing an
   * unverifiable fill recreates H44 while the flag claims protection. #694 settles the direction —
   * entries need fresh truth, because you can always decline to enter.
   */
  @Test
  void anArmedTakeIsRefusedWhenTheTickStoreCannotAnswer() {
    TakeAdmission.Verdict verdict =
        admission(InstrumentClass.OPTION, new ThrowingTickReader(), true).admit(7L, 75);

    assertThat(verdict.admitted()).isFalse();
    assertThat(verdict.reason())
        .as("a probe that cannot answer must not be read as a YES")
        .contains("cannot verify");
  }

  /**
   * ⚠️ The blast-radius case at this port too. Equities do not tick, so a class-blind gate would
   * refuse every swing equity TAKE the moment the flag was armed.
   */
  @Test
  void anArmedTakeOfANeverTickedEquityIsStillAdmitted() {
    assertThat(admission(InstrumentClass.EQUITY, new SilentTickReader(), true).admit(7L, 75).admitted())
        .isTrue();
  }

  /** The shipped default: disarmed changes nothing, even for an option that has never ticked. */
  @Test
  void aDisarmedTakeOfANeverTickedOptionIsAdmitted() {
    assertThat(admission(InstrumentClass.OPTION, new SilentTickReader(), false).admit(7L, 75).admitted())
        .isTrue();
  }

  /** Arming without a reader is a silently-inert safety gate, so construction refuses it outright. */
  @Test
  void armingWithoutATickReaderFailsFastRatherThanSilentlyDisarming() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> admission(InstrumentClass.OPTION, null, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("armed");
  }

  /**
   * ⚠️ THE ROUND-3 MAJOR, and the one no existing test covered: H44 is a PAPER rule and must not
   * gate a LIVE-only leg.
   *
   * <p>The DIVERGENT-LEG shape is {@code tradeableTradingsymbol != null} with a NULL
   * {@code scalperDetail}. Paper then routes the PRIMARY (here an equity) while live routes the
   * TRADEABLE (an option). Gating that option refuses the take on account of a leg the paper writer
   * never opens and {@code PaperService}'s own H44 gate never sees -- exactly the writer-vs-admission
   * disagreement {@code TakeAdmissionWriterAgreementTest} exists to prevent.
   *
   * <p>The option here has NEVER ticked and the gate is ARMED, so before the fix this was refused.
   */
  @Test
  void anArmedTakeDoesNotGateALiveOnlyOptionLeg() {
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.find(7L))
        .thenReturn(
            Optional.of(
                new SignalRepository.SignalRow(
                    7L, UUID.randomUUID(), "NSE", "RELIANCE", "1d", "ENTRY", "BUY",
                    new BigDecimal("100"), null, null, new BigDecimal("0.7"), null, "ACTIVE",
                    null, null, new BigDecimal("10"), "NFO", "RELIANCE26SEP3000CE", null,
                    null, null, null)));

    InstrumentMetaClient instruments = mock(InstrumentMetaClient.class);
    when(instruments.meta("NSE", "RELIANCE"))
        .thenReturn(new InstrumentMeta(InstrumentClass.EQUITY, new BigDecimal("0.05"), 1));
    when(instruments.meta("NFO", "RELIANCE26SEP3000CE"))
        .thenReturn(new InstrumentMeta(InstrumentClass.OPTION, new BigDecimal("0.05"), 10));

    // execution=live so the live-only intent exists at all; gate ARMED; nothing has ever ticked.
    TakeAdmission.Verdict verdict =
        new InstrumentTakeAdmission(
                signals, instruments, new SimpleMeterRegistry(), "live",
                new SilentTickReader(), true, mock(PaperOrderRejectionRecorder.class))
            .admit(7L, 10);

    assertThat(verdict.admitted())
        .as("the option is routed by the LIVE writer only; H44 is a paper-closability rule and"
            + " gating it here would refuse a take both writers would have filled")
        .isTrue();
  }

  /**
   * ⚠️ Round-3 Major: the SAME failure must not be a retryable 503 through the writer and a
   * permanent-looking 422 through /taken. An unreachable tick store is a dependency outage, not a bad
   * request, and a client cannot tell "try again" from "never" if both map to 422.
   */
  @Test
  void anUnreachableTickStoreIsA503NotA422() {
    TakeAdmission.Verdict verdict =
        admission(InstrumentClass.OPTION, new ThrowingTickReader(), true).admit(7L, 75);

    assertThat(verdict.httpStatus())
        .as("dependency unavailable -> 503, matching what PaperService already throws")
        .isEqualTo(503);
    assertThat(admission(InstrumentClass.OPTION, new SilentTickReader(), true).admit(7L, 75)
            .httpStatus())
        .as("a CONFIRMED never-ticked contract stays 422 — that one really is permanent")
        .isEqualTo(422);
  }

  /**
   * ⚠️ Round-3 Major: an armed refusal reached BEFORE the CAS must still leave the forensic row the
   * recorder contract promises. Manual takes return before publishing and auto-takes return before the
   * CAS, so this path never reaches PaperService — it produced only a log line and a metric, and the
   * ledger showed nothing for a refusal that really happened.
   */
  @Test
  void anAdmissionRefusalIsRecordedDurablyWithTheAttemptedQuantity() {
    PaperOrderRejectionRecorder rejections = mock(PaperOrderRejectionRecorder.class);
    new InstrumentTakeAdmission(
            store(), instruments(InstrumentClass.OPTION), new SimpleMeterRegistry(), "paper",
            new SilentTickReader(), true, rejections)
        .admit(7L, 75);

    verify(rejections)
        .recordNeverTicked(
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq("NFO"),
            org.mockito.ArgumentMatchers.eq("NIFTY24JUN24000CE"),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq(75L),
            org.mockito.ArgumentMatchers.anyString());
  }
}
