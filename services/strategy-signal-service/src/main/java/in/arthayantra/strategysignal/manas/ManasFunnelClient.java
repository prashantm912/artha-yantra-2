package in.arthayantra.strategysignal.manas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads the Manas Arora selection funnel from market-data (D8 — this service holds no marketdata
 * grant) for the daily swing batch. The exact sibling of {@link
 * in.arthayantra.strategysignal.minervini.MinerviniFunnelClient}. Returns the day's
 * immediately-buyable + on-deck base candidates with the BEST-setup pivot (reported on the detail)
 * plus BOTH setups' pivots ({@code breakoutPivot}/{@code vcpPivot}) so the engine seeds each strategy
 * its own §3.2/§3.3 pivot, plus the setup type + base footprint. Empty on any upstream failure — a
 * missing funnel means the batch simply has nothing to evaluate, never a crash.
 */
@Component
public class ManasFunnelClient {

  private static final Logger log = LoggerFactory.getLogger(ManasFunnelClient.class);

  /**
   * One funnel candidate + its seeded base geometry (the manas funnel has no cheat/thrust/stage).
   * {@code pivot} is the BEST setup's pivot (kept for the detail); {@code breakoutPivot}/{@code
   * vcpPivot} are the per-setup §3.2/§3.3 pivots (null when that setup is invalid) the engine routes
   * to the matching strategy.
   */
  public record Candidate(
      String symbol,
      BigDecimal close,
      BigDecimal pivot,
      String setupType,
      String footprint,
      BigDecimal breakoutPivot,
      BigDecimal vcpPivot) {}

  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  /** Wires the market-data base URL (REST only — no schema read). */
  public ManasFunnelClient(
      RestClient.Builder builder,
      ObjectMapper objectMapper,
      @Value("${artha.marketdata.base-url}") String baseUrl) {
    this.restClient = builder.baseUrl(baseUrl).build();
    this.objectMapper = objectMapper;
  }

  /** The buyable + on-deck candidates (the base names at or approaching their pivot). Empty on failure. */
  public List<Candidate> buyableAndOnDeck() {
    try {
      String body =
          restClient
              .get()
              .uri("/api/v1/market/screener/manas-arora/funnel")
              .retrieve()
              .body(String.class);
      JsonNode root = objectMapper.readTree(body);
      List<Candidate> out = new ArrayList<>();
      collect(root.path("immediatelyBuyable"), out);
      collect(root.path("onDeck"), out);
      return out;
    } catch (java.io.IOException | RuntimeException e) {
      log.warn("manas funnel fetch failed — no swing candidates this run: {}", e.getMessage());
      return List.of();
    }
  }

  private static void collect(JsonNode rows, List<Candidate> out) {
    for (JsonNode row : rows) {
      String symbol = row.path("symbol").asText("");
      if (symbol.isBlank()) {
        continue;
      }
      out.add(
          new Candidate(
              symbol,
              decimal(row, "close"),
              decimal(row, "pivot"),
              row.path("setupType").asText(null),
              row.path("footprint").asText(null),
              decimal(row, "breakoutPivot"),
              decimal(row, "vcpPivot")));
    }
  }

  private static BigDecimal decimal(JsonNode row, String field) {
    return row.hasNonNull(field) ? new BigDecimal(row.path(field).asText()) : null;
  }
}
