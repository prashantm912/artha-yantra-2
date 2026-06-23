package in.arthayantra.marketdata.nse.analytics;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class SectorIndexWatchTest {

  @Test
  void parsesAndFiltersToTheSectorStatsIndices() {
    WireMockServer wm = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wm.start();
    try {
      String json =
          "{\"data\":["
              + "{\"indexName\":\"NIFTY METAL\",\"last\":\"12664.45\",\"percChange\":\"-3.26\","
              + "\"open\":\"12987.7\",\"high\":\"12987.7\",\"low\":\"12647.85\","
              + "\"previousClose\":\"13091.2\",\"timeVal\":\"23-Jun-2026 13:21\"},"
              + "{\"indexName\":\"NIFTY ALPHA 50\",\"last\":\"100\",\"percChange\":\"1.0\"}]}"; // not a card index
      wm.stubFor(
          get(anyUrl())
              .willReturn(
                  aResponse().withHeader("Content-Type", "application/json").withBody(json)));

      SectorIndexWatch watch =
          new SectorIndexWatch(RestClient.builder(), new ObjectMapper(), wm.baseUrl());
      watch.refresh();

      assertThat(watch.cards()).hasSize(1); // NIFTY ALPHA 50 filtered out
      SectorIndexWatch.SectorIndexCard c = watch.cards().get(0);
      assertThat(c.name()).isEqualTo("NIFTY METAL");
      assertThat(c.last()).isEqualTo("12664.45");
      assertThat(c.changePct()).isEqualTo("-3.26");
      assertThat(c.prevClose()).isEqualTo("13091.2");
    } finally {
      wm.stop();
    }
  }

  @Test
  void refreshIsFailSafeOnUnreachableFeed() {
    SectorIndexWatch watch =
        new SectorIndexWatch(RestClient.builder(), new ObjectMapper(), "http://127.0.0.1:1/none");
    watch.refresh(); // must not throw
    assertThat(watch.cards()).isEmpty();
  }
}
