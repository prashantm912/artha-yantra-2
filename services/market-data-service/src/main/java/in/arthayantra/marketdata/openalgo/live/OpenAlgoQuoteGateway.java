package in.arthayantra.marketdata.openalgo.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.KiteCallExecutor;
import in.arthayantra.marketdata.kite.QuoteGateway;
import in.arthayantra.marketdata.openalgo.wire.OpenAlgoQuoteResponse;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * OpenAlgo spot quotes behind the {@link QuoteGateway} port (plan §3). OpenAlgo's {@code /quotes} is
 * a single-symbol {@code POST} with {@code apikey}+{@code symbol}+{@code exchange} in the JSON body
 * (contrast Kite's batched {@code GET}), so a multi-key request fans out one call per key through the
 * {@code OPENALGO_QUOTE} limiter family (B-3 resilience reused). WireMock-tested; the base URL is
 * configurable so the appliance can be stubbed.
 *
 * <p>OpenAlgo's quote omits open-interest — per-strike OI rides {@code /optionchain} (mapped via
 * {@link OpenAlgoMappers#legToQuote}); the {@code oi} returned here is always {@code null}.
 */
public class OpenAlgoQuoteGateway implements QuoteGateway {

  private final RestClient restClient;
  private final String apiKey;
  private final KiteCallExecutor executor;
  private final ObjectMapper objectMapper;

  /** Wires the wire client. */
  public OpenAlgoQuoteGateway(
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
  public Map<InstrumentKey, Quote> quotes(Collection<InstrumentKey> keys) {
    Map<InstrumentKey, Quote> out = new LinkedHashMap<>();
    for (InstrumentKey key : keys) {
      Quote quote = fetchOne(key);
      if (quote != null) {
        out.put(key, quote);
      }
    }
    return out;
  }

  private Quote fetchOne(InstrumentKey key) {
    OpenAlgoExchange.Target target = OpenAlgoExchange.map(key);
    String requestBody = body(target.symbol(), target.exchange());
    String responseBody =
        executor.execute(
            KiteCallExecutor.Family.OPENALGO_QUOTE,
            () ->
                restClient
                    .post()
                    .uri("/api/v1/quotes")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class));
    try {
      OpenAlgoQuoteResponse response =
          objectMapper.readValue(responseBody, OpenAlgoQuoteResponse.class);
      if (response.data() == null) {
        return null;
      }
      return OpenAlgoMappers.toQuote(key, response.data());
    } catch (Exception e) {
      throw new ApiException(
          502,
          ErrorCodes.OPENALGO_UPSTREAM_ERROR,
          "openalgo quote response parse failed: " + e.getMessage());
    }
  }

  private String body(String symbol, String exchange) {
    try {
      return objectMapper.writeValueAsString(
          Map.of("apikey", apiKey, "symbol", symbol, "exchange", exchange));
    } catch (JsonProcessingException e) {
      throw new ApiException(
          500, ErrorCodes.INTERNAL_ERROR, "openalgo request serialize failed: " + e.getMessage());
    }
  }
}
