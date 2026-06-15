package in.arthayantra.marketdata.kite.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketdata.kite.AccessTokenProvider;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.KiteCallExecutor;
import in.arthayantra.marketdata.kite.QuoteGateway;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;

/**
 * Live batched quotes over the Kite wire format ({@code GET /quote?i=NFO:SYM&...}), split into
 * {@code <= KITE_QUOTE_BATCH_SIZE} batches (default 250) and routed through the QUOTE limiter
 * family (1/1s — a chain refresh is 2–4 calls, B-3). WireMock-tested.
 */
public class LiveQuoteGateway implements QuoteGateway {

  private final RestClient restClient;
  private final String apiKey;
  private final AccessTokenProvider tokenProvider;
  private final KiteCallExecutor executor;
  private final ObjectMapper objectMapper;
  private final int batchSize;

  /** Wires the wire client. */
  public LiveQuoteGateway(
      RestClient.Builder builder,
      String baseUrl,
      String apiKey,
      AccessTokenProvider tokenProvider,
      KiteCallExecutor executor,
      ObjectMapper objectMapper,
      int batchSize) {
    this.restClient = builder.baseUrl(baseUrl).build();
    this.apiKey = apiKey;
    this.tokenProvider = tokenProvider;
    this.executor = executor;
    this.objectMapper = objectMapper;
    this.batchSize = batchSize;
  }

  @Override
  public Map<InstrumentKey, Quote> quotes(Collection<InstrumentKey> keys) {
    String accessToken =
        tokenProvider
            .currentToken()
            .orElseThrow(
                () ->
                    new ApiException(
                        401, ErrorCodes.KITE_TOKEN_EXPIRED, "no live Kite session for quotes"));
    Map<InstrumentKey, Quote> out = new LinkedHashMap<>();
    Iterator<InstrumentKey> iterator = keys.iterator();
    while (iterator.hasNext()) {
      List<InstrumentKey> batch = new ArrayList<>(batchSize);
      while (iterator.hasNext() && batch.size() < batchSize) {
        batch.add(iterator.next());
      }
      fetchBatch(batch, accessToken, out);
    }
    return out;
  }

  private void fetchBatch(
      List<InstrumentKey> batch, String accessToken, Map<InstrumentKey, Quote> out) {
    String body =
        executor.execute(
            KiteCallExecutor.Family.QUOTE,
            () ->
                restClient
                    .get()
                    .uri(
                        uriBuilder -> {
                          uriBuilder.path("/quote");
                          for (InstrumentKey key : batch) {
                            uriBuilder.queryParam("i", key.canonical());
                          }
                          return uriBuilder.build();
                        })
                    .header("X-Kite-Version", "3")
                    .header("Authorization", "token " + apiKey + ":" + accessToken)
                    .retrieve()
                    .body(String.class));
    try {
      JsonNode data = objectMapper.readTree(body).path("data");
      for (InstrumentKey key : batch) {
        JsonNode q = data.path(key.canonical());
        if (q.isMissingNode()) {
          continue;
        }
        JsonNode ohlcNode = q.path("ohlc");
        Quote.Ohlc ohlc =
            ohlcNode.isMissingNode()
                ? null
                : new Quote.Ohlc(
                    dec(ohlcNode, "open"),
                    dec(ohlcNode, "high"),
                    dec(ohlcNode, "low"),
                    dec(ohlcNode, "close"));
        out.put(
            key,
            new Quote(
                key,
                new BigDecimal(q.path("last_price").asText("0")),
                new BigDecimal(q.path("depth").path("buy").path(0).path("price").asText("0")),
                new BigDecimal(q.path("depth").path("sell").path(0).path("price").asText("0")),
                q.path("volume").isMissingNode() ? null : q.path("volume").asLong(),
                q.path("oi").isMissingNode() ? null : q.path("oi").asLong(),
                ohlc,
                OffsetDateTime.now(ZoneOffset.UTC)));
      }
    } catch (Exception e) {
      throw new ApiException(
          502, ErrorCodes.KITE_UPSTREAM_ERROR, "quote response parse failed: " + e.getMessage());
    }
  }

  /** A nullable decimal field of {@code parent} — {@code null} when absent/blank (no zero coercion). */
  private static BigDecimal dec(JsonNode parent, String field) {
    JsonNode node = parent.path(field);
    return node.isMissingNode() || node.isNull() || node.asText("").isBlank()
        ? null
        : new BigDecimal(node.asText());
  }
}
