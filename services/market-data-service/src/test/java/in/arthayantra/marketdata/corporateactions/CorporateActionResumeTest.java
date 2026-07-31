package in.arthayantra.marketdata.corporateactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.alerts.NtfyClient;
import in.arthayantra.marketdata.candles.Candle;
import in.arthayantra.marketdata.candles.CandleQueryService;
import in.arthayantra.marketdata.candles.CandleRepository;
import in.arthayantra.marketdata.instruments.Instrument;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import in.arthayantra.marketdata.kite.HistoricalCandleGateway;
import in.arthayantra.marketdata.kite.InstrumentKey;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * The checkpoint's failure semantics (task_6903cd5e). A remediation that ERRORED was unrecoverable
 * while one that CRASHED was resumable, even though both leave the symbol with a rebuilt base and
 * destroyed aggregates and neither can ever re-detect (post-rebuild the cache == Kite). These pin
 * the four properties the fix has to hold: the refresh phase is recorded, not inferred; only the
 * base-committed class resumes; the retry is bounded and lands in a visibly terminal state; and the
 * alert follows state CHANGES, so an armed retry never becomes a nightly page for one symbol.
 */
class CorporateActionResumeTest {

  /** Monday 2026-06-15 17:00 IST — inside the bundled 2024–2026 calendar, post-close. */
  private static final Instant NOW = Instant.parse("2026-06-15T11:30:00Z");

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final Instrument TCS =
      new Instrument(
          "NSE", "TCS", 1L, "TCS", "NSE", "EQ", null, null, null, null, null, null, true);
  private static final int MAX_ATTEMPTS = 3;

  private InstrumentRepository instruments;
  private CandleRepository candles;
  private CandleQueryService queryService;
  private HistoricalCandleGateway gateway;
  private CorporateActionRepository events;
  private NtfyClient ntfy;
  private CorporateActionJob job;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    instruments = mock(InstrumentRepository.class);
    candles = mock(CandleRepository.class);
    queryService = mock(CandleQueryService.class);
    gateway = mock(HistoricalCandleGateway.class);
    events = mock(CorporateActionRepository.class);
    ntfy = mock(NtfyClient.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));

    when(instruments.activeEquities()).thenReturn(List.of(TCS));
    when(events.latestEvent("NSE", "TCS")).thenReturn(Optional.empty());
    // the CAS lands unless a test deliberately arranges a losing race
    when(events.updateStatusIf(any(UUID.class), anyString(), anyString())).thenReturn(true);

    job =
        new CorporateActionJob(
            instruments,
            candles,
            queryService,
            gateway,
            events,
            MarketCalendar.nse(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            ntfy,
            redis,
            // the detection row's details JSONB carries LocalDate anchors — a bare mapper throws
            // REQUIRE_HANDLERS_FOR_JAVA8_TIMES, which the sweep's per-symbol catch would swallow
            new ObjectMapper().registerModule(new JavaTimeModule()),
            0.005,
            0.01,
            5,
            400,
            List.of(),
            MAX_ATTEMPTS,
            new SimpleMeterRegistry());
  }

  private static CorporateActionRepository.EventRow row(UUID id, String status, int attempts) {
    return new CorporateActionRepository.EventRow(
        id,
        "NSE",
        "TCS",
        OffsetDateTime.ofInstant(NOW, IST),
        LocalDate.parse("2026-06-01"),
        new BigDecimal("2.0"),
        8,
        8,
        status,
        attempts,
        null);
  }

  /** Kite re-serving TCS back-adjusted ×0.5 against a uniformly-halved cache ⇒ one detection. */
  private UUID armDetection() {
    when(candles.hasNonBhavcopyDaily("NSE", "TCS")).thenReturn(true);
    when(candles.closeAt(eq("NSE"), eq("TCS"), eq("1d"), any())).thenReturn(new BigDecimal("100"));
    when(candles.range(eq("NSE"), eq("TCS"), eq("1d"), any(), any()))
        .thenAnswer(
            invocation ->
                List.of(
                    new Candle(
                        "NSE",
                        "TCS",
                        "1d",
                        invocation.getArgument(3),
                        new BigDecimal("100"),
                        new BigDecimal("100"),
                        new BigDecimal("100"),
                        new BigDecimal("100"),
                        1,
                        null,
                        "KITE")));
    // one halved bar per calendar day back past the deepest (5-year) anchor, so every anchor the
    // sweep snaps to finds a Kite counterpart
    List<HistoricalCandleGateway.Candle> kiteBars = new ArrayList<>();
    LocalDate today = LocalDate.ofInstant(NOW, IST);
    for (LocalDate d = today.minusYears(6); !d.isAfter(today); d = d.plusDays(1)) {
      kiteBars.add(
          new HistoricalCandleGateway.Candle(
              new InstrumentKey("NSE", "TCS"),
              "1d",
              d.atStartOfDay().atOffset(IST),
              new BigDecimal("50"),
              new BigDecimal("50"),
              new BigDecimal("50"),
              new BigDecimal("50"),
              1,
              null));
    }
    when(gateway.fetch(any(), eq("1d"), any(), any())).thenReturn(kiteBars);
    UUID id = UUID.randomUUID();
    when(events.insertDetected(
            anyString(), anyString(), any(), any(), anyInt(), anyInt(), anyString()))
        .thenReturn(id);
    return id;
  }

  @Test
  void refreshPhaseFailureIsRecordedAsResumableNotPlainFailed() {
    UUID id = UUID.randomUUID();
    when(events.latestEvent("NSE", "TCS")).thenReturn(Optional.of(row(id, "BASE_REBUILT", 0)));
    when(events.statusOf(id)).thenReturn(Optional.of("BASE_REBUILT"));
    doThrow(new RuntimeException("max_tuples_decompressed_per_dml_transaction"))
        .when(candles)
        .refreshDerivedAggregatesForRebuild(any(), any());

    job.sweepNow();

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> verify(events).updateStatusIf(id, "BASE_REBUILT", "REFRESH_FAILED"));
    verify(events, never()).updateStatusIf(eq(id), anyString(), eq("FAILED"));
    verify(events).incrementRefreshAttempts(id); // the attempt is counted even though it threw
  }

  @Test
  void recordedResumableFailureIsRetriedRefreshOnlyOnTheNextSweep() {
    UUID id = UUID.randomUUID();
    when(events.latestEvent("NSE", "TCS")).thenReturn(Optional.of(row(id, "REFRESH_FAILED", 1)));

    job.sweepNow();

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> verify(events).updateStatusIf(id, "REFRESH_FAILED", "RESOLVED"));
    verify(candles).refreshDerivedAggregatesForRebuild(any(), any());
    // refresh-ONLY: the ~12-year purge + re-backfill are exactly what a resume must not redo
    verify(candles, never()).purgeSymbol(anyString(), anyString());
    verify(queryService, never()).prefetch(anyString(), anyString(), anyString(), any(), any());
  }

  @Test
  void anInFlightAttemptIsNeitherAbandonedNorRetriedByTheCrossingSweep() throws Exception {
    UUID id = UUID.randomUUID();
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    // sweep 1 sees one attempt left; sweep 2 crosses it while the attempt (which already recorded
    // itself, so the counter now reads as SPENT) is still running
    when(events.latestEvent("NSE", "TCS"))
        .thenReturn(
            Optional.of(row(id, "REFRESH_FAILED", MAX_ATTEMPTS - 1)),
            Optional.of(row(id, "REFRESH_FAILED", MAX_ATTEMPTS)));
    doAnswer(
            invocation -> {
              running.countDown();
              release.await(20, TimeUnit.SECONDS);
              return null;
            })
        .when(candles)
        .refreshDerivedAggregatesForRebuild(any(), any());

    job.sweepNow();
    assertThat(running.await(20, TimeUnit.SECONDS)).as("attempt started").isTrue();

    job.sweepNow(); // the crossing sweep — must do nothing at all

    verify(events, never()).updateStatusIf(eq(id), anyString(), eq("REFRESH_ABANDONED"));
    verify(ntfy, never()).send(anyString(), anyString(), anyString());

    release.countDown();
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> verify(events).updateStatusIf(id, "REFRESH_FAILED", "RESOLVED"));
    // exactly one attempt ran and one was counted — the crossing sweep queued nothing
    verify(candles, times(1)).refreshDerivedAggregatesForRebuild(any(), any());
    verify(events, times(1)).incrementRefreshAttempts(id);
  }

  @Test
  void workerThatLosesTheRaceNeverOverwritesTheTerminalVerdict() {
    UUID id = UUID.randomUUID();
    when(events.latestEvent("NSE", "TCS")).thenReturn(Optional.of(row(id, "REFRESH_FAILED", 1)));
    // a concurrent sweep judged the event while this attempt ran — the success CAS must LOSE
    when(events.updateStatusIf(id, "REFRESH_FAILED", "RESOLVED")).thenReturn(false);

    job.sweepNow();

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> verify(events).updateStatusIf(id, "REFRESH_FAILED", "RESOLVED"));
    verify(events, never()).updateStatus(eq(id), anyString()); // no unconditional write anywhere
    verify(ntfy, never()).send(anyString(), anyString(), anyString()); // no false "rebuilt" alert
  }

  @Test
  void failingAfterConcurrentAbandonNeverDowngradesTheTerminalState() {
    UUID id = UUID.randomUUID();
    when(events.latestEvent("NSE", "TCS")).thenReturn(Optional.of(row(id, "REFRESH_FAILED", 1)));
    // the sweep abandoned this event while the attempt was running; the attempt then fails
    when(events.statusOf(id)).thenReturn(Optional.of("REFRESH_ABANDONED"));
    doThrow(new RuntimeException("max_tuples_decompressed_per_dml_transaction"))
        .when(candles)
        .refreshDerivedAggregatesForRebuild(any(), any());

    job.sweepNow();

    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> verify(events).statusOf(id));
    // REFRESH_ABANDONED must not be re-classified into the wrong failure bucket on the way out
    verify(events, never()).updateStatusIf(eq(id), anyString(), eq("FAILED"));
    verify(events, never()).updateStatus(eq(id), anyString());
    verify(ntfy, never()).send(anyString(), anyString(), anyString());
  }

  @Test
  void failureBeforeTheBaseWasRebuiltIsNeverResumedRefreshOnly() {
    UUID id = armDetection();
    when(events.statusOf(id)).thenReturn(Optional.of("REBACKFILL_RUNNING"));
    doThrow(new RuntimeException("kite 1m re-backfill died"))
        .when(queryService)
        .prefetch(anyString(), anyString(), eq("1m"), any(), any(), anyBoolean());

    // detect → REBACKFILL_RUNNING → purge → 1d prefetch → 1m prefetch THROWS: base is half-rebuilt
    assertThat(job.sweepNow()).containsExactly(id);

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> verify(events).updateStatusIf(id, "REBACKFILL_RUNNING", "FAILED"));
    verify(events, never()).updateStatus(id, "BASE_REBUILT");
    verify(events, never()).updateStatusIf(eq(id), anyString(), eq("REFRESH_FAILED"));

    // the next sweep sees that FAILED row and must leave it alone: refreshing the caggs now would
    // materialise aggregates over a purged-but-not-refilled base
    when(events.latestEvent("NSE", "TCS")).thenReturn(Optional.of(row(id, "FAILED", 0)));
    job.sweepNow();
    verify(candles, never()).refreshDerivedAggregatesForRebuild(any(), any());
    verify(events, never()).updateStatusIf(eq(id), anyString(), eq("RESOLVED"));
  }

  @Test
  void retriesStopAtTheBoundAndLandInTheTerminalState() {
    UUID id = UUID.randomUUID();
    when(events.latestEvent("NSE", "TCS"))
        .thenReturn(Optional.of(row(id, "REFRESH_FAILED", MAX_ATTEMPTS)));

    job.sweepNow();

    verify(events).updateStatusIf(id, "REFRESH_FAILED", "REFRESH_ABANDONED");
    verify(candles, never()).refreshDerivedAggregatesForRebuild(any(), any());
    // the terminal state is the one an operator must act on, so it DOES page
    verify(ntfy).send(contains("ABANDONED"), eq("urgent"), anyString());

    // …and abandoning is a one-shot: the abandoned row is outside the resumable set, so the next
    // sweep neither retries nor re-pages
    when(events.latestEvent("NSE", "TCS"))
        .thenReturn(Optional.of(row(id, "REFRESH_ABANDONED", MAX_ATTEMPTS)));
    job.sweepNow();
    verify(events, times(1)).updateStatusIf(eq(id), anyString(), eq("REFRESH_ABANDONED"));
    verify(ntfy, times(1)).send(anyString(), anyString(), anyString());
  }

  @Test
  void repeatedRefreshFailuresAlertOnceNotOncePerAttempt() {
    UUID id = UUID.randomUUID();
    when(events.latestEvent("NSE", "TCS"))
        .thenReturn(
            Optional.of(row(id, "BASE_REBUILT", 0)), Optional.of(row(id, "REFRESH_FAILED", 1)));
    when(events.statusOf(id))
        .thenReturn(Optional.of("BASE_REBUILT"), Optional.of("REFRESH_FAILED"));
    doThrow(new RuntimeException("max_tuples_decompressed_per_dml_transaction"))
        .when(candles)
        .refreshDerivedAggregatesForRebuild(any(), any());

    // the in-flight fence makes a sweep a no-op while the prior attempt is still unwinding, so poll
    // the sweep itself rather than racing the claim release; extra no-op sweeps cost nothing and
    // extra FAILING sweeps would only strengthen the one-alert assertion below
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              job.sweepNow();
              verify(events, atLeast(2)).updateStatusIf(eq(id), anyString(), eq("REFRESH_FAILED"));
            });

    // every attempt after the first re-records the SAME state, so only the first one reports
    verify(ntfy, times(1)).send(anyString(), anyString(), anyString());
  }
}
