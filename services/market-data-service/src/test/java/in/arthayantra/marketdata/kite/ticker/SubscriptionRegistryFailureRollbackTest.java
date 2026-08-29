package in.arthayantra.marketdata.kite.ticker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.InstrumentTokenResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Cross-vendor review, one Critical and one Major: a hold must never outlive the operation that
 * claimed it.
 *
 * <p>Both defects were latent for as long as the ATM repin ran twice a day — a leaked slot healed at
 * the next restart. Making it recur every five minutes is what turned them into accumulating faults,
 * which is why they are fixed alongside it rather than filed.
 *
 * <p>⚠️ Every assertion here is about state AFTER a throw. A test that only checked the happy path
 * would pass against both pre-fix bodies, because on the happy path nothing throws and the orders are
 * indistinguishable.
 */
class SubscriptionRegistryFailureRollbackTest {

  private static final InstrumentKey KEY = new InstrumentKey("NFO", "NIFTY25000CE");

  /**
   * CRITICAL. The hold is inserted before the wire call, so a throwing ticker used to leave a hold
   * that looks successful: it counts against the cap and reports as pinned, but no tick ever arrives.
   * The next reconcile sees it already held, takes the unchanged-mode path, and never retries the
   * wire — so the contract is dark until restart. That is exactly the H44 stranding, recreated by the
   * mechanism meant to prevent it.
   */
  @Test
  void aFailedWireSubscribeLeavesNoHoldBehind() {
    RecordingTicker ticker = new RecordingTicker();
    SubscriptionRegistry registry = registry();
    registry.attachTicker(ticker);
    ticker.failNext.set(true);

    assertThatThrownBy(() -> registry.subscribe("system-opt-atm-pins", KEY))
        .isInstanceOf(IllegalStateException.class);

    assertThat(registry.view())
        .as("a hold the wire refused must not count against the cap")
        .isEmpty();
    assertThat(registry.heldBy("system-opt-atm-pins")).isEmpty();
  }

  /** And the retry must actually reach the wire — the whole point of not leaving a hold. */
  @Test
  void theNextReconcileRetriesTheWireRatherThanSeeingItAlreadyHeld() {
    RecordingTicker ticker = new RecordingTicker();
    SubscriptionRegistry registry = registry();
    registry.attachTicker(ticker);

    ticker.failNext.set(true);
    assertThatThrownBy(() -> registry.subscribe("system-opt-atm-pins", KEY))
        .isInstanceOf(IllegalStateException.class);
    registry.subscribe("system-opt-atm-pins", KEY);

    assertThat(ticker.subscribed)
        .as("the second attempt reached the ticker")
        .isNotEmpty();
    assertThat(registry.view()).hasSize(1);
  }

  /**
   * MAJOR. The durable removal used to run AFTER the in-memory hold was dropped, so a throwing store
   * left the instrument present with an EMPTY holds map: still counted against the cap, still
   * wire-subscribed, and unreleasable — every later unsubscribe returned early because the hold was
   * already gone. Each repin could leak another slot with nothing surfacing it.
   */
  @Test
  void aFailedDurableRemoveLeavesTheHoldIntactSoTheNextPassCanRetry() {
    ThrowingStore store = new ThrowingStore();
    SubscriptionRegistry registry = registry(store);
    registry.attachTicker(new RecordingTicker());
    registry.subscribe("system-opt-atm-pins", KEY);

    store.failNext.set(true);
    assertThatThrownBy(() -> registry.unsubscribe("system-opt-atm-pins", KEY))
        .isInstanceOf(IllegalStateException.class);

    assertThat(registry.heldBy("system-opt-atm-pins"))
        .as("the hold survives, so the release is retryable rather than lost")
        .containsExactly(KEY);

    // The retry, once the store recovers, genuinely releases it.
    registry.unsubscribe("system-opt-atm-pins", KEY);
    assertThat(registry.view()).isEmpty();
  }

  // ---------------------------------------------------------------- harness

  private static SubscriptionRegistry registry() {
    return registry(SubscriptionStore.NOOP);
  }

  private static SubscriptionRegistry registry(SubscriptionStore store) {
    Map<String, InstrumentTokenResolver.TokenInfo> master = new HashMap<>();
    master.put(KEY.canonical(), new InstrumentTokenResolver.TokenInfo(9_001L, "CE", "NFO-OPT"));
    return new SubscriptionRegistry(
        key -> Optional.ofNullable(master.get(key.canonical())),
        3_000,
        new SimpleMeterRegistry(),
        store);
  }

  private static final class RecordingTicker implements SubscriptionRegistry.TickerCommands {
    private final AtomicBoolean failNext = new AtomicBoolean();
    private final List<Long> subscribed = new ArrayList<>();

    @Override
    public void subscribe(List<Long> tokens, SubscriptionMode mode) {
      if (failNext.getAndSet(false)) {
        throw new IllegalStateException("websocket not connected");
      }
      subscribed.addAll(tokens);
    }

    @Override
    public void unsubscribe(List<Long> tokens) {
      subscribed.removeAll(tokens);
    }
  }

  private static final class ThrowingStore implements SubscriptionStore {
    private final AtomicBoolean failNext = new AtomicBoolean();

    @Override
    public void put(String s, InstrumentKey k, SubscriptionMode m, SubscriptionPriority p) {
      // durable writes are not what this case exercises
    }

    @Override
    public void remove(String s, InstrumentKey k) {
      if (failNext.getAndSet(false)) {
        throw new IllegalStateException("redis unavailable");
      }
    }

    @Override
    public List<PersistedHold> all() {
      return List.of();
    }
  }
}
