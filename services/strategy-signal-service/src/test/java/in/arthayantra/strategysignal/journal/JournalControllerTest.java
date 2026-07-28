package in.arthayantra.strategysignal.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JournalControllerTest {

  private JournalRepository repository;
  private JournalController controller;

  @BeforeEach
  void setUp() {
    repository = mock(JournalRepository.class);
    when(repository.list(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
    controller = new JournalController(repository);
  }

  private JournalController.JournalPage list(int limit, int offset) {
    return controller.list(null, null, null, null, null, limit, offset);
  }

  @Test
  void clampsNegativeLimitAndOffsetToTheBounds() {
    JournalController.JournalPage result = list(-5, -10);
    ArgumentCaptor<Integer> lim = ArgumentCaptor.forClass(Integer.class);
    ArgumentCaptor<Integer> off = ArgumentCaptor.forClass(Integer.class);
    verify(repository).list(any(), any(), any(), any(), any(), lim.capture(), off.capture());
    assertThat(lim.getValue()).isEqualTo(1);
    assertThat(off.getValue()).isZero();
    assertThat(result.limit()).isEqualTo(1);
    assertThat(result.offset()).isZero();
  }

  @Test
  void clampsLimitAboveTheMaximum() {
    list(1000, 0);
    ArgumentCaptor<Integer> lim = ArgumentCaptor.forClass(Integer.class);
    verify(repository).list(any(), any(), any(), any(), any(), lim.capture(), anyInt());
    assertThat(lim.getValue()).isEqualTo(500);
  }

  @Test
  void passesInBoundsPagingThrough() {
    list(25, 50);
    ArgumentCaptor<Integer> lim = ArgumentCaptor.forClass(Integer.class);
    ArgumentCaptor<Integer> off = ArgumentCaptor.forClass(Integer.class);
    verify(repository).list(any(), any(), any(), any(), any(), lim.capture(), off.capture());
    assertThat(lim.getValue()).isEqualTo(25);
    assertThat(off.getValue()).isEqualTo(50);
  }
}
