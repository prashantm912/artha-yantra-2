package in.arthayantra.marketdata.nse.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Live NSE sector-index levels (NIFTY METAL / IT / FMCG / …) for the Sector Stats cards. Sourced from
 * the niftyindices {@code LiveIndicesWatch.json} live feed (a public side-channel, like the Upstox
 * Market-Info ADR-0002 channel — OpenAlgo doesn't carry index levels). The container trusts the
 * intercepting-AV CA (dev-certs) so HTTPS works without skipping verification. The fetch is fail-safe
 * and cached; a {@code live}-profile scheduler refreshes it. Empty until first refresh (so mock/tests
 * carry no sector-index cards), and the values are LIVE — the Sector Stats stock table stays EOD.
 */
@Component
public class SectorIndexWatch {

  private static final Logger log = LoggerFactory.getLogger(SectorIndexWatch.class);

  /** The oipulse Sector Stats card set (19 sector indices). */
  private static final Set<String> SECTORS =
      Set.of(
          "NIFTY 50", "NIFTY BANK", "NIFTY FIN SERVICE", "NIFTY MID SELECT", "NIFTY IT",
          "NIFTY PHARMA", "NIFTY AUTO", "NIFTY METAL", "NIFTY FMCG", "NIFTY ENERGY", "NIFTY INFRA",
          "NIFTY PSU BANK", "NIFTY PVT BANK", "NIFTY HEALTHCARE", "NIFTY CONSR DURBL",
          "NIFTY OIL AND GAS", "NIFTY COMMODITIES", "NIFTY SERV SECTOR", "NIFTY IND DEFENCE");

  /** One sector-index card — values are the feed's decimal strings; asOf is the feed timestamp. */
  public record SectorIndexCard(
      String name,
      @Schema(types = {"string", "null"}) String last,
      @Schema(types = {"string", "null"}) String changePct,
      @Schema(types = {"string", "null"}) String open,
      @Schema(types = {"string", "null"}) String high,
      @Schema(types = {"string", "null"}) String low,
      @Schema(types = {"string", "null"}) String prevClose,
      @Schema(types = {"string", "null"}) String asOf) {}

  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private volatile List<SectorIndexCard> cards = List.of();

  public SectorIndexWatch(
      RestClient.Builder builder,
      ObjectMapper objectMapper,
      @Value("${artha.nse.index-watch-url:https://liveindexsa.niftyindices.com/jsonfiles/LiveIndicesWatch.json}")
          String url) {
    this.restClient = builder.baseUrl(url).build();
    this.objectMapper = objectMapper;
  }

  /** The latest sector-index cards (empty until the live scheduler first refreshes). */
  public List<SectorIndexCard> cards() {
    return cards;
  }

  /** Fetches + parses the live feed into the sector-index cards; failures keep the prior snapshot. */
  public void refresh() {
    try {
      String body = restClient.get().retrieve().body(String.class);
      JsonNode data = objectMapper.readTree(body).path("data");
      List<SectorIndexCard> next = new ArrayList<>();
      for (JsonNode n : data) {
        String name = n.path("indexName").asText("");
        if (SECTORS.contains(name)) {
          next.add(
              new SectorIndexCard(
                  name,
                  n.path("last").asText(null),
                  n.path("percChange").asText(null),
                  n.path("open").asText(null),
                  n.path("high").asText(null),
                  n.path("low").asText(null),
                  n.path("previousClose").asText(null),
                  n.path("timeVal").asText(null)));
        }
      }
      next.sort((a, b) -> a.name().compareTo(b.name()));
      cards = List.copyOf(next);
    } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
      log.warn("sector-index watch refresh failed (keeping prior {} cards): {}", cards.size(), e.getMessage());
    }
  }
}
