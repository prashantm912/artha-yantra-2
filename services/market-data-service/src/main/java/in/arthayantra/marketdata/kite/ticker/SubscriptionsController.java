package in.arthayantra.marketdata.kite.ticker;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketdata.kite.InstrumentKey;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The Phase-13 subscription surface (B-1): inspect, add, drop registry holds. */
@RestController
@RequestMapping("/api/v1/market/subscriptions")
public class SubscriptionsController {

  /** Subscribe request. */
  public record SubscribeRequest(
      String exchange, String tradingsymbol, String mode, String priority, String subscriber) {}

  private final SubscriptionRegistry registry;

  /** Wires the registry. */
  public SubscriptionsController(SubscriptionRegistry registry) {
    this.registry = registry;
  }

  /** The current effective subscription set. */
  @GetMapping
  public Map<String, List<SubscriptionRegistry.SubscriptionView>> list() {
    return Map.of("items", registry.view());
  }

  /** Adds (or raises) a hold; defaults: mode quote, priority UI, subscriber "ui". */
  @PostMapping
  public SubscribeResponse subscribe(@RequestBody SubscribeRequest request) {
    SubscriptionMode mode =
        SubscriptionMode.fromWireName(request.mode() == null ? "quote" : request.mode());
    if (mode == null) {
      throw new ApiException(
          400, ErrorCodes.VALIDATION_FAILED, "mode must be one of ltp|quote|full");
    }
    SubscriptionPriority priority;
    try {
      priority =
          request.priority() == null
              ? SubscriptionPriority.UI
              : SubscriptionPriority.valueOf(request.priority().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          400, ErrorCodes.VALIDATION_FAILED, "priority must be one of strategy|ui|speculative");
    }
    if (priority == SubscriptionPriority.PINNED_INDEX) {
      throw new ApiException(
          400, ErrorCodes.VALIDATION_FAILED, "PINNED_INDEX is system-reserved");
    }
    String subscriber = request.subscriber() == null ? "ui" : request.subscriber();
    SubscriptionMode effective =
        registry.subscribe(
            subscriber,
            new InstrumentKey(request.exchange(), request.tradingsymbol()),
            mode,
            priority);
    return new SubscribeResponse(
        request.exchange(), request.tradingsymbol(), effective.wireName());
  }

  /**
   * Subscribe receipt. {@code effectiveMode} is the mode the registry actually granted, which can
   * differ from the requested one. Multi-key {@code Map.of} before D3 — order NORMALISED.
   */
  public record SubscribeResponse(String exchange, String tradingsymbol, String effectiveMode) {}

  /** Releases one subscriber's hold. */
  @DeleteMapping
  public ResponseEntity<Void> unsubscribe(
      @RequestParam String exchange,
      @RequestParam String tradingsymbol,
      @RequestParam(defaultValue = "ui") String subscriber) {
    registry.unsubscribe(subscriber, new InstrumentKey(exchange, tradingsymbol));
    return ResponseEntity.noContent().build();
  }
}
