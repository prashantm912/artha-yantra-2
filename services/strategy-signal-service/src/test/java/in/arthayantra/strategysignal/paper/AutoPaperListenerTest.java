package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.strategysignal.signals.Books;
import in.arthayantra.strategysignal.signals.SignalEmitted;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.signals.SignalTaken;
import in.arthayantra.strategysignal.signals.SwingPaperEffectRepository;
import in.arthayantra.strategysignal.signals.TakeAdmission;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Auto-paper-trade: OFF ⇒ inert (the manual take flow is untouched); ON ⇒ an emitted entry is taken at
 * its suggested qty (transition TAKEN + publish {@link SignalTaken} → the paper listener opens it). A
 * null/zero suggested qty is skipped so nothing is opened blind.
 */
class AutoPaperListenerTest {

  /** These cases predate the take gate; it is exercised in {@code AutoPaperTakeAdmissionTest}. */
  private static final TakeAdmission ADMIT_ALL = (id, qty) -> TakeAdmission.Verdict.ADMITTED;

  private static SignalEmitted emitted(long id) {
    return new SignalEmitted(
        id, UUID.randomUUID(), "NFO", "NIFTY 50", "BUY",
        new BigDecimal("100"), null, null, new BigDecimal("0.7"), new BigDecimal("0.6"), null);
  }

  /** A real signal row (the record is final — cannot be mocked here) with only the fields we read set. */
  private static SignalRepository.SignalRow row(BigDecimal suggestedQty, JsonNode scalperDetail) {
    return new SignalRepository.SignalRow(
        7L, UUID.randomUUID(), "NFO", "NIFTY 50", "3m", "ENTRY", "BUY",
        new BigDecimal("100"), null, null, new BigDecimal("0.7"), null, "ACTIVE",
        null, null, suggestedQty, null, null, scalperDetail, null, null, null);
  }

  @Test
  void offIsInert() {
    RiskService risk = mock(RiskService.class);
    BookResolver books = mock(BookResolver.class);
    when(books.bookForSignal(anyLong())).thenReturn(Books.MINERVINI);
    when(risk.autoPaperTradeEnabled(Books.MINERVINI)).thenReturn(false);
    SignalRepository signals = mock(SignalRepository.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    SwingPaperEffectRepository effects = mock(SwingPaperEffectRepository.class);

    new AutoPaperListener(risk, signals, books, events, effects, ADMIT_ALL).onSignalEmitted(emitted(7));

    verifyNoInteractions(signals, events); // the toggle is OFF — nothing happens
    verify(effects).skipEntry(7L);
  }

  @Test
  void onTakesAtSuggestedQty() {
    RiskService risk = mock(RiskService.class);
    BookResolver books = mock(BookResolver.class);
    when(books.bookForSignal(anyLong())).thenReturn("scalper");
    when(risk.autoPaperTradeEnabled("scalper")).thenReturn(true);
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.find(7L)).thenReturn(Optional.of(row(new BigDecimal("5"), null)));
    when(signals.transitionIf(7L, "ACTIVE", "TAKEN")).thenReturn(true); // this caller wins the CAS
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    new AutoPaperListener(risk, signals, books, events, ADMIT_ALL).onSignalEmitted(emitted(7));

    verify(signals).transitionIf(7L, "ACTIVE", "TAKEN");
    ArgumentCaptor<SignalTaken> taken = ArgumentCaptor.forClass(SignalTaken.class);
    verify(events).publishEvent(taken.capture());
    assertThat(taken.getValue().signalId()).isEqualTo(7L);
    assertThat(taken.getValue().qty()).isEqualTo(5);
    assertThat(taken.getValue().fillPrice()).isEqualByComparingTo("100"); // the signal's entry price
  }

  @Test
  void anUnrecordedRequiredDecisionFailsClosed() {
    RiskService risk = mock(RiskService.class);
    BookResolver books = mock(BookResolver.class);
    when(books.bookForSignal(anyLong())).thenReturn(Books.MINERVINI);
    when(risk.autoPaperTradeEnabled(Books.MINERVINI)).thenReturn(true);
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.find(7L)).thenReturn(Optional.of(row(new BigDecimal("5"), null)));
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    SwingPaperEffectRepository effects = mock(SwingPaperEffectRepository.class);

    new AutoPaperListener(risk, signals, books, events, effects, ADMIT_ALL).onSignalEmitted(emitted(7));

    verify(effects).requireEntry(7L);
    verify(signals, never()).transitionIf(anyLong(), any(), any());
    verifyNoInteractions(events);
  }

  @Test
  void scalperDoesNotUseTheSwingEffectLease() {
    RiskService risk = mock(RiskService.class);
    BookResolver books = mock(BookResolver.class);
    when(books.bookForSignal(anyLong())).thenReturn(Books.SCALPER);
    when(risk.autoPaperTradeEnabled(Books.SCALPER)).thenReturn(true);
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.find(7L)).thenReturn(Optional.of(row(new BigDecimal("5"), null)));
    when(signals.transitionIf(7L, "ACTIVE", "TAKEN")).thenReturn(true);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    SwingPaperEffectRepository effects = mock(SwingPaperEffectRepository.class);

    new AutoPaperListener(risk, signals, books, events, effects, ADMIT_ALL).onSignalEmitted(emitted(7));

    verify(signals).transitionIf(7L, "ACTIVE", "TAKEN");
    verifyNoInteractions(effects);
    verify(events).publishEvent(org.mockito.ArgumentMatchers.any(SignalTaken.class));
  }

  @Test
  void lostTakeRacePublishesNothing() {
    // A concurrent manual / duplicate take already flipped ACTIVE→TAKEN — the CAS returns false, so
    // this auto-take must NOT double-open a position.
    RiskService risk = mock(RiskService.class);
    BookResolver books = mock(BookResolver.class);
    when(books.bookForSignal(anyLong())).thenReturn("scalper");
    when(risk.autoPaperTradeEnabled("scalper")).thenReturn(true);
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.find(7L)).thenReturn(Optional.of(row(new BigDecimal("5"), null)));
    when(signals.transitionIf(7L, "ACTIVE", "TAKEN")).thenReturn(false); // lost the CAS
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    new AutoPaperListener(risk, signals, books, events, ADMIT_ALL).onSignalEmitted(emitted(7));

    verifyNoInteractions(events);
  }

  @Test
  void onWithNoSuggestedQtyIsSkipped() {
    RiskService risk = mock(RiskService.class);
    BookResolver books = mock(BookResolver.class);
    when(books.bookForSignal(anyLong())).thenReturn("scalper");
    when(risk.autoPaperTradeEnabled("scalper")).thenReturn(true);
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.find(7L)).thenReturn(Optional.of(row(null, null)));
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    new AutoPaperListener(risk, signals, books, events, ADMIT_ALL).onSignalEmitted(emitted(7));

    verify(signals, never()).transitionIf(anyLong(), any(), any());
    verifyNoInteractions(events);
  }
}
