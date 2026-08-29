package in.arthayantra.marketdata.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.marketdata.feed.LastTickStore;
import in.arthayantra.marketdata.feed.NormalizedTick;
import in.arthayantra.marketdata.instruments.Instrument;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import in.arthayantra.marketdata.kite.InstrumentTokenResolver;
import in.arthayantra.marketdata.kite.ticker.SubscriptionRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Cross-vendor review Minor: the repin executor's queue is UNBOUNDED, and making the repin recurring
 * is what put traffic on it.
 *
 * <p>The executor is a single thread, and {@code repinAsync()} returns immediately — so Spring's
 * no-overlap guarantee protects the SCHEDULER, not this queue. If one pass ever outran the 5-minute
 * cron, every later fire would stack behind it and then run in sequence, each re-resolving a band the
 * next queued pass immediately supersedes. A repin always reconciles to CURRENT state, so at most ONE
 * pending pass is ever useful.
 */
class OptionAtmPinnerCoalesceTest {

  private static final LocalDate NEAR = LocalDate.now().plusDays(2);
  private static final String UNDERLYING = "NIFTY 50";

  /**
   * ⚠️ The assertion is on the number of passes that actually RAN, not on the counter. A counter
   * assertion would be satisfied by a gate that counted correctly and queued anyway; only counting
   * executions distinguishes "coalesced" from "counted".
   */
  @Test
  void firesArrivingWhileAPassIsStuckCollapseToOnePendingPass() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch firstPassEntered = new CountDownLatch(1);
    AtomicInteger passes = new AtomicInteger();

    InstrumentRepository instruments = mock(InstrumentRepository.class);
    when(instruments.optionChain(anyString(), any()))
        .thenAnswer(
            invocation -> {
              passes.incrementAndGet();
              firstPassEntered.countDown();
              // Hold the single executor thread, exactly as a slow network-backed pass would.
              release.await(5, TimeUnit.SECONDS);
              return ladder();
            });

    OptionAtmPinner pinner = pinner(instruments);

    pinner.onMasterUpdated(); // pass 1 — takes the thread and blocks
    assertThat(firstPassEntered.await(5, TimeUnit.SECONDS)).isTrue();

    for (int i = 0; i < 20; i++) {
      pinner.onMasterUpdated(); // 20 more fires, all while pass 1 is stuck
    }

    release.countDown();
    // Let the queue drain; the executor is a single thread so this is ordered.
    for (int i = 0; i < 50 && passes.get() < 2; i++) {
      Thread.sleep(20);
    }
    Thread.sleep(200);

    assertThat(passes.get())
        .as("21 fires must collapse to the running pass plus at most one pending one")
        .isLessThanOrEqualTo(2);
  }

  // ---------------------------------------------------------------- harness

  private static OptionAtmPinner pinner(InstrumentRepository instruments) {
    OptionsChainService chains = mock(OptionsChainService.class);
    when(chains.expiriesWithin(UNDERLYING, 7)).thenReturn(List.of(NEAR));

    LastTickStore ticks = new LastTickStore();
    ticks.update(
        new NormalizedTick(
            "NSE", UNDERLYING, new BigDecimal("25000"), 0L, null, OffsetDateTime.now(), 1L));

    Map<String, InstrumentTokenResolver.TokenInfo> master = new HashMap<>();
    long token = 1;
    for (Instrument contract : ladder()) {
      master.put(
          contract.exchange() + ":" + contract.tradingsymbol(),
          new InstrumentTokenResolver.TokenInfo(token++, contract.instrumentType(), "NFO-OPT"));
    }
    SubscriptionRegistry registry =
        new SubscriptionRegistry(
            key -> Optional.ofNullable(master.get(key.canonical())),
            3_000,
            new SimpleMeterRegistry());

    return new OptionAtmPinner(
        registry,
        chains,
        List.of(UNDERLYING),
        2,
        7,
        instruments,
        ticks,
        new SimpleMeterRegistry(),
        Clock.systemUTC());
  }

  private static List<Instrument> ladder() {
    return List.of(
        contract("NIFTY25000CE", "CE", 25_000),
        contract("NIFTY25000PE", "PE", 25_000),
        contract("NIFTY25100CE", "CE", 25_100),
        contract("NIFTY25100PE", "PE", 25_100));
  }

  private static Instrument contract(String symbol, String type, int strike) {
    return new Instrument(
        "NFO", symbol, null, null, "NFO-OPT", type, "NSE", UNDERLYING, NEAR,
        new BigDecimal(strike), null, 75, true, false);
  }
}
