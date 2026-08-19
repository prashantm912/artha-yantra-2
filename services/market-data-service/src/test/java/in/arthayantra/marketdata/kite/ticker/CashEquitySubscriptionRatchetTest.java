package in.arthayantra.marketdata.kite.ticker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.InstrumentTokenResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * RATCHET (PR #1251): a cash EQUITY must never reach the live tick feed.
 *
 * <p><b>Why this rule exists.</b> {@code PaperBracketEvaluator} (strategy-signal) prices every open
 * paper position off the {@code ticks:last} LTP and compares it to the STORED {@code
 * paper_positions.stop_loss} - a level the daily swing batch writes once, at entry, on the
 * corporate-action plane current then, and which nothing re-scales when a split or bonus
 * retroactively re-planes the market. A 1:2 split would halve the tick while the stored stop stayed
 * whole and stop a swing holding out INTRADAY, on the ex-date morning, at a price that never
 * happened. That exposure is real and deliberately UNFIXED - #1251 attempted fixes on both surfaces,
 * cross-vendor review found four Criticals in them, and the owner reverted to marking the exposure
 * rather than patching it. It is unreachable today for exactly one reason: no cash equity is
 * subscribed, so {@code lastTick} returns empty and the comparison never runs. Every open swing
 * holding already carries a non-null {@code stop_loss}, so ONE tick is the whole remaining distance.
 *
 * <p><b>Why the guard is on the registry and not on the config.</b> An earlier revision of this PR
 * ratcheted {@code artha.subscriptions.pinned-indices} in the pinned-indices subscriber constructor.
 * That looked equivalent and was not - it left two live bypasses, both covered here: {@code
 * SubscriptionsController.subscribe} takes an arbitrary exchange/symbol straight from the caller
 * into the registry, and {@code SubscriptionReplayer.replay} restores persisted holds through the
 * registry on every restart. An equity could have reached {@code ticks:last} with the config ratchet
 * fully green. {@code SubscriptionRegistry.subscribe} is the one method all of them share.
 */
class CashEquitySubscriptionRatchetTest {

  /** Every instrument resolves as a tradable cash EQUITY - the shape the ratchet must refuse. */
  private static SubscriptionRegistry registry() {
    return registryWhereEverythingIsA("EQ", "NSE");
  }

  private static SubscriptionRegistry registryWhereEverythingIsA(String type, String segment) {
    InstrumentTokenResolver resolver = mock(InstrumentTokenResolver.class);
    // Always resolvable - so a refusal can only come from the ratchet, never from an unknown symbol.
    when(resolver.resolve(any()))
        .thenAnswer(
            inv ->
                Optional.of(
                    new InstrumentTokenResolver.TokenInfo(
                        inv.getArgument(0).toString().hashCode() & 0xffff, type, segment)));
    return new SubscriptionRegistry(resolver, 3000, new SimpleMeterRegistry());
  }

  private static InstrumentKey key(String exchange, String tradingsymbol) {
    return new InstrumentKey(exchange, tradingsymbol);
  }

  /**
   * A registry backed by the REAL {@code instruments-fixture.csv}, so segments come from data rather
   * than from the test's own opinion.
   *
   * <p>This exists because the first version of the YAML check below used a resolver that classified
   * EVERY symbol as {@code INDICES}. That made the check a tautology: a {@code pinned-indices} value
   * of {@code NSE:RELIANCE} and the real one produced the identical result, so the guard could never
   * fail. A fixture-backed resolver reports {@code RELIANCE} in the {@code NSE} segment and
   * {@code NIFTY 50} in {@code INDICES}, which is what makes the two cases diverge.
   */
  private static SubscriptionRegistry fixtureRegistry() {
    Map<String, InstrumentTokenResolver.TokenInfo> master = new HashMap<>();
    try (var in = CashEquitySubscriptionRatchetTest.class.getResourceAsStream("/instruments-fixture.csv");
        var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith("#") || line.startsWith("instrument_token")) {
          continue;
        }
        String[] c = line.split(",", -1);
        if (c.length < 6) {
          continue;
        }
        master.put(
            c[1] + ":" + c[2],
            new InstrumentTokenResolver.TokenInfo(Long.parseLong(c[0]), c[4], c[5]));
      }
    } catch (Exception e) {
      throw new AssertionError("could not read instruments-fixture.csv", e);
    }
    assertThat(master).as("fixture must be loadable, or every assertion below is vacuous").isNotEmpty();
    return new SubscriptionRegistry(
        k -> Optional.ofNullable(master.get(k.canonical())), 3000, new SimpleMeterRegistry());
  }

  @Test
  void theControllerPathCannotSubscribeACashEquity() {
    // THE BYPASS the config-level ratchet missed. SubscriptionsController builds an InstrumentKey
    // from the request body verbatim and calls exactly this overload, so an API caller could have
    // put NSE:RELIANCE on the feed with every config-level assertion green.
    assertThatThrownBy(
            () ->
                registry()
                    .subscribe(
                        "ui", key("NSE", "RELIANCE"), SubscriptionMode.QUOTE, SubscriptionPriority.UI))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("NSE:RELIANCE")
        .hasMessageContaining("PaperBracketEvaluator")
        .hasMessageContaining("#1251")
        .hasMessageContaining("2026-08-02-manas-exit-stop-doctrine.md");
  }

  @Test
  void theRefusalIsA400SoTheControllerSurfacesItRatherThan500ing() {
    Throwable thrown =
        catchThrowable(
            () ->
                registry()
                    .subscribe("ui", key("NSE", "TCS"), SubscriptionMode.LTP, SubscriptionPriority.UI));
    assertThat(thrown).isInstanceOf(ApiException.class);
    assertThat(((ApiException) thrown).httpStatus()).isEqualTo(400);
  }

  /**
   * ⚠️ H29 (#1424) changed the POPULATION that reaches this ratchet, and that is exactly the kind of
   * change #1251 exists to catch.
   *
   * <p>27 actively-traded NSE BE-series equities previously died one step EARLIER — {@code
   * TokenResolverAdapter} could not resolve them at all, because Kite carries them under a {@code
   * -BE} suffixed tradingsymbol and every consumer we own uses the bare symbol. So unresolvability
   * was a SECOND, independent barrier between them and {@code ticks:last}. The fallback removes it,
   * deliberately, and this ratchet is now the ONLY thing standing there.
   *
   * <p>⚠️ <b>READ WHAT THIS PINS, AND WHAT IT CANNOT.</b> It mocks {@link InstrumentTokenResolver}
   * wholesale, so {@code TokenResolverAdapter} — the only production class #1424 touches — is never
   * constructed here. <b>Measured: this test passes with the {@code -BE} fallback removed
   * (9/9, compile-errors 0).</b> It therefore does NOT pin the H29 linkage, and calling it that
   * would be exactly the tautology {@link #fixtureRegistry} was built to escape.
   *
   * <p>What it DOES pin is forward: a resolvable NSE cash equity is refused with a 400, whatever
   * route delivered it. That is worth having now that resolution is no longer a second barrier —
   * but the fallback's own half (the twin yields a non-{@code INDICES} segment) is pinned where it
   * can actually redden, in {@code TokenResolverBeSuffixTest}.
   */
  @Test
  void aResolvableCashEquityIsRefusedWhateverRouteResolvedIt() {
    assertThatThrownBy(
            () ->
                registryWhereEverythingIsA("EQ", "NSE")
                    .subscribe(
                        "ui",
                        key("NSE", "KANORICHEM"),
                        SubscriptionMode.QUOTE,
                        SubscriptionPriority.UI))
        .as("a BE equity that newly RESOLVES must not newly SUBSCRIBE")
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("NSE:KANORICHEM")
        .hasMessageContaining("#1251")
        .extracting(t -> ((ApiException) t).httpStatus())
        .isEqualTo(400);
  }

  @Test
  void theReplayPathCannotRestoreACashEquityHold() {
    // SubscriptionReplayer calls the same overload for every persisted hold, so a hold written
    // before this ratchet existed cannot walk back in through a restart. Its catch turns the refusal
    // into warn-and-skip, which is the intended outcome: the equity is simply never re-subscribed.
    assertThatThrownBy(
            () ->
                registry()
                    .subscribe(
                        "replayed",
                        key("BSE", "TATASTEEL"),
                        SubscriptionMode.QUOTE,
                        SubscriptionPriority.SPECULATIVE))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("BSE:TATASTEEL");
  }

  @Test
  void thePinnedSubscriptionPortIsCoveredToo() {
    // The 1-arg PinnedSubscriptionRegistrar overload delegates to the same method, so the options
    // pinner and any other port user are covered without a guard of their own.
    assertThatThrownBy(() -> registry().subscribe("atm-pinner", key("NSE", "INFY")))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("NSE:INFY");
  }

  @Test
  void aRealIndexOnACashExchangeStillSubscribes() {
    // The must-stay-silent half, on REAL fixture data: these three carry segment INDICES in
    // instruments-fixture.csv, so they exercise the guard's allow branch rather than a resolver that
    // was told to say yes. If this trips, the ratchet has become stricter than the instrument master
    // and the live ticker loses its index spots.
    SubscriptionRegistry registry = fixtureRegistry();
    for (String index : List.of("NIFTY 50", "NIFTY BANK", "INDIA VIX")) {
      assertThatCode(
              () ->
                  registry.subscribe(
                      "system-pinned",
                      key("NSE", index),
                      SubscriptionMode.QUOTE,
                      SubscriptionPriority.PINNED_INDEX))
          .doesNotThrowAnyException();
    }
  }

  @Test
  void derivativeSegmentsAreNotTheRatchetsBusiness() {
    // NFO/BFO instruments cannot be a cash equity, so the guard must ignore them entirely - that is
    // what keeps it from firing on the futures pinner and the ATM option pinner, which subscribe
    // hundreds of contracts a session.
    SubscriptionRegistry registry = registry();
    assertThatCode(
            () ->
                registry.subscribe(
                    "futures-pinner",
                    key("NFO", "NIFTY26AUGFUT"),
                    SubscriptionMode.QUOTE,
                    SubscriptionPriority.PINNED_INDEX))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                registry.subscribe(
                    "atm-pinner",
                    key("BFO", "SENSEX26AUG80000CE"),
                    SubscriptionMode.QUOTE,
                    SubscriptionPriority.SPECULATIVE))
        .doesNotThrowAnyException();
  }

  @Test
  void aCashEquityInThePinnedIndicesConfigFailsTheBootPass() {
    // DISCRIMINATION FIRST, then the real value - otherwise this decays into a tautology, which is
    // exactly what the first version of this test did (its resolver called everything an INDEX, so
    // both YAML values passed). ensurePinned() now catches only NotFoundException, so the registry's
    // resolved-equity refusal propagates out of the ApplicationReadyEvent listener and fails boot
    // instead of being reduced to a warning.
    PinnedIndicesSubscriber bad =
        new PinnedIndicesSubscriber(fixtureRegistry(), List.of("NSE:NIFTY 50", "NSE:RELIANCE"));

    assertThatThrownBy(bad::ensurePinned)
        .as(
            "a cash equity in artha.subscriptions.pinned-indices must fail the boot pass loudly - if"
                + " this stops throwing, ensurePinned's catch has been widened again and a bad"
                + " config value is back to being a warning nobody reads")
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("NSE:RELIANCE")
        .hasMessageContaining("PaperBracketEvaluator");
  }

  @Test
  void theShippedPinnedIndicesConfigPassesTheSameBootPass() {
    // Same code path, same resolver, real application.yml value. The four index spots absent from the
    // fixture resolve to NotFoundException, which ensurePinned still treats as retryable - so this
    // asserts the shipped config is clean, not that the fixture is complete.
    PinnedIndicesSubscriber shipped =
        new PinnedIndicesSubscriber(fixtureRegistry(), shippedPinnedIndices());

    assertThatCode(shipped::ensurePinned)
        .as(
            "application.yml pins %s, which the ratchet refuses - if one of these is an EQUITY read"
                + " the refusal message before going further",
            shippedPinnedIndices())
        .doesNotThrowAnyException();
  }

  /** The {@code artha.subscriptions.pinned-indices} value as shipped, read from the real YAML. */
  private static List<String> shippedPinnedIndices() {
    Path yml = Path.of("src", "main", "resources", "application.yml");
    assertThat(Files.isRegularFile(yml)).as("expected %s", yml.toAbsolutePath()).isTrue();
    try {
      for (String line : Files.readAllLines(yml, StandardCharsets.UTF_8)) {
        String trimmed = line.trim();
        if (trimmed.startsWith("pinned-indices:")) {
          return java.util.Arrays.stream(
                  trimmed.substring("pinned-indices:".length()).split(","))
              .map(String::trim)
              .filter(e -> !e.isEmpty())
              .toList();
        }
      }
    } catch (java.io.IOException e) {
      throw new AssertionError("could not read " + yml.toAbsolutePath(), e);
    }
    throw new AssertionError("no pinned-indices key found in " + yml.toAbsolutePath());
  }
}
