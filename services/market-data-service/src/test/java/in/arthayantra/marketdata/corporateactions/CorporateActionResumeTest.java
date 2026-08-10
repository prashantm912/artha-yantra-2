package in.arthayantra.marketdata.corporateactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
import org.mockito.InOrder;
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
  private static final int COOLDOWN_DAYS = 7;

  private InstrumentRepository instruments;
  private CandleRepository candles;
  private CandleQueryService queryService;
  private HistoricalCandleGateway gateway;
  private CorporateActionRepository events;
  private NtfyClient ntfy;
  private CorporateActionJob job;
  private SimpleMeterRegistry meterRegistry;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    instruments = mock(InstrumentRepository.class);
    candles = mock(CandleRepository.class);
    queryService = mock(CandleQueryService.class);
    gateway = mock(HistoricalCandleGateway.class);
    events = mock(CorporateActionRepository.class);
    ntfy = mock(NtfyClient.class);
    meterRegistry = new SimpleMeterRegistry();
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
            COOLDOWN_DAYS,
            meterRegistry);
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
    // a HEALTHY staged coverage by default (V054): non-empty, nothing left unreplaced, and reaching
    // the cached series' end — so a test that wants the rebuild to proceed does not have to say so,
    // and a test about a REFUSAL has to state which of the three properties it breaks
    when(candles.stagedCoverage(eq("NSE"), eq("TCS"), anyString()))
        .thenReturn(
            new CandleRepository.StagedCoverage(
                500,
                OffsetDateTime.ofInstant(NOW, IST).minusYears(5),
                OffsetDateTime.ofInstant(NOW, IST),
                0,
                OffsetDateTime.ofInstant(NOW, IST),
                0));
    UUID id = UUID.randomUUID();
    when(events.insertDetected(
            anyString(), anyString(), any(), any(), anyInt(), anyInt(), anyString()))
        .thenReturn(id);
    return id;
  }

  @Test
  void theReBackfillFetchesAndVerifiesBeforeItDeletesAnything() {
    // The ordering fix itself (V054). The old body ran purgeSymbol FIRST and only then re-fetched,
    // so every failure mode of a multi-hour ~12-year Kite fetch landed after the destruction.
    UUID id = armDetection();
    when(events.statusOf(id)).thenReturn(Optional.of("REBACKFILL_RUNNING"));

    assertThat(job.sweepNow()).containsExactly(id);

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> verify(candles).swapStaged("NSE", "TCS", "1d"));

    // BOTH legs are staged and BOTH are verified before EITHER is swapped — a per-interval
    // verify/swap would let a good 1d swap land and a bad 1m refusal abort, leaving the symbol
    // half-adjusted: a series that looks complete and is silently wrong.
    //
    // …and the SWAPS run 1m FIRST, 1d LAST. See theDetectionInputIsSwappedLast below: this is not
    // cosmetic ordering, it is what stops a half-completed swap from sealing the symbol out of
    // detection forever.
    InOrder order = inOrder(queryService, candles);
    order.verify(queryService).stageFullRange(eq("NSE"), eq("TCS"), eq("1d"), any(), any());
    order.verify(queryService).stageFullRange(eq("NSE"), eq("TCS"), eq("1m"), any(), any());
    order.verify(candles).stagedCoverage("NSE", "TCS", "1d");
    order.verify(candles).stagedCoverage("NSE", "TCS", "1m");
    order.verify(candles).swapStaged("NSE", "TCS", "1m");
    order.verify(candles).swapStaged("NSE", "TCS", "1d");
    // the unguarded whole-symbol delete is gone from this path entirely
    verify(candles, never()).purgeSymbol(anyString(), anyString());
  }

  @Test
  void theDetectionInputIsSwappedLastSoAPartialSwapStaysDetectable() {
    // The two swaps are separate autocommitted statements — nothing in the chain is @Transactional
    // — so an ORDINARY catchable DB error (decompression cap, lock/statement timeout, disk) on the
    // SECOND swap commits the first and abandons the second. Detection reads only the 1d series, so
    // if 1d were swapped first the cache's 1d would then EQUAL Kite: no divergence, no re-detection,
    // and the symbol strands on adjusted 1d + unadjusted 1m forever — the self-sealing class this
    // PR closes, rebuilt one layer up.
    UUID id = armDetection();
    when(events.statusOf(id)).thenReturn(Optional.of("REBACKFILL_RUNNING"));
    doThrow(new RuntimeException("max_tuples_decompressed_per_dml_transaction"))
        .when(candles)
        .swapStaged("NSE", "TCS", "1d");

    assertThat(job.sweepNow()).containsExactly(id);

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> verify(events).updateStatusIf(id, "REBACKFILL_RUNNING", "FAILED"));
    // 1m committed, 1d did not — which is the RECOVERABLE half. The cached 1d still diverges from
    // Kite, so the next eligible sweep after the cooldown can re-detect and resume both legs
    // idempotently from the retained staging rows.
    verify(candles).swapStaged("NSE", "TCS", "1m");
    // Twice: once on the way IN to the attempt, once in the finally. Staging is NOT retained as a
    // resumable checkpoint — verifyStagedRebuild validates coverage only, so a checkpoint reused
    // after the cooldown could swap in pre-corporate-action prices that pass verification.
    verify(candles, times(2)).clearStaging("NSE", "TCS");
    verify(ntfy)
        .send(
            contains("FAILED"),
            eq("urgent"),
            contains("partial rebuild; swapped interval(s): 1m"));
    verify(events, never()).updateStatus(id, "BASE_REBUILT");
  }

  @Test
  void aPartialSwapFailureIsHeldByTheCooldownNotRetriedNextSweep() {
    // The CONSEQUENCE of the swap-order fix, executed rather than argued — and it is NOT the
    // consequence the ordering rationale originally claimed.
    //
    // theDetectionInputIsSwappedLast... proves the mechanism (1m committed, 1d not), which was then
    // reasoned forward to "so the next sweep re-detects". Traced end-to-end that is FALSE: the run
    // records FAILED with detected_at = today, and the rebuild cooldown then suppresses the very
    // next sweep. Recovery is rebuildRetryCooldownDays away, not one night. Pinning the real
    // behaviour here so the gap is visible in the suite instead of only in prose.
    UUID first = armDetection();
    when(events.statusOf(first)).thenReturn(Optional.of("REBACKFILL_RUNNING"));
    doThrow(new RuntimeException("max_tuples_decompressed_per_dml_transaction"))
        .when(candles)
        .swapStaged("NSE", "TCS", "1d");

    assertThat(job.sweepNow()).containsExactly(first);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> verify(events).updateStatusIf(first, "REBACKFILL_RUNNING", "FAILED"));

    // the row the failing run left behind: FAILED, stamped today
    when(events.latestEvent("NSE", "TCS")).thenReturn(Optional.of(row(first, "FAILED", 0)));

    assertThat(job.sweepNow()).as("the next sweep does NOT re-detect — the cooldown holds it").isEmpty();
    verify(events, times(1))
        .insertDetected(anyString(), anyString(), any(), any(), anyInt(), anyInt(), anyString());
    // …and the symbol sits on adjusted 1m + unadjusted 1d for the whole cooldown. It is paged, not
    // silent: recordFailure alerts on the REBACKFILL_RUNNING -> FAILED transition.
    verify(candles, times(1)).swapStaged("NSE", "TCS", "1m");
    verify(ntfy).send(contains("FAILED"), eq("urgent"), anyString());
  }

  @Test
  void barsOlderThanTheStagedSpanAreAlertedButNeverBlockTheSwap() {
    // The fourth measurement (M-A). Bars below the staged span keep PRE-event prices while
    // everything above is adjusted — a silent discontinuity no other signal can reach: the deepest
    // anchor is 5 years, and check 2 is span-scoped by construction. It is deliberately NOT a
    // refusal, because refusing would permanently strand the 49 live symbols that hold such bars
    // (1,276 1d rows measured 2026-08-04). This pins "alerted, not blocked" — get it wrong in the
    // refusing direction and those symbols never remediate again.
    UUID id = armDetection();
    when(events.statusOf(id)).thenReturn(Optional.of("REBACKFILL_RUNNING"));
    when(candles.stagedCoverage("NSE", "TCS", "1d"))
        .thenReturn(
            new CandleRepository.StagedCoverage(
                500,
                OffsetDateTime.ofInstant(NOW, IST).minusYears(5),
                OffsetDateTime.ofInstant(NOW, IST),
                0,
                OffsetDateTime.ofInstant(NOW, IST),
                1_276));

    assertThat(job.sweepNow()).containsExactly(id);

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> verify(candles).swapStaged("NSE", "TCS", "1d"));
    assertThat(
            meterRegistry
                .get("ay_corporate_action_unadjusted_tail_bars_total")
                .counter()
                .count())
        .isEqualTo(1_276.0);
    verify(events, never()).updateStatusIf(eq(id), anyString(), eq("FAILED"));
  }

  @Test
  void aFetchThatSilentlyReturnsNothingIsRefusedRatherThanSwappedIn() {
    // Check 1 of verifyStagedRebuild, which the other refusal tests do not reach: the gateway threw
    // nothing and returned no rows at all. Swapping an empty staging buffer in would delete the
    // symbol's live span and refill it with nothing — a purge wearing a rebuild's clothes, i.e. the
    // exact outcome this PR exists to make impossible.
    UUID id = armDetection();
    when(events.statusOf(id)).thenReturn(Optional.of("REBACKFILL_RUNNING"));
    when(candles.stagedCoverage("NSE", "TCS", "1m"))
        .thenReturn(new CandleRepository.StagedCoverage(0, null, null, 0, null, 0));

    assertThat(job.sweepNow()).containsExactly(id);

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> verify(events).updateStatusIf(id, "REBACKFILL_RUNNING", "FAILED"));
    verify(candles, never()).swapStaged(anyString(), anyString(), anyString());
    verify(candles, never()).purgeSymbol(anyString(), anyString());
  }

  @Test
  void aRecentlyFailedRebuildCoolsOffInsteadOfRetryingEveryNight() {
    // The rebuild path's retry bound. Leaving the cache intact deliberately restores re-detection,
    // which unbounded would re-fetch ~196 Kite pages per symbol per night against the shared
    // limiter, forever, for any symbol that can never pass verification.
    // ⚠️ armDetection() is load-bearing here, not scene-setting. Without it the sweep exits at the
    // "fewer than 2 comparable cached closes" branch, so the test passes whether the cooldown gate
    // exists or not — it would be asserting nothing. (Caught by red-proofing this very test: with
    // the gate deleted it stayed GREEN.) Armed, the ONLY thing standing between this sweep and a
    // fresh detection is the cooldown.
    UUID detected = armDetection();
    when(events.latestEvent("NSE", "TCS")).thenReturn(Optional.of(row(detected, "FAILED", 0)));

    assertThat(job.sweepNow()).isEmpty();

    // it never even reached the anchor diff, so no Kite page was spent
    verify(gateway, never()).fetch(any(), anyString(), any(), any());
    verify(events, never()).insertDetected(
        anyString(), anyString(), any(), any(), anyInt(), anyInt(), anyString());
  }

  @Test
  void aReBackfillThatDiesPartWayDeletesNothing() {
    // The discriminating case: the fetch fails on its SECOND leg, i.e. past the point the old order
    // had already purged the symbol. Nothing may have been deleted.
    UUID id = armDetection();
    when(events.statusOf(id)).thenReturn(Optional.of("REBACKFILL_RUNNING"));
    doThrow(new RuntimeException("kite 1m re-backfill died"))
        .when(queryService)
        .stageFullRange(anyString(), anyString(), eq("1m"), any(), any());

    assertThat(job.sweepNow()).containsExactly(id);

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> verify(events).updateStatusIf(id, "REBACKFILL_RUNNING", "FAILED"));
    verify(candles, never()).purgeSymbol(anyString(), anyString());
    verify(candles, never()).swapStaged(anyString(), anyString(), anyString());
    // the 1d leg staged fine, but a partial fetch must never be swapped in on the strength of it
    verify(candles, never()).swapStaged("NSE", "TCS", "1d");
    verify(events, never()).updateStatus(id, "BASE_REBUILT");
    // staging is emptied on the way out so the next attempt cannot inherit a partial fetch
    verify(candles, atLeast(2)).clearStaging("NSE", "TCS");
  }

  @Test
  void aStagedSeriesThatCannotReplaceWhatItOverwritesIsRefused() {
    // The silent-truncation case: the fetch THREW nothing, it just came back short. Only the
    // coverage check can catch this, and a swap that accepts it is the original defect with extra
    // steps — 4 cached bars would be deleted with no staged row to replace them.
    UUID id = armDetection();
    when(events.statusOf(id)).thenReturn(Optional.of("REBACKFILL_RUNNING"));
    when(candles.stagedCoverage("NSE", "TCS", "1m"))
        .thenReturn(
            new CandleRepository.StagedCoverage(
                500,
                OffsetDateTime.ofInstant(NOW, IST).minusYears(5),
                OffsetDateTime.ofInstant(NOW, IST),
                4,
                OffsetDateTime.ofInstant(NOW, IST),
                0));

    assertThat(job.sweepNow()).containsExactly(id);

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> verify(events).updateStatusIf(id, "REBACKFILL_RUNNING", "FAILED"));
    verify(candles, never()).swapStaged(anyString(), anyString(), anyString());
    verify(candles, never()).purgeSymbol(anyString(), anyString());
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
    // refresh-ONLY: the ~12-year re-backfill and the swap that follows it are exactly what a resume
    // must not redo
    verify(candles, never()).purgeSymbol(anyString(), anyString());
    verify(queryService, never()).prefetch(anyString(), anyString(), anyString(), any(), any());
    verify(queryService, never()).stageFullRange(anyString(), anyString(), anyString(), any(), any());
    verify(candles, never()).swapStaged(anyString(), anyString(), anyString());
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
        .stageFullRange(anyString(), anyString(), eq("1m"), any(), any());

    // detect → REBACKFILL_RUNNING → stage 1d → stage 1m THROWS: no base was committed. (Since V054
    // no base was DESTROYED either — that property is pinned by aReBackfillThatDiesPartWayDeletesNothing;
    // what this test still owns is that the FAILED row is not treated as refresh-resumable.)
    assertThat(job.sweepNow()).containsExactly(id);

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> verify(events).updateStatusIf(id, "REBACKFILL_RUNNING", "FAILED"));
    verify(events, never()).updateStatus(id, "BASE_REBUILT");
    verify(events, never()).updateStatusIf(eq(id), anyString(), eq("REFRESH_FAILED"));

    // the next sweep sees that FAILED row and must leave it alone: the run died before its base
    // was committed, so refreshing the caggs now would materialise aggregates over a base that was
    // never rebuilt (and, since V054, was also never destroyed — the cooldown then holds it off)
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
