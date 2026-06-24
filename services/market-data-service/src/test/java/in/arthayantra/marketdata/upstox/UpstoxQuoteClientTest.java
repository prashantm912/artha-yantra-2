package in.arthayantra.marketdata.upstox;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import in.arthayantra.marketdata.constituents.StockUpstoxKeyMap;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import in.arthayantra.marketdata.options.UpstoxQuoteGateway;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * WireMock test for the Wave-U2 direct-Upstox quote path: the {@link UpstoxQuoteClient} sends the
 * analytics Bearer token + the batched {@code instrument_key} CSV to {@code
 * /v2/market-quote/quotes}, and the {@link UpstoxQuoteGateway} adapter maps the wire ticks to the
 * domain {@link QuoteGateway.Quote} — spot index + futures LTP/OHLC/volume/OI + best bid/ask.
 */
class UpstoxQuoteClientTest {

  private static WireMockServer wireMock;

  // Two instruments in ONE batch response: a NIFTY index (no OI) and a NIFTY FUT (with OI). Upstox
  // keys the data map by the ':'-delimited variant of the request '|' key.
  private static final String QUOTES_BODY =
      """
      {"status":"success","data":{
        "NSE_INDEX:Nifty 50":{
          "instrument_token":"NSE_INDEX|Nifty 50","symbol":"Nifty 50","last_price":23845.85,
          "net_change":112.4,"volume":0,"average_price":23800.0,
          "ohlc":{"open":23733.45,"high":23901.0,"low":23710.2,"close":23733.45},
          "depth":{"buy":[],"sell":[]},
          "total_buy_quantity":0,"total_sell_quantity":0,
          "lower_circuit_limit":21449.0,"upper_circuit_limit":26129.0,
          "last_trade_time":"2026-06-24T15:30:00+05:30"},
        "NSE_FO:53001":{
          "instrument_token":"NSE_FO|53001","symbol":"NIFTY 26 JUN FUT","last_price":23870.5,
          "net_change":120.0,"volume":12500000,"average_price":23855.0,"oi":11250000,
          "ohlc":{"open":23760.0,"high":23925.0,"low":23740.0,"close":23750.5},
          "depth":{"buy":[{"price":23870.0,"quantity":75,"orders":3}],
                   "sell":[{"price":23871.0,"quantity":150,"orders":5}]},
          "oi_day_high":11500000,"oi_day_low":11000000}
      }}
      """;

  @BeforeAll
  static void start() {
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
  }

  @AfterAll
  static void stop() {
    wireMock.stop();
  }

  @BeforeEach
  void reset() {
    wireMock.resetAll();
  }

  private static UpstoxQuoteClient client() {
    return new UpstoxQuoteClient(
        RestClient.builder(),
        new UpstoxAnalyticsProperties(wireMock.baseUrl(), null, "test-token"));
  }

  @Test
  void mapsBatchWireQuotesToTicksAndSendsBearerTokenAndCsv() {
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/market-quote/quotes"))
            .willReturn(
                aResponse().withHeader("Content-Type", "application/json").withBody(QUOTES_BODY)));

    // The client takes RAW Upstox instrument keys — index + FUT in one batch call.
    Map<String, UpstoxQuoteClient.Tick> ticks =
        client().quotes(List.of("NSE_INDEX|Nifty 50", "NSE_FO|53001"));

    assertThat(ticks).hasSize(2);

    // Index leg — LTP + day OHLC; no OI, no depth → bid/ask null.
    UpstoxQuoteClient.Tick index = ticks.get("NSE_INDEX|Nifty 50");
    assertThat(index.lastPrice()).isEqualByComparingTo("23845.85");
    assertThat(index.open()).isEqualByComparingTo("23733.45");
    assertThat(index.high()).isEqualByComparingTo("23901.0");
    assertThat(index.low()).isEqualByComparingTo("23710.2");
    assertThat(index.prevClose()).isEqualByComparingTo("23733.45");
    assertThat(index.oi()).isNull();
    assertThat(index.bid()).isNull();
    assertThat(index.ask()).isNull();

    // FUT leg — LTP + OI + volume + best bid/ask from top-of-book depth.
    UpstoxQuoteClient.Tick fut = ticks.get("NSE_FO|53001");
    assertThat(fut.lastPrice()).isEqualByComparingTo("23870.5");
    assertThat(fut.oi()).isEqualTo(11250000L);
    assertThat(fut.volume()).isEqualTo(12500000L);
    assertThat(fut.bid()).isEqualByComparingTo("23870.0");
    assertThat(fut.ask()).isEqualByComparingTo("23871.0");
    assertThat(fut.prevClose()).isEqualByComparingTo("23750.5");

    // ONE batch call carrying the comma-separated keys + the analytics Bearer token (no fan-out).
    wireMock.verify(
        getRequestedFor(urlPathEqualTo("/v2/market-quote/quotes"))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .withQueryParam("instrument_key", equalTo("NSE_INDEX|Nifty 50,NSE_FO|53001")));
    assertThat(wireMock.getAllServeEvents()).as("single batch call").hasSize(1);
  }

  @Test
  void gatewayMapsIndexToDomainQuoteAndOmitsUnmappableFut() {
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/market-quote/quotes"))
            .willReturn(
                aResponse().withHeader("Content-Type", "application/json").withBody(QUOTES_BODY)));

    StockUpstoxKeyMap stockKeys = new StockUpstoxKeyMap(new ObjectMapper());
    UpstoxQuoteGateway gateway = new UpstoxQuoteGateway(client(), stockKeys);

    InstrumentKey nifty = new InstrumentKey("NSE", "NIFTY 50");
    InstrumentKey fut = new InstrumentKey("NFO", "NIFTY26JUNFUT"); // FUT symbol → no Upstox key

    Map<InstrumentKey, QuoteGateway.Quote> quotes = gateway.quotes(List.of(nifty, fut));

    // Only the index is mappable; the FUT symbol is omitted (left to the Kite path).
    assertThat(quotes).containsOnlyKeys(nifty);
    QuoteGateway.Quote q = quotes.get(nifty);
    assertThat(q.lastPrice()).isEqualByComparingTo("23845.85");
    assertThat(q.ohlc().open()).isEqualByComparingTo("23733.45");
    assertThat(q.ohlc().close()).isEqualByComparingTo("23733.45");

    // Only the index's Upstox key reaches Upstox; the unmapped FUT never does.
    wireMock.verify(
        getRequestedFor(urlPathEqualTo("/v2/market-quote/quotes"))
            .withQueryParam("instrument_key", equalTo("NSE_INDEX|Nifty 50")));
  }

  @Test
  void mapsNseEquitySpotViaStockReference() {
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/market-quote/quotes"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"success\",\"data\":{\"NSE_EQ:INE002A01018\":"
                            + "{\"last_price\":2987.4,\"volume\":4500000,"
                            + "\"depth\":{\"buy\":[{\"price\":2987.0}],\"sell\":[{\"price\":2987.5}]}}}}")));

    StockUpstoxKeyMap stockKeys = new StockUpstoxKeyMap(new ObjectMapper());
    UpstoxQuoteGateway gateway = new UpstoxQuoteGateway(client(), stockKeys);
    InstrumentKey reliance = new InstrumentKey("NSE", "RELIANCE");

    Map<InstrumentKey, QuoteGateway.Quote> quotes = gateway.quotes(List.of(reliance));

    assertThat(quotes).containsOnlyKeys(reliance);
    assertThat(quotes.get(reliance).lastPrice()).isEqualByComparingTo("2987.4");
    assertThat(quotes.get(reliance).bid()).isEqualByComparingTo("2987.0");

    wireMock.verify(
        getRequestedFor(urlPathEqualTo("/v2/market-quote/quotes"))
            .withQueryParam("instrument_key", equalTo("NSE_EQ|INE002A01018")));
  }

  @Test
  void emptyRequestAndUnmappableOnlyShortCircuitWithoutCallingUpstox() {
    StockUpstoxKeyMap stockKeys = new StockUpstoxKeyMap(new ObjectMapper());
    UpstoxQuoteGateway gateway = new UpstoxQuoteGateway(client(), stockKeys);

    assertThat(gateway.quotes(List.of())).isEmpty();
    // a request of only unmappable FUT keys never reaches Upstox
    assertThat(gateway.quotes(List.of(new InstrumentKey("NFO", "NIFTY26JUNFUT")))).isEmpty();
    assertThat(wireMock.getAllServeEvents()).as("no Upstox call for unmappable batch").isEmpty();
  }
}
