package in.arthayantra.strategysignal.minervini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
  private static final int CONNECT_TIMEOUT_MS = 2_000;
  private static final int READ_TIMEOUT_MS = 30_000;

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

  /** The screen date and candidates returned by one funnel HTTP response. */
  public record Snapshot(LocalDate screenDate, List<Candidate> candidates) {

    public Snapshot {
      candidates = List.copyOf(candidates);
    }
  }

  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  /** Wires the market-data base URL with explicit bounds for the synchronous funnel read. */
  @Autowired
  public MinerviniFunnelClient(
      RestClient.Builder builder,
      ObjectMapper objectMapper,
      @Value("${artha.marketdata.base-url}") String baseUrl) {
    this(builder, objectMapper, baseUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
  }

  /** Test seam for exercising the real request-factory timeout against a delayed HTTP server. */
  public MinerviniFunnelClient(
      RestClient.Builder builder,
      ObjectMapper objectMapper,
      String baseUrl,
      int connectTimeoutMs,
      int readTimeoutMs) {
    if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
      throw new IllegalArgumentException("funnel timeouts must be positive");
    }
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
    factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
    this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
    this.objectMapper = objectMapper;
  }

  /** The buyable + on-deck candidates (the VCP names at or approaching their pivot). Empty on failure. */
  public List<Candidate> buyableAndOnDeck() {
    try {
      JsonNode root = objectMapper.readTree(fetchFunnel());
      List<Candidate> out = new ArrayList<>();
      collect(root.path("immediatelyBuyable"), out, false);
      collect(root.path("onDeck"), out, true);
      return out;
    } catch (java.io.IOException | RuntimeException e) {
      log.warn("minervini funnel fetch failed — no swing candidates this run: {}", e.getMessage());
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
      log.warn("minervini funnel screen-date read failed: {}", e.getMessage());
      return java.util.Optional.empty();
    }
  }

  /** Reads the date and both candidate buckets from exactly one HTTP response. */
  public Optional<Snapshot> snapshot() {
    try {
      JsonNode root = objectMapper.readTree(fetchFunnel());
      JsonNode date = root.path("screenDate");
      if (date.isMissingNode() || date.isNull()) {
        log.warn("minervini funnel response has no screen date");
        return Optional.empty();
      }
      List<Candidate> out = new ArrayList<>();
      collect(root.path("immediatelyBuyable"), out, false);
      collect(root.path("onDeck"), out, true);
      return Optional.of(new Snapshot(LocalDate.parse(date.asText()), out));
    } catch (java.io.IOException | RuntimeException e) {
      log.warn("minervini funnel snapshot fetch failed: {}", e.getMessage());
      return Optional.empty();
    }
  }

  private String fetchFunnel() {
    return restClient
        .get()
        .uri("/api/v1/market/screener/minervini/funnel")
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
