package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
        store(), instruments(klass), new SimpleMeterRegistry(), "paper", ticks, armed);
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
}
