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

  /**
   * Frozen at the 2026-07-02 audit-fix baseline, ratcheted DOWN as handlers are converted. DOWN is
   * progress; UP fails the build.
   *
   * <p>backtest-service 10 → 7 (2026-07-29): {@code ResultsController.results} now returns the
   * typed {@code RunResult}, and {@code StressWindowController.stressWindow} the typed
   * {@code StressWindow} — both pure retypings of a LinkedHashMap, same keys in the same order, so
   * the wire is byte-identical and only the spec gained the shape. {@code IndicatorsController.list}
   * followed ({@code IndicatorRegistry}) — its ITEM type was already typed, so the envelope was the
   * only opaque part.
   *
   * <p>DELIBERATELY still a Map, assessed 2026-07-29 and not a miss: {@code
   * HeroZeroPremiumController.heroZeroPremium}. Its response is POLYMORPHIC — the empty path emits 5
   * keys, the populated path 16 — so one record would add 11 null keys to the empty response. That is
   * a wire change, not a retyping; typing it needs a deliberate shape decision, not a refactor.
   *
   * <p>strategy-signal-service 14 → 4 (2026-07-29, same day): the whole REGISTRY CRUD surface —
   * all <b>12</b> {@code RegistryController} handlers, typed at the SERVICE (see {@code
   * RegistryViews}) so the records are the single source of truth rather than a controller-side
   * re-mapping. Most sources were a {@code LinkedHashMap} whose insertion order the record
   * components now mirror, so key ORDER is unchanged. Two exceptions: {@code archive} and the
   * {@code list} envelope came from MULTI-key {@code Map.of}, whose iteration order is JVM-salted —
   * those are NORMALISED, not preserved, and must not be described as byte-identical. The {@code
   * versions} envelope was a SINGLE-key {@code Map.of}, which is trivially stable, so it belongs in
   * neither camp. No conditional keys existed on any of the 12 (every null was {@code put}
   * explicitly), so no response gained or lost a key.
   *
   * <p>⚠️ The threshold is 4 because this test counts only {@code *Controller.java}. A shell grep
   * over all of {@code src/main} returns 6 — it also catches two {@code describe()} methods on
   * pyramid POLICY classes, which are not handlers. The assertion is {@code
   * isLessThanOrEqualTo}, so an over-stated threshold passes while silently leaving room for new
   * opaque handlers; the first version of this row said 6 and would have let two through
   * (cross-vendor review, 2026-07-29). Count with the test's own regex, not a broader one.
   *
   * <p>strategy-signal-service 18 → 14 (2026-07-29, same day): the paper read surface + the
   * journal list. {@code PaperController}'s {@code positions} / {@code trades} / {@code pnl} and
   * {@code JournalController.list} were all envelopes whose ITEM types were ALREADY records
   * ({@code PositionDto}, {@code TradeDto}, {@code Entry}), so typing the envelope pulls the fully
   * enumerated item schema into the spec instead of leaving an {@code array of object}. {@code
   * PaperService.pnl} was retyped at the SERVICE too — its {@code summary} mirrored a {@code
   * LinkedHashMap} (component order load-bearing), its {@code points} a {@code Map.of} (order
   * unspecified, so the record only makes it deterministic).
   *
   * <p>strategy-signal-service 25 → 18 (2026-07-29): the whole SIGNALS surface. {@code
   * SignalsController}'s five handlers ({@code list} / {@code active} / {@code detail} / {@code
   * taken} / {@code dismiss}) all rendered through ONE private {@code dto} assembler, so retyping
   * that one method to {@code SignalViews.SignalDto} typed all five at once. {@code
   * SignalRejectionsController}'s {@code list} + {@code railCounts} lost their assemblers entirely —
   * {@code RejectionRow} and {@code RailCount} already matched the emitted keys name-for-name IN
   * ORDER, so the envelopes were the only opaque part. Its class javadoc claimed the Map return
   * meant "response keys never drift the OpenAPI spec"; that is exactly backwards, and describing
   * the blindness as a feature is the clearest argument for this ratchet existing.
   *
   * <p>edge-gateway 2 → 0 (ledger D3 slice 1, 2026-07-28): {@code AuthController.session} and
   * {@code SystemStatusController.status} now return records. Both were pure retypings — every key
   * name, nesting level and value type unchanged — so the wire is identical and only the SPEC
   * gained the shape. The two comments the old assembler carried ("Map return ⇒ this key never
   * drifts the contract") described exactly the blindness this ratchet exists to remove.
   *
   * <p>market-data-service 26 → 16 (2026-07-30): every SINGLE-handler controller — {@code
   * WorldIndices} / {@code UpstoxEntitlement} / {@code FuturesPreOpen} / {@code
   * ContinuousFuturesAdmin} / {@code OiBuzz} / {@code Announcement} / {@code IvAnalytics} /
   * {@code Subscriptions} / {@code Screener} / {@code OptionsChain.history}. All ten were
   * UNCONDITIONAL (every key {@code put} on every path), so no response gained or lost a key.
   * TWO needed no new record at all — {@code UpstoxAnalyticsClient.Entitlement} and {@code
   * FuturesPreOpen} already carried exactly the emitted components in the emitted order, so the
   * old {@code Map.of} was a field-for-field re-emission of a record that already existed.
   *
   * <p>Order: eight came from MULTI-key {@code Map.of}, whose iteration order is JVM-salted — those
   * are NORMALISED, not preserved, and must not be called byte-identical. {@code WorldIndices},
   * {@code OiBuzz} and {@code IvAnalytics} were single-key (trivially stable). {@code
   * Announcement} is the one to watch on any future edit: its wire form deliberately DIFFERS from
   * {@code AnnouncementService.Feed} — {@code from}/{@code to} are the {@code LocalDate.toString()}
   * STRING and a null {@code symbol} is emitted as {@code ""} — so the record pins those, rather
   * than returning {@code Feed} and quietly changing two values.
   *
   * <p>The remaining 16 are the two 6-handler analytics controllers ({@code OptionsAnalytics},
   * {@code FuturesAnalytics}) plus {@code PreOpen} and {@code MarketSurface}. Not yet assessed for
   * conditional keys — do that BEFORE converting, per the {@code HeroZeroPremium} precedent above.
   */
  private static final Map<String, Integer> FROZEN =
      Map.of(
          "edge-gateway", 0,
          "market-data-service", 16,
          "strategy-signal-service", 4,
          "backtest-service", 7);

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
