package in.arthayantra.strategysignal.paper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.strategysignal.signals.Books;
import in.arthayantra.strategysignal.signals.SignalEmitted;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.signals.SignalTaken;
import in.arthayantra.strategysignal.signals.SwingPaperEffectRepository;
import in.arthayantra.strategysignal.signals.TakeAdmission;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The AUTO take path's half of the ordering fix — the second {@code SignalTaken} publisher, and the
 * one an unattended book actually goes through. Covering only the controller would leave the defect
 * open exactly where nobody is watching.
 */
class AutoPaperTakeAdmissionTest {

  private static SignalEmitted emitted(long id) {
    return new SignalEmitted(
        id, UUID.randomUUID(), "NFO", "NIFTY 50", "BUY",
        new BigDecimal("100"), null, null, new BigDecimal("0.7"), new BigDecimal("0.6"), null);
  }

  private static SignalRepository.SignalRow row(BigDecimal suggestedQty) {
    return new SignalRepository.SignalRow(
        7L, UUID.randomUUID(), "NFO", "NIFTY 50", "3m", "ENTRY", "BUY",
        new BigDecimal("100"), null, null, new BigDecimal("0.7"), null, "ACTIVE",
        null, null, suggestedQty, null, null, null, null, null, null);
  }

  private static TakeAdmission refusing(String code, String reason) {
    return (id, qty) -> TakeAdmission.Verdict.refused(code, reason, Map.of("signalId", id));
  }

  /** Common wiring: auto-paper ON for {@code book}, signal 7 sized at {@code qty}. */
  private static SignalRepository signals(BigDecimal qty) {
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.find(7L)).thenReturn(Optional.of(row(qty)));
    return signals;
  }

  private static RiskService riskOn() {
    RiskService risk = mock(RiskService.class);
    when(risk.autoPaperTradeEnabled(any())).thenReturn(true);
    return risk;
  }

  private static BookResolver book(String name) {
    BookResolver books = mock(BookResolver.class);
    when(books.bookForSignal(anyLong())).thenReturn(name);
    return books;
  }

  @Test
  void anUnknownLotLeavesTheSignalActiveAndOpensNothing() {
    SignalRepository signals = signals(new BigDecimal("75"));
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    new AutoPaperListener(
            riskOn(),
            signals,
            book(Books.SCALPER),
            events,
            refusing(ErrorCodes.DATA_GAP, "no lot size in the instrument master for NFO:X"))
        .onSignalEmitted(emitted(7));

    verify(signals, never()).transitionIf(anyLong(), any(), any());
    verifyNoInteractions(events);
  }

  @Test
  void aMisalignedQuantityLeavesTheSignalActiveAndOpensNothing() {
    SignalRepository signals = signals(new BigDecimal("74"));
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    new AutoPaperListener(
            riskOn(),
            signals,
            book(Books.SCALPER),
            events,
            refusing(ErrorCodes.VALIDATION_FAILED, "qty 74 is not a multiple of the lot size 75"))
        .onSignalEmitted(emitted(7));

    verify(signals, never()).transitionIf(anyLong(), any(), any());
    verifyNoInteractions(events);
  }

  /**
   * A refused SWING auto-take must resolve the effect ledger as SKIPPED, exactly like the other
   * "auto-paper did not claim this emission" early returns. Left UNDECIDED it would page the
   * catch-up forever about a paper effect that is never coming.
   */
  @Test
  void aRefusedSwingAutoTakeSkipsTheEffectRatherThanLeavingItUndecided() {
    SignalRepository signals = signals(new BigDecimal("74"));
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    SwingPaperEffectRepository effects = mock(SwingPaperEffectRepository.class);

    new AutoPaperListener(
            riskOn(),
            signals,
            book(Books.MANAS_ARORA),
            events,
            effects,
            refusing(ErrorCodes.VALIDATION_FAILED, "qty 74 is not a multiple of the lot size 75"))
        .onSignalEmitted(emitted(7));

    verify(effects).skipEntry(7L);
    verify(effects, never()).requireEntry(anyLong());
    verify(signals, never()).transitionIf(anyLong(), any(), any());
    verifyNoInteractions(events);
  }

  @Test
  void anAdmittedAutoTakeStillTransitionsAndPublishesExactlyOnceAfterTheGate() {
    SignalRepository signals = signals(new BigDecimal("75"));
    when(signals.transitionIf(7L, "ACTIVE", "TAKEN")).thenReturn(true);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    TakeAdmission admission = mock(TakeAdmission.class);
    when(admission.admit(anyLong(), anyInt())).thenReturn(TakeAdmission.Verdict.ADMITTED);

    new AutoPaperListener(riskOn(), signals, book(Books.SCALPER), events, admission)
        .onSignalEmitted(emitted(7));

    InOrder order = inOrder(admission, signals);
    order.verify(admission).admit(7L, 75);
    order.verify(signals).transitionIf(7L, "ACTIVE", "TAKEN");
    verify(events).publishEvent(new SignalTaken(7L, 75, new BigDecimal("100"), false));
  }

  /** The CAS still owns the race: admitted, but a concurrent manual take already won. */
  @Test
  void anAdmittedAutoTakeThatLosesTheCasPublishesNothing() {
    SignalRepository signals = signals(new BigDecimal("75"));
    when(signals.transitionIf(7L, "ACTIVE", "TAKEN")).thenReturn(false);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    new AutoPaperListener(
            riskOn(),
            signals,
            book(Books.SCALPER),
            events,
            (id, qty) -> TakeAdmission.Verdict.ADMITTED)
        .onSignalEmitted(emitted(7));

    verify(signals).transitionIf(7L, "ACTIVE", "TAKEN");
    verifyNoInteractions(events);
  }
}
