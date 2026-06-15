package in.arthayantra.marketdata.kite.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketdata.kite.AccessTokenProvider;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.KiteCallExecutor;
import in.arthayantra.marketdata.kite.QuoteGateway;
import in.arthayantra.marketdata.kite.wire.KiteQuote;
import in.arthayantra.marketdata.kite.wire.KiteQuoteResponse;
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
      KiteQuoteResponse response = objectMapper.readValue(body, KiteQuoteResponse.class);
      Map<String, KiteQuote> data = response.data() == null ? Map.of() : response.data();
      for (InstrumentKey key : batch) {
        KiteQuote quote = data.get(key.canonical());
        if (quote != null) {
          out.put(key, toDomain(key, quote));
        }
      }
    } catch (Exception e) {
      throw new ApiException(
          502, ErrorCodes.KITE_UPSTREAM_ERROR, "quote response parse failed: " + e.getMessage());
    }
  }

  /**
   * Maps a Kite wire quote to the domain {@link Quote}; a missing {@code last_price} or depth side
   * defaults to zero, matching Kite's index feeds (which omit depth) and the prior hand-parse.
   */
  private static Quote toDomain(InstrumentKey key, KiteQuote quote) {
    KiteQuote.Ohlc wireOhlc = quote.ohlc();
    Quote.Ohlc ohlc =
        wireOhlc == null
            ? null
            : new Quote.Ohlc(wireOhlc.open(), wireOhlc.high(), wireOhlc.low(), wireOhlc.close());
    return new Quote(
        key,
        quote.lastPrice() != null ? quote.lastPrice() : BigDecimal.ZERO,
        firstPrice(quote.depth() == null ? null : quote.depth().buy()),
        firstPrice(quote.depth() == null ? null : quote.depth().sell()),
        quote.volume(),
        quote.oi() != null ? quote.oi().longValue() : null,
        ohlc,
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  /** Best price of a depth side, or zero when the side is absent/empty. */
  private static BigDecimal firstPrice(List<KiteQuote.Depth.Level> side) {
    if (side == null || side.isEmpty() || side.get(0).price() == null) {
      return BigDecimal.ZERO;
    }
    return side.get(0).price();
  }
}
