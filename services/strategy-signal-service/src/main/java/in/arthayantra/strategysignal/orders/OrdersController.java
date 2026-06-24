package in.arthayantra.strategysignal.orders;

import in.arthayantra.strategysignal.execution.OrderGateway;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The §18.1 order-READ surface — the broker orderbook / positions / tradebook / funds, routed by
 * edge-gateway under {@code /api/v1/orders/**}. Read-only: it delegates to the broker-agnostic
 * {@link OrderGateway} port (the {@link in.arthayantra.strategysignal.execution.DisabledOrderGateway}
 * by default — empty lists + a {@code NOT_CONFIGURED} funds row; a live
 * {@link in.arthayantra.strategysignal.execution.RestOpenAlgoOrderReadGateway} when OpenAlgo
 * order-read is configured). Each list endpoint returns the {@code {items:[...]}} envelope (the D8
 * convention); {@code funds} is a single object.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrdersController {

  private final OrderGateway gateway;

  /** Wires the broker-agnostic order port. */
  public OrdersController(OrderGateway gateway) {
    this.gateway = gateway;
  }

  /** The broker orderbook — placed orders with their lifecycle status. */
  @GetMapping("/orderbook")
  public Map<String, Object> orderbook() {
    return Map.of("items", gateway.orderbook());
  }

  /** The broker net positions with mark-to-market P&amp;L. */
  @GetMapping("/positions")
  public Map<String, Object> positions() {
    return Map.of("items", gateway.positions());
  }

  /** The broker tradebook — executed fills. */
  @GetMapping("/tradebook")
  public Map<String, Object> tradebook() {
    return Map.of("items", gateway.tradebook());
  }

  /** The funds/margin snapshot (a single object, not an envelope). */
  @GetMapping("/funds")
  public OrderGateway.Funds funds() {
    return gateway.funds();
  }
}
