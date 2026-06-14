package in.arthayantra.backtest.client;

import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Best-effort candle warmer over market-data-service's cache-first {@code GET
 * /api/v1/market/candles} (the only thing that backfills from Kite/mock into the shared {@code
 * marketdata} store the backtest {@link in.arthayantra.backtest.replay.CandleReader} then reads).
 * Backtest replay never fetches on demand, so a fresh DB / uncovered window reads empty — the
 * §D.6 pre-flight then reports a {@code DATA_GAP}. Warming the series before the pre-flight (and
 * again before replay, for context series) turns that into a populated store on the mock stack with
 * zero Kite credentials (the mock gateway backfills history). NEVER throws: a warm miss simply
 * leaves the existing pre-flight to surface the real coverage error.
 */
@Component
public class MarketDataClient {

  private static final Logger log = LoggerFactory.getLogger(MarketDataClient.class);

  private final RestClient http;

  /** Builds the client against the market-data base URL. */
  public MarketDataClient(@Value("${artha.marketdata.base-url}") String baseUrl) {
    this.http = RestClient.builder().baseUrl(baseUrl).build();
  }

  /**
   * Triggers a cache-first backfill of {@code [from, to]} for one series; the response body is
   * discarded (we warm the store, not read it here). Best-effort — any failure is logged at debug.
   */
  public void warm(
      String exchange,
      String tradingsymbol,
      String interval,
      OffsetDateTime from,
      OffsetDateTime to) {
    try {
      http.get()
          .uri(
              uri ->
                  uri.path("/api/v1/market/candles")
                      .queryParam("exchange", exchange)
                      .queryParam("tradingsymbol", tradingsymbol)
                      .queryParam("interval", interval)
                      .queryParam("from", from.toString())
                      .queryParam("to", to.toString())
                      .queryParam("limit", 5000)
                      .build())
          .retrieve()
          .toBodilessEntity();
    } catch (RuntimeException e) {
      log.debug(
          "market-data warm miss {}:{} {} [{}..{}]: {}",
          exchange,
          tradingsymbol,
          interval,
          from,
          to,
          e.toString());
    }
  }
}
