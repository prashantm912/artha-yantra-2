package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.ContractInfoClient.ContractInfo;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * RestClient call to the §8 SPAN appliance {@code POST {margin.base}/api/v1/margin/size}. Gated by
 * {@code artha.margin.span-enabled} (default false); when disabled it short-circuits to
 * {@link Optional#empty()} without any network call, so the flat-pct fallback runs. Any failure
 * (appliance down, 503 no-{@code .spn}, unresolved leg) also returns empty — never throws, never blocks.
 */
@Component
public class RestMarginServiceClient implements MarginServiceClient {

  private static final Logger log = LoggerFactory.getLogger(RestMarginServiceClient.class);

  private final boolean enabled;
  private final RestClient restClient;
  private final ContractInfoClient contracts;

  /** Wires the configured margin-service base URL + the capability flag. */
  public RestMarginServiceClient(
      RestClient.Builder builder,
      ContractInfoClient contracts,
      @Value("${artha.margin.span-enabled:false}") boolean enabled,
      @Value("${artha.margin.base:http://margin-service:8086}") String baseUrl) {
    this.enabled = enabled;
    this.contracts = contracts;
    this.restClient = builder.baseUrl(baseUrl).build();
  }

  @Override
  public Optional<MarginEstimate> marginFor(
      String exchange, String tradingsymbol, String side, BigDecimal price, long qty) {
    if (!enabled) {
      return Optional.empty(); // capability off: flat-pct fallback, no network call
    }
    Optional<ContractInfo> info = contracts.contract(exchange, tradingsymbol);
    if (info.isEmpty() || info.get().instrumentClass() == InstrumentClass.EQUITY) {
      return Optional.empty(); // not a derivative / unresolved leg -> fall back
    }
    ContractInfo c = info.get();
    String optionType = c.instrumentClass() == InstrumentClass.FUTURE ? "FUT" : c.optionType();
    if (optionType == null) {
      return Optional.empty();
    }
    // Sizing endpoint: we only need the marginRequired for the supplied qty, so pass a single lot of
    // size=qty and budget-rails large enough that the margin (not a rail) bounds the basket; the
    // returned marginRequired is the SPAN total for the leg. capital/dailyLossCap are placeholders —
    // the paper buying-power check applies the equity/free-cash gate itself (this is advisory margin).
    Map<String, Object> body =
        Map.of(
            "capital", "100000000",
            "maxCapitalPct", "1.0",
            "dailyLossUsed", "0",
            "dailyLossCap", "100000000",
            "entry", price.toPlainString(),
            "stop", price.multiply(new BigDecimal("0.5")).toPlainString(),
            "rrTarget", "2.0",
            "legs",
                List.of(
                    Map.of(
                        "symbol", c.spotSymbol(),
                        "expiry", c.expiry().toString(),
                        "strike", c.strike() == null ? 0 : c.strike(),
                        "optionType", optionType,
                        "side", side,
                        "lotSize", (int) qty)));
    try {
      SizeResponse resp =
          restClient
              .post()
              .uri("/api/v1/margin/size")
              .body(body)
              .retrieve()
              .body(SizeResponse.class);
      if (resp == null || resp.marginRequired() == null) {
        return Optional.empty();
      }
      return Optional.of(new MarginEstimate(new BigDecimal(resp.marginRequired())));
    } catch (RestClientException | NumberFormatException e) {
      log.warn(
          "SPAN margin sizing unavailable for {}:{} — flat-pct fallback: {}",
          exchange,
          tradingsymbol,
          e.getMessage());
      return Optional.empty();
    }
  }

  /** The single field of the §8.4 /margin/size response this client consumes (decimal-as-string). */
  private record SizeResponse(String marginRequired) {}
}
