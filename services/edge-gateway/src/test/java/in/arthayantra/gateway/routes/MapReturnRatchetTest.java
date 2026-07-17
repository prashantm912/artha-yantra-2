package in.arthayantra.gateway.routes;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Contract-surface ratchet (audit P2): springdoc cannot enumerate a {@code Map<String, Object>}
 * response — adding/renaming/removing keys inside one produces NO spec diff, so ci-contracts and
 * the generated TS types are structurally blind to ~42% of the API (69 of ~166 handlers at audit
 * time). This test freezes the per-service count of Map-returning controller methods: it may go
 * DOWN (convert to a record — then lower the frozen number here), never UP. New endpoints must
 * return typed records so the breaking-diff gate actually sees them.
 *
 * <p>Same pure-file pattern as {@link GatewayRouteAllowlistTest}: walks the sibling services'
 * sources from the repo root, rides the ci-java strategy-gateway shard, no containers.
 */
class MapReturnRatchetTest {

  /** Frozen at the 2026-07-02 audit-fix baseline. DOWN is progress; UP fails the build. */
  private static final Map<String, Integer> FROZEN =
      Map.of(
          "edge-gateway", 2,
          "market-data-service", 30,
          "strategy-signal-service", 28,
          "backtest-service", 10);

  private static final Pattern MAP_RETURN =
      Pattern.compile("public (Mono<)?Map<String, Object>");

  @Test
  void mapReturningControllerMethodsNeverIncrease() throws IOException {
    Path repoRoot = findRepoRoot();
    for (Map.Entry<String, Integer> frozen : FROZEN.entrySet()) {
      long count = countMapReturns(repoRoot.resolve("services").resolve(frozen.getKey()));
      assertThat(count)
          .withFailMessage(
              "%s now has %d Map<String,Object>-returning controller methods (frozen at %d)."
                  + " Map responses are INVISIBLE to the contract gate (no spec diff on key"
                  + " changes) — return a typed record for new endpoints. If you CONVERTED"
                  + " handlers to records, lower the frozen count in this test instead.",
              frozen.getKey(), count, frozen.getValue())
          .isLessThanOrEqualTo(frozen.getValue());
    }
  }

  private static long countMapReturns(Path serviceDir) throws IOException {
    try (Stream<Path> files = Files.walk(serviceDir.resolve("src/main/java"))) {
      return files
          .filter(f -> f.getFileName().toString().endsWith("Controller.java"))
          .mapToLong(MapReturnRatchetTest::matchesIn)
          .sum();
    }
  }

  private static long matchesIn(Path file) {
    try {
      return MAP_RETURN.matcher(Files.readString(file)).results().count();
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
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
