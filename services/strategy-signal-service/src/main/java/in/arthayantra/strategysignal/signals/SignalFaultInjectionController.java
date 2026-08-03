package in.arthayantra.strategysignal.signals;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator trigger for the subscriber-watchdog drill (see {@link SignalFaultInjector} for the full
 * rationale and safety posture).
 *
 * <p><b>This whole controller only exists when {@code artha.signals.fault-injection.enabled=true}</b>
 * — with the flag at its default the bean is never created, so the path 404s and does not appear in
 * the captured OpenAPI spec at all.
 *
 * <p><b>Deliberately NOT routable through the edge gateway.</b> {@code /api/v1/signal-fault-injection}
 * is absent from edge-gateway's {@code Path=} prefix allowlist (which lists {@code /api/v1/signals/**}
 * and {@code /api/v1/signal-rejections/**}, neither of which matches this path), so the gateway serves
 * the SPA index for it rather than proxying. Reaching this requires talking to the service container
 * directly. That omission is the access control — do not add an allowlist entry.
 *
 * <p>POST-only on purpose: no GET may carry this side effect.
 */
@RestController
@RequestMapping("/api/v1/signal-fault-injection")
@ConditionalOnProperty(value = "artha.signals.fault-injection.enabled", havingValue = "true")
public class SignalFaultInjectionController {

  private final SignalFaultInjector injector;

  /** Wires the injector (which exists under the same condition). */
  public SignalFaultInjectionController(SignalFaultInjector injector) {
    this.injector = injector;
  }

  /**
   * Suspends the engine's candle subscription so the watchdog's receive-stall branch fires for real.
   *
   * @param autoRestoreMs optional bounded auto-restore delay; clamped by the injector
   */
  @PostMapping("/subscription-stall")
  public SignalFaultInjector.SubscriptionStallInjection injectSubscriptionStall(
      @RequestParam(name = "autoRestoreMs", required = false) Long autoRestoreMs) {
    return injector.injectSubscriptionStall(autoRestoreMs);
  }
}
