package in.arthayantra.marketdata.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.InstrumentMasterUpdated;
import in.arthayantra.marketdata.kite.InstrumentTokenResolver;
import in.arthayantra.marketdata.kite.ticker.SubscriptionPriority;
import in.arthayantra.marketdata.kite.ticker.SubscriptionRegistry;
import in.arthayantra.marketdata.kite.ticker.SubscriptionReplayer;
import in.arthayantra.marketdata.kite.ticker.SubscriptionMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import java.time.LocalDateTime;

class OptionAtmPinnerTest {

  private static final LocalDate NEAR = LocalDate.of(2026, 7, 28);
  private static final LocalDate ROLLED = LocalDate.of(2026, 8, 4);

  @Test
  void pinsAtmWindowForBothConfiguredUnderlyingsAndReportsBudgetCounts() {
    OptionsChainService chains = mock(OptionsChainService.class);
    when(chains.expiriesWithin(anyString(), eq(7)))
        .thenAnswer(invocation -> List.of(invocation.getArgument(0).equals("NIFTY 50") ? NEAR : ROLLED));
    when(chains.chain("NIFTY 50", NEAR)).thenReturn(chain("NIFTY 50", NEAR, 100));
    when(chains.chain("SENSEX", ROLLED)).thenReturn(chain("SENSEX", ROLLED, 200));

    Map<String, InstrumentTokenResolver.TokenInfo> master = new HashMap<>();
    addExistingPins(master);
    addOptionKeys(master, "NFO", "NIFTY 50", 100);
    addOptionKeys(master, "BFO", "SENSEX", 200);
    SubscriptionRegistry registry = new SubscriptionRegistry(key -> Optional.ofNullable(master.get(key.canonical())), 3_000, new SimpleMeterRegistry());
    seedExistingPins(registry);
    int before = registry.view().size();

    OptionAtmPinner pinner = new OptionAtmPinner(registry, chains, List.of("NIFTY 50", "SENSEX"), 5, 7, new SimpleMeterRegistry());
    pinner.repin();

    assertThat(pinner.pinnedContracts()).hasSize(44);
    assertThat(pinner.pinnedContracts().stream().filter(key -> key.exchange().equals("NFO")).map(InstrumentKey::canonical).toList())
        .containsExactlyInAnyOrderElementsOf(expectedSymbols("NFO", "NIFTY", 100));
    assertThat(pinner.pinnedContracts().stream().filter(key -> key.exchange().equals("BFO")).map(InstrumentKey::canonical).toList())
        .containsExactlyInAnyOrderElementsOf(expectedSymbols("BFO", "SENSEX", 200));
    assertThat(registry.view()).hasSize(57);
    assertThat(before).isEqualTo(13);
    // PRIORITY SPLIT IS THE POINT. Option capture holds SPECULATIVE, the first tier evicted at the
    // cap; only the seeded futures keep PINNED_INDEX. subscribe() EVICTS lower-priority holds to make
    // room, so pinning research strikes at PINNED_INDEX would let a few dozen option legs displace a
    // live STRATEGY subscription — trading a tradeable signal for a research bar.
    assertThat(registry.view().stream().filter(v -> v.priority() == SubscriptionPriority.PINNED_INDEX))
        .as("seeded futures keep the top tier")
        .hasSize(13);
    assertThat(registry.view().stream().filter(v -> v.priority() == SubscriptionPriority.SPECULATIVE))
        .as("all 44 option pins sit BELOW live strategy subscriptions")
        .hasSize(44);
  }

  @Test
  void rolloverUnsubscribesContractsNoLongerInTheAtmWindow() {
    OptionsChainService chains = mock(OptionsChainService.class);
    when(chains.expiriesWithin("NIFTY 50", 1)).thenReturn(List.of(NEAR), List.of(ROLLED));
    when(chains.chain("NIFTY 50", NEAR)).thenReturn(chain("NIFTY 50", NEAR, 100));
    when(chains.chain("NIFTY 50", ROLLED)).thenReturn(chain("NIFTY 50", ROLLED, 300));
    Map<String, InstrumentTokenResolver.TokenInfo> master = new HashMap<>();
    addOptionKeys(master, "NFO", "NIFTY 50", 100);
    addOptionKeys(master, "NFO", "NIFTY 50", 300);
    SubscriptionRegistry registry = registry(master, 3_000);
    OptionAtmPinner pinner = new OptionAtmPinner(registry, chains, List.of("NIFTY 50"), 1, 1, new SimpleMeterRegistry());

    pinner.repin();
    pinner.repin();

    assertThat(pinner.pinnedContracts()).extracting(InstrumentKey::tradingsymbol)
        .containsExactlyInAnyOrder("NIFTY290CE", "NIFTY290PE", "NIFTY300CE", "NIFTY300PE", "NIFTY310CE", "NIFTY310PE");
    assertThat(registry.view()).extracting(SubscriptionRegistry.SubscriptionView::tradingsymbol)
        .containsExactlyInAnyOrder("NIFTY290CE", "NIFTY290PE", "NIFTY300CE", "NIFTY300PE", "NIFTY310CE", "NIFTY310PE");
  }

  @Test
  void capRefusalKeepsExistingPinnedSubscriptionsAndDoesNotAbortThePass() {
    OptionsChainService chains = mock(OptionsChainService.class);
    when(chains.expiriesWithin("NIFTY 50", 1)).thenReturn(List.of(NEAR));
    when(chains.chain("NIFTY 50", NEAR)).thenReturn(chain("NIFTY 50", NEAR, 100));
    Map<String, InstrumentTokenResolver.TokenInfo> master = new HashMap<>();
    addExistingPins(master);
    addOptionKeys(master, "NFO", "NIFTY 50", 100);
    SubscriptionRegistry registry = registry(master, 14);
    seedExistingPins(registry);
    OptionAtmPinner pinner = new OptionAtmPinner(registry, chains, List.of("NIFTY 50"), 1, 1, new SimpleMeterRegistry());

    pinner.repin();

    assertThat(pinner.pinnedContracts()).hasSize(1);
    assertThat(registry.view()).hasSize(14);
    assertThat(registry.view()).extracting(SubscriptionRegistry.SubscriptionView::tradingsymbol)
        .contains("INDEX-1", "INDEX-13");
  }

  @Test
  void oneUnresolvableContractDoesNotAbortTheRemainingPins() {
    OptionsChainService chains = mock(OptionsChainService.class);
    when(chains.expiriesWithin("NIFTY 50", 1)).thenReturn(List.of(NEAR));
    when(chains.chain("NIFTY 50", NEAR)).thenReturn(chain("NIFTY 50", NEAR, 100));
    Map<String, InstrumentTokenResolver.TokenInfo> master = new HashMap<>();
    addOptionKeys(master, "NFO", "NIFTY 50", 100);
    master.remove("NFO:NIFTY100CE");
    SubscriptionRegistry registry = registry(master, 3_000);
    OptionAtmPinner pinner = new OptionAtmPinner(registry, chains, List.of("NIFTY 50"), 1, 1, new SimpleMeterRegistry());

    pinner.repin();

    assertThat(pinner.pinnedContracts()).hasSize(5);
    assertThat(registry.view()).extracting(SubscriptionRegistry.SubscriptionView::tradingsymbol)
        .doesNotContain("NIFTY100CE");
  }

  /**
   * The discriminating case (cross-vendor review Major 4): the leg's exchange CONTRADICTS the root's
   * name. The underlying is {@code NIFTY 50} and every symbol starts with {@code NIFTY}, so the
   * removed helper — which mapped {@code SENSEX→BFO}, {@code *NIFTY*→NFO} and THREW on anything else
   * — pins {@code NFO:*}. Only reading the master-sourced value on each leg pins {@code BFO:*}.
   *
   * <p>This is what makes the pinner root-agnostic rather than merely correct for today's two
   * configured underlyings: a newly listed BSE root used to be unpinnable at all (the helper threw
   * once per cycle, forever), so the contracts were simply never captured.
   */
  @Test
  void theExchangeIsTheMastersEvenWhenItContradictsTheRootsName() {
    OptionsChainService chains = mock(OptionsChainService.class);
    when(chains.expiriesWithin("NIFTY 50", 7)).thenReturn(List.of(NEAR));
    when(chains.chain("NIFTY 50", NEAR)).thenReturn(chain("NIFTY 50", NEAR, 100, "BFO"));

    Map<String, InstrumentTokenResolver.TokenInfo> master = new HashMap<>();
    addOptionKeys(master, "BFO", "NIFTY 50", 100);
    SubscriptionRegistry registry = registry(master, 3_000);

    OptionAtmPinner pinner =
        new OptionAtmPinner(registry, chains, List.of("NIFTY 50"), 5, 7, new SimpleMeterRegistry());
    pinner.repin();

    assertThat(pinner.pinnedContracts())
        .as("a name-prefix guess would have pinned NFO for every one of these NIFTY-named symbols")
        .isNotEmpty()
        .allSatisfy(key -> assertThat(key.exchange()).isEqualTo("BFO"));
    assertThat(pinner.pinnedContracts().stream().map(InstrumentKey::canonical).toList())
        .containsExactlyInAnyOrderElementsOf(expectedSymbols("BFO", "NIFTY", 100));
  }

  /** A leg the master gave no exchange for is SKIPPED, not guessed — an unresolvable pin is silent. */
  @Test
  void aLegWithNoMasterExchangeIsSkippedRatherThanGuessed() {
    OptionsChainService chains = mock(OptionsChainService.class);
    when(chains.expiriesWithin("NIFTY 50", 7)).thenReturn(List.of(NEAR));
    when(chains.chain("NIFTY 50", NEAR)).thenReturn(chain("NIFTY 50", NEAR, 100, null));

    Map<String, InstrumentTokenResolver.TokenInfo> master = new HashMap<>();
    addOptionKeys(master, "NFO", "NIFTY 50", 100);
    SubscriptionRegistry registry = registry(master, 3_000);

    OptionAtmPinner pinner =
        new OptionAtmPinner(registry, chains, List.of("NIFTY 50"), 5, 7, new SimpleMeterRegistry());
    pinner.repin();

    assertThat(pinner.pinnedContracts())
        .as("guessing NFO here would pin a key the registry cannot vouch for")
        .isEmpty();
  }

  private static SubscriptionRegistry registry(Map<String, InstrumentTokenResolver.TokenInfo> master, int cap) {
    return new SubscriptionRegistry(key -> Optional.ofNullable(master.get(key.canonical())), cap, new SimpleMeterRegistry());
  }

  private static OptionsChainService.Chain chain(String underlying, LocalDate expiry, int center) {
    return chain(underlying, expiry, center, underlying.equals("NIFTY 50") ? "NFO" : "BFO");
  }

  /** Same chain, with the leg exchange stated explicitly — lets a test contradict the root's name. */
  private static OptionsChainService.Chain chain(
      String underlying, LocalDate expiry, int center, String legExchange) {
    List<OptionsChainService.StrikeRow> rows = new ArrayList<>();
    for (int offset = -10; offset <= 10; offset++) {
      int strike = center + offset * 10;
      rows.add(new OptionsChainService.StrikeRow(
          BigDecimal.valueOf(strike),
          leg(underlying, strike, "CE", legExchange),
          leg(underlying, strike, "PE", legExchange)));
    }
    return new OptionsChainService.Chain(
        underlying, expiry, BigDecimal.valueOf(center), null, "TEST", BigDecimal.ZERO, null,
        false, false, OffsetDateTime.parse("2026-07-20T10:00:00+05:30"), rows);
  }

  private static OptionsChainService.Leg leg(
      String underlying, int strike, String type, String exchange) {
    String symbol = underlying.equals("NIFTY 50") ? "NIFTY" : "SENSEX";
    return new OptionsChainService.Leg(exchange, symbol + strike + type, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null);
  }

  private static void addOptionKeys(Map<String, InstrumentTokenResolver.TokenInfo> master, String exchange, String underlying, int center) {
    String root = underlying.equals("NIFTY 50") ? "NIFTY" : "SENSEX";
    for (int offset = -5; offset <= 5; offset++) {
      int strike = center + offset * 10;
      master.put(exchange + ":" + root + strike + "CE", new InstrumentTokenResolver.TokenInfo(strike * 10L + 1, "CE", exchange + "-OPT"));
      master.put(exchange + ":" + root + strike + "PE", new InstrumentTokenResolver.TokenInfo(strike * 10L + 2, "PE", exchange + "-OPT"));
    }
  }

  private static List<String> expectedSymbols(String exchange, String root, int center) {
    return IntStream.rangeClosed(-5, 5)
        .boxed()
        .flatMap(offset -> {
          int strike = center + offset * 10;
          return java.util.stream.Stream.of(
              exchange + ":" + root + strike + "CE",
              exchange + ":" + root + strike + "PE");
        })
        .toList();
  }

  private static void addExistingPins(Map<String, InstrumentTokenResolver.TokenInfo> master) {
    for (int i = 1; i <= 13; i++) {
      master.put("NSE:INDEX-" + i, new InstrumentTokenResolver.TokenInfo(10_000L + i, "EQ", "INDICES"));
    }
  }

  private static void seedExistingPins(SubscriptionRegistry registry) {
    for (int i = 1; i <= 13; i++) {
      registry.subscribe("existing-" + i, new InstrumentKey("NSE", "INDEX-" + i), SubscriptionMode.QUOTE, SubscriptionPriority.PINNED_INDEX);
    }
  }

  /**
   * CRITICAL regression (cross-vendor review): a transient chain failure used to empty the desired
   * set for that underlying, and the stale sweep then unsubscribed its live pins — dropping capture
   * and opening unrecoverable 1-minute gaps until the next ready/master event. A failed underlying
   * must keep every pin it already holds.
   */
  @Test
  void aFailedUnderlyingKeepsItsExistingPinsInsteadOfBeingRolledOff() {
    OptionsChainService chains = mock(OptionsChainService.class);
    when(chains.expiriesWithin(anyString(), eq(7))).thenReturn(List.of(NEAR));
    when(chains.chain("NIFTY 50", NEAR)).thenReturn(chain("NIFTY 50", NEAR, 100));

    Map<String, InstrumentTokenResolver.TokenInfo> master = new HashMap<>();
    addOptionKeys(master, "NFO", "NIFTY 50", 100);
    SubscriptionRegistry registry =
        new SubscriptionRegistry(
            key -> Optional.ofNullable(master.get(key.canonical())), 3_000, new SimpleMeterRegistry());
    OptionAtmPinner pinner =
        new OptionAtmPinner(registry, chains, List.of("NIFTY 50"), 5, 7, new SimpleMeterRegistry());

    pinner.repin();
    assertThat(pinner.pinnedContracts()).hasSize(22);

    // The chain service now fails for this underlying — resolution is impossible, not "empty".
    when(chains.chain("NIFTY 50", NEAR)).thenThrow(new IllegalStateException("chain unavailable"));
    pinner.repin();

    assertThat(pinner.pinnedContracts())
        .as("a resolution FAILURE must not be read as 'nothing wanted'")
        .hasSize(22);
    assertThat(registry.view()).hasSize(22);
  }

  /**
   * An underlying with no expiry inside the horizon is also NOT resolved, so it must be left alone
   * rather than swept — same class as the failure case above.
   */
  @Test
  void anUnderlyingWithNoExpiryInHorizonKeepsItsPins() {
    OptionsChainService chains = mock(OptionsChainService.class);
    when(chains.expiriesWithin(anyString(), eq(7))).thenReturn(List.of(NEAR));
    when(chains.chain("NIFTY 50", NEAR)).thenReturn(chain("NIFTY 50", NEAR, 100));

    Map<String, InstrumentTokenResolver.TokenInfo> master = new HashMap<>();
    addOptionKeys(master, "NFO", "NIFTY 50", 100);
    SubscriptionRegistry registry =
        new SubscriptionRegistry(
            key -> Optional.ofNullable(master.get(key.canonical())), 3_000, new SimpleMeterRegistry());
    OptionAtmPinner pinner =
        new OptionAtmPinner(registry, chains, List.of("NIFTY 50"), 5, 7, new SimpleMeterRegistry());
    pinner.repin();
    assertThat(pinner.pinnedContracts()).hasSize(22);

    when(chains.expiriesWithin(anyString(), eq(7))).thenReturn(List.of());
    pinner.repin();

    assertThat(pinner.pinnedContracts()).hasSize(22);
  }

  /**
   * MAJOR regression: these holds are SPECULATIVE and therefore PERSISTED and replayed across a
   * restart, so a fresh pinner starts with an empty in-memory set while the registry already holds
   * yesterday's strikes. Without hydrating from the registry it could never roll an expired one off,
   * and they would accumulate forever.
   */
  @Test
  void aFreshPinnerHydratesRestoredHoldsAndRollsOffTheExpiredWindow() {
    OptionsChainService chains = mock(OptionsChainService.class);
    when(chains.expiriesWithin(anyString(), eq(7))).thenReturn(List.of(NEAR));
    when(chains.chain("NIFTY 50", NEAR)).thenReturn(chain("NIFTY 50", NEAR, 100));

    Map<String, InstrumentTokenResolver.TokenInfo> master = new HashMap<>();
    addOptionKeys(master, "NFO", "NIFTY 50", 100);
    addOptionKeys(master, "NFO", "NIFTY 50", 300);
    SubscriptionRegistry registry =
        new SubscriptionRegistry(
            key -> Optional.ofNullable(master.get(key.canonical())), 3_000, new SimpleMeterRegistry());

    // Stand in for SubscriptionReplayer: yesterday's window is already in the registry under the
    // pinner's own subscriber id, with NO in-memory state anywhere.
    for (String symbol : expectedSymbols("NFO", "NIFTY", 300)) {
      registry.subscribe("system-opt-atm-pins", key(symbol));
    }
    int restored = registry.view().size();
    assertThat(restored).isEqualTo(22);

    OptionAtmPinner fresh =
        new OptionAtmPinner(registry, chains, List.of("NIFTY 50"), 5, 7, new SimpleMeterRegistry());
    fresh.repin();

    assertThat(fresh.pinnedContracts())
        .as("the restored 300-window must be rolled off, not stranded forever")
        .containsExactlyInAnyOrderElementsOf(
            expectedSymbols("NFO", "NIFTY", 100).stream().map(OptionAtmPinnerTest::key).toList());
    assertThat(registry.view()).hasSize(22);
  }

  private static InstrumentKey key(String canonical) {
    String[] parts = canonical.split(":", 2);
    return new InstrumentKey(parts[0], parts[1]);
  }

  /**
   * Round-2 regression: ROLLOVER AT EXACTLY FULL CAP. The subscribe pass runs BEFORE the stale sweep
   * frees capacity, so on an expiry roll against a full registry every new contract is cap-refused,
   * the old window is then released, and without the post-sweep retry the freed slots would sit
   * unused for a whole cycle. The earlier cap test only populated an empty registry and so could
   * never reach this path.
   */
  @Test
  void rolloverAtExactlyFullCapRetriesTheRefusedWindowAfterTheStaleSweep() {
    OptionsChainService chains = mock(OptionsChainService.class);
    when(chains.expiriesWithin(anyString(), eq(7))).thenReturn(List.of(NEAR));
    when(chains.chain("NIFTY 50", NEAR)).thenReturn(chain("NIFTY 50", NEAR, 100));

    Map<String, InstrumentTokenResolver.TokenInfo> master = new HashMap<>();
    addOptionKeys(master, "NFO", "NIFTY 50", 100);
    addOptionKeys(master, "NFO", "NIFTY 50", 300);
    // Cap == exactly one window, so the second window cannot be admitted until the first is released.
    SubscriptionRegistry registry =
        new SubscriptionRegistry(
            key -> Optional.ofNullable(master.get(key.canonical())), 22, new SimpleMeterRegistry());
    OptionAtmPinner pinner =
        new OptionAtmPinner(registry, chains, List.of("NIFTY 50"), 5, 7, new SimpleMeterRegistry());

    pinner.repin();
    assertThat(pinner.pinnedContracts()).hasSize(22);
    assertThat(registry.view()).hasSize(22);

    // Expiry rolls: the whole desired set is new, and the registry is already exactly full.
    when(chains.chain("NIFTY 50", NEAR)).thenReturn(chain("NIFTY 50", NEAR, 300));
    pinner.repin();

    assertThat(pinner.pinnedContracts())
        .as("the freed slots must be reused in the SAME pass, not left idle for a cycle")
        .containsExactlyInAnyOrderElementsOf(
            expectedSymbols("NFO", "NIFTY", 300).stream().map(OptionAtmPinnerTest::key).toList());
    assertThat(registry.view()).hasSize(22);
  }

  /**
   * Round-2 regression: LISTENER ORDER. Hydrating from the registry is only sound if replay has
   * already restored the persisted holds. Both listeners once sat at LOWEST_PRECEDENCE, so the
   * pinner could win and never see the holds replay was about to put back. Asserted on the
   * annotations because the ordering — not any single pass — is the invariant.
   */
  @Test
  void subscriptionReplayerIsOrderedStrictlyBeforeThePinnerOnBothEvents() throws Exception {
    int replayReady = orderOf(SubscriptionReplayer.class, ApplicationReadyEvent.class);
    int replayMaster = orderOf(SubscriptionReplayer.class, InstrumentMasterUpdated.class);
    int pinnerReady = orderOf(OptionAtmPinner.class, ApplicationReadyEvent.class);
    int pinnerMaster = orderOf(OptionAtmPinner.class, InstrumentMasterUpdated.class);

    assertThat(replayReady)
        .as("replay must restore persisted holds before the pinner hydrates from the registry")
        .isLessThan(pinnerReady);
    assertThat(replayMaster)
        .as("same ordering on the instrument-master path")
        .isLessThan(pinnerMaster);
  }

  /** The @Order value of the listener method handling {@code event} on {@code type}. */
  private static int orderOf(Class<?> type, Class<?> event) {
    for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
      org.springframework.context.event.EventListener listener =
          method.getAnnotation(org.springframework.context.event.EventListener.class);
      if (listener == null) {
        continue;
      }
      boolean handles = false;
      for (Class<?> candidate : listener.value()) {
        if (candidate.equals(event)) {
          handles = true;
        }
      }
      if (!handles) {
        continue;
      }
      org.springframework.core.annotation.Order order =
          method.getAnnotation(org.springframework.core.annotation.Order.class);
      return order == null ? org.springframework.core.Ordered.LOWEST_PRECEDENCE : order.value();
    }
    throw new AssertionError("no @EventListener for " + event.getSimpleName() + " on " + type);
  }

  /**
   * ⚠️ H44: A SECOND REPIN MUST RE-CENTRE THE BAND ON THE NEW SPOT.
   *
   * <p>The band always CENTRED on current spot; the defect was that nothing re-ran it during the
   * session. Triggers were {@code ApplicationReadyEvent} and {@code InstrumentMasterUpdated} only, so
   * the band froze at roughly the 08:30 master sync and never moved. A contract outside it never
   * ticks, and every AUTOMATIC exit refuses without a real tick (#694) — which is how two SENSEX PE
   * legs became unsettleable on 2026-08-28 for -Rs 8,892.79.
   *
   * <p>⚠️ <b>THIS TEST DOES NOT PROVE THE FIX, and saying so is the point.</b> It calls
   * {@code repin()} directly, and {@code repin()} ALWAYS re-centred — red-proofed 2026-08-29: with
   * the scheduled trigger deleted this file still passes 12/12. The defect was never the band maths,
   * it was that nothing CALLED it during the session. The trigger is pinned by
   * {@link #theRepinScheduleFiresRepeatedlyInsideTheSessionAndNotOutsideIt}; this one only guards the
   * property that trigger depends on.
   *
   * <p>It drives the pinner the way the new schedule does — call {@code repin()} again after spot
   * has moved — and asserts the pinned SET changed. Asserting only that "repin ran" would pass even
   * if the band were frozen, which is precisely the bug.
   */
  @Test
  void aSecondRepinRecentresTheBandOnTheNewSpot() {
    OptionsChainService chains = mock(OptionsChainService.class);
    when(chains.expiriesWithin(anyString(), eq(7))).thenReturn(List.of(NEAR));
    when(chains.chain("NIFTY 50", NEAR)).thenReturn(chain("NIFTY 50", NEAR, 100));

    Map<String, InstrumentTokenResolver.TokenInfo> master = new HashMap<>();
    addOptionKeys(master, "NFO", "NIFTY 50", 100);
    addOptionKeys(master, "NFO", "NIFTY 50", 200); // the strikes the MOVED band will want
    SubscriptionRegistry registry =
        new SubscriptionRegistry(
            key -> Optional.ofNullable(master.get(key.canonical())), 3_000, new SimpleMeterRegistry());

    OptionAtmPinner pinner =
        new OptionAtmPinner(
            registry, chains, List.of("NIFTY 50"), 2, 7, new SimpleMeterRegistry());
    pinner.repin();
    List<String> atSpot100 =
        pinner.pinnedContracts().stream().map(InstrumentKey::canonical).sorted().toList();

    // Spot moves 100 -> 200, exactly what an intraday drift does.
    when(chains.chain("NIFTY 50", NEAR)).thenReturn(chain("NIFTY 50", NEAR, 200));
    pinner.repin();
    List<String> atSpot200 =
        pinner.pinnedContracts().stream().map(InstrumentKey::canonical).sorted().toList();

    assertThat(atSpot200)
        .as("the band must FOLLOW spot — a frozen band is the H44 failure mode")
        .isNotEqualTo(atSpot100);
    assertThat(atSpot200)
        .as("and it must now cover the NEW centre, which is the strike the picker can reach")
        .anyMatch(sym -> sym.contains("NIFTY200"));
    assertThat(atSpot200)
        .as("the reconcile RELEASES what drifted out of range, it does not accumulate")
        .hasSameSizeAs(atSpot100);
  }

  /**
   * ⚠️ THE ACTUAL H44 FIX: a repin must FIRE during the session. Everything else about the band
   * already worked.
   *
   * <p>Asserts the SEMANTICS of the schedule, not merely that an annotation is present: the cron must
   * fire more than once between 09:15 and 15:30 IST on a weekday (a band that re-centres once a day is
   * the bug), and must NOT fire in the evening (outside the session spot does not move, so a repin is
   * pure churn against the subscription cap).
   */
  @Test
  void theRepinScheduleFiresRepeatedlyInsideTheSessionAndNotOutsideIt() throws Exception {
    Scheduled scheduled =
        OptionAtmPinner.class.getMethod("repinDuringSession").getAnnotation(Scheduled.class);
    assertThat(scheduled)
        .as("the session repin must be SCHEDULED — boot + master-refresh alone froze the band")
        .isNotNull();
    assertThat(scheduled.zone()).isEqualTo("Asia/Kolkata");

    // The @Value default embedded in the placeholder is what ships when nothing overrides it.
    String cron = scheduled.cron();
    String expression = cron.substring(cron.indexOf(':') + 1, cron.length() - 1);
    CronExpression parsed = CronExpression.parse(expression);

    LocalDateTime open = LocalDateTime.of(2026, 8, 31, 9, 15); // a Monday
    LocalDateTime first = parsed.next(open);
    LocalDateTime second = parsed.next(first);
    assertThat(second)
        .as("must fire MORE THAN ONCE inside the session — once a day is the frozen-band bug")
        .isBefore(LocalDateTime.of(2026, 8, 31, 15, 30));

    LocalDateTime evening = LocalDateTime.of(2026, 8, 31, 20, 0);
    assertThat(parsed.next(evening))
        .as("must NOT fire in the evening — spot does not move, so a repin is pure churn")
        .isAfter(LocalDateTime.of(2026, 8, 31, 23, 59));
  }
}
