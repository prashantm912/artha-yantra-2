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
import in.arthayantra.strategysignal.signals.SignalEmitted;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.signals.SignalTaken;
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
        null, null, suggestedQty, null, null, scalperDetail, null);
  }

  @Test
  void offIsInert() {
    RiskService risk = mock(RiskService.class);
    BookResolver books = mock(BookResolver.class);
    when(books.bookForSignal(anyLong())).thenReturn("scalper");
    when(risk.autoPaperTradeEnabled("scalper")).thenReturn(false);
    SignalRepository signals = mock(SignalRepository.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    new AutoPaperListener(risk, signals, books, events).onSignalEmitted(emitted(7));

    verifyNoInteractions(signals, events); // the toggle is OFF — nothing happens
  }

  @Test
  void onTakesAtSuggestedQty() {
    RiskService risk = mock(RiskService.class);
    BookResolver books = mock(BookResolver.class);
    when(books.bookForSignal(anyLong())).thenReturn("scalper");
    when(risk.autoPaperTradeEnabled("scalper")).thenReturn(true);
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.find(7L)).thenReturn(Optional.of(row(new BigDecimal("5"), null)));
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    new AutoPaperListener(risk, signals, books, events).onSignalEmitted(emitted(7));

    verify(signals).transition(7L, "TAKEN");
    ArgumentCaptor<SignalTaken> taken = ArgumentCaptor.forClass(SignalTaken.class);
    verify(events).publishEvent(taken.capture());
    assertThat(taken.getValue().signalId()).isEqualTo(7L);
    assertThat(taken.getValue().qty()).isEqualTo(5);
    assertThat(taken.getValue().fillPrice()).isEqualByComparingTo("100"); // the signal's entry price
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

    new AutoPaperListener(risk, signals, books, events).onSignalEmitted(emitted(7));

    verify(signals, never()).transition(anyLong(), any());
    verifyNoInteractions(events);
  }
}
