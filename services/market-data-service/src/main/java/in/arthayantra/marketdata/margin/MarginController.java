package in.arthayantra.marketdata.margin;

import in.arthayantra.marketdata.instruments.Instrument;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import in.arthayantra.marketdata.upstox.UpstoxFnoMasterClient;
import in.arthayantra.marketdata.upstox.UpstoxMarginClient;
import in.arthayantra.marketdata.upstox.UpstoxMarginClient.MarginQuote;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pre-trade SPAN margin for an F&amp;O basket (F9 risk-layer SPAN source). Resolves each leg to its
 * Upstox {@code instrument_key} off the F&amp;O master, then asks Upstox to compute SPAN+exposure
 * server-side ({@link UpstoxMarginClient}) — no {@code .spn} file. Typed record in/out (never a
 * {@code Map} — the contract ratchet). Both Upstox collaborators resolve via {@link ObjectProvider},
 * so when the analytics token is off (mock stack, Kite-only live) the endpoint returns an {@code
 * unpriced} response instead of 500-ing — advisory only, fail-soft.
 *
 * <p>A leg may be specified either STRUCTURALLY ({@code underlying}/{@code optionType}/{@code
 * expiry}/{@code strike}) or by {@code tradingsymbol} — the latter is resolved to those fields from
 * the instrument master, so a caller holding only an option symbol (e.g. the paper book's margin
 * heat read) needn't re-derive the tuple.
 */
@RestController
public class MarginController {

  private final ObjectProvider<UpstoxMarginClient> client;
  private final ObjectProvider<UpstoxFnoMasterClient> master;
  private final InstrumentRepository instruments;

  /** Wires the optional Upstox collaborators + the instrument master. */
  public MarginController(
      ObjectProvider<UpstoxMarginClient> client,
      ObjectProvider<UpstoxFnoMasterClient> master,
      InstrumentRepository instruments) {
    this.client = client;
    this.master = master;
    this.instruments = instruments;
  }

  /**
   * One basket leg. Either give the structured tuple ({@code underlying}/{@code optionType}/{@code
   * expiry}/{@code strike}) OR just {@code tradingsymbol} (resolved from the instrument master).
   * {@code optionType} = {@code CE}/{@code PE}/{@code FUT}; strike null for a future.
   */
  public record MarginLeg(
      String exchange,
      String underlying,
      String optionType,
      LocalDate expiry,
      BigDecimal strike,
      int quantity,
      String side,
      String product,
      String tradingsymbol) {}

  /** The basket request (≤20 legs). */
  public record MarginRequest(List<MarginLeg> legs) {}

  /** The margin response — {@code priced=false} carries a human reason (never a 5xx). */
  public record MarginResponse(
      boolean priced,
      @Schema(types = {"string", "null"}) String unpricedReason,
      @Schema(types = {"number", "null"}) BigDecimal spanMargin,
      @Schema(types = {"number", "null"}) BigDecimal exposureMargin,
      @Schema(types = {"number", "null"}) BigDecimal equityMargin,
      @Schema(types = {"number", "null"}) BigDecimal netBuyPremium,
      @Schema(types = {"number", "null"}) BigDecimal additionalMargin,
      @Schema(types = {"number", "null"}) BigDecimal totalMargin,
      @Schema(types = {"number", "null"}) BigDecimal requiredMargin,
      @Schema(types = {"number", "null"}) BigDecimal finalMargin) {

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
      // A tradingsymbol-only leg is resolved to its structured tuple from the instrument master.
      String underlying = leg.underlying();
      String optionType = leg.optionType();
      LocalDate expiry = leg.expiry();
      BigDecimal strike = leg.strike();
      if (leg.tradingsymbol() != null && underlying == null) {
        Optional<Instrument> row = instruments.findByKey(leg.exchange(), leg.tradingsymbol());
        if (row.isEmpty()) {
          return MarginResponse.unpriced(
              "unknown instrument " + leg.exchange() + ":" + leg.tradingsymbol());
        }
        Instrument i = row.get();
        underlying = i.name();
        optionType = i.instrumentType();
        expiry = i.expiry();
        strike = i.strike();
      }
      String key = fnoMaster.keyFor(leg.exchange(), underlying, optionType, expiry, strike);
      if (key == null) {
        return MarginResponse.unpriced(
            "no Upstox instrument for "
                + underlying + " " + optionType + " " + expiry
                + (strike == null ? "" : " " + strike));
      }
      resolved.add(
          new UpstoxMarginClient.Leg(
              key, leg.quantity(), leg.side(), leg.product() == null ? "D" : leg.product()));
    }
    return MarginResponse.from(marginClient.quote(resolved));
  }
}
