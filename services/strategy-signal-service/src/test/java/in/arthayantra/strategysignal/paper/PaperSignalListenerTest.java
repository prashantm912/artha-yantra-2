package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.signals.SignalTaken;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The E10 stamping seam: a scalper take charges the paper open to a round-robin sub-account. */
class PaperSignalListenerTest {

  /** A signal store with no straddle detail → the single-leg open path (the legacy behaviour). */
  private static SignalRepository noStraddle() {
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.find(anyLong())).thenReturn(Optional.empty());
    return signals;
  }

  private static ArgumentCaptor<PaperService.OrderRequest> openedWith(
      PaperService paper, ScalperAccountModel accounts, SignalTaken event) {
    new PaperSignalListener(paper, accounts, noStraddle()).onSignalTaken(event);
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
    new PaperSignalListener(paper, accounts, noStraddle()).onSignalTaken(new SignalTaken(7L, null, null, true));
    verify(paper, never()).openOrder(any());
  }

  /** A directional scalper take opens the PICKED OPTION at its captured premium (audit P0-3). */
  @Test
  void aScalperTakeOpensTheTradeableOptionAtItsCapturedPremiumWithNoFutureBasisBrackets()
      throws Exception {
    PaperService paper = mock(PaperService.class);
    ScalperAccountModel accounts = mock(ScalperAccountModel.class);
    when(accounts.nextFreeAccount()).thenReturn(1);
    SignalRepository signals = mock(SignalRepository.class);
    var detail =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree("{\"side\":\"LONG_PE\",\"option_ltp\":\"82.50\"}");
    when(signals.find(7L))
        .thenReturn(
            Optional.of(
                new SignalRepository.SignalRow(
                    7L, java.util.UUID.randomUUID(), "NFO", "NIFTY26JULFUT", "3m", "ENTRY", "BUY",
                    new BigDecimal("25000"), new BigDecimal("24800"), new BigDecimal("25400"),
                    new BigDecimal("0.8"), null, "TAKEN", null, null, new BigDecimal("75"),
                    "NFO", "NIFTY26JUL24900PE", detail, null, null, null)));

    // The auto-take's fillPrice is the FUTURE entry price — the option leg must ignore it.
    new PaperSignalListener(paper, accounts, signals)
        .onSignalTaken(new SignalTaken(7L, 75, new BigDecimal("25000"), true));

    ArgumentCaptor<PaperService.OrderRequest> req =
        ArgumentCaptor.forClass(PaperService.OrderRequest.class);
    verify(paper).openOrder(req.capture());
    assertThat(req.getValue().exchange()).isEqualTo("NFO");
    assertThat(req.getValue().tradingsymbol()).isEqualTo("NIFTY26JUL24900PE");
    assertThat(req.getValue().side()).isEqualTo("BUY");
    assertThat(req.getValue().price()).isEqualByComparingTo("82.50");
    // index-future SL/TP must NOT ride the option leg (wrong basis = instant bracket close)
    assertThat(req.getValue().stopLoss()).isNull();
    assertThat(req.getValue().takeProfit()).isNull();
  }

  /** The YAML's premium_pct rules become option-premium bracket levels on the option leg (P1-8). */
  @Test
  void aScalperTakeDerivesPremiumBasisBracketsFromTheYamlExitRules() throws Exception {
    PaperService paper = mock(PaperService.class);
    ScalperAccountModel accounts = mock(ScalperAccountModel.class);
    when(accounts.nextFreeAccount()).thenReturn(1);
    SignalRepository signals = mock(SignalRepository.class);
    var om = new com.fasterxml.jackson.databind.ObjectMapper();
    var detail = om.readTree("{\"side\":\"LONG_CE\",\"option_ltp\":\"82.50\"}");
    java.util.UUID versionId = java.util.UUID.randomUUID();
    when(signals.find(7L))
        .thenReturn(
            Optional.of(
                new SignalRepository.SignalRow(
                    7L, versionId, "NFO", "NIFTY26JULFUT", "3m", "ENTRY", "BUY",
                    new BigDecimal("25000"), new BigDecimal("24800"), new BigDecimal("25400"),
                    new BigDecimal("0.8"), null, "TAKEN", null, null, new BigDecimal("75"),
                    "NFO", "NIFTY26JUL25100CE", detail, null, null, null)));
    when(signals.versionConfig(versionId))
        .thenReturn(
            Optional.of(
                om.readTree(
                    "{\"exit_rules\":["
                        + "{\"type\":\"stop_loss\",\"params\":{\"basis\":\"premium_pct\",\"value\":50}},"
                        + "{\"type\":\"take_profit\",\"params\":{\"basis\":\"premium_pct\",\"value\":35}},"
                        + "{\"type\":\"time_stop\",\"params\":{\"max_bars\":16}}]}")));

    new PaperSignalListener(paper, accounts, signals)
        .onSignalTaken(new SignalTaken(7L, 75, new BigDecimal("25000"), true));

    ArgumentCaptor<PaperService.OrderRequest> req =
        ArgumentCaptor.forClass(PaperService.OrderRequest.class);
    verify(paper).openOrder(req.capture());
    // 82.50 × (1−0.50) and 82.50 × (1+0.35) — premium basis, enforceable by PaperBracketEvaluator
    assertThat(req.getValue().stopLoss()).isEqualByComparingTo("41.25");
    assertThat(req.getValue().takeProfit()).isEqualByComparingTo("111.38");
  }

  /** A non-scalper take keeps the primary leg and carries the signal's same-basis brackets. */
  @Test
  void aNonScalperTakeCarriesTheSignalBracketsOnThePrimaryLeg() {
    PaperService paper = mock(PaperService.class);
    ScalperAccountModel accounts = mock(ScalperAccountModel.class);
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.find(7L))
        .thenReturn(
            Optional.of(
                new SignalRepository.SignalRow(
                    7L, java.util.UUID.randomUUID(), "NSE", "RELIANCE", "1m", "ENTRY", "BUY",
                    new BigDecimal("2500"), new BigDecimal("2450"), new BigDecimal("2600"),
                    new BigDecimal("0.8"), null, "TAKEN", null, null, new BigDecimal("10"),
                    null, null, null, null, null, null)));

    new PaperSignalListener(paper, accounts, signals)
        .onSignalTaken(new SignalTaken(7L, 10, new BigDecimal("2500"), false));

    ArgumentCaptor<PaperService.OrderRequest> req =
        ArgumentCaptor.forClass(PaperService.OrderRequest.class);
    verify(paper).openOrder(req.capture());
    assertThat(req.getValue().tradingsymbol()).isNull(); // primary-leg fallback in openOrder
    assertThat(req.getValue().stopLoss()).isEqualByComparingTo("2450");
    assertThat(req.getValue().takeProfit()).isEqualByComparingTo("2600");
  }
}
