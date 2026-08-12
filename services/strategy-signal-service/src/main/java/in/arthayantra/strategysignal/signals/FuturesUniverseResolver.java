package in.arthayantra.strategysignal.signals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.ErrorResponse;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * A7/A11 [FP-11a]: resolves {@code futures_of_underlying} to the ACTUAL front/next contract via
 * market-data's term-structure surface (Stage B Phase 15A). Roll re-subscribe: within
 * {@code roll_days_before_expiry} trading days of expiry the resolution slides one contract out
 * — the engine re-resolves daily at 08:40, so subscriptions follow the roll automatically. Live
 * always trades the actual contract; the continuous-series counterpart is replay territory.
 */
@Component
public class FuturesUniverseResolver {

  private static final Logger log = LoggerFactory.getLogger(FuturesUniverseResolver.class);
  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  /** Wires the market-data base URL. */
  public FuturesUniverseResolver(
      RestClient.Builder builder,
      ObjectMapper objectMapper,
      Clock clock,
      @Value("${artha.marketdata.base-url}") String baseUrl) {
    this.restClient = builder.baseUrl(baseUrl).build();
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  /**
   * The contract(s) the strategy trades right now; empty Optional when resolution FAILS.
   *
   * <p>A 404 {@code NOT_FOUND_INSTRUMENT} from term-structure is a FAILURE here, deliberately. It
   * means "no non-expired active FUT row for this underlying" — for an INDEX root (NIFTY 50 /
   * SENSEX, i.e. every live strategy) that can never legitimately mean "nothing to trade": the
   * index always has listed futures, so a 404 is definitionally a data fault (a tombstoned NFO
   * sync, a drifted underlying derivation, a typo'd ref). Reading it as a genuine empty is exactly
   * the 2026-07-15/16 outage — the engine loads 0 strategies, counts 0 drops, and the retry chain
   * reports "resolved every universe". Only {@link #resolveScreener} may treat it as empty, via
   * {@code missingInstrumentIsEmpty}, because there a picked STOCK mover legitimately may be
   * cash-only.
   *
   * <p><b>A 200 carrying {@code "contracts": []} follows the SAME asymmetry</b> (H8 /
   * {@code task_f624fca7}, closing #877's one remaining fused branch). #877 un-fused
   * {@code !isArray()} from {@code isEmpty()} and gave them different meanings — non-array ⇒
   * unresolved, empty ⇒ legitimately empty. The second half was a deliberate choice resting on a
   * cross-service premise ("market-data never returns 200-with-empty-contracts") that was true,
   * load-bearing, and pinned by nothing. It is pinned now, on the producer side, by
   * {@code FuturesTermStructureServiceTest}; and on this side an empty array on the INDEX path is
   * a fault, because every legitimate "nothing to trade" reaches this class through a different
   * door — a cold chain 503s, off-hours serves a stale non-empty ladder, an unlisted underlying
   * 404s, and a screener that picked no movers returns empty from {@link #resolveScreener}, a
   * different site entirely. So reclassifying this branch cannot fire on a legitimate state.
   *
   * <p>Full evidence, including why it is unreachable today and why it would nonetheless be a
   * silent whole-book outage if it ever fired:
   * {@code docs/signal-analysis/2026-08-03-h8-empty-index-contracts.md}.
   */
  public Optional<List<StrategyDefinition.InstrumentRef>> resolve(
      String underlyingExchange, String underlying, String contract, int rollDaysBeforeExpiry) {
    return resolve(underlyingExchange, underlying, contract, rollDaysBeforeExpiry, false);
  }

  private Optional<List<StrategyDefinition.InstrumentRef>> resolve(
      String underlyingExchange,
      String underlying,
      String contract,
      int rollDaysBeforeExpiry,
      boolean missingInstrumentIsEmpty) {
    try {
      String body =
          restClient
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/api/v1/market/futures/term-structure")
                          .queryParam("underlying", underlying)
                          .build())
              .retrieve()
              .body(String.class);
      JsonNode response = body == null || body.isBlank() ? null : objectMapper.readTree(body);
      JsonNode contracts = response == null ? null : response.path("contracts");
      if (contracts == null || !contracts.isArray()) {
        log.warn("malformed futures contracts response for {} — unresolved universe", underlying);
        return Optional.empty();
      }
      if (contracts.isEmpty()) {
        if (!missingInstrumentIsEmpty) {
          // INDEX path: same rule as the 404 below, for the same reason. An index always has listed
          // futures, so "the ladder is present but empty" can no more be a legitimate stand-aside
          // than "the ladder is missing" — and reading it as one is the 2026-07-15/16 outage shape:
          // the strategy is dropped as RESOLVED_EMPTY, which is not counted in `unresolved`, is not
          // `abnormal()`, and is therefore skipped by the ARMED T9 coverage watchdog. The whole book
          // can go dark with every counter green and the dedicated pager holding its fire.
          //
          // Unreachable from today's producer — FuturesTermStructureService either throws or returns
          // a non-empty list, now pinned by FuturesTermStructureServiceTest — so this is defence in
          // depth against that producer being made fail-soft later, not a live fix. Nothing about
          // live behaviour changes today.
          log.warn(
              "empty futures contracts array for INDEX underlying {} — unresolved universe"
                  + " (an index ladder is never legitimately empty)",
              underlying);
          return Optional.empty();
        }
        log.warn("no futures contracts for underlying {} — empty universe (screener)", underlying);
        return Optional.of(List.of());
      }
      int index = "next_month".equals(contract) ? 1 : 0;
      // roll window: within N days of the front expiry, slide one contract out
      JsonNode front = contracts.get(0);
      LocalDate expiry = LocalDate.parse(front.path("expiry").asText());
      LocalDate today = LocalDate.now(clock.withZone(IST));
      if (!today.isBefore(expiry.minusDays(rollDaysBeforeExpiry))) {
        index++;
        log.info(
            "futures roll window for {} (expiry {}, roll {} days out) — sliding to contract {}",
            underlying, expiry, rollDaysBeforeExpiry, index);
      }
      if (index >= contracts.size()) {
        index = contracts.size() - 1;
      }
      JsonNode chosen = contracts.get(index);
      String exchange = chosen.path("exchange").asText("NFO");
      String tradingsymbol = chosen.path("tradingsymbol").asText("");
      if (exchange.isBlank() || tradingsymbol.isBlank()) {
        log.warn("malformed futures contract for {} — unresolved universe", underlying);
        return Optional.empty();
      }
      return Optional.of(
          List.of(
              new StrategyDefinition.InstrumentRef(exchange, tradingsymbol)));
    } catch (HttpClientErrorException.NotFound e) {
      if (missingInstrumentIsEmpty && isMissingInstrument(e)) {
        // Screener path ONLY: a picked stock mover with no listed futures is cash-only — skip it.
        // ⚠️ This message used to be BYTE-IDENTICAL to the empty-array branch's above, which emits
        // for the opposite reason. A live log line could not tell an upstream fault from a correct
        // skip, and the H8 investigation's own "zero occurrences" evidence survived only because the
        // count was zero. Both sites now name their cause.
        log.warn(
            "no listed futures for screener-picked underlying {} — skipped (cash-only)", underlying);
        return Optional.of(List.of());
      }
      log.warn("futures universe resolution failed for {}: {}", underlying, e.getMessage());
      return Optional.empty();
    } catch (RestClientException | java.io.IOException | DateTimeException e) {
      log.warn("futures universe resolution failed for {}: {}", underlying, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * E1 §3.3: resolves a {@code futures_screener} universe to the top {@code maxPicks} stock movers'
   * FRONT futures. Re-screens at each live reload (the picks roll daily): GETs the Market-Movers
   * screen, takes the conviction-ranked candidates for {@code side}, then maps each picked underlying
   * to its actual front contract via {@link #resolve} (so roll handling is shared). Empty when the
   * screen or any picked term structure fails; a picked mover with no listed futures is skipped, and
   * an empty present list means the screen resolved with no tradable movers.
   * {@code source} selects the screener radar: default = the captured NIFTY-Bank snapshot set;
   * {@code upstox} (E1 v2) = the full NIFTY-50 from on-demand Upstox daily candles.
   */
  public Optional<List<StrategyDefinition.InstrumentRef>> resolveScreener(
      String side, int maxPicks, String source) {
    boolean upstox = "upstox".equals(source);
    List<String> underlyings;
    try {
      String body =
          restClient
              .get()
              .uri(
                  uriBuilder -> {
                    uriBuilder.path("/api/v1/market/futures/movers-screen");
                    if (upstox) {
                      uriBuilder.queryParam("source", "upstox");
                    }
                    return uriBuilder.build();
                  })
              .retrieve()
              .body(String.class);
      JsonNode response = body == null || body.isBlank() ? null : objectMapper.readTree(body);
      String candidatesKey = "short".equals(side) ? "shortCandidates" : "longCandidates";
      JsonNode candidates = response == null ? null : response.path(candidatesKey);
      if (candidates == null || !candidates.isArray()) {
        log.warn("malformed movers-screen response — unresolved universe");
        return Optional.empty();
      }
      underlyings = screenerPicks(response, side, maxPicks);
    } catch (RestClientException | java.io.IOException e) {
      log.warn("movers-screen universe resolution failed: {}", e.getMessage());
      return Optional.empty();
    }
    List<StrategyDefinition.InstrumentRef> out = new ArrayList<>();
    for (String underlying : underlyings) {
      // missingInstrumentIsEmpty=true: a picked mover with no listed futures is legitimately
      // cash-only, NOT an upstream fault — skipping it must not brick the whole screen.
      Optional<List<StrategyDefinition.InstrumentRef>> resolved =
          resolve("NSE", underlying, "front_month", 2, true);
      if (resolved.isEmpty()) {
        return Optional.empty();
      }
      for (StrategyDefinition.InstrumentRef ref : resolved.get()) {
        if (!out.contains(ref)) {
          out.add(ref);
        }
      }
    }
    return Optional.of(out);
  }

  private boolean isMissingInstrument(HttpClientErrorException.NotFound notFound) {
    try {
      ErrorResponse error =
          objectMapper.readValue(notFound.getResponseBodyAsByteArray(), ErrorResponse.class);
      return error != null && ErrorCodes.NOT_FOUND_INSTRUMENT.equals(error.code());
    } catch (java.io.IOException malformedError) {
      return false;
    }
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
}
