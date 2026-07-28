package in.arthayantra.strategysignal.scalper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Resolves an option's canonical exchange from the instrument master, by tradingsymbol alone.
 *
 * <p><b>Why this exists: deploy ordering.</b> The exchange normally rides the chain payload —
 * market-data resolved the leg from the master to quote it, so it publishes {@code (exchange,
 * tradingsymbol)} as a pair and the scalper path costs no extra lookup. But that field is NEW. A
 * strategy-signal newer than its market-data sees it on no leg at all, and since the entry path
 * refuses a leg without one (it would 404 the instrument-meta lookup into a lot-1 equity proxy and
 * mis-size the position), <b>every scalper entry would be silently refused</b> until market-data
 * caught up — a worse failure than the mis-routing being fixed, and one that looks exactly like a
 * quiet tape.
 *
 * <p>Rather than document a deploy order and hope, this resolves the same value from {@code GET
 * /api/v1/instruments/search} — an endpoint that long predates the new field, so it is available on
 * every market-data build the scalpers have ever run against. The answer is still the instrument
 * master's; this is a second route to the same authority, <b>never</b> a name-prefix guess. Cross-
 * vendor review (Major 3) rejected the counter-plus-documented-order mitigation for precisely this
 * reason: the handshake has to be a runtime capability, not a runbook step.
 *
 * <p><b>Exact match only.</b> {@code /search} is a ranked trigram typeahead, so its ORDER is not a
 * contract. Only rows whose tradingsymbol equals the query exactly are considered, and the exchange
 * is returned only when those rows agree on one — {@code (exchange, tradingsymbol)} is the master's
 * primary key, so the same symbol on two exchanges is genuinely ambiguous and gets refused, not
 * broken arbitrarily.
 */
@Component
public class OptionExchangeResolver {

  private static final Logger log = LoggerFactory.getLogger(OptionExchangeResolver.class);

  /**
   * Positive answers only, and never evicted by age: an instrument's exchange is immutable for the
   * life of the contract. Negatives are deliberately NOT cached — a miss usually means the master
   * has not synced that contract yet (the 08:30 IST sync), and caching it would outlive the gap.
   * Cleared wholesale past the cap so a long-running process cannot accumulate expired weeklies.
   */
  private static final int CACHE_CAP = 4_000;

  private final Map<String, String> cache = new ConcurrentHashMap<>();
  private final RestClient restClient;

  /** Wires the configured market-data base URL (same bean pattern as the other clients). */
  public OptionExchangeResolver(
      RestClient.Builder builder, @Value("${artha.marketdata.base-url}") String baseUrl) {
    this.restClient = builder.baseUrl(baseUrl).build();
  }

  /**
   * The master's exchange for {@code tradingsymbol}, or null when it cannot be resolved
   * unambiguously. Fail-soft: an upstream error returns null (the caller then refuses the entry),
   * never an exception into the eval loop.
   */
  public String resolve(String tradingsymbol) {
    if (tradingsymbol == null || tradingsymbol.isBlank()) {
      return null;
    }
    String cached = cache.get(tradingsymbol);
    if (cached != null) {
      return cached;
    }
    String resolved = lookup(tradingsymbol);
    if (resolved != null) {
      if (cache.size() >= CACHE_CAP) {
        cache.clear();
      }
      cache.put(tradingsymbol, resolved);
    }
    return resolved;
  }

  private String lookup(String tradingsymbol) {
    try {
      List<InstrumentRow> rows =
          restClient
              .get()
              .uri(
                  builder ->
                      builder
                          .path("/api/v1/instruments/search")
                          .queryParam("q", tradingsymbol)
                          .queryParam("limit", 20)
                          .build())
              .retrieve()
              .body(new org.springframework.core.ParameterizedTypeReference<List<InstrumentRow>>() {});
      if (rows == null) {
        return null;
      }
      List<String> exact =
          rows.stream()
              .filter(r -> tradingsymbol.equals(r.tradingsymbol()))
              .map(InstrumentRow::exchange)
              .filter(e -> e != null && !e.isBlank())
              .distinct()
              .toList();
      if (exact.size() == 1) {
        return exact.get(0);
      }
      log.warn(
          "instrument master could not resolve one exchange for {} — {} exact matches {}",
          tradingsymbol, exact.size(), exact);
      return null;
    } catch (RestClientException e) {
      log.warn("instrument-master exchange lookup failed for {}: {}", tradingsymbol, e.getMessage());
      return null;
    }
  }

  /** The slice of the instrument-master row this resolver needs. */
  private record InstrumentRow(String exchange, String tradingsymbol) {}
}
