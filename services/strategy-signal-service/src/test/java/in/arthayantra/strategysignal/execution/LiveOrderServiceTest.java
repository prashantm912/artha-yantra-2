package in.arthayantra.strategysignal.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.execution.OrderGateway.OrderAck;
import in.arthayantra.strategysignal.execution.OrderGateway.OrderRequest;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.signals.SignalRepository.SignalRow;
import in.arthayantra.strategysignal.signals.SignalTaken;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** §12.5 semi-auto live arm: the Take routes to the gateway only when execution=live + a real order. */
class LiveOrderServiceTest {

  /** Every symbol resolves as a 50-lot option — the lot the existing fixtures' qty 50/25 assume. */
  private static InstrumentMetaClient lot(long lotSize) {
    return (exchange, tradingsymbol) ->
        new InstrumentMeta(InstrumentClass.OPTION, new BigDecimal("0.05"), lotSize);
  }

  private static SignalRow signal(String tradeableExch, String tradeableSym, String side) {
    return new SignalRow(
        1L, UUID.randomUUID(), "NSE", "NIFTY24JUNFUT", "3m", "ENTRY", side,
        new BigDecimal("100"), null, null, null, null, "ACTIVE", null, null, null,
        tradeableExch, tradeableSym, null, null, null, null);
  }

  @Test
  void paperModeDoesNotPlaceAnyOrder() {
    OrderGateway gateway = mock(OrderGateway.class);
    SignalRepository signals = mock(SignalRepository.class);
    LiveOrderService service = new LiveOrderService("paper", gateway, signals, lot(50));

    service.onSignalTaken(new SignalTaken(1L, 50, new BigDecimal("120")));

    verifyNoInteractions(gateway);
    verifyNoInteractions(signals);
  }

  @Test
  void liveModePlacesTheTradeableOptionWithTheSignalSide() {
    OrderGateway gateway = mock(OrderGateway.class);
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.find(1L)).thenReturn(Optional.of(signal("NFO", "NIFTY24JUN24000CE", "BUY")));
    when(gateway.place(any())).thenReturn(new OrderAck("OA-1", "COMPLETE", "ok"));
    LiveOrderService service = new LiveOrderService("live", gateway, signals, lot(50));

    service.onSignalTaken(new SignalTaken(1L, 50, new BigDecimal("120")));

    ArgumentCaptor<OrderRequest> captor = ArgumentCaptor.forClass(OrderRequest.class);
    org.mockito.Mockito.verify(gateway).place(captor.capture());
    OrderRequest req = captor.getValue();
    assertThat(req.exchange()).isEqualTo("NFO");
    assertThat(req.tradingsymbol()).isEqualTo("NIFTY24JUN24000CE"); // the option, not the future
    assertThat(req.side()).isEqualTo("BUY");
    assertThat(req.qty()).isEqualTo(50);
    assertThat(req.product()).isEqualTo("MIS");
    assertThat(req.orderType()).isEqualTo("MARKET");
  }

  @Test
  void liveModeWithoutAQtyPlacesNothing() {
    OrderGateway gateway = mock(OrderGateway.class);
    SignalRepository signals = mock(SignalRepository.class);
    LiveOrderService service = new LiveOrderService("live", gateway, signals, lot(50));

    service.onSignalTaken(new SignalTaken(1L, null, null));

    verifyNoInteractions(gateway);
  }

  @Test
  void liveModeFallsBackToTheKeyedInstrumentWhenNoTradeable() {
    OrderGateway gateway = mock(OrderGateway.class);
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.find(1L)).thenReturn(Optional.of(signal(null, null, "BUY")));
    when(gateway.place(any())).thenReturn(new OrderAck("OA-2", "COMPLETE", "ok"));
    LiveOrderService service = new LiveOrderService("live", gateway, signals, lot(25));

    service.onSignalTaken(new SignalTaken(1L, 25, new BigDecimal("100")));

    ArgumentCaptor<OrderRequest> captor = ArgumentCaptor.forClass(OrderRequest.class);
    org.mockito.Mockito.verify(gateway).place(captor.capture());
    assertThat(captor.getValue().tradingsymbol()).isEqualTo("NIFTY24JUNFUT"); // the keyed instrument
  }

  @Test
  void aGatewayFailureNeverPropagates() {
    OrderGateway gateway = mock(OrderGateway.class);
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.find(1L)).thenReturn(Optional.of(signal("NFO", "NIFTY24JUN24000CE", "BUY")));
    when(gateway.place(any())).thenThrow(new RuntimeException("broker down"));
    LiveOrderService service = new LiveOrderService("live", gateway, signals, lot(50));

    // must not throw — the /taken caller is never impacted
    service.onSignalTaken(new SignalTaken(1L, 50, new BigDecimal("120")));
  }

  /**
   * Cross-vendor review Critical 3. {@code openOrder} is the sole PAPER-entry writer; it is not the
   * only writer. This one builds an {@code OrderRequest} straight from {@code event.qty()} and sends
   * it to a real broker, and {@code PaperSignalListener} swallows its own refusal, so a paper veto
   * can never reach here. The quantity is therefore admitted again at this seam.
   *
   * <p>Latent today, exactly like the defect underneath it: {@code ARTHA_SCALPER_EXECUTION} is absent
   * from the live container env (verified 2026-08-25 via {@code docker inspect}) and the default is
   * {@code paper}, so this arm is not armed — which is precisely why it would have shipped unnoticed.
   */
  @Test
  void liveModeRefusesToSendAQuantityWhoseLotIsUnknown() {
    OrderGateway gateway = mock(OrderGateway.class);
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.find(1L)).thenReturn(Optional.of(signal("NFO", "NIFTY26MAY24000CE", "BUY")));
    LiveOrderService service = new LiveOrderService("live", gateway, signals, lot(0));

    service.onSignalTaken(new SignalTaken(1L, 50, new BigDecimal("120")));

    verifyNoInteractions(gateway); // nothing reaches the broker at an assumed lot of 1
  }

  @Test
  void liveModeRefusesToSendANonLotMultipleQuantity() {
    OrderGateway gateway = mock(OrderGateway.class);
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.find(1L)).thenReturn(Optional.of(signal("NFO", "NIFTY26MAY24000CE", "BUY")));
    LiveOrderService service = new LiveOrderService("live", gateway, signals, lot(75));

    service.onSignalTaken(new SignalTaken(1L, 50, new BigDecimal("120"))); // 50 % 75 != 0

    verifyNoInteractions(gateway); // the broker would reject it (UDAPI1104) — we refuse first
  }

  @Test
  void liveModeStillSendsALotAlignedQuantity() {
    // The weakening control: the admission must not refuse a quantity that IS aligned.
    OrderGateway gateway = mock(OrderGateway.class);
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.find(1L)).thenReturn(Optional.of(signal("NFO", "NIFTY26MAY24000CE", "BUY")));
    when(gateway.place(any())).thenReturn(new OrderAck("OA-3", "COMPLETE", "ok"));
    LiveOrderService service = new LiveOrderService("live", gateway, signals, lot(75));

    service.onSignalTaken(new SignalTaken(1L, 150, new BigDecimal("120"))); // 150 % 75 == 0

    ArgumentCaptor<OrderRequest> captor = ArgumentCaptor.forClass(OrderRequest.class);
    org.mockito.Mockito.verify(gateway).place(captor.capture());
    assertThat(captor.getValue().qty()).isEqualTo(150);
  }

  @Test
  void disabledGatewayRejectsAndPlacesNothing() {
    OrderAck ack = new DisabledOrderGateway().place(
        new OrderRequest("NFO", "NIFTY24JUN24000CE", "BUY", 50, "MIS", "MARKET", null));
    assertThat(ack.status()).isEqualTo("REJECTED");
    assertThat(ack.orderId()).isNull();
  }
}
