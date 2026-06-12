package in.arthayantra.marketdata.kite.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import in.arthayantra.marketdata.kite.AccessTokenProvider;
import in.arthayantra.marketdata.kite.HistoricalCandleGateway;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.InstrumentTokenResolver;
import in.arthayantra.marketdata.kite.KiteCallExecutor;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

/**
 * Live OHLCV fetch over the Kite wire format (B-6 port 4/5): {@code GET
 * /instruments/historical/{token}/{interval}} with {@code continuous=1} for derivatives and
 * {@code oi=1}; timestamps arrive as {@code +0530} (no colon). Routed through the
 * {@link KiteCallExecutor} historical family (3/1s, retry, breaker). WireMock-tested.
 */
public class LiveHistoricalCandleGateway implements HistoricalCandleGateway {

  private static final DateTimeFormatter KITE_TS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
  private static final DateTimeFormatter KITE_PARAM =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final Set<String> DERIVATIVE_TYPES = Set.of("FUT", "CE", "PE");

  private final RestClient restClient;
  private final String apiKey;
  private final AccessTokenProvider tokenProvider;
  private final InstrumentTokenResolver tokenResolver;
  private final KiteCallExecutor executor;
  private final ObjectMapper objectMapper;

  /** Wires the wire client. */
  public LiveHistoricalCandleGateway(
      RestClient.Builder builder,
      String baseUrl,
      String apiKey,
      AccessTokenProvider tokenProvider,
      InstrumentTokenResolver tokenResolver,
      KiteCallExecutor executor,
      ObjectMapper objectMapper) {
    this.restClient = builder.baseUrl(baseUrl).build();
    this.apiKey = apiKey;
    this.tokenProvider = tokenProvider;
    this.tokenResolver = tokenResolver;
    this.executor = executor;
    this.objectMapper = objectMapper;
  }

  @Override
  public List<Candle> fetch(InstrumentKey key, String interval, Instant from, Instant to) {
    String accessToken =
        tokenProvider
            .currentToken()
            .orElseThrow(
                () ->
                    new ApiException(
                        401, ErrorCodes.KITE_TOKEN_EXPIRED, "no live Kite session for historical fetch"));
    InstrumentTokenResolver.TokenInfo info =
        tokenResolver
            .resolve(key)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        ErrorCodes.NOT_FOUND_INSTRUMENT, "unknown instrument " + key.canonical()));
    String kiteInterval =
        switch (interval) {
          case "1m" -> "minute";
          case "1d" -> "day";
          default -> throw new IllegalArgumentException("only 1m and 1d are fetched from Kite");
        };
    boolean derivative = DERIVATIVE_TYPES.contains(info.instrumentType());
    String fromParam = KITE_PARAM.format(OffsetDateTime.ofInstant(from, in.arthayantra.common.web.time.Ist.ZONE));
    String toParam = KITE_PARAM.format(OffsetDateTime.ofInstant(to, in.arthayantra.common.web.time.Ist.ZONE));

    String body =
        executor.execute(
            KiteCallExecutor.Family.HISTORICAL,
            () ->
                restClient
                    .get()
                    .uri(
                        uriBuilder ->
                            uriBuilder
                                .path("/instruments/historical/{token}/{interval}")
                                .queryParam("from", fromParam)
                                .queryParam("to", toParam)
                                .queryParam("oi", "1")
                                .queryParam("continuous", derivative ? "1" : "0")
                                .build(info.instrumentToken(), kiteInterval))
                    .header("X-Kite-Version", "3")
                    .header("Authorization", "token " + apiKey + ":" + accessToken)
                    .retrieve()
                    .onStatus(
                        status -> status.value() == 429,
                        (request, response) -> {
                          long retryAfterMs = parseRetryAfter(response.getHeaders().getFirst("Retry-After"));
                          throw new KiteCallExecutor.KiteRateLimitedException(retryAfterMs);
                        })
                    .onStatus(
                        HttpStatusCode::is5xxServerError,
                        (request, response) -> {
                          throw new org.springframework.web.client.HttpServerErrorException(
                              response.getStatusCode());
                        })
                    .body(String.class));
    return parse(key, interval, body);
  }

  private static long parseRetryAfter(String header) {
    if (header == null || header.isBlank()) {
      return 0;
    }
    try {
      return Long.parseLong(header.trim()) * 1_000;
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private List<Candle> parse(InstrumentKey key, String interval, String body) {
    try {
      JsonNode candles = objectMapper.readTree(body).path("data").path("candles");
      List<Candle> out = new ArrayList<>();
      for (JsonNode row : candles) {
        OffsetDateTime bucket = OffsetDateTime.parse(row.get(0).asText(), KITE_TS);
        out.add(
            new Candle(
                key,
                interval,
                bucket,
                new BigDecimal(row.get(1).asText()),
                new BigDecimal(row.get(2).asText()),
                new BigDecimal(row.get(3).asText()),
                new BigDecimal(row.get(4).asText()),
                row.get(5).asLong(),
                row.size() > 6 && !row.get(6).isNull() ? row.get(6).asLong() : null));
      }
      return out;
    } catch (Exception e) {
      throw new ApiException(
          502, ErrorCodes.KITE_UPSTREAM_ERROR, "historical response parse failed: " + e.getMessage());
    }
  }
}
