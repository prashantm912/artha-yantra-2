package in.arthayantra.marketdata.upstox;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import in.arthayantra.marketdata.upstox.UpstoxRateLimiter.WindowStat;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * EXT-02 wiring proof: every Upstox analytics-token client draws from the ONE shared {@link
 * UpstoxRateLimiter} bean. All six clients (the expired-backfill walker + the option-chain / quote /
 * market-status / global-index / margin clients) are constructed with a SINGLE limiter; one call
 * through each — batch AND the previously-unmetered live path — must land as one hit in the shared
 * budget, so the 30-min window reads exactly six. Before EXT-02 three clients each owned an
 * independent limiter (the count would fragment) and three fired unmetered (they would not count at
 * all).
 */
class UpstoxSharedRateBudgetTest {

  private static WireMockServer wireMock;

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
    // Minimal 200 bodies for every endpoint the six clients touch — the limiter is acquired before the
    // call, so the response shape is irrelevant; each call just needs to reach its acquire().
    stub(get(urlPathEqualTo("/v2/expired-instruments/expiries")), "{\"status\":\"success\",\"data\":[]}");
    stub(get(urlPathEqualTo("/v2/option/chain")), "{\"status\":\"success\",\"data\":[]}");
    stub(get(urlPathEqualTo("/v2/market-quote/quotes")), "{\"status\":\"success\",\"data\":{}}");
    stub(get(urlPathEqualTo("/v2/market/status/NSE")),
        "{\"status\":\"success\",\"data\":{\"exchange\":\"NSE\",\"status\":\"NORMAL_OPEN\"}}");
    stub(post(urlPathEqualTo("/v2/charges/margin")),
        "{\"status\":\"success\",\"data\":{\"margins\":[],\"required_margin\":0,\"final_margin\":0}}");
  }

  private static void stub(
      com.github.tomakehurst.wiremock.client.MappingBuilder mapping, String body) {
    wireMock.stubFor(
        mapping.willReturn(
            aResponse().withHeader("Content-Type", "application/json").withBody(body)));
  }

  @Test
  void allSixClientsDrawFromTheOneSharedBudget() {
    UpstoxRateLimiter shared = new UpstoxRateLimiter();
    RestClient.Builder builder = RestClient.builder();
    UpstoxAnalyticsProperties props =
        new UpstoxAnalyticsProperties(wireMock.baseUrl(), null, "test-token", wireMock.baseUrl());

    final UpstoxExpiredInstrumentsClient expired =
        new UpstoxExpiredInstrumentsClient(builder, props, shared);
    final UpstoxOptionChainClient optionChain = new UpstoxOptionChainClient(builder, props, shared);
    final UpstoxQuoteClient quote = new UpstoxQuoteClient(builder, props, shared);
    final UpstoxMarketStatusClient status = new UpstoxMarketStatusClient(builder, props, shared);
    final UpstoxGlobalInstrumentsClient global =
        new UpstoxGlobalInstrumentsClient(builder, new ObjectMapper(), props, shared);
    final UpstoxMarginClient margin = new UpstoxMarginClient(builder, props, shared);

    // One call per client — batch (expired) + the five live paths (chain/quote/status/global/margin).
    expired.expiries("NSE_INDEX|Nifty 50"); // acquireForBatch
    optionChain.optionChain("NSE_INDEX|Nifty 50", LocalDate.parse("2026-06-30")); // acquire (was unmetered)
    quote.quotes(List.of("NSE_INDEX|Nifty 50")); // acquire (was unmetered)
    status.status("NSE"); // acquire
    global.quote(List.of("GLOBAL_INDEX|^HSI")); // acquire
    margin.quote(List.of(new UpstoxMarginClient.Leg("NSE_FO|53001", 75, "SELL", "D"))); // acquire (was unmetered)

    // Six calls (1 batch + 5 live) all landed in the ONE budget: the 30-min window reads exactly six.
    assertThat(used(shared, "30m")).isEqualTo(6);
    assertThat(used(shared, "1m")).isEqualTo(6);
  }

  private static int used(UpstoxRateLimiter limiter, String window) {
    for (WindowStat s : limiter.getUsageStats()) {
      if (s.window().equals(window)) {
        return s.used();
      }
    }
    throw new IllegalArgumentException("no window " + window);
  }
}
