package in.arthayantra.marketdata.kite.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import in.arthayantra.marketdata.kite.AccessTokenProvider;
import in.arthayantra.marketdata.kite.HistoricalCandleGateway;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.InstrumentTokenResolver;
import in.arthayantra.marketdata.kite.KiteCallExecutor;
import in.arthayantra.marketdata.kite.wire.KiteCandle;
import in.arthayantra.marketdata.kite.wire.KiteHistoricalResponse;
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

  private static final DateTimeFormatter KITE_PARAM =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  // continuous=1 ONLY for options AT THE DAY INTERVAL: Kite rejects continuous=1 with the minute
  // interval ("invalid interval for continuous data", 400), so option 1m fetches (straddle/options
  // charts) MUST send continuous=0 — a specific live contract's intraday candles want no stitching
  // anyway. For FUT it is Kite's roll-unaware stitched series, which B-18/B-19 explicitly forbid —
  // per-contract FUT history must stay per-contract (15A/15B). (live-verified 2026-06-23)
  private static final Set<String> CONTINUOUS_TYPES = Set.of("CE", "PE");

  // ⚠️ NEW-14 — AND ONLY ON NFO. Kite refuses continuous data for the BSE derivatives segment with
  // a 400 "invalid segment for continuous data", so every 1d fetch for a BFO SENSEX option failed
  // permanently. Measured 2026-09-02 15:45 IST: five of them, from the EOD backfill pass asking for
  // 1d on every subscribed key, were enough to open the SHARED kite-rest breaker (COUNT_BASED over
  // 10 at 50% — the breaker is most fragile when traffic is lowest). #1567 stopped a permanent 400
  // from being read as an unavailable upstream; this stops the request being made at all.
  //
  // ⚠️ EXCHANGE, never `segment`. TokenInfo carries a segment and "BFO-OPT" reads like the obvious
  // discriminator — but 175 766 of our NFO option rows and 6 423 BFO ones carry an EMPTY segment
  // (measured live 2026-09-03), so gating on it would silently send continuous=0 for most of the
  // NFO path that works today. The exchange is always populated.
  //
  // ⚠️ Allow-list, not a BFO deny-list: only NFO is measured to serve continuous data (1 289
  // source='KITE' option 1d bars against ZERO for BFO). MCX and CDS may well serve it too, but we
  // trade neither, and a deny-list would hand any future exchange the failing default.
  private static final Set<String> CONTINUOUS_EXCHANGES = Set.of("NFO");

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
    // continuous=1 is valid only for NFO options on the DAY interval. The minute interval 400s
    // ("invalid interval for continuous data" — the bug that blanked the option-premium charts) and
    // so does the BSE derivatives segment ("invalid segment for continuous data" — NEW-14).
    boolean useContinuous =
        CONTINUOUS_TYPES.contains(info.instrumentType())
            && "day".equals(kiteInterval)
            && CONTINUOUS_EXCHANGES.contains(key.exchange());
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
                                .queryParam("continuous", useContinuous ? "1" : "0")
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
      KiteHistoricalResponse response = objectMapper.readValue(body, KiteHistoricalResponse.class);
      List<Candle> out = new ArrayList<>();
      if (response.data() == null || response.data().candles() == null) {
        return out;
      }
      for (var row : response.data().candles()) {
        KiteCandle candle = KiteCandle.of(row);
        out.add(
            new Candle(
                key,
                interval,
                candle.timestamp(),
                candle.open(),
                candle.high(),
                candle.low(),
                candle.close(),
                candle.volume(),
                candle.openInterest()));
      }
      return out;
    } catch (Exception e) {
      throw new ApiException(
          502, ErrorCodes.KITE_UPSTREAM_ERROR, "historical response parse failed: " + e.getMessage());
    }
  }
}
