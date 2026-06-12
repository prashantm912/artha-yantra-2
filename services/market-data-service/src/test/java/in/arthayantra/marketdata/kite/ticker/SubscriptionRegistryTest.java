package in.arthayantra.marketdata.kite.ticker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.InstrumentTokenResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Phase-13 registry rules: refcounts, highest-mode, eviction priority, the 3,000 cap. */
class SubscriptionRegistryTest {

  private static final Map<String, InstrumentTokenResolver.TokenInfo> MASTER =
      Map.of(
          "NSE:AAA", new InstrumentTokenResolver.TokenInfo(1, "EQ", "NSE"),
          "NSE:BBB", new InstrumentTokenResolver.TokenInfo(2, "EQ", "NSE"),
          "NSE:CCC", new InstrumentTokenResolver.TokenInfo(3, "EQ", "NSE"),
          "NSE:NIFTY 50", new InstrumentTokenResolver.TokenInfo(256265, "EQ", "INDICES"));

  private static final InstrumentTokenResolver RESOLVER =
      key -> Optional.ofNullable(MASTER.get(key.canonical()));

  /** Records every command the registry would send to a live ticker. */
  static class RecordingCommands implements SubscriptionRegistry.TickerCommands {
    final List<String> commands = new ArrayList<>();

    @Override
    public void subscribe(List<Long> tokens, SubscriptionMode mode) {
      commands.add("subscribe" + tokens + ":" + mode.wireName());
    }

    @Override
    public void unsubscribe(List<Long> tokens) {
      commands.add("unsubscribe" + tokens);
    }
  }

  private static SubscriptionRegistry registry(int cap) {
    return new SubscriptionRegistry(RESOLVER, cap, new SimpleMeterRegistry());
  }

  private static InstrumentKey key(String symbol) {
    return new InstrumentKey("NSE", symbol);
  }

  @Test
  void refcountsResolveToTheHighestRequestedMode() {
    SubscriptionRegistry registry = registry(3_000);
    RecordingCommands ticker = new RecordingCommands();
    registry.attachTicker(ticker);

    registry.subscribe("ui", key("AAA"), SubscriptionMode.QUOTE, SubscriptionPriority.UI);
    registry.subscribe("strategy", key("AAA"), SubscriptionMode.FULL, SubscriptionPriority.STRATEGY);

    assertThat(registry.view()).hasSize(1);
    assertThat(registry.view().get(0).mode()).isEqualTo(SubscriptionMode.FULL);
    assertThat(registry.view().get(0).subscribers()).isEqualTo(2);
    assertThat(ticker.commands)
        .containsExactly("subscribe[1]:quote", "subscribe[1]:full");

    // dropping the FULL holder downgrades back to quote, never silently keeps full
    registry.unsubscribe("strategy", key("AAA"));
    assertThat(registry.view().get(0).mode()).isEqualTo(SubscriptionMode.QUOTE);
    assertThat(ticker.commands).last().isEqualTo("subscribe[1]:quote");

    registry.unsubscribe("ui", key("AAA"));
    assertThat(registry.view()).isEmpty();
    assertThat(ticker.commands).last().isEqualTo("unsubscribe[1]");
  }

  @Test
  void unknownInstrumentIs404() {
    assertThatThrownBy(
            () ->
                registry(10)
                    .subscribe("ui", key("NOPE"), SubscriptionMode.LTP, SubscriptionPriority.UI))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void capPressureEvictsTheLowestPriorityWithWarning() {
    SubscriptionRegistry registry = registry(2);
    RecordingCommands ticker = new RecordingCommands();
    registry.attachTicker(ticker);
    registry.subscribe("ui", key("AAA"), SubscriptionMode.QUOTE, SubscriptionPriority.UI);
    registry.subscribe("scan", key("BBB"), SubscriptionMode.LTP, SubscriptionPriority.SPECULATIVE);

    registry.subscribe("engine", key("CCC"), SubscriptionMode.FULL, SubscriptionPriority.STRATEGY);

    assertThat(registry.view()).hasSize(2);
    assertThat(registry.view())
        .extracting(SubscriptionRegistry.SubscriptionView::tradingsymbol)
        .containsExactlyInAnyOrder("AAA", "CCC"); // the SPECULATIVE one died first
    assertThat(ticker.commands).contains("unsubscribe[2]");
  }

  @Test
  void capWithNothingLowerPriorityRejectsWithValidationFailed() {
    SubscriptionRegistry registry = registry(2);
    registry.subscribe("e1", key("AAA"), SubscriptionMode.QUOTE, SubscriptionPriority.STRATEGY);
    registry.subscribe("e2", key("BBB"), SubscriptionMode.QUOTE, SubscriptionPriority.STRATEGY);

    assertThatThrownBy(
            () -> registry.subscribe("ui", key("CCC"), SubscriptionMode.LTP, SubscriptionPriority.UI))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.httpStatus()).isEqualTo(400);
              assertThat(e.code()).isEqualTo(ErrorCodes.VALIDATION_FAILED);
            });
    // equal priority cannot evict either
    assertThatThrownBy(
            () ->
                registry.subscribe(
                    "e3", key("CCC"), SubscriptionMode.QUOTE, SubscriptionPriority.STRATEGY))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void pinnedIndicesAreNeverEvicted() {
    SubscriptionRegistry registry = registry(1);
    registry.subscribe(
        "system-pinned", key("NIFTY 50"), SubscriptionMode.QUOTE, SubscriptionPriority.PINNED_INDEX);

    assertThatThrownBy(
            () ->
                registry.subscribe(
                    "engine", key("AAA"), SubscriptionMode.FULL, SubscriptionPriority.STRATEGY))
        .isInstanceOf(ApiException.class);
    assertThat(registry.view().get(0).tradingsymbol()).isEqualTo("NIFTY 50");
  }

  @Test
  void layoutLookupUsesModeAndIndexFlag() {
    SubscriptionRegistry registry = registry(10);
    registry.subscribe(
        "system-pinned", key("NIFTY 50"), SubscriptionMode.QUOTE, SubscriptionPriority.PINNED_INDEX);
    registry.subscribe("ui", key("AAA"), SubscriptionMode.FULL, SubscriptionPriority.UI);

    assertThat(registry.layoutFor(256265).orElseThrow().expectedBytes())
        .as("index quote packets are 28 B, not 44 B")
        .isEqualTo(28);
    assertThat(registry.layoutFor(1).orElseThrow().expectedBytes()).isEqualTo(184);
    assertThat(registry.layoutFor(999)).isEmpty();
  }

  @Test
  void replaySetGroupsTokensByEffectiveMode() {
    SubscriptionRegistry registry = registry(10);
    registry.subscribe("ui", key("AAA"), SubscriptionMode.FULL, SubscriptionPriority.UI);
    registry.subscribe("ui", key("BBB"), SubscriptionMode.LTP, SubscriptionPriority.UI);
    registry.subscribe("ui", key("CCC"), SubscriptionMode.LTP, SubscriptionPriority.UI);

    Map<SubscriptionMode, List<Long>> byMode = registry.tokensByMode();

    assertThat(byMode.get(SubscriptionMode.FULL)).containsExactly(1L);
    assertThat(byMode.get(SubscriptionMode.LTP)).containsExactlyInAnyOrder(2L, 3L);
  }
}
