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

  /**
   * MAJOR, review round 2 — and the half the first fix missed. Rolling the in-memory hold back
   * was not enough: {@code persist()} ran BEFORE the wire call, so a refused mode raise still
   * left the FAILED mode in the durable store. Memory and the store then disagreed, and
   * {@code SubscriptionReplayer} resurrects the store verbatim on the next restart — installing a
   * mode nothing in memory ever agreed to, at the moment least likely to be watched.
   *
   * <p>⚠️ The assertion is on the STORE, not on the holds map. The round-1 test asserted only
   * memory and passed against this defect, which is precisely how it survived a review round.
   */
  @Test
  void aRefusedModeRaiseWritesNothingDurable() {
    // ⚠️ SPECULATIVE, not PINNED_INDEX: persist() SKIPS PINNED_INDEX entirely
    // (SubscriptionRegistry:361), so the first version of this test wrote nothing durable and
    // passed against the very defect it exists to catch. It failed for a FIXTURE reason that
    // looks exactly like a passing gate.
    RecordingStore store = new RecordingStore();
    RecordingTicker ticker = new RecordingTicker();
    SubscriptionRegistry registry = registry(store);
    registry.attachTicker(ticker);

    // An existing LTP hold, persisted normally.
    registry.subscribe("holder-a", KEY, SubscriptionMode.LTP, SubscriptionPriority.SPECULATIVE);
    store.writes.clear();

    // A second subscriber RAISES the effective mode, and the wire refuses the raise.
    ticker.failNext.set(true);
    assertThatThrownBy(
            () ->
                registry.subscribe(
                    "holder-b", KEY, SubscriptionMode.FULL, SubscriptionPriority.SPECULATIVE))
        .isInstanceOf(IllegalStateException.class);

    assertThat(store.writes)
        .as("a mode the wire refused must not survive a restart via the durable store")
        .isEmpty();
  }

  /**
   * Review round 3, Suggestion: the OTHER arm of the rollback.
   *
   * <p>Every failure test above raises through a NEW subscriber, so {@code previous} is null and
   * the restore path taken is always {@code holds().remove(...)}. The
   * {@code holds().put(subscriber, previous)} arm — a subscriber that already held the instrument
   * at a LOWER mode and fails to raise it — was never executed. It is the arm where a wrong
   * rollback is silent rather than loud: dropping the hold entirely would release a subscription
   * the caller still legitimately holds at LTP, and nothing would report it.
   */
  @Test
  void aRefusedRaiseRestoresTheSubscriberOwnEarlierModeRatherThanDroppingIt() {
    RecordingStore store = new RecordingStore();
    RecordingTicker ticker = new RecordingTicker();
    SubscriptionRegistry registry = registry(store);
    registry.attachTicker(ticker);

    // A second holder keeps the instrument alive so the raise goes through the existing branch.
    registry.subscribe("holder-a", KEY, SubscriptionMode.LTP, SubscriptionPriority.SPECULATIVE);
    registry.subscribe("holder-b", KEY, SubscriptionMode.LTP, SubscriptionPriority.SPECULATIVE);
    store.writes.clear();

    // holder-b, which ALREADY holds at LTP, tries to raise itself to FULL and the wire refuses.
    ticker.failNext.set(true);
    assertThatThrownBy(
            () ->
                registry.subscribe(
                    "holder-b", KEY, SubscriptionMode.FULL, SubscriptionPriority.SPECULATIVE))
        .isInstanceOf(IllegalStateException.class);

    assertThat(registry.heldBy("holder-b"))
        .as("the refused RAISE must not cost the subscriber the hold it already had")
        .containsExactly(KEY);
    assertThat(registry.view())
        .as("and the instrument stays subscribed for both holders")
        .hasSize(1);
    assertThat(store.writes)
        .as("nothing durable was written for a mode the wire refused")
        .isEmpty();

    // ⚠️ THE MODE, NOT JUST THE PRESENCE (review round 4). containsExactly(KEY) above proves the
    // hold was not DROPPED, and a rollback that did nothing at all would satisfy it too -- the map
    // would simply keep the FULL hold nobody agreed to. Dropping holder-a makes holder-b the only
    // holder, so the effective mode IS its restored mode and a no-op rollback reports FULL here.
    registry.unsubscribe("holder-a", KEY);
    assertThat(registry.view())
        .singleElement()
        .extracting(SubscriptionRegistry.SubscriptionView::mode)
        .as("the subscriber must be back at LTP, not left holding the FULL it never got")
        .isEqualTo(SubscriptionMode.LTP);
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

  /** Records durable writes so a test can assert on what a restart would replay. */
  private static final class RecordingStore implements SubscriptionStore {
    private final List<String> writes = new ArrayList<>();

    @Override
    public void put(String s, InstrumentKey k, SubscriptionMode m, SubscriptionPriority p) {
      writes.add(s + "|" + k.canonical() + "|" + m);
    }

    @Override
    public void remove(String s, InstrumentKey k) {
      writes.removeIf(w -> w.startsWith(s + "|" + k.canonical() + "|"));
    }

    @Override
    public List<PersistedHold> all() {
      return List.of();
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
