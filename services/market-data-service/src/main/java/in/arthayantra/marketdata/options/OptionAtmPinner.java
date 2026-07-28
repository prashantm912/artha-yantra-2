package in.arthayantra.marketdata.options;

import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.InstrumentMasterUpdated;
import in.arthayantra.marketdata.kite.PinnedSubscriptionRegistrar;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
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

  /**
   * Off-thread repin.
   *
   * <p>{@code repin()} does network-backed, rate-limited full-chain resolution.
   * {@code InstrumentMasterUpdated} is published SYNCHRONOUSLY from inside instrument sync, and
   * {@code ApplicationReadyEvent} runs on the boot thread — so calling it inline made quote latency
   * and retries block instrument sync, and behind it the single default {@code taskScheduler} that
   * ~32 scheduled jobs share. Starving that pool is the S1–S3 failure mode (#1016). A dedicated
   * single daemon thread keeps every caller's thread free; passes can only ever queue behind each
   * other, which is what {@code synchronized repin()} already guaranteed. (Cross-vendor review Major.)
   */
  private final java.util.concurrent.ExecutorService repinExecutor =
      java.util.concurrent.Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "option-atm-pinner");
            thread.setDaemon(true);
            return thread;
          });

  /** Initial pin pass after startup — dispatched, never on the boot thread. */
  @EventListener(ApplicationReadyEvent.class)
  @Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE)
  public void onReady() {
    repinAsync();
  }

  /** Re-resolves after each instrument-master refresh — dispatched, never on the sync thread. */
  @EventListener(InstrumentMasterUpdated.class)
  @Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE)
  public void onMasterUpdated() {
    repinAsync();
  }

  /** Hands one reconcile to the dedicated thread; never throws into the publishing caller. */
  private void repinAsync() {
    repinExecutor.execute(
        () -> {
          try {
            repin();
          } catch (RuntimeException failure) {
            log.warn("option ATM repin pass failed: {}", failure.toString());
          }
        });
  }

  /** Releases the repin thread on context shutdown. */
  @jakarta.annotation.PreDestroy
  public void shutdown() {
    repinExecutor.shutdownNow();
  }

  /**
   * Resolves each underlying's nearest expiry and reconciles ONLY that underlying's pin set.
   *
   * <p><b>A failed underlying keeps its existing pins.</b> Reconciling against a single flat desired
   * set made "I could not resolve" indistinguishable from "nothing is wanted": one chain-service
   * outage emptied the desired set and the stale phase then unsubscribed every live pin, opening
   * unrecoverable 1-minute gaps until the next ready/master event. Resolution failure is now scoped
   * per underlying and simply skips that underlying's reconcile. (Cross-vendor review Critical.)
   */
  public synchronized void repin() {
    // Hydrate from the REGISTRY, not from our own field. These holds are SPECULATIVE and therefore
    // PERSISTED and replayed across a restart, so a fresh process starts with an empty currentPins
    // while the registry already holds yesterday's strikes — which could then never be rolled off.
    // (Cross-vendor review Major.)
    currentPins.addAll(registry.heldBy(SUBSCRIBER));

    Map<String, Set<InstrumentKey>> resolved = desiredPins();
    Set<InstrumentKey> desiredAll = new HashSet<>();
    resolved.values().forEach(desiredAll::addAll);

    Set<InstrumentKey> capRefused = new HashSet<>();
    for (InstrumentKey key : desiredAll) {
      subscribeOne(key, capRefused);
    }

    // Roll off ONLY within underlyings that resolved this pass.
    for (Map.Entry<String, Set<InstrumentKey>> entry : resolved.entrySet()) {
      for (InstrumentKey stale : new HashSet<>(currentPins)) {
        if (entry.getValue().contains(stale)) {
          continue;
        }
        if (!belongsTo(stale, entry.getKey())) {
          continue;
        }
        registry.unsubscribe(SUBSCRIBER, stale);
        currentPins.remove(stale);
        log.info("option ATM pin rolled off: {}", stale.canonical());
      }
    }
    // Cap refusals get ONE retry AFTER the stale sweep. On an expiry rollover against a full
    // registry the new window is refused first and the OLD window is only released afterwards, so
    // without this the pass would free the slots and then leave them unused for a whole cycle.
    // (Cross-vendor review Major.)
    if (!capRefused.isEmpty()) {
      log.info("retrying {} cap-refused option pins after the stale sweep", capRefused.size());
      for (InstrumentKey key : capRefused) {
        subscribeOne(key, null);
      }
    }
    log.info(
        "option ATM pin pass: underlyings resolved={}/{}, desired={}, pinned={}",
        resolved.size(), underlyings.size(), desiredAll.size(), currentPins.size());
  }

  /** Whether a pinned key belongs to this underlying's option root (registry grammar). */
  private static boolean belongsTo(InstrumentKey key, String underlying) {
    return key.tradingsymbol().startsWith(registryRoot(underlying));
  }

  /** "NIFTY 50" -> "NIFTY"; the option tradingsymbol root strips at the first space. */
  private static String registryRoot(String underlying) {
    int space = underlying.indexOf(' ');
    return space < 0 ? underlying : underlying.substring(0, space);
  }

  /** Subscribes one leg; collects cap refusals when {@code capRefused} is non-null. */
  private void subscribeOne(InstrumentKey key, Set<InstrumentKey> capRefused) {
    try {
      registry.subscribe(SUBSCRIBER, key);
      currentPins.add(key);
    } catch (Exception failure) {
      String message = failure.getMessage();
      if (message != null && message.contains("subscription cap")) {
        if (capRefused != null) {
          capRefused.add(key);
          return; // retried after the stale sweep frees slots
        }
        log.error("option ATM pin {} refused at the subscription cap: {}", key.canonical(), message);
      } else {
        log.warn("option ATM pin {} failed: {}", key.canonical(), message);
      }
    }
  }

  /** The current option pin set. */
  public Set<InstrumentKey> pinnedContracts() {
    return Set.copyOf(currentPins);
  }

  private Map<String, Set<InstrumentKey>> desiredPins() {
    Map<String, Set<InstrumentKey>> desired = new LinkedHashMap<>();
    for (String configuredUnderlying : underlyings) {
      String underlying = configuredUnderlying.trim();
      try {
        List<LocalDate> expiries = chains.expiriesWithin(underlying, expiryHorizonDays);
        if (expiries.isEmpty()) {
          // NOT resolved — absent from the map, so this underlying's pins are left untouched.
          log.warn("no option expiry within {} days for {} yet", expiryHorizonDays, underlying);
          continue;
        }
        OptionsChainService.Chain chain = chains.chain(underlying, expiries.get(0));
        List<OptionsChainService.StrikeRow> rows = atmWindow(chain);
        Set<InstrumentKey> forUnderlying = new HashSet<>();
        for (OptionsChainService.StrikeRow row : rows) {
          addLeg(forUnderlying, underlying, row.ce());
          addLeg(forUnderlying, underlying, row.pe());
        }
        desired.put(underlying, forUnderlying);
      } catch (RuntimeException failure) {
        // NOT resolved — absent from the map, so repin() leaves this underlying's live pins alone
        // rather than reading the failure as "nothing wanted" and unsubscribing them.
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
      Set<InstrumentKey> desired, String underlying, OptionsChainService.Leg leg) {
    if (leg == null || leg.tradingsymbol() == null || leg.tradingsymbol().isBlank()) {
      log.warn("option ATM pin has no resolvable leg for {}", underlying);
      return;
    }
    // The exchange comes from the instrument master via the chain leg, never from the underlying's
    // NAME (task_767294d5). The old helper mapped SENSEX->BFO, *NIFTY*->NFO and THREW on anything
    // else — so it could not express BANKEX or FOCIT at all, and adding either to the pin list would
    // have thrown once per cycle forever. Deriving it removes both the guess and that ceiling.
    if (leg.exchange() == null || leg.exchange().isBlank()) {
      // Skip rather than guess: an unknown exchange would pin a key the registry cannot resolve, and
      // the contract would silently never be captured.
      log.warn(
          "option ATM pin: leg {} for {} carries no exchange from the instrument master — skipped",
          leg.tradingsymbol(), underlying);
      return;
    }
    desired.add(new InstrumentKey(leg.exchange(), leg.tradingsymbol()));
  }

}
