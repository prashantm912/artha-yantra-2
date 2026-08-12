package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A close the caller did not perform must not be REPORTED as one (architecture candidate §9-05 —
 * the CAS-leaks-onto-callers interface leak).
 *
 * <p>{@code PaperService.doSettle} closes compare-and-swap: only the thread that actually flips
 * OPEN→CLOSED books the exit fill and fires the event. That part was always correct. What leaked was
 * the RETURN: the realized amount came back whether or not this call won, so a caller could not tell
 * "I closed it" from "someone else already had". The 15s bracket sweep therefore counted the
 * position, logged {@code "paper STOP_LOSS closed position N at <ltp>"}, and cleared its starvation
 * flag — for an exit performed by an engine exit or a manual close, at a price that was not the
 * settlement price. The count feeds {@code PaperScheduler}'s "auto-exit closed N position(s)", so the
 * operator-facing number was inflated too.
 *
 * <p>Nothing was mis-BOOKED by this — no duplicate fill, no double event. The damage is to the
 * record: a confident, wrong account of why a position left the book, which is exactly what someone
 * reconstructing an exit reads first.
 */
class PaperCloseRaceReportingTest {

  private static PositionRow open(long id) {
    return new PositionRow(
        id, "NFO", "NIFTY26AUG25000CE", "BUY", 75, new BigDecimal("100"), BigDecimal.ZERO, "OPEN",
        null, null, null, new BigDecimal("90"), new BigDecimal("120"), "scalper");
  }

  private record Harness(
      PaperBracketEvaluator evaluator, PaperService paper, PaperStaleTickAlerter staleTicks) {}

  /** Wires the evaluator over one OPEN position whose LTP has breached the stop. */
  private static Harness harness(Optional<BigDecimal> settleResult) {
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    LastTickReader lastTick = mock(LastTickReader.class);
    PaperService paper = mock(PaperService.class);
    PaperStaleTickAlerter staleTicks = mock(PaperStaleTickAlerter.class);

    when(positions.listOpen()).thenReturn(List.of(open(1L)));
    when(lastTick.lastTick(anyString(), anyString()))
        .thenReturn(
            Optional.of(new LastTickReader.TickView(new BigDecimal("85"), Duration.ofSeconds(1))));
    when(paper.settle(any(), any(), anyString())).thenReturn(settleResult);

    return new Harness(
        new PaperBracketEvaluator(positions, lastTick, paper, staleTicks), paper, staleTicks);
  }

  @Test
  void aClosePerformedByThisPassIsCountedAndTheStarvationFlagCleared() {
    Harness h = harness(Optional.of(new BigDecimal("-1125.0000")));

    assertThat(h.evaluator().evaluate()).isEqualTo(1);
    verify(h.staleTicks()).bracketRecovered(1L);
  }

  /**
   * Review round 1 found three MORE callers ignoring the CAS outcome. The manual close is the worst:
   * {@code PaperController} writes a {@code MANUAL_CLOSE} audit row off a successful return, so a
   * lost race produced an audit entry for a close the request never performed — attributing someone
   * else's exit to a human operator.
   *
   * <p>This drives the REAL path — {@code closePosition} → {@code settle} → {@code doSettle} → the
   * CAS — with only the repository mocked, so it fails if any link stops propagating the loss.
   */
  @Test
  void aManualCloseThatLostTheRaceRaises409RatherThanClaimingTheWinnersTrade() {
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    when(positions.find(7L)).thenReturn(Optional.of(open(7L)));
    // The race: the pre-read saw OPEN, but the CAS finds the row already closed (rowcount 0).
    when(positions.close(anyLong(), any(), anyString())).thenReturn(0);

    InstrumentMetaClient instruments = mock(InstrumentMetaClient.class);
    when(instruments.meta(anyString(), anyString()))
        .thenReturn(
            new InstrumentMetaClient.InstrumentMeta(
                in.arthayantra.strategyengine.fills.InstrumentClass.OPTION, new BigDecimal("0.05"), 75));

    PaperService paper =
        new PaperService(
            mock(PaperOrderRepository.class), mock(PaperPositionLotRepository.class), positions,
            new PaperFillService(), mock(LastTickReader.class),
            instruments, mock(in.arthayantra.strategysignal.signals.SignalRepository.class),
            mock(PaperAccountService.class), mock(BookResolver.class), mock(RiskService.class),
            mock(ScalperAccountModel.class),
            mock(org.springframework.context.ApplicationEventPublisher.class),
            mock(PaperStaleTickAlerter.class), mock(PaperOrderRejectionRecorder.class),
            new ManasGoverningStopCache(),
            mock(org.springframework.transaction.PlatformTransactionManager.class),
            new BigDecimal("1.0"), 15L, 60L);

    assertThatThrownBy(() -> paper.closePosition(7L, new BigDecimal("100")))
        .as("a close this request did not perform must not return the winner's trade")
        .isInstanceOf(in.arthayantra.common.web.error.ApiException.class)
        .hasMessageContaining("already closed");
  }

  @Test
  void aCloseThisPassLostTheRaceForIsNeitherCountedNorClaimed() {
    // EMPTY = the CAS was lost; some other closer flipped OPEN→CLOSED first.
    Harness h = harness(Optional.empty());

    assertThat(h.evaluator().evaluate())
        .as("the sweep must not report an exit it did not perform")
        .isZero();
    verify(h.staleTicks(), never()).bracketRecovered(anyLong());
  }
}
