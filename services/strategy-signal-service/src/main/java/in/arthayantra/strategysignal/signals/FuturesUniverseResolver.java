package in.arthayantra.strategysignal.signals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * A7/A11 [FP-11a]: resolves {@code futures_of_underlying} to the ACTUAL front/next contract via
 * market-data's term-structure surface (Stage B Phase 15A). Roll re-subscribe: within
 * {@code roll_days_before_expiry} trading days of expiry the resolution slides one contract out
 * — the engine re-resolves daily at 08:40, so subscriptions follow the roll automatically. Live
 * always trades the actual contract; the continuous-series counterpart is replay territory.
 */
@Component
public class FuturesUniverseResolver {

  private static final Logger log = LoggerFactory.getLogger(FuturesUniverseResolver.class);
  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  /** Wires the market-data base URL. */
  public FuturesUniverseResolver(
      RestClient.Builder builder,
      ObjectMapper objectMapper,
      Clock clock,
      @Value("${artha.marketdata.base-url}") String baseUrl) {
    this.restClient = builder.baseUrl(baseUrl).build();
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  /** The contract(s) the strategy trades right now; empty on resolution failure. */
  public List<StrategyDefinition.InstrumentRef> resolve(
      String underlyingExchange, String underlying, String contract, int rollDaysBeforeExpiry) {
    try {
      String body =
          restClient
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/api/v1/market/futures/term-structure")
                          .queryParam("underlying", underlying)
                          .build())
              .retrieve()
              .body(String.class);
      JsonNode contracts = objectMapper.readTree(body).path("contracts");
      if (!contracts.isArray() || contracts.isEmpty()) {
        log.warn("no futures contracts for underlying {} — empty universe", underlying);
        return List.of();
      }
      int index = "next_month".equals(contract) ? 1 : 0;
      // roll window: within N days of the front expiry, slide one contract out
      JsonNode front = contracts.get(0);
      LocalDate expiry = LocalDate.parse(front.path("expiry").asText());
      LocalDate today = LocalDate.now(clock.withZone(IST));
      if (!today.isBefore(expiry.minusDays(rollDaysBeforeExpiry))) {
        index++;
        log.info(
            "futures roll window for {} (expiry {}, roll {} days out) — sliding to contract {}",
            underlying, expiry, rollDaysBeforeExpiry, index);
      }
      if (index >= contracts.size()) {
        index = contracts.size() - 1;
      }
      JsonNode chosen = contracts.get(index);
      return List.of(
          new StrategyDefinition.InstrumentRef(
              chosen.path("exchange").asText("NFO"), chosen.path("tradingsymbol").asText()));
    } catch (RestClientException | java.io.IOException e) {
      log.warn("futures universe resolution failed for {}: {}", underlying, e.getMessage());
      return List.of();
    }
  }
}
