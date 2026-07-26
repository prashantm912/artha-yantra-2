package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class PaperControllerTest {

  private PaperService paper;
  private PaperController controller;
  private PaperAdminAuditLedger adminAudit;

  @BeforeEach
  void setUp() {
    paper = mock(PaperService.class);
    adminAudit = mock(PaperAdminAuditLedger.class);
    when(paper.trades(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
    controller =
        new PaperController(
            paper,
            mock(PaperAccountService.class),
            mock(InstrumentMetaClient.class),
            adminAudit,
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

  @Test
  void manualCloseUsesLightweightBookBeforeCloseAndDoesNotLoadFullDetail() {
    BigDecimal price = new BigDecimal("123.45");
    BigDecimal realizedPnl = new BigDecimal("23.45");
    PaperService.TradeDto trade =
        new PaperService.TradeDto(
            7L, "NSE", "TESTCO", "BUY", 10L, new BigDecimal("100"), realizedPnl, null, null);
    when(paper.positionBook(7L)).thenReturn("manas-arora");
    when(paper.closePosition(7L, price)).thenReturn(trade);

    PaperService.TradeDto result = controller.close(7L, new PaperController.CloseBody(price));

    assertThat(result).isSameAs(trade);
    InOrder order = inOrder(paper, adminAudit);
    order.verify(paper).positionBook(7L);
    order.verify(paper).closePosition(7L, price);
    order.verify(adminAudit).recordManualClose("manas-arora", 7L, price, realizedPnl);
    verify(paper, never()).positionDetail(7L);
  }
}
