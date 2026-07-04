package in.arthayantra.strategysignal.registry;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Phase 44 (S8) universe resolver. Resolves a strategy's {@code universe} to an ORDERED
 * {@code (exchange, tradingsymbol)} list + a SHA-256 checksum, at SUBMISSION time, by COPY — never an
 * FK, never lazy resolution inside workers. {@code index_constituents} resolves via market-data's
 * constituents REST (this service holds NO {@code marketdata} grant — D7/D10);
 * {@code futures_of_underlying} resolves the front/next contract via the term-structure REST. The
 * checksum is byte-identical to the market-data canonical form ({@code EXCHANGE:SYMBOL\n} joined).
 */
@Component
public class UniverseResolver {

  /** One resolved member. */
  public record Constituent(String exchange, String tradingsymbol) {}

  /** The pinned universe copy + its checksum (and the survivorship caveat where it applies). */
  public record ResolvedUniverse(
      String mode, String asOf, List<Constituent> items, String checksum, String survivorshipCaveat) {}

  private static final String SURVIVORSHIP_CAVEAT =
      "Resolves CURRENT index membership — backtest windows predating constituent capture carry a"
          + " survivorship-bias caveat (NSE publishes only the current list).";

  private final RestClient marketData;

  /** Wires the market-data base URL (REST only — no schema read). */
  public UniverseResolver(
      RestClient.Builder builder, @Value("${artha.marketdata.base-url}") String baseUrl) {
    this.marketData = builder.baseUrl(baseUrl).build();
  }

  /** Resolves the universe of a strategy config into a pinned, checksummed copy. */
  public ResolvedUniverse resolve(JsonNode config) {
    JsonNode universe = config.path("universe");
    String mode = universe.path("mode").asText("explicit");
    return switch (mode) {
      case "index_constituents" -> resolveIndex(universe);
      case "futures_of_underlying" -> resolveFutures(universe);
      case "futures_screener" -> resolveFuturesScreener(universe);
      case "minervini_funnel" -> resolveMinerviniFunnel(universe);
      default -> resolveExplicit(mode, universe);
    };
  }

  /**
   * Phase-9 (MV-9.1): the Minervini SEPA-funnel universe — the day's immediately-buyable (+ on-deck)
   * VCP candidates from market-data's funnel. Pinned by copy like the other dynamic modes; empty when
   * the funnel is unavailable/unpopulated (off-hours / fresh env), so the batch simply has nothing to
   * evaluate rather than 5xx-ing. The live daily batch re-reads the funnel each session — this pin is
   * a display/validation snapshot, never the authoritative evaluation universe.
   */
  private ResolvedUniverse resolveMinerviniFunnel(JsonNode universe) {
    boolean onDeck = !"buyable".equals(universe.path("bucket").asText("buyable_on_deck"));
    List<Constituent> items = new ArrayList<>();
    try {
      JsonNode funnel = get("/api/v1/market/screener/minervini/funnel");
      collectFunnel(funnel.path("immediatelyBuyable"), items);
      if (onDeck) {
        collectFunnel(funnel.path("onDeck"), items);
      }
    } catch (ApiException unavailable) {
      // funnel down / no screen yet -> pin empty (nothing to evaluate this session)
    }
    return new ResolvedUniverse("minervini_funnel", null, items, checksum(items), null);
  }

  /** Appends each funnel row's symbol as an NSE-EQ constituent (dedup-safe). */
  private static void collectFunnel(JsonNode rows, List<Constituent> items) {
    for (JsonNode row : rows) {
      String symbol = row.path("symbol").asText("");
      Constituent c = new Constituent("NSE", symbol);
      if (!symbol.isBlank() && !items.contains(c)) {
        items.add(c);
      }
    }
  }

  private ResolvedUniverse resolveIndex(JsonNode universe) {
    String index = universe.path("index").asText();
    if (index.isBlank()) {
      throw new ApiException(422, ErrorCodes.STRATEGY_SCHEMA_INVALID, "index_constituents requires universe.index");
    }
    JsonNode body =
        get("/api/v1/instruments/indices/{index}/constituents", index);
    String asOf = body.path("asOf").asText(null);
    Set<String> exclude = excludeSet(universe);
    List<Constituent> items = new ArrayList<>();
    for (JsonNode row : body.path("items")) {
      Constituent c = new Constituent(row.path("exchange").asText(), row.path("tradingsymbol").asText());
      if (!exclude.contains(c.tradingsymbol())) {
        items.add(c);
      }
    }
    return new ResolvedUniverse("index_constituents", asOf, items, checksum(items), SURVIVORSHIP_CAVEAT);
  }

  private ResolvedUniverse resolveFutures(JsonNode universe) {
    String underlying = universe.path("underlying").asText();
    String contract = universe.path("futures").path("contract").asText("front_month");
    JsonNode ts = get("/api/v1/market/futures/term-structure?underlying={u}", underlying);
    JsonNode legs = ts.path("contracts");
    int leg = "next_month".equals(contract) && legs.size() > 1 ? 1 : 0;
    List<Constituent> items = new ArrayList<>();
    if (legs.size() > leg) {
      items.add(new Constituent("NFO", legs.get(leg).path("tradingsymbol").asText()));
    }
    return new ResolvedUniverse("futures_of_underlying", null, items, checksum(items), null);
  }

  /**
   * E1 §3.3: pins the Market-Movers stock-future universe at submission — the screener's top
   * {@code max_picks} conviction-ranked movers for {@code side}, each mapped to its FRONT contract via
   * term-structure. Pinned by copy (like {@code futures_of_underlying}/{@code index_constituents}), so
   * backtest replay reads THIS snapshot, never re-screens. Empty when the screener/term-structure is
   * unavailable at submission (off-hours / no movers) — the backtest then runs an empty universe.
   */
  private ResolvedUniverse resolveFuturesScreener(JsonNode universe) {
    String side = universe.path("side").asText("long");
    int maxPicks = universe.path("max_picks").asInt(5);
    String moversUri =
        "upstox".equals(universe.path("source").asText("captured"))
            ? "/api/v1/market/futures/movers-screen?source=upstox"
            : "/api/v1/market/futures/movers-screen";
    List<Constituent> items = new ArrayList<>();
    try {
      JsonNode screen = get(moversUri);
      for (String underlying : screenerPicks(screen, side, maxPicks)) {
        JsonNode legs =
            get("/api/v1/market/futures/term-structure?underlying={u}", underlying).path("contracts");
        if (!legs.isEmpty()) {
          Constituent c = new Constituent("NFO", legs.get(0).path("tradingsymbol").asText());
          if (!items.contains(c)) {
            items.add(c);
          }
        }
      }
    } catch (ApiException unavailable) {
      // screener/term-structure down at submission -> pin empty (no movers to trade this window)
    }
    return new ResolvedUniverse("futures_screener", null, items, checksum(items), null);
  }

  /** The screener's conviction-ranked picked UNDERLYINGS for {@code side}, capped at {@code maxPicks}. */
  static List<String> screenerPicks(JsonNode moversScreen, String side, int maxPicks) {
    String key = "short".equals(side) ? "shortCandidates" : "longCandidates";
    List<String> out = new ArrayList<>();
    for (JsonNode row : moversScreen.path(key)) {
      if (out.size() >= maxPicks) {
        break;
      }
      String symbol = row.path("symbol").asText("");
      if (!symbol.isBlank() && !out.contains(symbol)) {
        out.add(symbol);
      }
    }
    return out;
  }

  private ResolvedUniverse resolveExplicit(String mode, JsonNode universe) {
    List<Constituent> items = new ArrayList<>();
    for (JsonNode row : universe.path("instruments")) {
      items.add(new Constituent(row.path("exchange").asText(), row.path("tradingsymbol").asText()));
    }
    return new ResolvedUniverse(mode, null, items, checksum(items), null);
  }

  private static Set<String> excludeSet(JsonNode universe) {
    Set<String> exclude = new HashSet<>();
    for (JsonNode row : universe.path("filters").path("exclude")) {
      exclude.add(row.asText());
    }
    return exclude;
  }

  private JsonNode get(String uri, Object... vars) {
    try {
      JsonNode body = marketData.get().uri(uri, vars).retrieve().body(JsonNode.class);
      if (body == null) {
        throw new ApiException(503, ErrorCodes.UPSTREAM_UNAVAILABLE, "empty market-data response");
      }
      return body;
    } catch (RestClientException e) {
      throw new ApiException(
          503, ErrorCodes.UPSTREAM_UNAVAILABLE, "market-data unavailable for universe resolution: " + e.getMessage());
    }
  }

  /** SHA-256 over the canonical ordered list ({@code EXCHANGE:SYMBOL} joined by newlines). */
  public static String checksum(List<Constituent> items) {
    StringBuilder sb = new StringBuilder();
    for (Constituent c : items) {
      sb.append(c.exchange()).append(':').append(c.tradingsymbol()).append('\n');
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
