package in.arthayantra.marketdata.options;

import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.InstrumentMasterUpdated;
import in.arthayantra.marketdata.kite.PinnedSubscriptionRegistrar;
import in.arthayantra.marketcalendar.MarketCalendar;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.time.Clock;
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
  private final in.arthayantra.marketdata.instruments.InstrumentRepository instruments;
  private final in.arthayantra.marketdata.feed.LastTickStore lastTick;
  private final List<String> underlyings;
  private final int strikeWidth;
  private final int expiryHorizonDays;
  private final Set<InstrumentKey> currentPins = ConcurrentHashMap.newKeySet();

  /** The authority on what a session is — the cron is only a coarse wake-up. */
  private final MarketCalendar calendar = MarketCalendar.nse();

  private final Clock clock;

  /** One-slot coalescing gate — see {@link #repinAsync()}. */
  private final java.util.concurrent.atomic.AtomicBoolean pendingRepin =
      new java.util.concurrent.atomic.AtomicBoolean(false);

  /** Counts fires dropped because a pass was already pending — a rising value means passes are slow. */
  private final io.micrometer.core.instrument.Counter repinsCoalesced;

  /** Passes served from the registry + last tick (free) vs a full chain fetch (REST). */
  private final io.micrometer.core.instrument.Counter repinsFromTick;

  private final io.micrometer.core.instrument.Counter repinsFromChain;

  public OptionAtmPinner(
      PinnedSubscriptionRegistrar registry,
      OptionsChainService chains,
      @Value("${artha.options.atm-pinner.underlyings:NIFTY 50,SENSEX}") List<String> underlyings,
      // H44: 10. The LAST of four places this default lives (yml, compose, .env.example,
      // here) -- review caught two of them still at 5, which would have made the change a
      // no-op. See application.yml for the measurement.
      @Value("${artha.options.atm-pinner.strike-width:10}") int strikeWidth,
      @Value("${artha.options.atm-pinner.expiry-horizon-days:7}") int expiryHorizonDays,
      in.arthayantra.marketdata.instruments.InstrumentRepository instruments,
      in.arthayantra.marketdata.feed.LastTickStore lastTick,
      MeterRegistry meterRegistry,
      Clock clock) {
    this.clock = clock;
    this.registry = registry;
    this.chains = chains;
    this.instruments = instruments;
    this.lastTick = lastTick;
    this.underlyings = underlyings;
    this.strikeWidth = Math.max(0, strikeWidth);
    this.expiryHorizonDays = Math.max(0, expiryHorizonDays);
    meterRegistry.gauge("ay_options_atm_pinned_contracts", currentPins, Set::size);
    this.repinsCoalesced = meterRegistry.counter("ay_options_atm_repin_coalesced_total");
    this.repinsFromTick =
        meterRegistry.counter("ay_options_atm_repin_source_total", "source", "tick");
    this.repinsFromChain =
        meterRegistry.counter("ay_options_atm_repin_source_total", "source", "chain");
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
   * <p>⚠️ <b>THE CRON ALONE IS NOT SESSION-BOUNDED, and I claimed it was.</b> {@code 0 *&#47;5 9-15}
   * fires 84 times, 09:00 through 15:55 — EIGHT of them outside the 09:15–15:30 session (09:00/05/10
   * and 15:35/40/45/50/55) plus every weekday exchange HOLIDAY. My javadoc, commit and PR all said
   * "09:00–15:35, deliberately bounded"; cross-vendor review computed the real range. A cron whose
   * hour field looks bounded is not the same as a schedule that is.
   *
   * <p>So the gate is in CODE, where it can be exact: {@link MarketCalendar#isOpen} refuses any fire
   * outside a real trading session, holidays included. The cron stays coarse on purpose — it is a
   * cheap wake-up, and the calendar is the authority on what a session is.
   *
   * <p>Outside the session spot does not move, so a repin would be pure churn against the
   * subscription cap; the boot and master-refresh triggers already cover the pre-open pin. The
   * reconcile is idempotent, so a pass with no drift subscribes nothing and releases nothing.
   */
  @Scheduled(cron = "${artha.options.atm-pinner.repin-cron:0 */5 9-15 * * MON-FRI}",
      zone = "Asia/Kolkata")
  public void repinDuringSession() {
    if (!calendar.isOpen(clock.instant())) {
      return; // pre-open, post-close, or a holiday — nothing to re-centre on
    }
    repinAsync();
  }

  /** Hands one reconcile to the dedicated thread; never throws into the publishing caller. */
  private void repinAsync() {
    // ⚠️ COALESCED, NOT QUEUED. The executor is a single thread with an UNBOUNDED queue, and the
    // recurring schedule is what made that reachable: repinAsync() returns immediately, so Spring's
    // no-overlap guarantee protects the SCHEDULER, not this executor. If one network-backed pass ever
    // outran the 5-minute cron, every later fire would pile up behind it and then run in sequence --
    // each re-resolving a band that the NEXT queued pass immediately supersedes. Pure churn against
    // the subscription cap, and unbounded growth while the stall lasts.
    //
    // A repin is idempotent and always reconciles to CURRENT state, so a queued pass carries no
    // information a later one lacks: at most ONE pending pass is ever useful. pendingRepin is that
    // one slot -- if a pass is already waiting, this fire is dropped rather than stacked.
    if (!pendingRepin.compareAndSet(false, true)) {
      repinsCoalesced.increment();
      return;
    }
    repinExecutor.execute(
        () -> {
          pendingRepin.set(false); // released BEFORE the work: a fire arriving mid-pass must still
          try {                    // be able to queue, or a drift during a slow pass is lost.
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
        releaseOne(stale);
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

  /**
   * Releases one stale pin, and drops it from {@code currentPins} EVEN IF the wire call throws.
   *
   * <p><b>Architect audit, on top of an APPROVED review.</b> This call used to be bare, while the
   * subscribe directly above it was wrapped — and the asymmetry mattered, because
   * {@code SubscriptionRegistry.unsubscribe} completes the durable AND in-memory release BEFORE
   * its wire call. So a throw there means the hold is genuinely GONE while
   * {@code currentPins.remove} never ran: the pinner then believes it holds a contract the
   * registry has released, that contract is dark, and no later pass retries it because the pinner
   * still counts it as pinned. H44 stranding by a third route, reached from the fix for the first
   * two.
   *
   * <p>It also aborted the whole sweep — every remaining stale pin for that underlying, plus the
   * cap-refused retry below it. At the old two passes a day this healed at the next restart; at 84
   * it accumulates, which is what makes the recurring schedule the thing that promoted it.
   *
   * <p>⚠️ {@code currentPins.remove} is OUTSIDE the try on purpose. The release already happened;
   * continuing to claim the pin would be the actual defect, and a failed WIRE notification is the
   * ticker's to recover, not ours to model.
   */
  private void releaseOne(InstrumentKey stale) {
    try {
      registry.unsubscribe(SUBSCRIBER, stale);
      log.info("option ATM pin rolled off: {}", stale.canonical());
    } catch (RuntimeException failure) {
      log.warn(
          "option ATM pin {} released but the wire notification failed: {}",
          stale.canonical(), failure.toString());
    } finally {
      currentPins.remove(stale);
    }
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
        Set<InstrumentKey> forUnderlying = pinsFromTick(underlying, expiries.get(0));
        if (forUnderlying == null) {
          // FALLBACK: no live tick for the underlying yet (boot, or before the index has
          // ticked). A full chain fetch prices every strike we do not need, but it is the only
          // path that can produce a band with no tick at all, and it is what ran before this
          // fast path existed.
          repinsFromChain.increment();
          OptionsChainService.Chain chain = chains.chain(underlying, expiries.get(0));
          List<OptionsChainService.StrikeRow> rows = atmWindow(chain);
          forUnderlying = new HashSet<>();
          for (OptionsChainService.StrikeRow row : rows) {
            addLeg(forUnderlying, underlying, row.ce());
            addLeg(forUnderlying, underlying, row.pe());
          }
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

  /**
   * The band from data we ALREADY HAVE (the instrument master plus the last tick), or {@code
   * null} when the underlying has never ticked and only a chain fetch can answer.
   *
   * <p><b>Why this exists.</b> Making the repin RECURRING is what created the cost. The old
   * triggers fired twice a day, so a full-chain fetch per underlying per pass was free in
   * practice; at every five minutes it is not. Each default-path {@code chain()} costs a spot
   * quote plus a batched quote over EVERY strike in the expiry, against the same ~60/min Kite
   * budget that futures-OI capture draws on. Starving that limiter would either leave the band
   * where it was (the exact defect being fixed) or cost OI capture, so the fix would have paid
   * for itself in the currency it was trying to save. (Cross-vendor review Major.)
   *
   * <p><b>Why it is not a shortcut.</b> The pinner never reads a price. It needs the strike
   * LADDER with each strike's CE/PE legs, which live in the instrument master and not in any
   * quote, plus the SPOT to centre on. The ladder for an expiry does not change intraday, and
   * the underlying is already subscribed, so its last tick IS the live spot. The full chain was
   * computing several hundred prices to hand back two fields we already had.
   *
   * <p>WARNING: tick AGE is deliberately not checked. This is the #694 EXIT polarity, not the
   * entry one. A stale spot re-centres the band slightly wrong; refusing on staleness leaves it
   * where it was, which is the stranding this whole change exists to stop. A dead feed is the
   * feed watchdog's job to report, and it already does.
   */
  private Set<InstrumentKey> pinsFromTick(String underlying, LocalDate expiry) {
    List<in.arthayantra.marketdata.instruments.Instrument> ladder =
        instruments.optionChain(underlying, expiry);
    if (ladder.isEmpty()) {
      return null;
    }
    // Same key rule as OptionsChainService: the underlying OWN exchange from the master, never
    // a guess from its name, defaulting to NSE only when the master carries none.
    String spotExchange =
        ladder.get(0).underlyingExchange() == null ? "NSE" : ladder.get(0).underlyingExchange();
    BigDecimal spot =
        lastTick
            .latest(new InstrumentKey(spotExchange, underlying))
            .map(in.arthayantra.marketdata.feed.NormalizedTick::lastPrice)
            .orElse(null);
    if (spot == null) {
      return null;
    }

    List<BigDecimal> strikes =
        ladder.stream()
            .map(in.arthayantra.marketdata.instruments.Instrument::strike)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
    if (strikes.isEmpty()) {
      return null;
    }
    int atmIndex = 0;
    BigDecimal best = null;
    for (int i = 0; i < strikes.size(); i++) {
      BigDecimal distance = strikes.get(i).subtract(spot).abs();
      if (best == null || distance.compareTo(best) < 0) {
        best = distance;
        atmIndex = i;
      }
    }
    java.util.Set<BigDecimal> band =
        new HashSet<>(
            strikes.subList(
                Math.max(0, atmIndex - strikeWidth),
                Math.min(strikes.size(), atmIndex + strikeWidth + 1)));

    Set<InstrumentKey> pins = new HashSet<>();
    for (in.arthayantra.marketdata.instruments.Instrument contract : ladder) {
      if (contract.strike() == null || !band.contains(contract.strike())) {
        continue;
      }
      if (contract.exchange() == null || contract.exchange().isBlank()) {
        // Skip rather than guess, identical rule to addLeg: an unknown exchange pins a key the
        // registry cannot resolve and the contract silently never gets captured.
        log.warn(
            "option ATM pin: {} for {} carries no exchange from the instrument master, skipped",
            contract.tradingsymbol(),
            underlying);
        continue;
      }
      pins.add(new InstrumentKey(contract.exchange(), contract.tradingsymbol()));
    }
    repinsFromTick.increment();
    return pins;
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
