package in.arthayantra.strategysignal.manas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
   * to the matching strategy. {@code onDeck} is which funnel bucket the row came from — the engine
   * admits it only for a strategy whose {@code universe.bucket} includes on-deck.
   */
  public record Candidate(
      String symbol,
      BigDecimal close,
      BigDecimal pivot,
      String setupType,
      String footprint,
      BigDecimal breakoutPivot,
      BigDecimal vcpPivot,
      boolean onDeck) {}

  /** The screen date and candidates returned by one funnel HTTP response. */
  public record Snapshot(LocalDate screenDate, List<Candidate> candidates) {

    public Snapshot {
      candidates = List.copyOf(candidates);
    }
  }

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
      JsonNode root = objectMapper.readTree(fetchFunnel());
      List<Candidate> out = new ArrayList<>();
      collect(root.path("immediatelyBuyable"), out, false);
      collect(root.path("onDeck"), out, true);
      return out;
    } catch (java.io.IOException | RuntimeException e) {
      log.warn("manas funnel fetch failed — no swing candidates this run: {}", e.getMessage());
      return List.of();
    }
  }

  /**
   * The funnel's own {@code screenDate} — WHICH session's screen these candidates came from. The
   * catch-up path's input-readiness gate: the funnel endpoint falls back to the newest PERSISTED screen
   * date, so a session whose screener never ran serves the PRIOR day's names with no error (exactly the
   * 2026-07-17 shape — the screens did not land until 22:15). Empty on any upstream failure, which the
   * caller must treat as "not ready", never as "ready".
   */
  public java.util.Optional<java.time.LocalDate> screenDate() {
    try {
      JsonNode date = objectMapper.readTree(fetchFunnel()).path("screenDate");
      return date.isMissingNode() || date.isNull()
          ? java.util.Optional.empty()
          : java.util.Optional.of(java.time.LocalDate.parse(date.asText()));
    } catch (java.io.IOException | RuntimeException e) {
      log.warn("manas funnel screen-date read failed: {}", e.getMessage());
      return java.util.Optional.empty();
    }
  }

  /** Reads the date and both candidate buckets from exactly one HTTP response. */
  public Optional<Snapshot> snapshot() {
    try {
      JsonNode root = objectMapper.readTree(fetchFunnel());
      JsonNode date = root.path("screenDate");
      if (date.isMissingNode() || date.isNull()) {
        log.warn("manas funnel response has no screen date");
        return Optional.empty();
      }
      List<Candidate> out = new ArrayList<>();
      collect(root.path("immediatelyBuyable"), out, false);
      collect(root.path("onDeck"), out, true);
      return Optional.of(new Snapshot(LocalDate.parse(date.asText()), out));
    } catch (java.io.IOException | RuntimeException e) {
      log.warn("manas funnel snapshot fetch failed: {}", e.getMessage());
      return Optional.empty();
    }
  }

  private String fetchFunnel() {
    return restClient
        .get()
        .uri("/api/v1/market/screener/manas-arora/funnel")
        .retrieve()
        .body(String.class);
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
              row.path("setupType").asText(null),
              row.path("footprint").asText(null),
              decimal(row, "breakoutPivot"),
              decimal(row, "vcpPivot"),
              onDeck));
    }
  }

  private static BigDecimal decimal(JsonNode row, String field) {
    return row.hasNonNull(field) ? new BigDecimal(row.path(field).asText()) : null;
  }
}
