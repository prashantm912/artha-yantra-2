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
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * EXT-02 wiring proof: every Upstox analytics-token client draws from the ONE shared {@link
 * UpstoxRateLimiter} bean. The six core clients (the expired-backfill walker + the option-chain /
 * quote / market-status / global-index / margin clients) plus the three additional REST token clients
 * (analytics FII/DII+news, fundamentals, the WS-authorize supplier) are each constructed with a SINGLE
 * limiter; one call through each lands as one hit in the shared budget. Before EXT-02 three clients each
 * owned an independent limiter (the count would fragment) and the rest fired unmetered (they would not
 * count at all). A saturated budget makes the live margin call return {@code unpriced} FAST (item 2).
 * (The tenth token client, the contract canary, is proven to debit in {@code
 * UpstoxContractCanaryIntegrationTest}, where its full collaborator graph is already wired.)
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
    // The three additional REST token clients.
    stub(get(urlPathEqualTo("/v2/market/fii")), "{\"status\":\"success\",\"data\":{}}");
    stub(get(urlPathEqualTo("/v2/fundamentals/INE001/key-ratios")), "{\"status\":\"success\",\"data\":{}}");
    stub(get(urlPathEqualTo("/v3/feed/market-data-feed/authorize")),
        "{\"status\":\"success\",\"data\":{\"authorized_redirect_uri\":\"wss://x\"}}");
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

  @Test
  void theThreeAdditionalRestTokenClientsAlsoDebitTheSharedBudget() {
    UpstoxRateLimiter shared = new UpstoxRateLimiter();
    UpstoxAnalyticsProperties props =
        new UpstoxAnalyticsProperties(wireMock.baseUrl(), null, "test-token", wireMock.baseUrl());

    // A FRESH builder per client — the interceptor-based clients (analytics / fundamentals) add a
    // request interceptor to the builder, and Spring hands each bean its own prototype builder, so a
    // reused mutable builder would accumulate interceptors and double-count here.
    final UpstoxAnalyticsClient analytics =
        new UpstoxAnalyticsClient(RestClient.builder(), props, shared);
    final UpstoxFundamentalsClient fundamentals =
        new UpstoxFundamentalsClient(RestClient.builder(), props, shared);
    final Supplier<String> authorize =
        UpstoxMarketFeedClient.authorizeSupplier(RestClient.builder(), props, shared);

    analytics.probe(); // interceptor: live tryAcquire
    fundamentals.keyRatios("INE001"); // interceptor: batch acquireForBatch (market closed → no pause)
    assertThat(authorize.get()).isEqualTo("wss://x"); // explicit live tryAcquire on the WS-authorize GET

    assertThat(used(shared, "30m")).isEqualTo(3);
  }

  @Test
  void saturatedBudgetMakesTheLiveMarginClientReturnUnpricedFast() {
    // A single-slot budget, already full: the live margin call (the armed F9 governor path) must fail
    // soft (unpriced) within its bound, never park a thread ~30 min while the strategy-signal caller
    // times out at 2 s and allows the entry.
    UpstoxRateLimiter shared =
        new UpstoxRateLimiter(
            0.0, () -> false, new String[] {"w"}, new long[] {3_600_000L}, new int[] {1});
    assertThat(shared.tryAcquire(50)).isTrue(); // saturate the only slot

    UpstoxMarginClient margin =
        new UpstoxMarginClient(
            RestClient.builder(),
            new UpstoxAnalyticsProperties(wireMock.baseUrl(), null, "test-token"),
            shared);

    long start = System.nanoTime();
    UpstoxMarginClient.MarginQuote quote =
        margin.quote(List.of(new UpstoxMarginClient.Leg("NSE_FO|53001", 75, "SELL", "D")));
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(quote.priced()).isFalse();
    assertThat(quote.unpricedReason()).containsIgnoringCase("budget");
    assertThat(elapsedMs).as("returns within the bound, never parks ~30 min").isLessThan(1_500);
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
