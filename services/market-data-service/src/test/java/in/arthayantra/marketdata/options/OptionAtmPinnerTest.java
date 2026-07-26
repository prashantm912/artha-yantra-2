package in.arthayantra.marketdata.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.InstrumentTokenResolver;
import in.arthayantra.marketdata.kite.ticker.SubscriptionPriority;
import in.arthayantra.marketdata.kite.ticker.SubscriptionRegistry;
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

  private static SubscriptionRegistry registry(Map<String, InstrumentTokenResolver.TokenInfo> master, int cap) {
    return new SubscriptionRegistry(key -> Optional.ofNullable(master.get(key.canonical())), cap, new SimpleMeterRegistry());
  }

  private static OptionsChainService.Chain chain(String underlying, LocalDate expiry, int center) {
    List<OptionsChainService.StrikeRow> rows = new ArrayList<>();
    for (int offset = -10; offset <= 10; offset++) {
      int strike = center + offset * 10;
      rows.add(new OptionsChainService.StrikeRow(
          BigDecimal.valueOf(strike),
          leg(underlying, strike, "CE"),
          leg(underlying, strike, "PE")));
    }
    return new OptionsChainService.Chain(
        underlying, expiry, BigDecimal.valueOf(center), null, "TEST", BigDecimal.ZERO, null,
        false, OffsetDateTime.parse("2026-07-20T10:00:00+05:30"), rows);
  }

  private static OptionsChainService.Leg leg(String underlying, int strike, String type) {
    String symbol = underlying.equals("NIFTY 50") ? "NIFTY" : "SENSEX";
    return new OptionsChainService.Leg(symbol + strike + type, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null);
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
}
