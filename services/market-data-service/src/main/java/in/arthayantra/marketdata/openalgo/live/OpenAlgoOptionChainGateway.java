package in.arthayantra.marketdata.openalgo.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.KiteCallExecutor;
import in.arthayantra.marketdata.kite.OptionChainGateway;
import in.arthayantra.marketdata.openalgo.OpenAlgoSymbols;
import in.arthayantra.marketdata.openalgo.wire.OpenAlgoChainRow;
import in.arthayantra.marketdata.openalgo.wire.OpenAlgoLeg;
import in.arthayantra.marketdata.openalgo.wire.OpenAlgoOptionChainResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * OpenAlgo full option chain behind the {@link OptionChainGateway} port (plan §4.5 / §17.4). One
 * {@code POST /api/v1/optionchain} with {@code underlying} + {@code exchange} (the underlying's, via
 * {@link OpenAlgoExchange}) + the {@code DDMMMYY} {@code expiry_date} (via {@link OpenAlgoSymbols})
 * returns every strike's CE/PE legs incl. the load-bearing per-strike {@code oi} — the field this
 * whole integration exists to route (OpenAlgo's {@code /quotes} omits OI). The call rides the
 * {@code OPENALGO_QUOTE} limiter family. Greeks on the wire are ignored — IV/greeks come from
 * {@code libs/black76-math} only (§17.9). WireMock-tested; the base URL is configurable so the
 * appliance can be stubbed.
 */
public class OpenAlgoOptionChainGateway implements OptionChainGateway {

  private final RestClient restClient;
  private final String apiKey;
  private final KiteCallExecutor executor;
  private final ObjectMapper objectMapper;

  /** Wires the wire client. */
  public OpenAlgoOptionChainGateway(
      RestClient.Builder builder,
      String baseUrl,
      String apiKey,
      KiteCallExecutor executor,
      ObjectMapper objectMapper) {
    this.restClient = builder.baseUrl(baseUrl).build();
    this.apiKey = apiKey;
    this.executor = executor;
    this.objectMapper = objectMapper;
  }

  @Override
  public Chain fetch(InstrumentKey underlying, LocalDate expiry) {
    OpenAlgoExchange.Target target = OpenAlgoExchange.map(underlying);
    String requestBody = body(target.symbol(), target.exchange(), OpenAlgoSymbols.expiryToken(expiry));
    String responseBody =
        executor.execute(
            KiteCallExecutor.Family.OPENALGO_QUOTE,
            () ->
                restClient
                    .post()
                    .uri("/api/v1/optionchain")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class));
    try {
      OpenAlgoOptionChainResponse response =
          objectMapper.readValue(responseBody, OpenAlgoOptionChainResponse.class);
      List<Row> rows = new ArrayList<>();
      if (response.chain() != null) {
        for (OpenAlgoChainRow row : response.chain()) {
          rows.add(new Row(row.strike(), leg(row.ce()), leg(row.pe())));
        }
      }
      return new Chain(response.underlying(), expiry, response.underlyingLtp(), List.copyOf(rows));
    } catch (Exception e) {
      throw new ApiException(
          502,
          ErrorCodes.OPENALGO_UPSTREAM_ERROR,
          "openalgo optionchain response parse failed: " + e.getMessage());
    }
  }

  /** One CE/PE wire leg -> the venue-neutral port leg; {@code oi} (decimal) narrows to {@code Long}. */
  private static Leg leg(OpenAlgoLeg wire) {
    if (wire == null) {
      return null;
    }
    return new Leg(
        wire.ltp(),
        wire.bid(),
        wire.ask(),
        wire.volume(),
        wire.oi() != null ? wire.oi().longValue() : null);
  }

  private String body(String underlying, String exchange, String expiryDate) {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("apikey", apiKey);
    request.put("underlying", underlying);
    request.put("exchange", exchange);
    request.put("expiry_date", expiryDate);
    try {
      return objectMapper.writeValueAsString(request);
    } catch (JsonProcessingException e) {
      throw new ApiException(
          500, ErrorCodes.INTERNAL_ERROR, "openalgo request serialize failed: " + e.getMessage());
    }
  }
}
