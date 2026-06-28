package in.arthayantra.strategysignal.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.paper.PaperPositionClosed;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** Unit-tests the auto-journal stub: opt-out flag + the win/loss tagging by realized sign. */
class AutoJournalListenerTest {

  private final JournalRepository journal = Mockito.mock(JournalRepository.class);

  @Test
  void disabledWritesNothing() {
    new AutoJournalListener(journal, false)
        .onPaperPositionClosed(new PaperPositionClosed(7L, new BigDecimal("100"), "TAKE_PROFIT"));
    verify(journal, never()).insert(Mockito.any());
  }

  @Test
  void aWinningCloseStubsAnAutoWinEntryLinkedToThePositionWithNullRatings() {
    when(journal.insert(Mockito.any())).thenReturn(1L);
    new AutoJournalListener(journal, true)
        .onPaperPositionClosed(new PaperPositionClosed(42L, new BigDecimal("250.50"), "TAKE_PROFIT"));

    ArgumentCaptor<JournalRepository.EntryInput> captor =
        ArgumentCaptor.forClass(JournalRepository.EntryInput.class);
    verify(journal).insert(captor.capture());
    JournalRepository.EntryInput in = captor.getValue();
    assertThat(in.paperPositionId()).isEqualTo(42L);
    assertThat(in.signalId()).isNull();
    assertThat(in.tags()).containsExactly("auto", "win");
    assertThat(in.note()).contains("TAKE_PROFIT").contains("250.5");
    assertThat(in.disciplineRating()).isNull();
    assertThat(in.emotionRating()).isNull();
  }

  @Test
  void aLosingCloseTagsLoss() {
    when(journal.insert(Mockito.any())).thenReturn(1L);
    new AutoJournalListener(journal, true)
        .onPaperPositionClosed(new PaperPositionClosed(9L, new BigDecimal("-30"), "STOP_LOSS"));

    ArgumentCaptor<JournalRepository.EntryInput> captor =
        ArgumentCaptor.forClass(JournalRepository.EntryInput.class);
    verify(journal).insert(captor.capture());
    assertThat(captor.getValue().tags()).containsExactly("auto", "loss");
  }

  @Test
  void aRepositoryFailureIsSwallowedNotPropagated() {
    when(journal.insert(Mockito.any())).thenThrow(new RuntimeException("db down"));
    // must not throw — a journal failure can never roll back the trade close.
    new AutoJournalListener(journal, true)
        .onPaperPositionClosed(new PaperPositionClosed(1L, BigDecimal.ZERO, "EXPIRY_SETTLEMENT"));
  }
}
