package in.arthayantra.strategysignal.swing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import in.arthayantra.strategysignal.manas.ManasFunnelClient;
import in.arthayantra.strategysignal.minervini.MinerviniFunnelClient;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Proves both swing funnel clients fail fast when market-data holds the response past the read bound. */
class FunnelClientTimeoutTest {

  private WireMockServer wireMock;

  @BeforeEach
  void setUp() {
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
  }

  @AfterEach
  void tearDown() {
    wireMock.stop();
  }

  @Test
  void manasSnapshotFailsFastWhenTheReadTimeoutIsExceeded() {
    stubSlow("manas-arora");
    ManasFunnelClient client =
        new ManasFunnelClient(RestClient.builder(), new ObjectMapper(), wireMock.baseUrl(), 1_000, 250);

    long startedAt = System.nanoTime();
    Optional<ManasFunnelClient.Snapshot> result = client.snapshot();
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

    assertThat(result).isEmpty();
    assertThat(elapsedMs).isLessThan(1_500);
  }

  @Test
  void minerviniSnapshotFailsFastWhenTheReadTimeoutIsExceeded() {
    stubSlow("minervini");
    MinerviniFunnelClient client =
        new MinerviniFunnelClient(RestClient.builder(), new ObjectMapper(), wireMock.baseUrl(), 1_000, 250);

    long startedAt = System.nanoTime();
    Optional<MinerviniFunnelClient.Snapshot> result = client.snapshot();
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

    assertThat(result).isEmpty();
    assertThat(elapsedMs).isLessThan(1_500);
  }

  private void stubSlow(String family) {
    wireMock.stubFor(
        get(urlPathEqualTo("/api/v1/market/screener/" + family + "/funnel"))
            .willReturn(
                aResponse()
                    .withFixedDelay(2_000)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"screenDate\":\"2026-07-17\",\"immediatelyBuyable\":[],\"onDeck\":[]}")));
  }
}
