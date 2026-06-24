package in.arthayantra.strategysignal.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.execution.DisabledOrderGateway;
import in.arthayantra.strategysignal.execution.OrderGateway;
import in.arthayantra.strategysignal.execution.OrderGateway.Funds;
import in.arthayantra.strategysignal.execution.OrderGateway.OrderbookEntry;
import in.arthayantra.strategysignal.execution.OrderGateway.PositionEntry;
import in.arthayantra.strategysignal.execution.OrderGateway.TradebookEntry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The §18.1 order-read controller: each list endpoint wraps the gateway result in the
 * {@code {items:[...]}} envelope (the D8 list convention) and {@code funds} is the bare object. With
 * the default {@link DisabledOrderGateway} every list is empty and funds is the
 * {@code NOT_CONFIGURED} sentinel; against a live gateway the rows pass straight through.
 */
class OrdersControllerTest {

  @Test
  @SuppressWarnings("unchecked")
  void disabledGatewayReturnsEmptyEnvelopesAndNotConfiguredFunds() {
    OrdersController controller = new OrdersController(new DisabledOrderGateway());

    assertThat((List<OrderbookEntry>) controller.orderbook().get("items")).isEmpty();
    assertThat((List<PositionEntry>) controller.positions().get("items")).isEmpty();
    assertThat((List<TradebookEntry>) controller.tradebook().get("items")).isEmpty();
    assertThat(controller.funds().status()).isEqualTo("NOT_CONFIGURED");
    assertThat(controller.funds().availableCash()).isNull();
  }

  @Test
  void listEndpointsWrapGatewayRowsInTheItemsEnvelope() {
    OrderGateway gateway = mock(OrderGateway.class);
    OrderbookEntry order =
        new OrderbookEntry(
            "INFY", "NSE", "SELL", new BigDecimal("5"), new BigDecimal("1500"), BigDecimal.ZERO,
            "LIMIT", "MIS", "24120900000213", "complete", "09-Dec-2024 09:44:09");
    PositionEntry position =
        new PositionEntry(
            "INFY", "NSE", "SELL", new BigDecimal("-1"), "MIS", new BigDecimal("0.00"), null, null);
    TradebookEntry trade =
        new TradebookEntry(
            "INFY", "NSE", "BUY", new BigDecimal("1"), new BigDecimal("1914.4"),
            new BigDecimal("1914.4"), "MIS", "24120900009388", "09-Dec-2024 09:16:48");
    when(gateway.orderbook()).thenReturn(List.of(order));
    when(gateway.positions()).thenReturn(List.of(position));
    when(gateway.tradebook()).thenReturn(List.of(trade));
    when(gateway.funds())
        .thenReturn(new Funds("OK", new BigDecimal("18083.01"), BigDecimal.ZERO, null, null, null));

    OrdersController controller = new OrdersController(gateway);

    assertThat(controller.orderbook()).isEqualTo(Map.of("items", List.of(order)));
    assertThat(controller.positions()).isEqualTo(Map.of("items", List.of(position)));
    assertThat(controller.tradebook()).isEqualTo(Map.of("items", List.of(trade)));
    assertThat(controller.funds().status()).isEqualTo("OK");
    assertThat(controller.funds().availableCash()).isEqualByComparingTo("18083.01");
  }
}
