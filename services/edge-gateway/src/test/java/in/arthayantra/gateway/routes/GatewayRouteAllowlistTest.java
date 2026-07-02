package in.arthayantra.gateway.routes;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Route-allowlist tripwire (audit P2): every {@code /api/v1} path a backend service publishes in
 * its committed OpenAPI spec must be matched by that service's {@code Path=} predicates in the
 * gateway's route table — an unmatched prefix falls through to the SPA catch-all and returns
 * HTTP 200 + index.html, the recurring silently-empty-page incident this repo has already paid
 * for (the {@code /api/v1/signal-rejections} miss, #404: a sibling prefix does NOT match).
 *
 * <p>Pure-file test: parses the committed specs under {@code contracts/} and the raw route yml —
 * no containers, runs in every ci-java strategy-gateway shard. When it fails: add the new prefix
 * to the owning service's Path= list in application.yml AND rebuild the edge-gateway image (the
 * yml is embedded in the JAR).
 */
class GatewayRouteAllowlistTest {

  /** spec file → the uri host its route table entry targets. margin has no committed spec yet. */
  private static final Map<String, String> SPEC_TO_ROUTE_HOST =
      Map.of(
          "market-data-service.openapi.json", "market-data-service",
          "strategy-signal-service.openapi.json", "strategy-signal-service",
          "backtest-service.openapi.json", "backtest-service",
          "optimizer-service.openapi.json", "optimizer-service");

  @Test
  void everySpecPathIsMatchedByItsServicesRoutePredicates() throws IOException {
    Path repoRoot = findRepoRoot();
    Map<String, List<String>> prefixesByHost = routePrefixesByHost(repoRoot);
    ObjectMapper mapper = new ObjectMapper();

    List<String> misses = new ArrayList<>();
    for (Map.Entry<String, String> spec : SPEC_TO_ROUTE_HOST.entrySet()) {
      Path specFile = repoRoot.resolve("contracts").resolve(spec.getKey());
      assertThat(specFile).as("committed spec %s", spec.getKey()).exists();
      List<String> prefixes = prefixesByHost.getOrDefault(spec.getValue(), List.of());
      assertThat(prefixes).as("route predicates for %s", spec.getValue()).isNotEmpty();

      JsonNode paths = mapper.readTree(specFile.toFile()).path("paths");
      paths
          .fieldNames()
          .forEachRemaining(
              apiPath -> {
                if (!apiPath.startsWith("/api/v1/")) {
                  return; // non-API paths (actuator/docs) are not routed surfaces
                }
                if (prefixes.stream().noneMatch(p -> matches(p, apiPath))) {
                  misses.add(spec.getKey() + " → " + apiPath);
                }
              });
    }

    assertThat(misses)
        .withFailMessage(
            "These spec paths fall through to the SPA catch-all (200 + index.html — the #404"
                + " incident class). Add the prefix to the owning service's Path= list in"
                + " services/edge-gateway/src/main/resources/application.yml and rebuild the"
                + " gateway image:%n%s",
            String.join(System.lineSeparator(), misses))
        .isEmpty();
  }

  /** A gateway Path predicate ({@code /x/**} prefix or an exact literal) vs a spec path. */
  private static boolean matches(String predicate, String apiPath) {
    if (predicate.endsWith("/**")) {
      String prefix = predicate.substring(0, predicate.length() - 2); // keep the trailing slash
      return apiPath.startsWith(prefix) || apiPath.equals(prefix.substring(0, prefix.length() - 1));
    }
    return apiPath.equals(predicate);
  }

  /**
   * Parses the raw route yml: pairs each {@code uri:} host with its following {@code Path=} list.
   * Regex on purpose — the file is small, the shapes are pinned by this very test, and a full
   * Spring context boot would defeat the pure-file speed.
   */
  private static Map<String, List<String>> routePrefixesByHost(Path repoRoot) throws IOException {
    String yml =
        Files.readString(
            repoRoot.resolve("services/edge-gateway/src/main/resources/application.yml"));
    Map<String, List<String>> byHost = new LinkedHashMap<>();
    Matcher route =
        Pattern.compile(
                "uri:\\s*\\$\\{[A-Z_]+:http://([a-z-]+):\\d+}[\\s\\S]*?Path=([^\\n]+)")
            .matcher(yml);
    while (route.find()) {
      String host = route.group(1);
      for (String predicate : route.group(2).trim().split(",")) {
        byHost.computeIfAbsent(host, h -> new ArrayList<>()).add(predicate.trim());
      }
    }
    return byHost;
  }

  private static Path findRepoRoot() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.isDirectory(dir.resolve("contracts"))) {
      dir = dir.getParent();
    }
    assertThat(dir).as("repo root (contracts/ dir) above the module dir").isNotNull();
    return dir;
  }
}
