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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

  /** Every instrument resolves as an INDEX - the shape the ratchet must let through. */
  private static SubscriptionRegistry indexRegistry() {
    return registryWhereEverythingIsA("EQ", "INDICES");
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
  void everyAllowedIndexStillSubscribes() {
    // The must-stay-silent half. If this trips, the ratchet has become stricter than the
    // configuration it guards and the live ticker loses its index spots.
    SubscriptionRegistry registry = indexRegistry();
    for (String index :
        List.of("NIFTY 50", "NIFTY BANK", "NIFTY FIN SERVICE", "NIFTY MID SELECT", "INDIA VIX")) {
      assertThatCode(
              () ->
                  registry.subscribe(
                      "system-pinned",
                      key("NSE", index),
                      SubscriptionMode.QUOTE,
                      SubscriptionPriority.PINNED_INDEX))
          .doesNotThrowAnyException();
    }
    for (String index : List.of("SENSEX", "BANKEX")) {
      assertThatCode(
              () ->
                  registry.subscribe(
                      "system-pinned",
                      key("BSE", index),
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
  void theShippedPinnedIndicesConfigSatisfiesTheRatchet() {
    // Authoring-time collision for the config path. PinnedIndicesSubscriber.ensurePinned() catches
    // whatever the registry throws and only WARNS, so a bad application.yml value would be dropped
    // quietly at runtime instead of failing loudly. Asserting the shipped list here puts that
    // collision back into CI without a second production guard to keep in step.
    SubscriptionRegistry registry = indexRegistry();
    for (String entry : shippedPinnedIndices()) {
      int colon = entry.indexOf(':');
      assertThat(colon).as("malformed pinned-indices entry '%s'", entry).isPositive();
      String exchange = entry.substring(0, colon).trim();
      String symbol = entry.substring(colon + 1).trim();
      assertThatCode(
              () ->
                  registry.subscribe(
                      "system-pinned",
                      key(exchange, symbol),
                      SubscriptionMode.QUOTE,
                      SubscriptionPriority.PINNED_INDEX))
          .as(
              "application.yml pins '%s', which this ratchet refuses - if it is an EQUITY read the"
                  + " refusal message before going further; if it is another INDEX add it to"
                  + " SubscriptionRegistry.ALLOWED_CASH_INSTRUMENTS",
              entry)
          .doesNotThrowAnyException();
    }
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
