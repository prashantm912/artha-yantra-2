package in.arthayantra.marketdata.kite.ticker;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.kite.FuturesContractSource;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.InstrumentMasterUpdated;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Pins the front/next/far monthly FUT contracts of the configured underlyings (Phase 15A / B-18)
 * at quote mode, pinned-priority tier. Re-resolves after every instrument sync — expiry rollover
 * re-pins the new far month and unpins the dead contract automatically; the
 * {@code ay_futures_pinned_contracts} gauge tracks the live pin count.
 */
@Component
public class FuturesPinner {

  private static final Logger log = LoggerFactory.getLogger(FuturesPinner.class);
  private static final String SUBSCRIBER = "system-fut-pins";

  private final SubscriptionRegistry registry;
  private final FuturesContractSource contracts;
  private final Clock clock;
  private final List<String> underlyings;
  private final Set<InstrumentKey> currentPins = ConcurrentHashMap.newKeySet();

  /** Wires the pinner ({@code ARTHA_FUTURES_UNDERLYINGS}, default NIFTY 50 + NIFTY BANK). */
  public FuturesPinner(
      SubscriptionRegistry registry,
      FuturesContractSource contracts,
      Clock clock,
      @Value("${artha.futures.underlyings:NIFTY 50,NIFTY BANK}") List<String> underlyings,
      MeterRegistry meterRegistry) {
    this.registry = registry;
    this.contracts = contracts;
    this.clock = clock;
    this.underlyings = underlyings;
    meterRegistry.gauge("ay_futures_pinned_contracts", currentPins, Set::size);
  }

  /** Initial pin pass after startup. */
  @EventListener(ApplicationReadyEvent.class)
  @Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE)
  public void onReady() {
    repin();
  }

  /** Morning re-resolution: every sync re-pins, making expiry rollover automatic. */
  @EventListener(InstrumentMasterUpdated.class)
  public void onMasterUpdated() {
    repin();
  }

  /** Resolves front/next/far per underlying and reconciles the pin set. */
  public synchronized void repin() {
    LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
    Set<InstrumentKey> desired = new HashSet<>();
    for (String underlying : underlyings) {
      List<FuturesContractSource.FutContract> ladder =
          contracts.monthlyFutures(underlying.trim(), today);
      ladder.stream().limit(3).forEach(c -> desired.add(c.key()));
      if (ladder.isEmpty()) {
        log.warn("no FUT contracts resolvable for {} yet", underlying.trim());
      }
    }
    for (InstrumentKey key : desired) {
      try {
        registry.subscribe(SUBSCRIBER, key, SubscriptionMode.QUOTE, SubscriptionPriority.PINNED_INDEX);
        currentPins.add(key);
      } catch (Exception unresolvable) {
        log.warn("FUT pin {} failed: {}", key.canonical(), unresolvable.getMessage());
      }
    }
    // rollover: drop pins for contracts no longer in the front/next/far window
    for (InstrumentKey stale : new HashSet<>(currentPins)) {
      if (!desired.contains(stale)) {
        registry.unsubscribe(SUBSCRIBER, stale);
        currentPins.remove(stale);
        log.info("FUT pin rolled off: {}", stale.canonical());
      }
    }
  }

  /** The current pin set (EOD backfill + tests). */
  public Set<InstrumentKey> pinnedContracts() {
    return Set.copyOf(currentPins);
  }
}
