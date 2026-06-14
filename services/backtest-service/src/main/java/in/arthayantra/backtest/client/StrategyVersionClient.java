package in.arthayantra.backtest.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Resolves and validates an immutable strategy version over the strategy-signal REST API (D14).
 * Resolved versions are Caffeine-cached because immutable versions never change (D11). The
 * version-resolution default (§D.5): an explicit version is fetched directly; an omitted version
 * pins the registry's current resolution (latest published, else latest draft) at submission so a
 * job is never "whatever is current".
 */
@Component
public class StrategyVersionClient {

  private final RestClient http;
  private final Cache<String, ResolvedVersion> cache =
      Caffeine.newBuilder().maximumSize(500).expireAfterAccess(Duration.ofHours(1)).build();

  /** The pinned version: strategy id, semver, immutable checksum and the full config. */
  public record ResolvedVersion(UUID strategyId, String version, String checksum, JsonNode config) {}

  /** Builds the client against the strategy-signal base URL. */
  public StrategyVersionClient(@Value("${artha.strategy.base-url}") String baseUrl) {
    this.http = RestClient.builder().baseUrl(baseUrl).build();
  }

  /** Resolves (and validates the existence of) the pinned version. */
  public ResolvedVersion resolve(String strategyId, String version) {
    UUID id = parseId(strategyId);
    if (version != null && !version.isBlank()) {
      return cache.get(id + "|" + version, k -> fetch(id, version));
    }
    return fetch(id, null);
  }

  private ResolvedVersion fetch(UUID strategyId, String version) {
    String uri =
        version == null
            ? "/api/v1/strategies/" + strategyId
            : "/api/v1/strategies/" + strategyId + "/versions/" + version;
    try {
      JsonNode body = http.get().uri(uri).retrieve().body(JsonNode.class);
      if (body == null) {
        throw new NotFoundException(ErrorCodes.NOT_FOUND_RESOURCE, "empty strategy response");
      }
      return new ResolvedVersion(
          strategyId,
          body.path("version").asText(),
          body.path("checksum").asText(),
          body.path("config"));
    } catch (HttpClientErrorException.NotFound e) {
      throw new NotFoundException(
          ErrorCodes.NOT_FOUND_RESOURCE,
          "strategy not found: " + strategyId + (version == null ? "" : (" v" + version)));
    } catch (HttpServerErrorException | ResourceAccessException e) {
      throw new ApiException(
          503, ErrorCodes.UPSTREAM_UNAVAILABLE, "strategy-signal unavailable: " + e.getMessage());
    }
  }

  /**
   * Resolves the strategy's universe to the pinned copy + checksum over REST (Phase 44 / S8). The
   * resolver lives in strategy-signal-service (REST-only, no marketdata grant); backtest-service
   * merely COPIES the result into the job request. Empty when the mode needs no pinning or the
   * resolver is unreachable.
   */
  public Optional<JsonNode> resolveUniverse(UUID strategyId, String version) {
    String uri =
        "/api/v1/strategies/" + strategyId + "/universe"
            + (version == null || version.isBlank() ? "" : "?version=" + version);
    try {
      return Optional.ofNullable(http.get().uri(uri).retrieve().body(JsonNode.class));
    } catch (HttpClientErrorException | HttpServerErrorException | ResourceAccessException e) {
      return Optional.empty();
    }
  }

  private static UUID parseId(String strategyId) {
    if (strategyId == null || strategyId.isBlank()) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "strategyId is required");
    }
    try {
      return UUID.fromString(strategyId);
    } catch (IllegalArgumentException e) {
      throw new NotFoundException(ErrorCodes.NOT_FOUND_RESOURCE, "not a strategy id: " + strategyId);
    }
  }
}
