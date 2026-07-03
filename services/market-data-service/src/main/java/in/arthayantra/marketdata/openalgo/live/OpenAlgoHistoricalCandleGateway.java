package in.arthayantra.marketdata.openalgo.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.kite.HistoricalCandleGateway;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.KiteCallExecutor;
import in.arthayantra.marketdata.openalgo.wire.OpenAlgoHistoryResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * OpenAlgo historical OHLCV behind the {@link HistoricalCandleGateway} port (plan §3). OpenAlgo's
 * {@code /history} is a {@code POST} with {@code apikey}/{@code symbol}/{@code exchange}/{@code
 * interval}/{@code start_date}/{@code end_date} in the JSON body; routed through the
 * {@code OPENALGO_HISTORICAL} limiter family (B-3, with 429 Retry-After honored). WireMock-tested.
 *
 * <p>OpenAlgo intervals: {@code 1m/3m/5m/10m/15m/30m/1h/D}. This port maps the two intervals the
 * Kite path fetches: {@code 1m -> 1m}, {@code 1d -> D}. {@code start_date}/{@code end_date} are
 * formatted {@code yyyy-MM-dd} in IST — VERIFY the exact format (and whether intraday needs a
 * datetime range) against the pinned appliance in Phase 1.
 */
public class OpenAlgoHistoricalCandleGateway implements HistoricalCandleGateway {

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

  private final RestClient restClient;
  private final String apiKey;
  private final KiteCallExecutor executor;
  private final ObjectMapper objectMapper;

  /** Wires the wire client. */
  public OpenAlgoHistoricalCandleGateway(
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
  public List<Candle> fetch(InstrumentKey key, String interval, Instant from, Instant to) {
    OpenAlgoExchange.Target target = OpenAlgoExchange.map(key);
    String openAlgoInterval =
        switch (interval) {
          case "1m" -> "1m";
          case "1d" -> "D";
          default -> throw new IllegalArgumentException("only 1m and 1d are fetched from OpenAlgo");
        };
    String start = DATE.format(OffsetDateTime.ofInstant(from, Ist.ZONE));
    String end = DATE.format(OffsetDateTime.ofInstant(to, Ist.ZONE));
    String requestBody = body(target.symbol(), target.exchange(), openAlgoInterval, start, end);

    String responseBody =
        executor.execute(
            KiteCallExecutor.Family.OPENALGO_HISTORICAL,
            () ->
                restClient
                    .post()
                    .uri("/api/v1/history")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .onStatus(
                        status -> status.value() == 429,
                        (request, response) -> {
                          long retryAfterMs =
                              parseRetryAfter(response.getHeaders().getFirst("Retry-After"));
                          throw new KiteCallExecutor.KiteRateLimitedException(retryAfterMs);
                        })
                    .onStatus(
                        HttpStatusCode::is5xxServerError,
                        (request, response) -> {
                          throw new org.springframework.web.client.HttpServerErrorException(
                              response.getStatusCode());
                        })
                    .body(String.class));
    return parse(key, interval, responseBody);
  }

  @Override
  public String sourceLabel() {
    return "OPENALGO";
  }

  private List<Candle> parse(InstrumentKey key, String interval, String responseBody) {
    try {
      OpenAlgoHistoryResponse response =
          objectMapper.readValue(responseBody, OpenAlgoHistoryResponse.class);
      List<Candle> out = new ArrayList<>();
      if (response.data() == null) {
        return out;
      }
      for (var candle : response.data()) {
        out.add(OpenAlgoMappers.toCandle(key, interval, candle));
      }
      return out;
    } catch (Exception e) {
      throw new ApiException(
          502,
          ErrorCodes.OPENALGO_UPSTREAM_ERROR,
          "openalgo historical response parse failed: " + e.getMessage());
    }
  }

  private String body(
      String symbol, String exchange, String interval, String start, String end) {
    try {
      return objectMapper.writeValueAsString(
          Map.of(
              "apikey", apiKey,
              "symbol", symbol,
              "exchange", exchange,
              "interval", interval,
              "start_date", start,
              "end_date", end));
    } catch (JsonProcessingException e) {
      throw new ApiException(
          500, ErrorCodes.INTERNAL_ERROR, "openalgo request serialize failed: " + e.getMessage());
    }
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
}
