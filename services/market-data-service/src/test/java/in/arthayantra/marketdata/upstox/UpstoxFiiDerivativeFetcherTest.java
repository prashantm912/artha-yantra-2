package in.arthayantra.marketdata.upstox;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import in.arthayantra.marketdata.nse.FiiDerivativeFetcher;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * WireMock test for {@link UpstoxFiiDerivativeFetcher}: one {@code /v2/market/fii} call requesting
 * the four {@code NSE_FO|*} segments maps to canonical-segment {@code FiiDerivativeRow}s
 * (INR→₹crore, IST trade date, net = buy − sell), in stable segment order.
 */
class UpstoxFiiDerivativeFetcherTest {

  /** 2026-04-29T20:00:00Z = 2026-04-30 IST — proves the timestamp is read in IST. */
  private static final long TS = Instant.parse("2026-04-29T20:00:00Z").toEpochMilli();

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

  private static UpstoxFiiDerivativeFetcher fetcher() {
    UpstoxAnalyticsClient client =
        new UpstoxAnalyticsClient(
            RestClient.builder(),
            new UpstoxAnalyticsProperties(wireMock.baseUrl(), null, "test-token"));
    return new UpstoxFiiDerivativeFetcher(client);
  }

  private static String seg(String key, long ts, double buyInr, double sellInr) {
    return "\""
        + key
        + "\":[{\"time_stamp\":"
        + ts
        + ",\"buy_amount\":"
        + buyInr
        + ",\"sell_amount\":"
        + sellInr
        + "}]";
  }

  @Test
  void mapsFourFnoSegmentsToCroreRowsInIst() {
    String body =
        "{\"status\":\"success\",\"data\":{"
            + seg("NSE_FO|INDEX_FUTURES", TS, 3288800000.0, 0.0)
            + ","
            + seg("NSE_FO|INDEX_OPTIONS", TS, 0.0, 54466200000.0)
            + ","
            + seg("NSE_FO|STOCK_FUTURES", TS, 273100000.0, 0.0)
            + ","
            + seg("NSE_FO|STOCK_OPTIONS", TS, 0.0, 5127100000.0)
            + "}}";
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/market/fii"))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));

    List<FiiDerivativeFetcher.FiiDerivativeRow> rows = fetcher().fetchLatest();

    assertThat(rows).hasSize(4);
    assertThat(rows)
        .extracting(FiiDerivativeFetcher.FiiDerivativeRow::segment)
        .containsExactly("INDEX_FUTURES", "INDEX_OPTIONS", "STOCK_FUTURES", "STOCK_OPTIONS");
    assertThat(rows).allSatisfy(r -> assertThat(r.date()).isEqualTo(LocalDate.of(2026, 4, 30)));
    assertThat(rows.get(0).net()).isEqualByComparingTo("328.88"); // 3_288_800_000 / 1e7
    assertThat(rows.get(1).net()).isEqualByComparingTo("-5446.62"); // -54_466_200_000 / 1e7
    assertThat(rows.get(2).net()).isEqualByComparingTo("27.31");
    assertThat(rows.get(3).net()).isEqualByComparingTo("-512.71");

    // ONE call requesting all four segments, with the Bearer token + 1D interval.
    wireMock.verify(
        getRequestedFor(urlPathEqualTo("/v2/market/fii"))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .withQueryParam("data_type", equalTo("NSE_FO|INDEX_FUTURES"))
            .withQueryParam("data_type", equalTo("NSE_FO|STOCK_OPTIONS"))
            .withQueryParam("interval", equalTo("1D")));
  }
}
