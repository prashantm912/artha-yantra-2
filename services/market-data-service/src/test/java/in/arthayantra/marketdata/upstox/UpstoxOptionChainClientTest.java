package in.arthayantra.marketdata.upstox;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import in.arthayantra.marketdata.options.OptionChainQuoteSource;
import in.arthayantra.marketdata.options.UpstoxOptionChainQuoteSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * WireMock test for the Wave-U1 direct-Upstox option-chain path: the {@link UpstoxOptionChainClient}
 * sends the analytics Bearer token + the (instrument_key, expiry_date) query, and the {@link
 * UpstoxOptionChainQuoteSource} adapter maps the wire chain to the {@link OptionChainQuoteSource}
 * domain record — per-strike CE/PE LTP/OI/prev_oi and the underlying spot, for both sides.
 */
class UpstoxOptionChainClientTest {

  private static WireMockServer wireMock;

  // Two strikes, NIFTY shape; the second has only a CE side (PE absent → must map to absent).
  private static final String CHAIN_BODY =
      """
      {"status":"success","data":[
        {"expiry":"2026-06-30","pcr":1.44,"strike_price":25000.0,
         "underlying_key":"NSE_INDEX|Nifty 50","underlying_spot_price":23845.85,
         "call_options":{"instrument_key":"NSE_FO|58624",
           "market_data":{"ltp":120.5,"volume":345600,"oi":1820075,
             "bid_price":120.0,"ask_price":121.0,"prev_oi":1750000}},
         "put_options":{"instrument_key":"NSE_FO|58625",
           "market_data":{"ltp":255.75,"volume":290100,"oi":2105500,
             "bid_price":255.0,"ask_price":256.5,"prev_oi":2200000}}},
        {"expiry":"2026-06-30","pcr":1.1,"strike_price":25100.0,
         "underlying_key":"NSE_INDEX|Nifty 50","underlying_spot_price":23845.85,
         "call_options":{"instrument_key":"NSE_FO|58626",
           "market_data":{"ltp":80.0,"volume":12000,"oi":500000,
             "bid_price":79.5,"ask_price":80.5,"prev_oi":480000}}}
      ]}
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

  private static UpstoxOptionChainClient client() {
    return new UpstoxOptionChainClient(
        RestClient.builder(),
        new UpstoxAnalyticsProperties(wireMock.baseUrl(), null, "test-token"));
  }

  @Test
  void mapsWireChainToDomainAndSendsBearerTokenAndParams() {
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/option/chain"))
            .willReturn(
                aResponse().withHeader("Content-Type", "application/json").withBody(CHAIN_BODY)));

    UpstoxOptionChainQuoteSource source = new UpstoxOptionChainQuoteSource(client());
    Optional<OptionChainQuoteSource.ChainQuotes> result =
        source.fetch("NIFTY 50", LocalDate.parse("2026-06-30"));

    assertThat(result).isPresent();
    OptionChainQuoteSource.ChainQuotes cq = result.get();
    assertThat(cq.spot()).isEqualByComparingTo("23845.85");

    // CE 25000 — LTP/OI/prev_oi/bid/ask round-trip; keys are normalized to scale-2 (25000.00)
    OptionChainQuoteSource.Leg ce = cq.ce().get(new BigDecimal("25000.00"));
    assertThat(ce.ltp()).isEqualByComparingTo("120.5");
    assertThat(ce.oi()).isEqualTo(1820075L);
    assertThat(ce.prevOi()).isEqualTo(1750000L);
    assertThat(ce.bid()).isEqualByComparingTo("120.0");
    assertThat(ce.ask()).isEqualByComparingTo("121.0");
    assertThat(ce.volume()).isEqualTo(345600L);

    // PE 25000
    OptionChainQuoteSource.Leg pe = cq.pe().get(new BigDecimal("25000.00"));
    assertThat(pe.oi()).isEqualTo(2105500L);
    assertThat(pe.prevOi()).isEqualTo(2200000L);

    // CE 25100 present; PE 25100 absent (the row had no put_options)
    assertThat(cq.ce().get(new BigDecimal("25100.00")).oi()).isEqualTo(500000L);
    assertThat(cq.pe().get(new BigDecimal("25100.00"))).isNull();

    wireMock.verify(
        getRequestedFor(urlPathEqualTo("/v2/option/chain"))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .withQueryParam("instrument_key", equalTo("NSE_INDEX|Nifty 50"))
            .withQueryParam("expiry_date", equalTo("2026-06-30")));
  }

  @Test
  void emptyChainAndUnknownUnderlyingMapToEmpty() {
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/option/chain"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"status\":\"success\",\"data\":[]}")));

    UpstoxOptionChainQuoteSource source = new UpstoxOptionChainQuoteSource(client());
    assertThat(source.fetch("NIFTY 50", LocalDate.parse("2026-06-30"))).isEmpty();
    // unmapped underlying never reaches Upstox at all
    assertThat(source.fetch("WILD UNKNOWN", LocalDate.parse("2026-06-30"))).isEmpty();
  }
}
