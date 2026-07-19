package in.arthayantra.strategysignal.paper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The expiry-session settlement spot via market-data REST (D8 — this service holds no marketdata
 * grant), mirroring {@link RestContractInfoClient}: {@code GET /api/v1/market/candles} for the {@code
 * 1d} bar on the expiry date. The bar's close is the day's settlement reference; empty when that
 * session was never captured. Fail-soft (an upstream error reads as "not captured" — the caller then
 * leaves the position durably OPEN, never fabricated).
 */
@Component
public class RestExpirySpotReader implements ExpirySpotReader {

  private static final Logger log = LoggerFactory.getLogger(RestExpirySpotReader.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final RestClient restClient;

  /** Wires the configured market-data base URL. */
  public RestExpirySpotReader(
      RestClient.Builder builder, @Value("${artha.marketdata.base-url}") String baseUrl) {
    this.restClient = builder.baseUrl(baseUrl).build();
  }

  @Override
  public Optional<BigDecimal> expirySessionClose(
      String exchange, String tradingsymbol, LocalDate session) {
    OffsetDateTime from = session.atStartOfDay(IST).toOffsetDateTime();
    OffsetDateTime to = session.plusDays(1).atStartOfDay(IST).toOffsetDateTime();
    try {
      CandlesResponse resp =
          restClient
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/api/v1/market/candles")
                          .queryParam("exchange", exchange)
                          .queryParam("tradingsymbol", tradingsymbol)
                          .queryParam("interval", "1d")
                          // UTC instants (…Z), never the +05:30 offset form: a literal '+' in a
                          // query value decodes as a space and 500s the read (the
                          // MarketDataCandlesClient lesson). Same instant, no '+' to corrupt.
                          .queryParam("from", from.toInstant().toString())
                          .queryParam("to", to.toInstant().toString())
                          // Raw close — no split/bonus adjustment (indices have none; any adjust
                          // would only apply to equities and is not the settlement reference).
                          .queryParam("adjust", "none")
                          .build())
              .retrieve()
              .body(CandlesResponse.class);
      if (resp == null || resp.items() == null || resp.items().isEmpty()) {
        return Optional.empty();
      }
      // The last captured 1d bar within the session — its close is the expiry-day settlement spot.
      String close = resp.items().get(resp.items().size() - 1).close();
      return close == null ? Optional.empty() : Optional.of(new BigDecimal(close));
    } catch (RestClientException | NumberFormatException e) {
      log.warn(
          "expiry-session close lookup failed for {}:{} on {}: {}",
          exchange, tradingsymbol, session, e.getMessage());
      return Optional.empty();
    }
  }

  /** The one candle field the settlement reads (prices serialize as decimal strings). */
  private record CandleDto(String close) {}

  /** The {@code /candles} envelope — only {@code items} is read. */
  private record CandlesResponse(List<CandleDto> items) {}
}
