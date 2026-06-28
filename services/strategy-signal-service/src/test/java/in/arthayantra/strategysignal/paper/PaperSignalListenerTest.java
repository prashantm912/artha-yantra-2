package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.signals.SignalTaken;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The E10 stamping seam: a scalper take charges the paper open to a round-robin sub-account. */
class PaperSignalListenerTest {

  private static ArgumentCaptor<PaperService.OrderRequest> openedWith(
      PaperService paper, ScalperAccountModel accounts, SignalTaken event) {
    new PaperSignalListener(paper, accounts).onSignalTaken(event);
    ArgumentCaptor<PaperService.OrderRequest> req =
        ArgumentCaptor.forClass(PaperService.OrderRequest.class);
    verify(paper).openOrder(req.capture());
    return req;
  }

  @Test
  void aScalperTakeChargesTheOpenToARoundRobinSubAccount() {
    PaperService paper = mock(PaperService.class);
    ScalperAccountModel accounts = mock(ScalperAccountModel.class);
    when(accounts.nextFreeAccount()).thenReturn(3);
    ArgumentCaptor<PaperService.OrderRequest> req =
        openedWith(paper, accounts, new SignalTaken(7L, 50, new BigDecimal("100"), true));
    assertThat(req.getValue().subaccountIdx()).isEqualTo(3);
  }

  @Test
  void aNonScalperTakeLeavesTheSubAccountUnstamped() {
    PaperService paper = mock(PaperService.class);
    ScalperAccountModel accounts = mock(ScalperAccountModel.class);
    ArgumentCaptor<PaperService.OrderRequest> req =
        openedWith(paper, accounts, new SignalTaken(7L, 50, new BigDecimal("100"), false));
    assertThat(req.getValue().subaccountIdx()).isNull();
    verify(accounts, never()).nextFreeAccount();
  }

  @Test
  void noQtyOpensNothing() {
    PaperService paper = mock(PaperService.class);
    ScalperAccountModel accounts = mock(ScalperAccountModel.class);
    new PaperSignalListener(paper, accounts).onSignalTaken(new SignalTaken(7L, null, null, true));
    verify(paper, never()).openOrder(any());
  }
}
