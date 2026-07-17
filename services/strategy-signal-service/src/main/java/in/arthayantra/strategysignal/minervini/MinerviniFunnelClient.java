package in.arthayantra.strategysignal.minervini;

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
 * Reads the SEPA funnel from market-data (D8 — this service holds no marketdata grant) for the
 * Phase-9 daily swing batch. Returns the day's immediately-buyable + on-deck VCP candidates with the
 * per-symbol geometry the engine seeds into the entry gate ({@code pivot}/{@code cheatPivot}/{@code
 * thrust}) plus the Stage. Empty on any upstream failure — a missing funnel means the batch simply
 * has nothing to evaluate, never a crash.
 */
@Component
public class MinerviniFunnelClient {

  private static final Logger log = LoggerFactory.getLogger(MinerviniFunnelClient.class);

  /**
   * One funnel candidate + its seeded geometry. {@code onDeck} is which funnel bucket the row came
   * from — the engine admits it only for a strategy whose {@code universe.bucket} includes on-deck.
   */
  public record Candidate(
      String symbol,
      BigDecimal close,
      BigDecimal pivot,
      BigDecimal cheatPivot,
      boolean thrust,
      Integer stage,
      String footprint,
      boolean onDeck) {}

  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  /** Wires the market-data base URL (REST only — no schema read). */
  public MinerviniFunnelClient(
      RestClient.Builder builder,
      ObjectMapper objectMapper,
      @Value("${artha.marketdata.base-url}") String baseUrl) {
    this.restClient = builder.baseUrl(baseUrl).build();
    this.objectMapper = objectMapper;
  }

  /** The buyable + on-deck candidates (the VCP names at or approaching their pivot). Empty on failure. */
  public List<Candidate> buyableAndOnDeck() {
    try {
      String body =
          restClient
              .get()
              .uri("/api/v1/market/screener/minervini/funnel")
              .retrieve()
              .body(String.class);
      JsonNode root = objectMapper.readTree(body);
      List<Candidate> out = new ArrayList<>();
      collect(root.path("immediatelyBuyable"), out, false);
      collect(root.path("onDeck"), out, true);
      return out;
    } catch (java.io.IOException | RuntimeException e) {
      log.warn("minervini funnel fetch failed — no swing candidates this run: {}", e.getMessage());
      return List.of();
    }
  }

  private static void collect(JsonNode rows, List<Candidate> out, boolean onDeck) {
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
              decimal(row, "cheatPivot"),
              row.path("thrust").asBoolean(false),
              row.hasNonNull("stage") ? row.path("stage").asInt() : null,
              row.path("footprint").asText(null),
              onDeck));
    }
  }

  private static BigDecimal decimal(JsonNode row, String field) {
    return row.hasNonNull(field) ? new BigDecimal(row.path(field).asText()) : null;
  }
}
