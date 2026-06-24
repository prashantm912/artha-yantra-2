package in.arthayantra.strategysignal.execution;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * The fail-safe default {@link OrderGateway} — it places NOTHING and returns a REJECTED ack, and the
 * order-READ surface (§18.1) returns EMPTY lists / a {@code NOT_CONFIGURED} funds row. It is the only
 * order gateway until a real broker gateway ({@link RestOpenAlgoOrderReadGateway} for reads, a live
 * write gateway gated on §S3 + the §17.3 latency gate for writes) is wired, so even with
 * {@code artha.scalper.execution=live} no real order can be placed without ALSO deliberately
 * configuring a live gateway (defense in depth — the flag alone is inert).
 */
@Component
@ConditionalOnMissingBean(value = OrderGateway.class, ignored = DisabledOrderGateway.class)
public class DisabledOrderGateway implements OrderGateway {

  private static final Logger log = LoggerFactory.getLogger(DisabledOrderGateway.class);

  @Override
  public OrderAck place(OrderRequest request) {
    log.warn(
        "live order execution gateway not configured — order NOT placed: {} {} x{} on {}:{}",
        request.side(), request.orderType(), request.qty(), request.exchange(), request.tradingsymbol());
    return new OrderAck(null, "REJECTED", "live execution gateway not configured");
  }

  @Override
  public List<OrderbookEntry> orderbook() {
    return List.of();
  }

  @Override
  public List<PositionEntry> positions() {
    return List.of();
  }

  @Override
  public List<TradebookEntry> tradebook() {
    return List.of();
  }

  @Override
  public Funds funds() {
    return Funds.notConfigured();
  }
}
