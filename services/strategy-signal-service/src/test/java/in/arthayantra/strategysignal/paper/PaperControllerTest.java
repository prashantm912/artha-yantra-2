package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PaperControllerTest {

  private PaperService paper;
  private PaperController controller;

  @BeforeEach
  void setUp() {
    paper = mock(PaperService.class);
    when(paper.trades(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
    controller =
        new PaperController(
            paper,
            mock(PaperAccountService.class),
            mock(InstrumentMetaClient.class),
            mock(PaperAdminAuditLedger.class),
            mock(PaperEventRepository.class));
  }

  private Map<String, Object> trades(int limit, int offset) {
    return controller.trades(null, null, null, null, limit, offset);
  }

  @Test
  void clampsNegativeLimitAndOffsetToTheBounds() {
    Map<String, Object> result = trades(-5, -10);
    ArgumentCaptor<Integer> lim = ArgumentCaptor.forClass(Integer.class);
    ArgumentCaptor<Integer> off = ArgumentCaptor.forClass(Integer.class);
    verify(paper).trades(any(), any(), any(), any(), lim.capture(), off.capture());
    assertThat(lim.getValue()).isEqualTo(1);
    assertThat(off.getValue()).isZero();
    assertThat(result).containsEntry("limit", 1).containsEntry("offset", 0);
  }

  @Test
  void clampsLimitAboveTheMaximum() {
    trades(1000, 0);
    ArgumentCaptor<Integer> lim = ArgumentCaptor.forClass(Integer.class);
    verify(paper).trades(any(), any(), any(), any(), lim.capture(), anyInt());
    assertThat(lim.getValue()).isEqualTo(500);
  }

  @Test
  void passesInBoundsPagingThrough() {
    trades(25, 50);
    ArgumentCaptor<Integer> lim = ArgumentCaptor.forClass(Integer.class);
    ArgumentCaptor<Integer> off = ArgumentCaptor.forClass(Integer.class);
    verify(paper).trades(any(), any(), any(), any(), lim.capture(), off.capture());
    assertThat(lim.getValue()).isEqualTo(25);
    assertThat(off.getValue()).isEqualTo(50);
  }
}
