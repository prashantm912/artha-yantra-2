package in.arthayantra.strategysignal.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.execution.OrderGateway.OrderAck;
import in.arthayantra.strategysignal.execution.OrderGateway.OrderRequest;
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
    LiveOrderService service = new LiveOrderService("paper", gateway, signals);

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
    LiveOrderService service = new LiveOrderService("live", gateway, signals);

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
    LiveOrderService service = new LiveOrderService("live", gateway, signals);

    service.onSignalTaken(new SignalTaken(1L, null, null));

    verifyNoInteractions(gateway);
  }

  @Test
  void liveModeFallsBackToTheKeyedInstrumentWhenNoTradeable() {
    OrderGateway gateway = mock(OrderGateway.class);
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.find(1L)).thenReturn(Optional.of(signal(null, null, "BUY")));
    when(gateway.place(any())).thenReturn(new OrderAck("OA-2", "COMPLETE", "ok"));
    LiveOrderService service = new LiveOrderService("live", gateway, signals);

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
    LiveOrderService service = new LiveOrderService("live", gateway, signals);

    // must not throw — the /taken caller is never impacted
    service.onSignalTaken(new SignalTaken(1L, 50, new BigDecimal("120")));
  }

  @Test
  void disabledGatewayRejectsAndPlacesNothing() {
    OrderAck ack = new DisabledOrderGateway().place(
        new OrderRequest("NFO", "NIFTY24JUN24000CE", "BUY", 50, "MIS", "MARKET", null));
    assertThat(ack.status()).isEqualTo("REJECTED");
    assertThat(ack.orderId()).isNull();
  }
}
