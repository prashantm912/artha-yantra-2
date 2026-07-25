package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.registry.StrategyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class StrategyCoverageWatchdogTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final LocalDate TRADING_DAY = LocalDate.of(2026, 7, 24);

  @Test
  void partialReloadPagesExactlyOnceWithTheSlugAndClassification() {
    List<StrategyCoverageAlert> alerts = new ArrayList<>();
    MutableClock clock = clockAt(LocalTime.of(10, 0));
    SignalEngine engine = mock(SignalEngine.class);
    StrategyRepository registry = registryWithEnabledPublished();
    ApplicationEventPublisher events = publisherCapturing(alerts);
    when(engine.strategyCoverageSnapshot())
        .thenReturn(
            completed(
                1,
                Map.of("dark-partial", StrategyCoverageSnapshot.Classification.UNRESOLVED),
                StrategyCoverageSnapshot.TerminalState.DEGRADED_TERMINAL));

    StrategyCoverageWatchdog watchdog = watchdog(engine, registry, events, clock, "ARMED", 1);
    watchdog.sweep();
    watchdog.sweep();

    assertThat(alerts).singleElement().satisfies(alert -> {
      assertThat(alert.kind()).isEqualTo(StrategyCoverageAlert.Kind.STRATEGY_DARK);
      assertThat(alert.slug()).isEqualTo("dark-partial");
      assertThat(alert.classification()).isEqualTo(StrategyCoverageSnapshot.Classification.UNRESOLVED);
      assertThat(alert.message()).contains("dark-partial", "UNRESOLVED");
    });
  }

  @Test
  void notLiveResolvablePagesDirectlyEvenWhenNoRetryChainCouldExhaust() {
    List<StrategyCoverageAlert> alerts = new ArrayList<>();
    MutableClock clock = clockAt(LocalTime.of(10, 1));
    SignalEngine engine = mock(SignalEngine.class);
    StrategyRepository registry = registryWithEnabledPublished();
    ApplicationEventPublisher events = publisherCapturing(alerts);
    when(engine.strategyCoverageSnapshot())
        .thenReturn(
            completed(
                2,
                Map.of(
                    "permanent-config-error",
                    StrategyCoverageSnapshot.Classification.NOT_LIVE_RESOLVABLE),
                StrategyCoverageSnapshot.TerminalState.DEGRADED_TERMINAL));

    watchdog(engine, registry, events, clock, "ARMED", 1).sweep();

    assertThat(alerts).singleElement().satisfies(alert -> {
      assertThat(alert.slug()).isEqualTo("permanent-config-error");
      assertThat(alert.classification())
          .isEqualTo(StrategyCoverageSnapshot.Classification.NOT_LIVE_RESOLVABLE);
    });
  }

  @Test
  void failingMorningReloadPagesWithoutAConnectedChain() {
    List<StrategyCoverageAlert> alerts = new ArrayList<>();
    MutableClock clock = clockAt(LocalTime.of(10, 2));
    SignalEngine engine = mock(SignalEngine.class);
    StrategyRepository registry = registryWithEnabledPublished();
    ApplicationEventPublisher events = publisherCapturing(alerts);
    when(engine.strategyCoverageSnapshot())
        .thenReturn(
            completed(
                3,
                Map.of("morning-reload-failure", StrategyCoverageSnapshot.Classification.LOAD_ERROR),
                StrategyCoverageSnapshot.TerminalState.DEGRADED_TERMINAL));

    watchdog(engine, registry, events, clock, "ARMED", 1).sweep();

    assertThat(alerts).singleElement().extracting(StrategyCoverageAlert::classification)
        .isEqualTo(StrategyCoverageSnapshot.Classification.LOAD_ERROR);
  }

  @Test
  void failingHotSwapPagesWithoutAConnectedChain() {
    List<StrategyCoverageAlert> alerts = new ArrayList<>();
    MutableClock clock = clockAt(LocalTime.of(10, 3));
    SignalEngine engine = mock(SignalEngine.class);
    StrategyRepository registry = registryWithEnabledPublished();
    ApplicationEventPublisher events = publisherCapturing(alerts);
    when(engine.strategyCoverageSnapshot())
        .thenReturn(
            completed(
                4,
                Map.of("hot-swap-failure", StrategyCoverageSnapshot.Classification.NO_BOUNDING_EXIT),
                StrategyCoverageSnapshot.TerminalState.DEGRADED_TERMINAL));

    watchdog(engine, registry, events, clock, "ARMED", 1).sweep();

    assertThat(alerts).singleElement().extracting(StrategyCoverageAlert::classification)
        .isEqualTo(StrategyCoverageSnapshot.Classification.NO_BOUNDING_EXIT);
  }

  @Test
  void resolvedEmptyIsNotLoadedAndDoesNotAlarm() {
    List<StrategyCoverageAlert> alerts = new ArrayList<>();
    MutableClock clock = clockAt(LocalTime.of(10, 4));
    SignalEngine engine = mock(SignalEngine.class);
    when(engine.strategyCoverageSnapshot())
        .thenReturn(
            completed(
                5,
                Map.of("stand-aside", StrategyCoverageSnapshot.Classification.RESOLVED_EMPTY),
                StrategyCoverageSnapshot.TerminalState.HEALTHY));

    watchdog(engine, registryWithEnabledPublished(), publisherCapturing(alerts), clock, "ARMED", 1)
        .sweep();

    verify(engine).strategyCoverageSnapshot();
    assertThat(alerts).isEmpty();
  }

  @Test
  void resolvedLoadedHeroZeroPairIsVisitedButSilentOnANonExpiryFriday() {
    List<StrategyCoverageAlert> alerts = new ArrayList<>();
    MutableClock clock = clockAt(LocalTime.of(14, 45));
    SignalEngine engine = mock(SignalEngine.class);
    when(engine.strategyCoverageSnapshot())
        .thenReturn(
            completed(
                6,
                Map.of(
                    "hero-zero-ce", StrategyCoverageSnapshot.Classification.RESOLVED,
                    "hero-zero-pe", StrategyCoverageSnapshot.Classification.RESOLVED),
                StrategyCoverageSnapshot.TerminalState.HEALTHY));

    watchdog(engine, registryWithEnabledPublished(), publisherCapturing(alerts), clock, "ARMED", 1)
        .sweep();

    assertThat(alerts).isEmpty();
  }

  @Test
  void healthySnapshotCanAgeAcrossTheSessionWithoutBecomingSuspicious() {
    List<StrategyCoverageAlert> alerts = new ArrayList<>();
    MutableClock clock = clockAt(LocalTime.of(9, 16));
    SignalEngine engine = mock(SignalEngine.class);
    when(engine.strategyCoverageSnapshot())
        .thenReturn(
            new StrategyCoverageSnapshot(
                7,
                7,
                7,
                at(LocalTime.of(9, 15)),
                StrategyCoverageSnapshot.TerminalState.HEALTHY,
                Map.of("healthy", StrategyCoverageSnapshot.Classification.RESOLVED)));
    StrategyCoverageWatchdog watchdog =
        watchdog(engine, registryWithEnabledPublished(), publisherCapturing(alerts), clock, "ARMED", 1);

    for (LocalTime time : List.of(LocalTime.of(9, 16), LocalTime.NOON, LocalTime.of(15, 29))) {
      clock.set(time);
      watchdog.sweep();
    }

    assertThat(alerts).isEmpty();
  }

  @Test
  void unchangedNotLiveStatusAcrossANewGenerationDoesNotRepageButStatusChangeAndRecoveryRearm() {
    List<StrategyCoverageAlert> alerts = new ArrayList<>();
    MutableClock clock = clockAt(LocalTime.of(11, 0));
    SignalEngine engine = mock(SignalEngine.class);
    StrategyCoverageWatchdog watchdog =
        watchdog(engine, registryWithEnabledPublished(), publisherCapturing(alerts), clock, "ARMED", 1);
    when(engine.strategyCoverageSnapshot())
        .thenReturn(
            completed(
                8,
                Map.of("same-slug", StrategyCoverageSnapshot.Classification.NOT_LIVE_RESOLVABLE),
                StrategyCoverageSnapshot.TerminalState.DEGRADED_TERMINAL),
            completed(
                9,
                Map.of("same-slug", StrategyCoverageSnapshot.Classification.NOT_LIVE_RESOLVABLE),
                StrategyCoverageSnapshot.TerminalState.DEGRADED_TERMINAL),
            completed(
                10,
                Map.of("same-slug", StrategyCoverageSnapshot.Classification.UNRESOLVED),
                StrategyCoverageSnapshot.TerminalState.DEGRADED_TERMINAL),
            completed(
                11,
                Map.of("same-slug", StrategyCoverageSnapshot.Classification.RESOLVED),
                StrategyCoverageSnapshot.TerminalState.HEALTHY),
            completed(
                12,
                Map.of("same-slug", StrategyCoverageSnapshot.Classification.UNRESOLVED),
                StrategyCoverageSnapshot.TerminalState.DEGRADED_TERMINAL));

    for (int i = 0; i < 5; i++) {
      watchdog.sweep();
    }

    assertThat(alerts).extracting(StrategyCoverageAlert::classification)
        .containsExactly(
            StrategyCoverageSnapshot.Classification.NOT_LIVE_RESOLVABLE,
            StrategyCoverageSnapshot.Classification.UNRESOLVED,
            StrategyCoverageSnapshot.Classification.UNRESOLVED);
  }

  @Test
  void notLiveStatusRePagesOnlyOnTheNextIstDay() {
    List<StrategyCoverageAlert> alerts = new ArrayList<>();
    MutableClock clock = clockAt(LocalTime.of(11, 1));
    SignalEngine engine = mock(SignalEngine.class);
    StrategyCoverageWatchdog watchdog =
        watchdog(engine, registryWithEnabledPublished(), publisherCapturing(alerts), clock, "ARMED", 1);
    when(engine.strategyCoverageSnapshot())
        .thenReturn(
            completed(
                13,
                Map.of("daily", StrategyCoverageSnapshot.Classification.NOT_LIVE_RESOLVABLE),
                StrategyCoverageSnapshot.TerminalState.DEGRADED_TERMINAL),
            completed(
                14,
                Map.of("daily", StrategyCoverageSnapshot.Classification.NOT_LIVE_RESOLVABLE),
                StrategyCoverageSnapshot.TerminalState.DEGRADED_TERMINAL));
    watchdog.sweep();
    watchdog.sweep();
    assertThat(alerts).hasSize(1);

    clock.set(LocalTime.of(10, 0));
    clock.setDate(LocalDate.of(2026, 7, 27));
    when(engine.strategyCoverageSnapshot())
        .thenReturn(
            completed(
                15,
                Map.of("daily", StrategyCoverageSnapshot.Classification.NOT_LIVE_RESOLVABLE),
                StrategyCoverageSnapshot.TerminalState.DEGRADED_TERMINAL));
    watchdog.sweep();

    assertThat(alerts).hasSize(2);
  }

  @Test
  void firstReloadMissingPagesOnceAndNeverInventsAStrategyDarkAlert() {
    List<StrategyCoverageAlert> alerts = new ArrayList<>();
    MutableClock clock = clockAt(LocalTime.of(10, 5));
    SignalEngine engine = mock(SignalEngine.class);
    when(engine.strategyCoverageSnapshot())
        .thenReturn(StrategyCoverageSnapshot.inFlight(16, 0, at(LocalTime.of(10, 0))));
    StrategyCoverageWatchdog watchdog =
        watchdog(engine, registryWithEnabledPublished(), publisherCapturing(alerts), clock, "ARMED", 1);

    watchdog.sweep();
    watchdog.sweep();

    assertThat(alerts).singleElement().extracting(StrategyCoverageAlert::kind)
        .isEqualTo(StrategyCoverageAlert.Kind.SNAPSHOT_MISSING);
    assertThat(alerts).noneMatch(alert -> alert.kind() == StrategyCoverageAlert.Kind.STRATEGY_DARK);
  }

  @Test
  void newerAbortedGenerationIsMissingEvenWithAHealthyOlderSnapshot() {
    List<StrategyCoverageAlert> alerts = new ArrayList<>();
    MutableClock clock = clockAt(LocalTime.of(10, 6));
    SignalEngine engine = mock(SignalEngine.class);
    when(engine.strategyCoverageSnapshot())
        .thenReturn(StrategyCoverageSnapshot.inFlight(17, 16, at(LocalTime.of(10, 0))));

    watchdog(engine, registryWithEnabledPublished(), publisherCapturing(alerts), clock, "ARMED", 1)
        .sweep();

    assertThat(alerts).singleElement().satisfies(alert -> {
      assertThat(alert.kind()).isEqualTo(StrategyCoverageAlert.Kind.SNAPSHOT_MISSING);
      assertThat(alert.requestedGeneration()).isEqualTo(17);
      assertThat(alert.completedGeneration()).isEqualTo(16);
    });
  }

  @Test
  void normalCompletionAndInFlightWithinGraceAreSilent() {
    List<StrategyCoverageAlert> alerts = new ArrayList<>();
    MutableClock clock = clockAt(LocalTime.of(10, 7));
    SignalEngine engine = mock(SignalEngine.class);
    when(engine.strategyCoverageSnapshot())
        .thenReturn(StrategyCoverageSnapshot.inFlight(18, 17, at(LocalTime.of(10, 6, 59))),
            completed(
                18,
                Map.of("healthy", StrategyCoverageSnapshot.Classification.RESOLVED),
                StrategyCoverageSnapshot.TerminalState.HEALTHY));
    StrategyCoverageWatchdog watchdog =
        watchdog(engine, registryWithEnabledPublished(), publisherCapturing(alerts), clock, "ARMED", 180_000);

    watchdog.sweep();
    watchdog.sweep();

    assertThat(alerts).isEmpty();
  }

  @Test
  void bearishAndClosedStrategyWindowsDoNotCreateCoverageAlarms() {
    List<StrategyCoverageAlert> alerts = new ArrayList<>();
    MutableClock clock = clockAt(LocalTime.of(15, 15));
    SignalEngine engine = mock(SignalEngine.class);
    when(engine.strategyCoverageSnapshot())
        .thenReturn(
            completed(
                19,
                Map.of(
                    "bearish-leg", StrategyCoverageSnapshot.Classification.RESOLVED,
                    "closed-window", StrategyCoverageSnapshot.Classification.RESOLVED),
                StrategyCoverageSnapshot.TerminalState.HEALTHY));
    StrategyCoverageWatchdog watchdog =
        watchdog(engine, registryWithEnabledPublished(), publisherCapturing(alerts), clock, "ARMED", 1);

    watchdog.sweep();
    clock.set(LocalTime.of(15, 31));
    watchdog.sweep();

    assertThat(alerts).isEmpty();
  }

  @Test
  void observeOnlyLogsThroughTheSweepButNeverPublishes() {
    List<StrategyCoverageAlert> alerts = new ArrayList<>();
    MutableClock clock = clockAt(LocalTime.of(10, 8));
    SignalEngine engine = mock(SignalEngine.class);
    when(engine.strategyCoverageSnapshot())
        .thenReturn(
            completed(
                20,
                Map.of("observed", StrategyCoverageSnapshot.Classification.UNRESOLVED),
                StrategyCoverageSnapshot.TerminalState.DEGRADED_TERMINAL));

    ApplicationEventPublisher events = publisherCapturing(alerts);
    watchdog(engine, registryWithEnabledPublished(), events, clock, "OBSERVE_ONLY", 1).sweep();

    verify(events, times(0)).publishEvent(any(Object.class));
    assertThat(alerts).isEmpty();
  }

  @Test
  void disabledModeDoesNotReadTheEngine() {
    SignalEngine engine = mock(SignalEngine.class);
    StrategyCoverageWatchdog watchdog =
        watchdog(
            engine,
            registryWithEnabledPublished(),
            mock(ApplicationEventPublisher.class),
            clockAt(LocalTime.of(10, 9)),
            "DISABLED",
            1);

    watchdog.sweep();

    verify(engine, times(0)).strategyCoverageSnapshot();
  }

  private static StrategyCoverageWatchdog watchdog(
      SignalEngine engine,
      StrategyRepository registry,
      ApplicationEventPublisher events,
      Clock clock,
      String mode,
      long graceMs) {
    return new StrategyCoverageWatchdog(engine, registry, events, clock, mode, graceMs);
  }

  private static StrategyRepository registryWithEnabledPublished() {
    StrategyRepository registry = mock(StrategyRepository.class);
    when(registry.countEnabledWithPublishedPointer()).thenReturn(1L);
    return registry;
  }

  private static ApplicationEventPublisher publisherCapturing(List<StrategyCoverageAlert> alerts) {
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    doAnswer(invocation -> {
      alerts.add((StrategyCoverageAlert) invocation.getArgument(0));
      return null;
    }).when(events).publishEvent(any(Object.class));
    return events;
  }

  private static StrategyCoverageSnapshot completed(
      long generation,
      Map<String, StrategyCoverageSnapshot.Classification> classifications,
      StrategyCoverageSnapshot.TerminalState terminalState) {
    return new StrategyCoverageSnapshot(
        generation,
        generation,
        generation,
        at(LocalTime.of(9, 15)),
        terminalState,
        classifications);
  }

  private static OffsetDateTime at(LocalTime time) {
    return OffsetDateTime.of(TRADING_DAY, time, IST);
  }

  private static MutableClock clockAt(LocalTime time) {
    return new MutableClock(at(time).toInstant());
  }

  private static final class MutableClock extends Clock {
    private LocalDate date = TRADING_DAY;
    private LocalTime time;

    private MutableClock(Instant instant) {
      setInstant(instant);
    }

    void set(LocalTime time) {
      this.time = time;
    }

    void setDate(LocalDate date) {
      this.date = date;
    }

    private void setInstant(Instant instant) {
      OffsetDateTime local = instant.atOffset(IST);
      this.date = local.toLocalDate();
      this.time = local.toLocalTime();
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return OffsetDateTime.of(date, time, IST).toInstant();
    }
  }
}
