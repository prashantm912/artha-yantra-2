package in.arthayantra.marketdata.options;

import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.InstrumentMasterUpdated;
import in.arthayantra.marketdata.kite.PinnedSubscriptionRegistrar;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
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

/** Pins the live near-expiry ATM option window for the configured index underlyings. */
@Component
public class OptionAtmPinner {

  private static final Logger log = LoggerFactory.getLogger(OptionAtmPinner.class);
  private static final String SUBSCRIBER = "system-opt-atm-pins";

  private final PinnedSubscriptionRegistrar registry;
  private final OptionsChainService chains;
  private final List<String> underlyings;
  private final int strikeWidth;
  private final int expiryHorizonDays;
  private final Set<InstrumentKey> currentPins = ConcurrentHashMap.newKeySet();

  public OptionAtmPinner(
      PinnedSubscriptionRegistrar registry,
      OptionsChainService chains,
      @Value("${artha.options.atm-pinner.underlyings:NIFTY 50,SENSEX}") List<String> underlyings,
      @Value("${artha.options.atm-pinner.strike-width:5}") int strikeWidth,
      @Value("${artha.options.atm-pinner.expiry-horizon-days:7}") int expiryHorizonDays,
      MeterRegistry meterRegistry) {
    this.registry = registry;
    this.chains = chains;
    this.underlyings = underlyings;
    this.strikeWidth = Math.max(0, strikeWidth);
    this.expiryHorizonDays = Math.max(0, expiryHorizonDays);
    meterRegistry.gauge("ay_options_atm_pinned_contracts", currentPins, Set::size);
  }

  /** Initial pin pass after startup. */
  @EventListener(ApplicationReadyEvent.class)
  @Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE)
  public void onReady() {
    repin();
  }

  /** Re-resolves the option window after each instrument-master refresh. */
  @EventListener(InstrumentMasterUpdated.class)
  public void onMasterUpdated() {
    repin();
  }

  /** Resolves the nearest expiry and reconciles its ATM±N CE/PE pin set. */
  public synchronized void repin() {
    Set<InstrumentKey> desired = desiredPins();
    for (InstrumentKey key : desired) {
      try {
        registry.subscribe(SUBSCRIBER, key);
        currentPins.add(key);
      } catch (Exception failure) {
        if (failure.getMessage() != null && failure.getMessage().contains("subscription cap")) {
          log.error("option ATM pin {} refused at the subscription cap: {}", key.canonical(), failure.getMessage());
        } else {
          log.warn("option ATM pin {} failed: {}", key.canonical(), failure.getMessage());
        }
      }
    }
    for (InstrumentKey stale : new HashSet<>(currentPins)) {
      if (!desired.contains(stale)) {
        registry.unsubscribe(SUBSCRIBER, stale);
        currentPins.remove(stale);
        log.info("option ATM pin rolled off: {}", stale.canonical());
      }
    }
    log.info("option ATM pin pass: desired={}, pinned={}", desired.size(), currentPins.size());
  }

  /** The current option pin set. */
  public Set<InstrumentKey> pinnedContracts() {
    return Set.copyOf(currentPins);
  }

  private Set<InstrumentKey> desiredPins() {
    Set<InstrumentKey> desired = new HashSet<>();
    for (String configuredUnderlying : underlyings) {
      String underlying = configuredUnderlying.trim();
      try {
        List<LocalDate> expiries = chains.expiriesWithin(underlying, expiryHorizonDays);
        if (expiries.isEmpty()) {
          log.warn("no option expiry within {} days for {} yet", expiryHorizonDays, underlying);
          continue;
        }
        OptionsChainService.Chain chain = chains.chain(underlying, expiries.get(0));
        List<OptionsChainService.StrikeRow> rows = atmWindow(chain);
        String exchange = optionExchange(underlying);
        for (OptionsChainService.StrikeRow row : rows) {
          addLeg(desired, exchange, underlying, row.ce());
          addLeg(desired, exchange, underlying, row.pe());
        }
      } catch (RuntimeException failure) {
        log.warn("option ATM pin resolution failed for {}: {}", underlying, failure.getMessage());
      }
    }
    return desired;
  }

  private List<OptionsChainService.StrikeRow> atmWindow(OptionsChainService.Chain chain) {
    if (chain.rows() == null || chain.rows().isEmpty() || chain.spot() == null) {
      return List.of();
    }
    List<OptionsChainService.StrikeRow> rows =
        chain.rows().stream()
            .filter(row -> row != null && row.strike() != null)
            .sorted(Comparator.comparing(OptionsChainService.StrikeRow::strike))
            .toList();
    if (rows.isEmpty()) {
      return List.of();
    }
    int atmIndex = 0;
    BigDecimal bestDistance = null;
    for (int i = 0; i < rows.size(); i++) {
      BigDecimal distance = rows.get(i).strike().subtract(chain.spot()).abs();
      if (bestDistance == null || distance.compareTo(bestDistance) < 0) {
        bestDistance = distance;
        atmIndex = i;
      }
    }
    int from = Math.max(0, atmIndex - strikeWidth);
    int to = Math.min(rows.size(), atmIndex + strikeWidth + 1);
    return rows.subList(from, to);
  }

  private static void addLeg(
      Set<InstrumentKey> desired,
      String exchange,
      String underlying,
      OptionsChainService.Leg leg) {
    if (leg == null || leg.tradingsymbol() == null || leg.tradingsymbol().isBlank()) {
      log.warn("option ATM pin has no resolvable leg for {}", underlying);
      return;
    }
    desired.add(new InstrumentKey(exchange, leg.tradingsymbol()));
  }

  private static String optionExchange(String underlying) {
    if ("SENSEX".equalsIgnoreCase(underlying)) {
      return "BFO";
    }
    if (underlying.toUpperCase().contains("NIFTY")) {
      return "NFO";
    }
    throw new IllegalArgumentException("unsupported option ATM underlying " + underlying);
  }
}
