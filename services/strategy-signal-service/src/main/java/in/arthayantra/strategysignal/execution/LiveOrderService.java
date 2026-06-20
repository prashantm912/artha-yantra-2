package in.arthayantra.strategysignal.execution;

import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.signals.SignalRepository.SignalRow;
import in.arthayantra.strategysignal.signals.SignalTaken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Routes a TAKEN signal to a real broker order — the LIVE arm of the §12.5 semi-auto flow, the
 * sibling of {@code PaperSignalListener} (which opens a PAPER position). It listens to the same
 * {@code SignalTaken} event (fired by the owner's "Take" click), so live placement stays behind a
 * deliberate human action — never auto-fired off a signal.
 *
 * <p><b>Default OFF.</b> Two independent gates must both be on before any real order leaves: the
 * config flag {@code artha.scalper.execution=live} (default {@code paper} ⇒ this is a no-op and the
 * paper path handles the Take), AND a real {@link OrderGateway} must be wired (the default {@link
 * DisabledOrderGateway} places nothing). Full-auto (no human click) is a deliberately separate, later
 * flag. A scalper signal carries the chosen option on the V009 side-channel — the order routes the
 * OPTION (tradeable), not the index future the signal is keyed on.
 */
@Component
public class LiveOrderService {

  private static final Logger log = LoggerFactory.getLogger(LiveOrderService.class);

  private final boolean executionLive;
  private final OrderGateway gateway;
  private final SignalRepository signals;

  /** Wires the gateway + signal lookup; {@code artha.scalper.execution} defaults to {@code paper}. */
  public LiveOrderService(
      @Value("${artha.scalper.execution:paper}") String executionMode,
      OrderGateway gateway,
      SignalRepository signals) {
    this.executionLive = "live".equalsIgnoreCase(executionMode);
    this.gateway = gateway;
    this.signals = signals;
    if (executionLive) {
      log.warn(
          "artha.scalper.execution=live — TAKEN signals will route to {} (a real order is placed"
              + " only if a live gateway is also wired)",
          gateway.getClass().getSimpleName());
    }
  }

  /** On the owner's Take: when live execution is armed, place the order; else leave it to paper. */
  @EventListener
  public void onSignalTaken(SignalTaken event) {
    if (!executionLive) {
      return; // default — paper handles the Take (PaperSignalListener)
    }
    if (event.qty() == null || event.qty() <= 0) {
      return;
    }
    try {
      SignalRow signal = signals.find(event.signalId()).orElse(null);
      if (signal == null) {
        log.warn("live order skipped — taken signal {} not found", event.signalId());
        return;
      }
      boolean hasTradeable = signal.tradeableTradingsymbol() != null;
      String exchange = hasTradeable ? signal.tradeableExchange() : signal.exchange();
      String tradingsymbol = hasTradeable ? signal.tradeableTradingsymbol() : signal.tradingsymbol();
      OrderGateway.OrderRequest request =
          new OrderGateway.OrderRequest(
              exchange, tradingsymbol, signal.side(), event.qty(), "MIS", "MARKET", null);
      OrderGateway.OrderAck ack = gateway.place(request);
      log.info(
          "live order for taken signal {}: {} {} x{} on {}:{} → {} ({})",
          event.signalId(), signal.side(), "MARKET", event.qty(), exchange, tradingsymbol,
          ack.status(), ack.orderId());
    } catch (Exception e) {
      // never propagate to the /taken caller — mirror PaperSignalListener's isolation
      log.error("live order placement failed for taken signal {}: {}", event.signalId(), e.getMessage());
    }
  }
}
