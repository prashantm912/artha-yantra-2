package in.arthayantra.marketdata.upstox;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Market-data admin surface — the Upstox Market-Information <b>entitlement probe</b> (ADR-0002, U1).
 * Owner-triggered after the dedicated analytics token is staged into its secret file: it makes one
 * live Upstox call and reports whether the token is entitled ({@code covered:true} on HTTP 200) or
 * not ({@code covered:false} on 403). A live 200 is the gate that unlocks the rest of the analytics
 * build (U2+). {@code 503} when the client is not wired (live profile + {@code
 * artha.upstox.analytics.enabled=true}). Map-envelope so it does not enumerate a springdoc schema.
 */
@RestController
@RequestMapping("/api/v1/market/admin")
public class UpstoxEntitlementController {

  private final ObjectProvider<UpstoxAnalyticsClient> client;

  /** The client is an optional bean (present only when the analytics flag is on), hence the provider. */
  public UpstoxEntitlementController(ObjectProvider<UpstoxAnalyticsClient> client) {
    this.client = client;
  }

  /** Probes the live Upstox Market-Information entitlement — {@code {covered, httpStatus, detail}}. */
  @GetMapping("/upstox-entitlement")
  public Map<String, Object> entitlement() {
    UpstoxAnalyticsClient resolved = client.getIfAvailable();
    if (resolved == null) {
      throw new ApiException(
          503,
          ErrorCodes.NOT_CONFIGURED,
          "Upstox analytics not configured (needs the live profile + artha.upstox.analytics.enabled=true)");
    }
    UpstoxAnalyticsClient.Entitlement e = resolved.probe();
    return Map.of("covered", e.covered(), "httpStatus", e.httpStatus(), "detail", e.detail());
  }
}
