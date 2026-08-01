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
   * <p>backtest-service 10 → 2 (2026-07-30): {@code ResultsController.results} now returns the
   * typed {@code RunResult}, and {@code StressWindowController.stressWindow} the typed
   * {@code StressWindow} — both pure retypings of a LinkedHashMap, same keys in the same order, so
   * the wire is byte-identical and only the spec gained the shape. {@code IndicatorsController.list}
   * followed ({@code IndicatorRegistry}) — its ITEM type was already typed, so the envelope was the
   * only opaque part.
   *
   * <p>The D3 backtest slice converted the five unconditional handlers: {@code
   * ResultsController.trades}/{@code summary}, {@code JobsController.jobs}/{@code job}, and
   * {@code IndicatorsController.series}. The two multi-key {@code Map.of} envelopes are
   * normalized into deterministic record order; the job detail record mirrors its old
   * {@code LinkedHashMap} order. {@code OiAttributionController.attribution} remains a deliberate
   * conditional Map because its empty path emits fewer keys than its populated path.
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
   * FuturesPreOpen} already carried exactly the same components, by name and type, in the same
   * sequence the old {@code Map.of} listed them, so that call was a field-for-field re-emission of
   * a record that already existed.
   *
   * <p>⚠️ SECOND-ORDER EFFECT, missed on the first cut and caught by cross-vendor review: typing an
   * ENVELOPE pulls its ITEM schema into the spec for the first time. Those item records had never
   * been enumerated, so none of them declared nullability, and five ({@code PreOpenRow},
   * {@code WorldIndex}, {@code ScreenerService.Row}, {@code Announcement},
   * {@code OptionsSnapshotRepository.SnapshotRow}) would have published nullable fields as
   * NON-NULLABLE — a lie in the generated TS, not merely a missing annotation. Precisely: a record
   * component is always PRESENT, so {@code required} is correct and must stay; what was wrong was
   * the TYPE, which claimed a value can never be null. The fix is the 3.1 type union, never
   * dropping {@code required}. They now carry
   * {@code @Schema(types = {"X", "null"})} on every genuinely nullable component, scoped from the
   * construction sites and, for {@code SnapshotRow}, from the V006 DDL's NOT NULL set. When
   * converting an envelope, check the ITEM type's nullability too, not just the envelope's.
   *
   * <p>Order: SEVEN came from MULTI-key {@code Map.of}, whose iteration order is JVM-salted — those
   * are NORMALISED, not preserved, and must not be called byte-identical. The record components
   * mirror the {@code Map.of} ARGUMENT SEQUENCE, which is not the same thing as an emitted order:
   * there was no stable emitted order to preserve. {@code WorldIndices},
   * {@code OiBuzz} and {@code IvAnalytics} were single-key (trivially stable). {@code
   * Announcement} is the one to watch on any future edit: its wire form deliberately DIFFERS from
   * {@code AnnouncementService.Feed} — {@code from}/{@code to} are the {@code LocalDate.toString()}
   * STRING and a null {@code symbol} is emitted as {@code ""} — so the record pins those, rather
   * than returning {@code Feed} and quietly changing two values.
   *
   * <p>market-data-service 16 → 12 (2026-07-30): {@code PreOpenController} status/preOpen and
   * {@code MarketSurfaceController} status/holidays. {@code preOpen} has TWO return paths but both
   * emit the SAME three keys, so it is not conditional; it now returns the client's own
   * {@code UpstoxMarketStatusClient.PreOpen}, which already carried exactly those components, with
   * the not-configured branch as that same shape empty. {@code MarketSurfaceStatus} keeps its three
   * {@code ""}-for-null date ternaries verbatim — the pre-D3 {@code Map.of} would have THROWN on a
   * null, which is why they exist, so those components are correctly non-nullable.
   *
   * <p>{@code holidays} is the one worth noting: its ITEM was itself an anonymous
   * {@code Map<String,Object>}, so a holiday row published as a bare object and NOTHING about it was
   * enumerated. Now {@code HolidayEntry}. Per the item-schema lesson above, the two newly exposed
   * item records were audited first: {@code MarketStatus.asOf} is null on the {@code unknown()}
   * degrade path, and {@code PreOpenIndex}'s four numerics are null when the live tick does not
   * resolve — both annotated BEFORE their envelopes were typed.
   *
   * <p>market-data-service 12 → 6 (D3 futures analytics slice, 2026-07-30): all six
   * {@code FuturesAnalyticsController} handlers were unconditional and now return service-owned
   * records. {@code oiChart} and {@code moversScreen} return the existing {@code FutOiChart} and
   * {@code Screen} records directly; the other four use envelopes owned by their existing services.
   * The newly exposed item records were audited for genuinely nullable components before capture.
   * The remaining 6 are the {@code OptionsAnalytics} handlers. ASSESSED
   * 2026-07-30 — all six FuturesAnalytics handlers and four OptionsAnalytics handlers are
   * unconditional and convertible (every early exit THROWS rather than returning a partial). TWO are
   * DELIBERATE STOPS of the {@code HeroZeroPremium} kind, both in {@code OptionsAnalytics}:
   * {@code oiExpiry} emits 3 keys on the empty path and 4 when populated ({@code asOf}), and
   * {@code openHighStrategy} emits 3 vs 5 ({@code spot}, {@code asOf}). A record would ADD those
   * keys as nulls to the empty response — a wire change on a live OI page needing a deliberate
   * shape decision, not a refactor. {@code openHighStrategy} says so itself: "nullable off-hours
   * (LinkedHashMap permits null; Map.of would not)". So the floor can reach 2, not 0.
   *
   * <p>⚠️ <b>REGEX WIDENED 2026-08-01 (D3 backtest slice) — the ratchet was BLIND to {@code
   * ResponseEntity<Map<String, Object>>}.</b> The old pattern anchored on {@code public} immediately
   * followed by {@code Mono<}-or-nothing, so a handler that wrapped its opaque map in a {@code
   * ResponseEntity} (to set a 202/201 status) was never counted — while publishing exactly the same
   * {@code additionalProperties:{}} blob this ratchet exists to prevent. Three existed platform-wide,
   * uncounted: {@code JobsController.run} and {@code JobsController.cancel} in backtest (converted in
   * this slice) and {@code WatchlistController.create} in market-data (NOT converted — another
   * service's slice). {@code JobsController.run}'s own javadoc had written the gap down as though it
   * were a property of the design: "{@code ResponseEntity<Map>} is NOT a ratchet-counted Map return".
   * It was a hole in the counter, not a safe shape.
   *
   * <p><b>market-data-service 6 → 7 is a MEASUREMENT CORRECTION, not a regression</b> — no handler
   * was added; {@code WatchlistController.create} has been opaque all along and is only now visible.
   * Raising the number is strictly TIGHTER than leaving it: before, any service could add unlimited
   * {@code ResponseEntity}-wrapped maps and stay green; now market-data is capped at the one that
   * exists, and converting it lowers the number to 6. backtest stays at 2 because its two
   * newly-countable handlers were converted in the same change — a widened regex that simultaneously
   * ratchets a count down is the only combination that proves the widening actually bites.
   */
  private static final Map<String, Integer> FROZEN =
      Map.of(
          "edge-gateway", 0,
          "market-data-service", 7,
          "strategy-signal-service", 4,
          "backtest-service", 2);

  private static final Pattern MAP_RETURN =
      Pattern.compile("public (Mono<|ResponseEntity<)?Map<String, Object>");

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
