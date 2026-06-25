package in.arthayantra.backtest.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
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
      // URI-template variables (not a UriBuilder lambda): RestClient strictly encodes them, so the
      // `+05:30` offset in the ISO timestamps becomes %2B — a raw `+` would be decoded server-side
      // as a space and 400 the request, silently defeating the warm.
      http.get()
          .uri(
              "/api/v1/market/candles?exchange={e}&tradingsymbol={s}&interval={i}"
                  + "&from={f}&to={t}&limit=5000",
              exchange,
              tradingsymbol,
              interval,
              from.toString(),
              to.toString())
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

  /**
   * Reads the historical "Connecting Dots" per-interval sentiment matrix for an index session — the
   * OI-confluence the {@link in.arthayantra.backtest.analytics.OiAttributionService post-hoc trade
   * attribution} joins each trade's entry against. {@code name} is the index (e.g. {@code NIFTY 50}),
   * {@code interval} the OI token ({@code 5m}); reads stored snapshots only (no live capture), so a
   * session with no backfilled/captured OI returns an empty row list. Best-effort: a transport/4xx
   * failure yields an empty list (the attribution then marks the session uncovered, never throws).
   */
  public CdResponse connectingDots(String name, LocalDate date, String interval) {
    try {
      CdResponse body =
          http.get()
              .uri(
                  "/api/v1/market/connecting-dots?mode=history&name={n}&date={d}&interval={i}",
                  name,
                  date.toString(),
                  interval)
              .retrieve()
              .body(CdResponse.class);
      return body == null || body.rows() == null ? CdResponse.EMPTY : body;
    } catch (RuntimeException e) {
      log.debug("connecting-dots miss {} {} {}: {}", name, date, interval, e.toString());
      return CdResponse.EMPTY;
    }
  }

  /**
   * Mirror of market-data's {@code ConnectingDots} envelope. {@code derived} = the OI factors were
   * reconstructed from candles (no captured snapshots that session) — surfaced so the attribution can
   * badge a run whose OI is candle-derived rather than live-captured.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CdResponse(boolean derived, List<CdRow> rows) {
    public static final CdResponse EMPTY = new CdResponse(false, List.of());

    /** Null-safe row accessor. */
    public List<CdRow> rowsOrEmpty() {
      return rows == null ? List.of() : rows;
    }
  }

  /**
   * One interval row: the {@code HH:mm-HH:mm} IST bucket label, the 5-state composite {@code trend}
   * (0 Ext.Bearish … 4 Ext.Bullish), and the 11 three-state factor codes (0 Neutral / 1 Bullish / 2
   * Bearish) used to derive the net confluence vote.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CdRow(
      String timeInterval,
      int trend,
      int dow,
      int vix,
      int volume,
      int activeStrikeIv,
      int activeStrikeOi,
      int futOi,
      int vwap,
      int supertrend,
      int rsi,
      int futPrice,
      int dailyTrend) {

    /** Net confluence vote = #bullish − #bearish across the 11 factors (factor code 1 vs 2). */
    public int net() {
      int[] f = {
        dow, vix, volume, activeStrikeIv, activeStrikeOi, futOi, vwap, supertrend, rsi, futPrice,
        dailyTrend
      };
      int net = 0;
      for (int v : f) {
        if (v == 1) {
          net++;
        } else if (v == 2) {
          net--;
        }
      }
      return net;
    }
  }
}
