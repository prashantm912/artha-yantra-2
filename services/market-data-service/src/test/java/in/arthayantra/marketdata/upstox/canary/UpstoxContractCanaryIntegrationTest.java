package in.arthayantra.marketdata.upstox.canary;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Upstox canary IT (live profile vs WireMock, ntfy captured on the same server), twin of {@code
 * OpenAlgoContractCanaryIntegrationTest}: faithful responses -&gt; ZERO drift; a removed consumed
 * {@code buy_amount} -&gt; critical drift + ntfy POST; an added top-level block -&gt; warning; the
 * Redis daily-once marker makes the second run of a day a no-op. Also covers the W-U4 live-capture
 * shapes — a removed consumed {@code option_chain} OI / {@code market_quote} {@code last_price} /
 * {@code ws_authorize} {@code authorized_redirect_uri} is critical, while an empty option chain (a
 * stale probe expiry) is skipped, never an alarm.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=live",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false",
      "artha.ntfy.topic=ay-test-topic",
      "artha.upstox.canary-enabled=true",
      // binds UpstoxFnoMasterClient (the margin probe resolves the NIFTY key off it) + the margin beans
      "artha.upstox.analytics.enabled=true",
      // ...but keep the (now-bound) expired-backfill from firing its boot auto-resume at WireMock
      "artha.marketdata.expired-backfill.auto-resume=false"
    })
class UpstoxContractCanaryIntegrationTest extends MarketDataIntegrationTestBase {

  private static final WireMockServer WIREMOCK =
      new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
  private static final Path SECRETS_DIR;

  static {
    WIREMOCK.start();
    try {
      SECRETS_DIR = Files.createTempDirectory("upstox-canary-secrets");
      Files.writeString(SECRETS_DIR.resolve("kite_api_key"), "test-key");
      Files.writeString(SECRETS_DIR.resolve("kite_api_secret"), "test-secret");
      Files.writeString(SECRETS_DIR.resolve("openalgo_api_key"), "test-openalgo-key");
      Files.writeString(SECRETS_DIR.resolve("upstox_analytics_token"), "test-upstox-token");
      byte[] key = new byte[32];
      Files.writeString(
          SECRETS_DIR.resolve("artha_master_key"), Base64.getEncoder().encodeToString(key));
    } catch (java.io.IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("artha.kite.base-url", WIREMOCK::baseUrl);
    registry.add("artha.ntfy.url", WIREMOCK::baseUrl);
    registry.add("artha.kite.api-key-file", () -> SECRETS_DIR.resolve("kite_api_key").toString());
    registry.add(
        "artha.kite.api-secret-file", () -> SECRETS_DIR.resolve("kite_api_secret").toString());
    registry.add(
        "artha.kite.master-key-file", () -> SECRETS_DIR.resolve("artha_master_key").toString());
    registry.add("artha.openalgo.base-url", WIREMOCK::baseUrl);
    registry.add(
        "artha.openalgo.api-key-file", () -> SECRETS_DIR.resolve("openalgo_api_key").toString());
    registry.add("artha.upstox.analytics.base-url", WIREMOCK::baseUrl);
    // the margin probe resolves its NIFTY key off the F&O master (the auth-free assets CDN)
    registry.add("artha.upstox.analytics.instruments-base-url", WIREMOCK::baseUrl);
    registry.add(
        "artha.upstox.analytics.token-file",
        () -> SECRETS_DIR.resolve("upstox_analytics_token").toString());
  }

  private static final String FII_CASH =
      "{\"status\":\"success\",\"data\":{\"NSE_EQ|CASH\":[{\"time_stamp\":1750000000000,"
          + "\"buy_amount\":1.0,\"sell_amount\":1.0}]}}";
  private static final String FII_FNO =
      "{\"status\":\"success\",\"data\":{\"NSE_FO|INDEX_OPTIONS\":[{\"time_stamp\":1750000000000,"
          + "\"buy_amount\":1.0,\"sell_amount\":1.0,\"buy_contracts\":1}]}}";
  private static final String DII_CASH = FII_CASH;
  private static final String MAX_PAIN =
      "{\"status\":\"success\",\"data\":{\"instrument_key\":\"NSE_INDEX|Nifty 50\","
          + "\"expiry_date\":\"23-06-2026\",\"max_pain\":24100.0,\"spot_closing_price\":24087.2,"
          + "\"insights\":[{\"max_pain\":24100.0,\"spot_price\":24075.1,\"time\":\"09:15\"}]}}";
  private static final String PCR_OPT =
      "{\"status\":\"success\",\"data\":{\"instrument_key\":\"NSE_INDEX|Nifty 50\","
          + "\"expiry_date\":\"23-06-2026\",\"pcr\":0.8762,\"spot_closing_price\":24087.2,"
          + "\"insights\":[{\"pcr\":0.78,\"spot_price\":24075.1,\"time\":\"09:15\"}]}}";

  // W-U4 live-capture fixtures (one strike / one tick / the authorize URL — the consumed fields).
  private static final String OPTION_CHAIN =
      "{\"status\":\"success\",\"data\":[{\"expiry\":\"2026-06-30\",\"pcr\":1.44,"
          + "\"strike_price\":25000.0,\"underlying_key\":\"NSE_INDEX|Nifty 50\","
          + "\"underlying_spot_price\":23845.85,"
          + "\"call_options\":{\"instrument_key\":\"NSE_FO|58624\",\"market_data\":{\"ltp\":120.5,"
          + "\"volume\":345600,\"oi\":1820075,\"close_price\":118.0,\"bid_price\":120.0,"
          + "\"bid_qty\":75,\"ask_price\":121.0,\"ask_qty\":150,\"prev_oi\":1750000},"
          + "\"option_greeks\":{\"vega\":12.3,\"theta\":-8.1,\"gamma\":0.0004,\"delta\":0.42,"
          + "\"iv\":13.6,\"pop\":38.2}},"
          + "\"put_options\":{\"instrument_key\":\"NSE_FO|58625\",\"market_data\":{\"ltp\":255.75,"
          + "\"volume\":290100,\"oi\":2105500,\"close_price\":260.0,\"bid_price\":255.0,"
          + "\"bid_qty\":75,\"ask_price\":256.5,\"ask_qty\":225,\"prev_oi\":2200000},"
          + "\"option_greeks\":{\"vega\":12.1,\"theta\":-7.9,\"gamma\":0.0004,\"delta\":-0.58,"
          + "\"iv\":14.1,\"pop\":61.8}}}]}";
  private static final String MARKET_QUOTE =
      "{\"status\":\"success\",\"data\":{\"NSE_INDEX:Nifty 50\":{"
          + "\"instrument_token\":\"NSE_INDEX|Nifty 50\",\"symbol\":\"Nifty 50\","
          + "\"last_price\":23845.85,\"net_change\":120.0,\"volume\":0,\"average_price\":0.0,"
          + "\"oi\":0,\"oi_day_high\":0,\"oi_day_low\":0,\"total_buy_quantity\":0,"
          + "\"total_sell_quantity\":0,\"lower_circuit_limit\":21483.0,"
          + "\"upper_circuit_limit\":26257.0,\"last_trade_time\":\"2026-06-24T15:30:00+05:30\","
          + "\"ohlc\":{\"open\":23760.0,\"high\":23925.0,\"low\":23740.0,\"close\":23750.5},"
          + "\"depth\":{\"buy\":[{\"price\":23845.0,\"quantity\":75,\"orders\":3}],"
          + "\"sell\":[{\"price\":23846.0,\"quantity\":150,\"orders\":5}]}}}}";
  private static final String WS_AUTHORIZE =
      "{\"status\":\"success\",\"data\":{\"authorized_redirect_uri\":"
          + "\"wss://wsfeeder-api.upstox.com/?requestId=abc123\"}}";

  // Margin probe (EXT-03 residual). The canary resolves a near-ATM front-weekly NIFTY CE key off the
  // F&O master, then POSTs a 1-lot short basket. The master fixture carries a 24000 CE row at the
  // NEXT weekly index expiry (Tuesday) so keyFor resolves the same expiry the canary computes at runtime.
  private static final String MARGIN_MASTER_PATH =
      "/market-quote/instruments/exchange/complete.json.gz";
  private static final String MARGIN_PATH = "/v2/charges/margin";
  // A faithful priced basket — span_margin/total_margin per leg + basket required/final margins.
  private static final String MARGIN_OK =
      "{\"status\":\"success\",\"data\":{\"margins\":[{\"span_margin\":337004.85,"
          + "\"exposure_margin\":50000.0,\"equity_margin\":0.0,\"net_buy_premium\":0.0,"
          + "\"additional_margin\":0.0,\"total_margin\":387004.85,\"tender_margin\":0.0}],"
          + "\"required_margin\":387004.85,\"final_margin\":188604.45}}";

  /**
   * The gzipped F&O master with one NIFTY 24000 CE at the next weekly index expiry (Tuesday). Carries
   * {@code lot_size:50} — a value distinct from the canary's 65 hardcoded fallback, so the probe qty
   * asserted below can ONLY have come from the master (proves the lot is master-resolved, not a
   * hardcode).
   */
  private static byte[] marginMaster() {
    LocalDate expiry =
        MarketCalendar.nse().nextWeeklyIndexExpiry(LocalDate.now(ZoneId.of("Asia/Kolkata")));
    long expiryMillis =
        expiry.atTime(15, 30).atZone(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli();
    String json =
        "[{\"segment\":\"NSE_FO\",\"name\":\"NIFTY\",\"asset_symbol\":\"NIFTY\","
            + "\"underlying_symbol\":\"NIFTY\",\"instrument_key\":\"NSE_FO|MARGINPROBE\","
            + "\"instrument_type\":\"CE\",\"trading_symbol\":\"NIFTY 24000 CE\",\"expiry\":"
            + expiryMillis
            + ",\"strike_price\":24000.0,\"lot_size\":50}]";
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
      gz.write(json.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  @Autowired private UpstoxContractCanary canary;
  @Autowired private StringRedisTemplate redis;

  @BeforeEach
  void stubFaithfulFixtures() {
    WIREMOCK.resetAll();
    redis.delete(UpstoxContractCanary.MARKER_KEY_PREFIX + LocalDate.now(ZoneId.of("Asia/Kolkata")));
    WIREMOCK.stubFor(
        get(urlPathEqualTo("/v2/market/fii"))
            .withQueryParam("data_type", equalTo("NSE_EQ|CASH"))
            .willReturn(json(FII_CASH)));
    WIREMOCK.stubFor(
        get(urlPathEqualTo("/v2/market/fii"))
            .withQueryParam("data_type", equalTo("NSE_FO|INDEX_OPTIONS"))
            .willReturn(json(FII_FNO)));
    WIREMOCK.stubFor(
        get(urlPathEqualTo("/v2/market/dii"))
            .withQueryParam("data_type", equalTo("NSE_EQ|CASH"))
            .willReturn(json(DII_CASH)));
    WIREMOCK.stubFor(get(urlPathEqualTo("/v2/market/max-pain")).willReturn(json(MAX_PAIN)));
    WIREMOCK.stubFor(get(urlPathEqualTo("/v2/market/pcr")).willReturn(json(PCR_OPT)));
    WIREMOCK.stubFor(get(urlPathEqualTo("/v2/option/chain")).willReturn(json(OPTION_CHAIN)));
    WIREMOCK.stubFor(
        get(urlPathEqualTo("/v2/market-quote/quotes")).willReturn(json(MARKET_QUOTE)));
    WIREMOCK.stubFor(
        get(urlPathEqualTo("/v3/feed/market-data-feed/authorize")).willReturn(json(WS_AUTHORIZE)));
    // Margin probe: the F&O master (gunzips to a NIFTY 24000 CE) + the priced basket.
    WIREMOCK.stubFor(
        get(urlPathEqualTo(MARGIN_MASTER_PATH))
            .willReturn(
                aResponse().withHeader("Content-Type", "application/gzip").withBody(marginMaster())));
    WIREMOCK.stubFor(post(urlPathEqualTo(MARGIN_PATH)).willReturn(json(MARGIN_OK)));
    WIREMOCK.stubFor(post(urlPathEqualTo("/ay-test-topic")).willReturn(aResponse().withStatus(200)));
  }

  private static ResponseDefinitionBuilder json(String body) {
    return aResponse().withHeader("Content-Type", "application/json").withBody(body);
  }

  @Test
  void fixtureFaithfulResponsesProduceZeroDrift() {
    UpstoxContractCanary.CanaryResult result = canary.runNow();

    assertThat(result.drift()).isEmpty();
    assertThat(result.lastContractCheck()).isNotNull();
    WIREMOCK.verify(0, postRequestedFor(urlPathEqualTo("/ay-test-topic")));
    // The margin probe MUST post a lot-multiple qty (a wrong qty 400s UDAPI1104 → silent skip → a
    // blind canary). The qty is resolved FROM the master's lot_size (fixture: 50) — a value distinct
    // from the 65 hardcoded fallback, so asserting 50 proves the lot flows from the master (an NSE
    // lot change is reflected automatically), not a stale hardcode (task_309ea822).
    WIREMOCK.verify(
        postRequestedFor(urlPathEqualTo(MARGIN_PATH))
            .withRequestBody(matchingJsonPath("$.instruments[0].quantity", equalTo("50"))));
    assertThat(redis.opsForValue().get(UpstoxContractCanary.RESULT_KEY)).contains("\"drift\":[]");
  }

  @Test
  void removedConsumedFieldIsCriticalDriftWithNtfyAlert() {
    // FII cash loses buy_amount — a removed CONSUMED field must surface as critical drift + ntfy.
    WIREMOCK.stubFor(
        get(urlPathEqualTo("/v2/market/fii"))
            .withQueryParam("data_type", equalTo("NSE_EQ|CASH"))
            .willReturn(
                json(
                    "{\"status\":\"success\",\"data\":{\"NSE_EQ|CASH\":[{\"time_stamp\":1750000000000,"
                        + "\"sell_amount\":1.0}]}}")));

    UpstoxContractCanary.CanaryResult result = canary.runNow();

    assertThat(result.drift()).contains("MISSING:fii_cash.data.NSE_EQ|CASH.*.buy_amount");
    WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo("/ay-test-topic")));
  }

  @Test
  void addedTopLevelFieldIsWarningNotCritical() {
    WIREMOCK.stubFor(
        get(urlPathEqualTo("/v2/market/fii"))
            .withQueryParam("data_type", equalTo("NSE_EQ|CASH"))
            .willReturn(
                json(
                    "{\"status\":\"success\",\"new_block\":{\"x\":1},\"data\":{\"NSE_EQ|CASH\":"
                        + "[{\"time_stamp\":1750000000000,\"buy_amount\":1.0,\"sell_amount\":1.0}]}}")));

    UpstoxContractCanary.CanaryResult result = canary.runNow();

    assertThat(result.drift()).contains("NEW:fii_cash.new_block");
    assertThat(result.drift()).noneMatch(d -> d.startsWith("MISSING:"));
    WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo("/ay-test-topic")));
  }

  @Test
  void removedConsumedOptionChainOiIsCriticalDriftWithNtfyAlert() {
    // The live-capture /v2/option/chain loses the per-strike call OI — the field source.optionchain
    // capture depends on. It must surface as critical drift + ntfy, off the live path.
    WIREMOCK.stubFor(
        get(urlPathEqualTo("/v2/option/chain"))
            .willReturn(
                json(
                    "{\"status\":\"success\",\"data\":[{\"strike_price\":25000.0,"
                        + "\"underlying_spot_price\":23845.85,\"call_options\":{\"market_data\":"
                        + "{\"ltp\":120.5,\"volume\":345600,\"prev_oi\":1750000,\"bid_price\":120.0,"
                        + "\"ask_price\":121.0}},\"put_options\":{\"market_data\":{\"ltp\":255.75,"
                        + "\"oi\":2105500,\"prev_oi\":2200000}}}]}")));

    UpstoxContractCanary.CanaryResult result = canary.runNow();

    assertThat(result.drift()).contains("MISSING:option_chain.data.*.call_options.market_data.oi");
    WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo("/ay-test-topic")));
  }

  @Test
  void removedConsumedMarketQuoteLastPriceIsCriticalDriftWithNtfyAlert() {
    // The live-capture /v2/market-quote/quotes tick loses last_price (the field source.quotes maps).
    WIREMOCK.stubFor(
        get(urlPathEqualTo("/v2/market-quote/quotes"))
            .willReturn(
                json(
                    "{\"status\":\"success\",\"data\":{\"NSE_INDEX:Nifty 50\":{\"volume\":0,"
                        + "\"oi\":0,\"ohlc\":{\"open\":23760.0,\"high\":23925.0,\"low\":23740.0,"
                        + "\"close\":23750.5},\"depth\":{\"buy\":[{\"price\":23845.0}],"
                        + "\"sell\":[{\"price\":23846.0}]}}}}")));

    UpstoxContractCanary.CanaryResult result = canary.runNow();

    assertThat(result.drift()).contains("MISSING:market_quote.data.*.last_price");
    WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo("/ay-test-topic")));
  }

  @Test
  void removedAuthorizedRedirectUriIsCriticalDriftWithNtfyAlert() {
    // The v3 WS authorize-GET loses authorized_redirect_uri — without it the source.ticker WS path
    // cannot connect; a removal must be caught off-band.
    WIREMOCK.stubFor(
        get(urlPathEqualTo("/v3/feed/market-data-feed/authorize"))
            .willReturn(json("{\"status\":\"success\",\"data\":{}}")));

    UpstoxContractCanary.CanaryResult result = canary.runNow();

    assertThat(result.drift()).contains("MISSING:ws_authorize.data.authorized_redirect_uri");
    WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo("/ay-test-topic")));
  }

  @Test
  void removedConsumedSpanMarginIsCriticalDriftWithNtfyAlert() {
    // The margin basket loses the per-leg span_margin — the field RiskService.currentHeatPct sums —
    // while total_margin/required_margin/final_margin stay valid+positive (the client's
    // wholesale-empty guard #929 never trips, and aggregate() sums span to a FALSE 0). Only this
    // raw-shape probe catches the span-only rename; it must surface critical drift + ntfy.
    WIREMOCK.stubFor(
        post(urlPathEqualTo(MARGIN_PATH))
            .willReturn(
                json(
                    "{\"status\":\"success\",\"data\":{\"margins\":[{\"total_margin\":387004.85,"
                        + "\"tender_margin\":0.0}],\"required_margin\":387004.85,"
                        + "\"final_margin\":188604.45}}")));

    UpstoxContractCanary.CanaryResult result = canary.runNow();

    assertThat(result.drift()).contains("MISSING:margin.data.margins.*.span_margin");
    WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo("/ay-test-topic")));
  }

  @Test
  void emptyMarginBasketFromOffHoursIsSkippedNotDrift() {
    // An empty margins array (off-hours / a stale probe contract) is tolerated like an empty option
    // chain — the shape is checked only when Upstox prices a basket, so never a false alarm.
    WIREMOCK.stubFor(
        post(urlPathEqualTo(MARGIN_PATH))
            .willReturn(json("{\"status\":\"success\",\"data\":{\"margins\":[]}}")));

    UpstoxContractCanary.CanaryResult result = canary.runNow();

    assertThat(result.drift()).noneMatch(d -> d.contains("margin"));
    WIREMOCK.verify(0, postRequestedFor(urlPathEqualTo("/ay-test-topic")));
  }

  @Test
  void marginEndpoint5xxIsAProbeFailureNotASilentSkip() {
    // A 4xx (UDAPI1104 / stale contract) is benign drift-tolerance; a 5xx is an Upstox server fault —
    // it must reach PROBE_FAILED + alert, never be swallowed like a 4xx (EXT-03 residual review finding).
    WIREMOCK.stubFor(
        post(urlPathEqualTo(MARGIN_PATH)).willReturn(aResponse().withStatus(500)));

    UpstoxContractCanary.CanaryResult result = canary.runNow();

    assertThat(result.drift()).anyMatch(d -> d.startsWith("PROBE_FAILED:"));
  }

  @Test
  void emptyOptionChainFromAStaleExpiryIsSkippedNotDrift() {
    // A stale weekly expiry returns data:[] — tolerated like the max-pain/pcr historical probes, so
    // the canary never false-alarms when the fixed probe expiry rolls past.
    WIREMOCK.stubFor(
        get(urlPathEqualTo("/v2/option/chain"))
            .willReturn(json("{\"status\":\"success\",\"data\":[]}")));

    UpstoxContractCanary.CanaryResult result = canary.runNow();

    assertThat(result.drift()).noneMatch(d -> d.contains("option_chain"));
    WIREMOCK.verify(0, postRequestedFor(urlPathEqualTo("/ay-test-topic")));
  }

  @Test
  void dailyOnceMarkerMakesTheSecondRunANoOp() {
    canary.maybeRunDaily();
    int callsAfterFirst = WIREMOCK.getAllServeEvents().size();

    canary.maybeRunDaily();

    assertThat(WIREMOCK.getAllServeEvents())
        .as("idempotent per trading day via the Redis marker")
        .hasSize(callsAfterFirst);
  }
}
