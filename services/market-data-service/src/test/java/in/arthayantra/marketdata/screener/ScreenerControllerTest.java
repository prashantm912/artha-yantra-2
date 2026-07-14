package in.arthayantra.marketdata.screener;

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

class ScreenerControllerTest {

  private ScreenerService screener;
  private ScreenerController controller;

  @BeforeEach
  void setUp() {
    screener = mock(ScreenerService.class);
    when(screener.run(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
    controller = new ScreenerController(screener);
  }

  @Test
  void clampsNegativeLimitAndOffsetToZero() {
    Map<String, Object> result = screen(-5, -10);
    ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
    ArgumentCaptor<Integer> offset = ArgumentCaptor.forClass(Integer.class);

    verify(screener).run(any(), any(), any(), any(), limit.capture(), offset.capture());

    assertThat(limit.getValue()).isZero();
    assertThat(offset.getValue()).isZero();
    assertThat(result).containsEntry("limit", 0).containsEntry("offset", 0);
  }

  @Test
  void passesLimitAndOffsetWithinBoundsThrough() {
    Map<String, Object> result = screen(25, 50);
    ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
    ArgumentCaptor<Integer> offset = ArgumentCaptor.forClass(Integer.class);

    verify(screener).run(any(), any(), any(), any(), limit.capture(), offset.capture());

    assertThat(limit.getValue()).isEqualTo(25);
    assertThat(offset.getValue()).isEqualTo(50);
    assertThat(result).containsEntry("limit", 25).containsEntry("offset", 50);
  }

  @Test
  void clampsLimitAboveMaximumToFiveHundred() {
    Map<String, Object> result = screen(1000, 0);
    ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
    ArgumentCaptor<Integer> offset = ArgumentCaptor.forClass(Integer.class);

    verify(screener).run(any(), any(), any(), any(), limit.capture(), offset.capture());

    assertThat(limit.getValue()).isEqualTo(500);
    assertThat(offset.getValue()).isZero();
    assertThat(result).containsEntry("limit", 500).containsEntry("offset", 0);
  }

  private Map<String, Object> screen(int limit, int offset) {
    return controller.screen(
        null, null, null, null, null, null, null, null, null, limit, offset);
  }
}
