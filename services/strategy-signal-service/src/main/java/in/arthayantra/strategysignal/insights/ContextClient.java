package in.arthayantra.strategysignal.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The insight plane's read seam to market-data's trust/context rails (INT design §9.2): a
 * {@code RestClient} to the configured market-data base URL, structurally the {@code MarketOiClient}
 * precedent but with the explicit short connect/read timeouts of {@code PaperMarginClient} (1.5s /
 * 2s) so a slow (not merely down) market-data can never stall an insight sweep. EVERY read is
 * fail-soft: any transport/parse failure yields {@link Optional#empty()} → the caller degrades that
 * family to a conservative state, never a 5xx (§7.1, "any fetch failure → conservative DEGRADED").
 */
@Component
public class ContextClient {

  private static final Logger log = LoggerFactory.getLogger(ContextClient.class);

  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  /** Wires the market-data base URL with explicit short timeouts (fail-soft, off the hot path). */
  public ContextClient(
      RestClient.Builder builder,
      ObjectMapper objectMapper,
      @Value("${artha.marketdata.base-url}") String baseUrl) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(1500));
    factory.setReadTimeout(Duration.ofMillis(2000));
    this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
    this.objectMapper = objectMapper;
  }

  /** The per-source EOD ingest health board ({@code GET /api/v1/market/health/ingest}), or empty. */
  public Optional<JsonNode> ingestHealth() {
    return get("/api/v1/market/health/ingest");
  }

  /** The live data-plane health ({@code GET /api/v1/market/health/data}), or empty. */
  public Optional<JsonNode> dataHealth() {
    return get("/api/v1/market/health/data");
  }

  /**
   * The options-context digest for one underlying ({@code GET /api/v1/market/context/options-digest})
   * parsed into the shift view (INT design §6.1.1, SHIPPED #745). Empty when the endpoint is
   * unreachable / returns nothing — the CONTEXT_SHIFT generator then simply skips this underlying.
   */
  public Optional<MarketContext.OptionsShift> optionsShift(String exchange, String name) {
    String path =
        UriComponentsBuilder.fromPath("/api/v1/market/context/options-digest")
            .queryParam("name", name)
            .toUriString();
    return get(path)
        .map(
            d ->
                new MarketContext.OptionsShift(
                    exchange,
                    d.path("underlying").asText(name),
                    text(d, "expiry"),
                    decimal(d.path("pcr"), "now"),
                    decimal(d.path("pcr"), "deltaVsOpen"),
                    decimal(d.path("maxPain"), "drift"),
                    decimal(d.path("atmStraddle"), "deltaPct"),
                    d.path("activeStrikes").has("changedCount")
                        ? d.path("activeStrikes").path("changedCount").asInt()
                        : null,
                    text(d.path("oiStructure"), "verdict"),
                    d.path("dataTrust").asText("OK"),
                    reasons(d.path("trustReasons")),
                    d.path("asOf").asText(null)));
  }

  /**
   * The day-context one-call ({@code GET /api/v1/market/context/day-context}) parsed into the
   * market-structure view (INT design §6.1.5, SHIPPED #745): INDIA VIX band + index range regime.
   */
  public Optional<MarketContext.MarketStructureView> marketStructure() {
    return get("/api/v1/market/context/day-context")
        .map(
            d -> {
              JsonNode vix = d.path("vix");
              JsonNode ipa = d.path("indexPriceAction");
              String optionsTrust = d.path("options").path("dataTrust").asText("OK");
              return new MarketContext.MarketStructureView(
                  text(ipa, "symbol"),
                  decimal(vix, "level"),
                  text(vix, "band"),
                  decimal(ipa, "gapOpenPct"),
                  text(ipa, "rangeState"),
                  decimal(ipa, "rangeVsAvg"),
                  text(ipa, "direction"),
                  optionsTrust,
                  d.path("asOf").asText(null));
            });
  }

  /**
   * The live-capture canary's stalled instruments ({@code exchange:tradingsymbol} key → problem
   * detail) from {@code /market/health/data} — the RISK_STALE_TICK interim trigger (§12). Only
   * {@code TICK_BAR_DIVERGENCE} problems (a bar stall on a held instrument) are surfaced. Empty map
   * when the feed is healthy or the rail is unreachable.
   */
  public Map<String, String> staleFeedKeys() {
    return dataHealth()
        .map(
            report -> {
              Map<String, String> keys = new LinkedHashMap<>();
              for (JsonNode p : report.path("problems")) {
                if ("TICK_BAR_DIVERGENCE".equals(p.path("check").asText())) {
                  keys.put(p.path("key").asText(), p.path("detail").asText("feed stalled"));
                }
              }
              return keys;
            })
        .orElseGet(Map::of);
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node.path(field);
    return v.isMissingNode() || v.isNull() ? null : v.asText();
  }

  private static BigDecimal decimal(JsonNode node, String field) {
    JsonNode v = node.path(field);
    if (v.isMissingNode() || v.isNull()) {
      return null;
    }
    if (v.isNumber()) {
      return v.decimalValue();
    }
    try {
      return new BigDecimal(v.asText());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static List<String> reasons(JsonNode arr) {
    if (arr == null || !arr.isArray()) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (JsonNode r : arr) {
      out.add(r.asText());
    }
    return out;
  }

  private Optional<JsonNode> get(String path) {
    try {
      String body = restClient.get().uri(path).retrieve().body(String.class);
      if (body == null || body.isBlank()) {
        return Optional.empty();
      }
      return Optional.of(objectMapper.readTree(body));
    } catch (HttpClientErrorException.NotFound e) {
      // A 404 here is an EXPECTED, permanent shape, not a fault: the endpoint answers only for
      // underlyings it has option expiries for. DEBUG deliberately, because the alternative is 28
      // WARN lines every weekday (the context sweep is `0 */15 9-15 * * MON-FRI`), and a WARN that
      // fires on schedule trains the reader to ignore WARNs -- which is the exact property this
      // class's siblings were just changed to protect.
      //
      // ⚠️ It is currently hiding a REAL defect, and that is recorded rather than re-buried:
      // `artha.insights.context.underlyings` is [NIFTY, SENSEX] (application.yml:268) but the
      // canonical name is `NIFTY 50` -- measured 2026-08-18, NIFTY 404s and `NIFTY 50` returns 200,
      // and `strategy.insights` holds ZERO NIFTY-scoped CONTEXT_SHIFT rows in the entire history of
      // the feature. Fixing the name is a BEHAVIOUR change (insights that have never fired would
      // start), so it is a ledger item, not a line in an observability PR.
      log.debug("insight trust read {} — no such digest (returning empty)", path);
      return Optional.empty();
    } catch (Exception e) {
      log.warn("insight trust read {} FAILED (returning empty): {}", path, e.toString());
      return Optional.empty();
    }
  }
}
