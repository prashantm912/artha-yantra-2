package in.arthayantra.marketdata.margin;

import in.arthayantra.marketdata.upstox.UpstoxFnoMasterClient;
import in.arthayantra.marketdata.upstox.UpstoxMarginClient;
import in.arthayantra.marketdata.upstox.UpstoxMarginClient.MarginQuote;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pre-trade SPAN margin for an F&amp;O basket (F9 risk-layer SPAN source). Resolves each structured
 * leg to its Upstox {@code instrument_key} off the F&amp;O master, then asks Upstox to compute
 * SPAN+exposure server-side ({@link UpstoxMarginClient}) — no {@code .spn} file. Typed record in/out
 * (never a {@code Map} — the contract ratchet). Both collaborators resolve via {@link
 * ObjectProvider}, so when the Upstox analytics token is off (mock stack, Kite-only live) the
 * endpoint returns an {@code unpriced} response instead of 500-ing — advisory only, fail-soft.
 */
@RestController
public class MarginController {

  private final ObjectProvider<UpstoxMarginClient> client;
  private final ObjectProvider<UpstoxFnoMasterClient> master;

  /** Wires the optional Upstox collaborators. */
  public MarginController(
      ObjectProvider<UpstoxMarginClient> client, ObjectProvider<UpstoxFnoMasterClient> master) {
    this.client = client;
    this.master = master;
  }

  /** One structured basket leg. {@code optionType} = {@code CE}/{@code PE}/{@code FUT}; strike null for a future. */
  public record MarginLeg(
      String exchange,
      String underlying,
      String optionType,
      LocalDate expiry,
      BigDecimal strike,
      int quantity,
      String side,
      String product) {}

  /** The basket request (≤20 legs). */
  public record MarginRequest(List<MarginLeg> legs) {}

  /** The margin response — {@code priced=false} carries a human reason (never a 5xx). */
  public record MarginResponse(
      boolean priced,
      String unpricedReason,
      BigDecimal spanMargin,
      BigDecimal exposureMargin,
      BigDecimal equityMargin,
      BigDecimal netBuyPremium,
      BigDecimal additionalMargin,
      BigDecimal totalMargin,
      BigDecimal requiredMargin,
      BigDecimal finalMargin) {

    static MarginResponse unpriced(String reason) {
      return new MarginResponse(false, reason, null, null, null, null, null, null, null, null);
    }

    static MarginResponse from(MarginQuote q) {
      return new MarginResponse(
          q.priced(), q.unpricedReason(), q.spanMargin(), q.exposureMargin(), q.equityMargin(),
          q.netBuyPremium(), q.additionalMargin(), q.totalMargin(), q.requiredMargin(),
          q.finalMargin());
    }
  }

  /** Computes the basket's SPAN+exposure margin, or an {@code unpriced} response on any gap. */
  @PostMapping("/api/v1/market/margin")
  public MarginResponse margin(@RequestBody MarginRequest request) {
    UpstoxMarginClient marginClient = client.getIfAvailable();
    UpstoxFnoMasterClient fnoMaster = master.getIfAvailable();
    if (marginClient == null || fnoMaster == null) {
      return MarginResponse.unpriced("margin service not configured (Upstox analytics token required)");
    }
    if (request == null || request.legs() == null || request.legs().isEmpty()) {
      return MarginResponse.unpriced("no legs");
    }
    List<UpstoxMarginClient.Leg> resolved = new ArrayList<>();
    for (MarginLeg leg : request.legs()) {
      String key =
          fnoMaster.keyFor(
              leg.exchange(), leg.underlying(), leg.optionType(), leg.expiry(), leg.strike());
      if (key == null) {
        return MarginResponse.unpriced(
            "no Upstox instrument for "
                + leg.underlying() + " " + leg.optionType() + " " + leg.expiry()
                + (leg.strike() == null ? "" : " " + leg.strike()));
      }
      resolved.add(
          new UpstoxMarginClient.Leg(
              key, leg.quantity(), leg.side(), leg.product() == null ? "D" : leg.product()));
    }
    return MarginResponse.from(marginClient.quote(resolved));
  }
}
