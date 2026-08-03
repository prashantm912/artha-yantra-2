package in.arthayantra.marketdata.kite.ticker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.InstrumentTokenResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The replayer re-subscribes persisted holds into a fresh (post-restart) registry. */
class SubscriptionReplayerTest {

  private static final Map<String, InstrumentTokenResolver.TokenInfo> MASTER =
      Map.of(
          "NFO:TCS", new InstrumentTokenResolver.TokenInfo(2, "FUT", "NFO-FUT"),
          "NFO:AAA", new InstrumentTokenResolver.TokenInfo(1, "FUT", "NFO-FUT"));

  private static final InstrumentTokenResolver RESOLVER =
      key -> Optional.ofNullable(MASTER.get(key.canonical()));

  private static SubscriptionRegistry registry(SubscriptionStore store) {
    return new SubscriptionRegistry(RESOLVER, 3_000, new SimpleMeterRegistry(), store);
  }

  @Test
  void replayRestoresPersistedHoldsIntoTheRegistry() {
    InMemorySubscriptionStore store = new InMemorySubscriptionStore();
    store.put(
        "ui:tab1", new InstrumentKey("NFO", "TCS"), SubscriptionMode.QUOTE, SubscriptionPriority.UI);
    store.put(
        "strategy:s1",
        new InstrumentKey("NFO", "AAA"),
        SubscriptionMode.FULL,
        SubscriptionPriority.STRATEGY);
    SubscriptionRegistry registry = registry(store);

    new SubscriptionReplayer(store, registry).replay();

    assertThat(registry.view())
        .extracting(SubscriptionRegistry.SubscriptionView::tradingsymbol)
        .containsExactlyInAnyOrder("TCS", "AAA");
    assertThat(registry.view())
        .filteredOn(v -> v.tradingsymbol().equals("AAA"))
        .singleElement()
        .extracting(SubscriptionRegistry.SubscriptionView::mode)
        .isEqualTo(SubscriptionMode.FULL);
  }

  @Test
  void replaySkipsUnresolvableInstrumentsWithoutFailing() {
    InMemorySubscriptionStore store = new InMemorySubscriptionStore();
    store.put(
        "ui:tab1",
        new InstrumentKey("NFO", "GONE"),
        SubscriptionMode.QUOTE,
        SubscriptionPriority.UI);
    SubscriptionRegistry registry = registry(store);

    assertThatCode(() -> new SubscriptionReplayer(store, registry).replay())
        .doesNotThrowAnyException();
    assertThat(registry.view()).isEmpty();
  }
}
