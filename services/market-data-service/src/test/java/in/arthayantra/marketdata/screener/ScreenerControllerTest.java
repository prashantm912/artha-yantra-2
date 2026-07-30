package in.arthayantra.marketdata.screener;

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
    ScreenerController.ScreenerResponse result = screen(-5, -10);
    ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
    ArgumentCaptor<Integer> offset = ArgumentCaptor.forClass(Integer.class);

    verify(screener).run(any(), any(), any(), any(), limit.capture(), offset.capture());

    assertThat(limit.getValue()).isZero();
    assertThat(offset.getValue()).isZero();
    assertThat(result.limit()).isZero();
    assertThat(result.offset()).isZero();
  }

  @Test
  void passesLimitAndOffsetWithinBoundsThrough() {
    ScreenerController.ScreenerResponse result = screen(25, 50);
    ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
    ArgumentCaptor<Integer> offset = ArgumentCaptor.forClass(Integer.class);

    verify(screener).run(any(), any(), any(), any(), limit.capture(), offset.capture());

    assertThat(limit.getValue()).isEqualTo(25);
    assertThat(offset.getValue()).isEqualTo(50);
    assertThat(result.limit()).isEqualTo(25);
    assertThat(result.offset()).isEqualTo(50);
  }

  @Test
  void clampsLimitAboveMaximumToFiveHundred() {
    ScreenerController.ScreenerResponse result = screen(1000, 0);
    ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
    ArgumentCaptor<Integer> offset = ArgumentCaptor.forClass(Integer.class);

    verify(screener).run(any(), any(), any(), any(), limit.capture(), offset.capture());

    assertThat(limit.getValue()).isEqualTo(500);
    assertThat(offset.getValue()).isZero();
    assertThat(result.limit()).isEqualTo(500);
    assertThat(result.offset()).isZero();
  }

  private ScreenerController.ScreenerResponse screen(int limit, int offset) {
    return controller.screen(
        null, null, null, null, null, null, null, null, null, limit, offset);
  }
}
