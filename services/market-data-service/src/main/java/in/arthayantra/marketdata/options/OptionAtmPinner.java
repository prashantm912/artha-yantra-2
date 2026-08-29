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
import org.springframework.scheduling.annotation.Scheduled;
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
      // H44: 10. The LAST of four places this default lives (yml, compose, .env.example,
      // here) -- review caught two of them still at 5, which would have made the change a
      // no-op. See application.yml for the measurement.
      @Value("${artha.options.atm-pinner.strike-width:10}") int strikeWidth,
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

  /**
   * H44: RE-CENTRE THE BAND ON SPOT DURING THE SESSION.
   *
   * <p><b>The band already follows spot; nothing else did.</b> {@link #atmWindow} centres on the
   * chain's CURRENT spot every time it runs, and {@link #repin()} is a full reconcile that
   * subscribes what is newly desired and releases what is stale. The defect was purely its
   * TRIGGERS: {@code ApplicationReadyEvent} and {@code InstrumentMasterUpdated} only, so the band
   * was fixed at whatever spot was around the 08:30 master sync and never moved again all session.
   *
   * <p><b>Why that stranded money.</b> A contract outside the pinned band never ticks, and every
   * AUTOMATIC exit refuses without a real tick (#694, never fabricate a price) — so a leg filled on
   * an out-of-band strike cannot be settled by any automatic path. Measured 2026-08-28: two SENSEX
   * PE legs sat through their TIME_STOPs, their signal-exit and the 15:44 square-off, and were
   * released only by a manual explicit-price close at -Rs 8,892.79.
   *
   * <p><b>Why widening was not enough.</b> Width went 5 → 10 (±1000 pts on BFO), but the CENTRE
   * stayed fixed, and BFO picks reach 829 points from spot — so roughly 171 points of one-way drift
   * exhausts even the wider band. Width buys margin; only re-centring removes the failure mode.
   *
   * <p>⚠️ <b>This does NOT forfeit entries, which is the whole point.</b> The H44 closability gate
   * ({@code artha.paper.refuse-no-tick-entries}) refuses such trades; this stops them arising. The
   * two are complementary and the gate stays disarmed.
   *
   * <p>⚠️ <b>ON THE SHARED DEFAULT POOL, DELIBERATELY — and the census test is what forced the
   * question.</b> {@code ScheduledPoolCensusTest} failed on this change because a new
   * {@code @Scheduled} lands somewhere, and that pool has ONE thread shared by ~30 jobs. Two
   * properties make it safe here rather than another dedicated bean:
   *
   * <ul>
   *   <li><b>It cannot block a neighbour.</b> The method only hands work to {@code repinExecutor}
   *       and returns, so it holds the pool thread for microseconds. All the real work — chain
   *       reads, subscribe/release — runs off-pool, exactly as the boot and master-refresh
   *       triggers already do.
   *   <li><b>Being DELAYED by a neighbour is immaterial at this cadence.</b> The worst observed
   *       hold on that pool is the ~70 s options pass (the S1 shape); a repin arriving a minute
   *       late on a FIVE-MINUTE schedule still re-centres the band long before drift matters.
   * </ul>
   *
   * <p>A dedicated single-thread scheduler for a microsecond dispatch would be cost without a
   * property to show for it. If this ever grows real work, it needs its own bean — that is the
   * moment to revisit, not now.
   *
   * <p>Every 5 minutes across 09:00–15:35 IST — deliberately bounded rather than {@code *}: outside
   * the session spot does not move, so a repin would be pure churn against the subscription cap, and
   * the boot/master-refresh triggers already cover the pre-open pin. The reconcile is idempotent, so
   * a pass with no drift subscribes nothing and releases nothing.
   */
  @Scheduled(cron = "${artha.options.atm-pinner.repin-cron:0 */5 9-15 * * MON-FRI}",
      zone = "Asia/Kolkata")
  public void repinDuringSession() {
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
