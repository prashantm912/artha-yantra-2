package in.arthayantra.strategysignal.signals;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator trigger for the subscriber-watchdog drill (see {@link SignalFaultInjector} for the full
 * rationale and safety posture).
 *
 * <p><b>This whole controller only exists when {@code artha.signals.fault-injection.enabled=true}
 * AND the engine is enabled</b> — with the flag at its default the bean is never created, so the path
 * 404s and does not appear in the captured OpenAPI spec at all. The engine half of the condition
 * mirrors {@link SignalFaultInjector}'s: without it, enabling injection on an engine-disabled
 * instance would fail startup rather than simply leaving the drill absent.
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
@ConditionalOnExpression(
    "${artha.signals.fault-injection.enabled:false} and ${artha.signals.engine-enabled:true}")
public class SignalFaultInjectionController {

  private final SignalFaultInjector injector;

  /** Wires the injector (which exists under the same condition). */
  public SignalFaultInjectionController(SignalFaultInjector injector) {
    this.injector = injector;
  }

  /**
   * Suspends the engine's candle subscription so the watchdog's receive-stall branch fires for real.
   *
   * @param autoRestoreMs optional bounded restore delay. OMIT IT for a real drill: the default is
   *     derived from the canary's own threshold + sweep cadence and is guaranteed long enough for the
   *     watchdog to fire. A shorter value is honoured but comes back with {@code detectorCapable=false}.
   */
  @PostMapping("/subscription-stall")
  public SignalFaultInjector.SubscriptionStallInjection injectSubscriptionStall(
      @RequestParam(name = "autoRestoreMs", required = false) Long autoRestoreMs) {
    return injector.injectSubscriptionStall(autoRestoreMs);
  }
}
