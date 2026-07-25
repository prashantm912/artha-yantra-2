package in.arthayantra.strategysignal.signals;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * T9 load-coverage watchdog. It observes reload classifications, never runtime signal silence.
 *
 * <p>The bean shares the live engine's lifecycle because it reads {@link SignalEngine}; the
 * explicit property guard is therefore required even though the engine itself is conditional.
 */
@Component
@ConditionalOnProperty(
    name = "artha.signals.engine-enabled", havingValue = "true", matchIfMissing = true)
public class StrategyCoverageWatchdog {

  private static final Logger log = LoggerFactory.getLogger(StrategyCoverageWatchdog.class);
  private static final Duration DEFAULT_GRACE = Duration.ofMinutes(3);

  enum Mode {
    DISABLED,
    OBSERVE_ONLY,
    ARMED
  }

  private record EpisodeKey(String slug, StrategyCoverageSnapshot.Classification classification) {}

  private final SignalEngine engine;
  private final StrategyRepository registry;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final Mode mode;
  private final Duration grace;
  private final MarketCalendar calendar = MarketCalendar.nse();
  private final Set<EpisodeKey> emittedEpisodes = new HashSet<>();
  private final Set<EpisodeKey> observedEpisodes = new HashSet<>();
  private final Map<String, LocalDate> lastNotLivePage = new HashMap<>();
  private boolean missingEmitted;
  private boolean missingObserved;

  public StrategyCoverageWatchdog(
      SignalEngine engine,
      StrategyRepository registry,
      ApplicationEventPublisher events,
      Clock clock,
      @Value("${artha.signals.strategy-coverage-watchdog.mode:DISABLED}") String configuredMode,
      @Value("${artha.signals.strategy-coverage-watchdog.grace-ms:180000}") long graceMs) {
    this.engine = engine;
    this.registry = registry;
    this.events = events;
    this.clock = clock;
    this.mode = parseMode(configuredMode);
    this.grace = graceMs > 0 ? Duration.ofMillis(graceMs) : DEFAULT_GRACE;
    log.info("strategy coverage watchdog mode={} graceMs={}", mode, grace.toMillis());
  }

  /** One minute detector sweep on the isolated monitor scheduler. */
  @Scheduled(fixedDelay = 60_000L, initialDelay = 120_000L, scheduler = "monitorTaskScheduler")
  public void sweep() {
    if (mode == Mode.DISABLED) {
      return;
    }
    ZonedDateTime now = clock.instant().atZone(Ist.ZONE);
    if (!inSession(now)) {
      return;
    }
    StrategyCoverageSnapshot snapshot = engine.strategyCoverageSnapshot();
    if (snapshot == null) {
      return;
    }
    if (!snapshot.completedCurrentGeneration()) {
      sweepMissing(snapshot, now);
      return;
    }

    missingEmitted = false;
    missingObserved = false;
    Set<EpisodeKey> activeEpisodes = activeEpisodes(snapshot);
    emittedEpisodes.retainAll(activeEpisodes);
    observedEpisodes.retainAll(activeEpisodes);

    if (snapshot.terminalState() != StrategyCoverageSnapshot.TerminalState.DEGRADED_TERMINAL
        || !hasEnabledPublished()) {
      return;
    }
    for (Map.Entry<String, StrategyCoverageSnapshot.Classification> entry :
        snapshot.classifications().entrySet()) {
      if (!entry.getValue().abnormal()) {
        continue;
      }
      considerDark(snapshot, entry.getKey(), entry.getValue(), now.toLocalDate());
    }
  }

  private void sweepMissing(StrategyCoverageSnapshot snapshot, ZonedDateTime now) {
    if (snapshot.requestedGeneration() <= snapshot.completedGeneration()
        || !graceExpired(snapshot.reloadTimestamp(), now)) {
      return;
    }
    if (!hasEnabledPublished()) {
      return;
    }
    StrategyCoverageAlert alert =
        new StrategyCoverageAlert(
            StrategyCoverageAlert.Kind.SNAPSHOT_MISSING,
            null,
            null,
            snapshot.generation(),
            snapshot.requestedGeneration(),
            snapshot.completedGeneration(),
            snapshot.reloadTimestamp(),
            snapshot.terminalState());
    if (mode == Mode.OBSERVE_ONLY) {
      if (!missingObserved) {
        missingObserved = true;
        log.info(
            "strategy coverage would-page: SNAPSHOT_MISSING requestedGeneration={} completedGeneration={}",
            snapshot.requestedGeneration(), snapshot.completedGeneration());
      }
      return;
    }
    if (!missingEmitted) {
      missingEmitted = publish(alert);
    }
  }

  private void considerDark(
      StrategyCoverageSnapshot snapshot,
      String slug,
      StrategyCoverageSnapshot.Classification classification,
      LocalDate today) {
    EpisodeKey key = new EpisodeKey(slug, classification);
    StrategyCoverageAlert alert =
        new StrategyCoverageAlert(
            StrategyCoverageAlert.Kind.STRATEGY_DARK,
            slug,
            classification,
            snapshot.generation(),
            snapshot.requestedGeneration(),
            snapshot.completedGeneration(),
            snapshot.reloadTimestamp(),
            snapshot.terminalState());
    if (mode == Mode.OBSERVE_ONLY) {
      if (observedEpisodes.add(key)) {
        log.info("strategy coverage would-page: {} {}", slug, classification);
      }
      return;
    }
    boolean dailyNotLive =
        classification == StrategyCoverageSnapshot.Classification.NOT_LIVE_RESOLVABLE;
    if ((!dailyNotLive && emittedEpisodes.contains(key))
        || (dailyNotLive && today.equals(lastNotLivePage.get(slug)))) {
      return;
    }
    if (!publish(alert)) {
      return;
    }
    emittedEpisodes.add(key);
    if (dailyNotLive) {
      lastNotLivePage.put(slug, today);
    }
  }

  private Set<EpisodeKey> activeEpisodes(StrategyCoverageSnapshot snapshot) {
    if (snapshot.terminalState() != StrategyCoverageSnapshot.TerminalState.DEGRADED_TERMINAL) {
      return Set.of();
    }
    Set<EpisodeKey> active = new HashSet<>();
    snapshot.classifications().forEach((slug, classification) -> {
      if (classification.abnormal()) {
        active.add(new EpisodeKey(slug, classification));
      }
    });
    return active;
  }

  private boolean hasEnabledPublished() {
    try {
      return registry.countEnabledPublished() > 0;
    } catch (RuntimeException e) {
      log.warn("strategy coverage registry count failed: {}", e.toString());
      return false;
    }
  }

  private boolean graceExpired(OffsetDateTime requestedAt, ZonedDateTime now) {
    return !now.toInstant().isBefore(requestedAt.toInstant().plus(grace));
  }

  private boolean inSession(ZonedDateTime now) {
    if (now.toLocalTime().isBefore(java.time.LocalTime.of(9, 15))
        || !now.toLocalTime().isBefore(java.time.LocalTime.of(15, 30))) {
      return false;
    }
    try {
      return calendar.isOpen(now.toInstant());
    } catch (IllegalArgumentException uncoveredYear) {
      return false;
    }
  }

  private boolean publish(StrategyCoverageAlert alert) {
    try {
      events.publishEvent(alert);
      return true;
    } catch (RuntimeException e) {
      log.warn("strategy coverage alert publish failed: {}", e.toString());
      return false;
    }
  }

  private static Mode parseMode(String configuredMode) {
    try {
      return Mode.valueOf(configuredMode.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      log.warn("unknown strategy coverage watchdog mode '{}'; forcing DISABLED", configuredMode);
      return Mode.DISABLED;
    }
  }
}
