package in.arthayantra.strategysignal.minervini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.eval.EntryEvaluator;
import in.arthayantra.strategyengine.eval.ExitEvaluator;
import in.arthayantra.strategyengine.eval.IndicatorBank;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyschema.StrategyDocuments;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import in.arthayantra.strategysignal.signals.Books;
import in.arthayantra.strategysignal.signals.EmissionGuard;
import in.arthayantra.strategysignal.signals.MarketDataCandlesClient;
import in.arthayantra.strategysignal.signals.SignalPublisher;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.swing.SwingBatchEngine;
import in.arthayantra.strategysignal.swing.SwingBatchRefusalRepository;
import in.arthayantra.strategysignal.swing.SwingCandidate;
import in.arthayantra.strategysignal.swing.SwingDoctrine;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Minervini doctrine driving the shared {@link SwingBatchEngine}: {@code buildBank} seeds the VCP
 * pivot context and the FROZEN {@link EntryEvaluator}/{@link ExitEvaluator} decide entry/exit on the
 * just-closed daily bar (parity — the batch never re-implements scoring). Pins entry on a
 * volume-expansion breakout, no entry on a flat-volume breakout, the 8% protective stop, the audit-H2
 * superseded-version adoption (both halves), the P0-3 fetch-retry, and the H3 per-emit gate re-check.
 */
class MinerviniSwingEngineTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final BigDecimal PIVOT = new BigDecimal("150");

  /** The Minervini candidate context seeds, matching the doctrine's unconditional 3-context form. */
  private static Map<String, BigDecimal> seeds(BigDecimal pivot, BigDecimal cheat, boolean thrust) {
    return Map.of(
        "MINERVINI_PIVOT", pivot == null ? BigDecimal.ZERO : pivot,
        "MINERVINI_CHEAT", cheat == null ? BigDecimal.ZERO : cheat,
        "MINERVINI_THRUST", thrust ? BigDecimal.ONE : BigDecimal.ZERO);
  }

  @Test
  void runDailyIsAnInertNoOpWhenDisabled() {
    StrategyRepository registry = mock(StrategyRepository.class);
    MinerviniFunnelClient funnel = mock(MinerviniFunnelClient.class);
    MarketDataCandlesClient candles = mock(MarketDataCandlesClient.class);
    SignalRepository signals = mock(SignalRepository.class);
    SignalPublisher publisher = mock(SignalPublisher.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    TransactionTemplate tx = mock(TransactionTemplate.class);

    SwingBatchEngine engine =
        new SwingBatchEngine(
            registry, candles, signals, publisher, events, Optional.empty(), tx,
            new ObjectMapper(), Clock.systemUTC());
    MinerviniDoctrine doctrine =
        new MinerviniDoctrine(funnel, signals, new ObjectMapper(), false, 520, 60, 1440);

    SwingBatchEngine.SwingRun run = engine.runDaily(doctrine);

    assertThat(run.strategies()).isZero();
    assertThat(run.entries()).isZero();
    assertThat(run.exits()).isZero();
    verifyNoInteractions(registry, funnel, candles, signals, publisher, events, tx);
  }

  @Test
  void entryFiresOnPivotBreakoutWithExpandingVolume() throws IOException {
    List<EngineCandle> series = craft(3_000L);
    IndicatorBank bank =
        SwingBatchEngine.buildBank(vcp(), "TESTCO", series, seeds(PIVOT, BigDecimal.ZERO, false));
    Optional<EntryEvaluator.Evaluation> eval = EntryEvaluator.evaluate(vcp(), bank, series.size() - 1);
    assertThat(eval).isPresent();
    assertThat(eval.get().entry()).as("the last bar breaks out above the 150 pivot on 3x volume").isTrue();
  }

  @Test
  void entryBlockedWhenTheBreakoutLacksVolume() throws IOException {
    List<EngineCandle> series = craft(1_000L);
    IndicatorBank bank =
        SwingBatchEngine.buildBank(vcp(), "TESTCO", series, seeds(PIVOT, BigDecimal.ZERO, false));
    Optional<EntryEvaluator.Evaluation> eval = EntryEvaluator.evaluate(vcp(), bank, series.size() - 1);
    assertThat(eval).isPresent();
    assertThat(eval.get().entry()).as("crossover fires but the vol>1.2 gate blocks it").isFalse();
  }

  @Test
  void protectiveStopExitsAnEightPercentUnderwaterPosition() throws IOException {
    List<EngineCandle> series = craftDecline();
    IndicatorBank bank =
        SwingBatchEngine.buildBank(
            vcp(), "TESTCO", series, seeds(BigDecimal.ZERO, BigDecimal.ZERO, false));
    Optional<ExitEvaluator.ExitDecision> exit =
        ExitEvaluator.evaluate(
            vcp(), bank,
            new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, new BigDecimal("152"), 0),
            series.size() - 1);
    assertThat(exit).isPresent();
    assertThat(exit.get().type()).isEqualTo("stop_loss");
  }

  @Test
  void exitPassAdoptsAnchorsOfASupersededVersion() throws IOException {
    UUID strategyId = UUID.randomUUID();
    UUID publishedVersion = UUID.randomUUID();
    UUID supersededVersion = UUID.randomUUID();
    JsonNode config = vcpConfig();
    StrategyRepository registry = mock(StrategyRepository.class);
    StrategyRepository.StrategyRow strategyRow = strategyRow(strategyId, publishedVersion);
    when(registry.listAll()).thenReturn(List.of(strategyRow));
    when(registry.findById(strategyId)).thenReturn(Optional.of(strategyRow));
    when(registry.findVersionById(publishedVersion))
        .thenReturn(Optional.of(version(publishedVersion, strategyId, "2", config)));
    when(registry.findVersionById(supersededVersion))
        .thenReturn(Optional.of(version(supersededVersion, strategyId, "1", config)));

    List<EngineCandle> series = craftDecline();
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.activeEntries())
        .thenReturn(List.of(anchor(42L, supersededVersion, new BigDecimal("152"), series)));
    stubInsert(signals, 43L);

    MinerviniFunnelClient funnel = mock(MinerviniFunnelClient.class);
    when(funnel.buyableAndOnDeck()).thenReturn(List.of());
    MarketDataCandlesClient candles = mock(MarketDataCandlesClient.class);
    when(candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any())).thenReturn(series);

    SwingBatchEngine.SwingRun run = engine(registry, candles, signals, funnel).runDaily(doctrine(funnel, signals, true, 60));

    assertThat(run.exits()).as("the superseded-version anchor is adopted and its stop fires").isEqualTo(1);
    verify(signals).transition(42L, "EXPIRED");
  }

  @Test
  void heldSymbolsBlocksReEntryForSupersededVersionAnchors() throws IOException {
    UUID strategyId = UUID.randomUUID();
    UUID publishedVersion = UUID.randomUUID();
    UUID supersededVersion = UUID.randomUUID();
    JsonNode config = vcpConfig();
    StrategyRepository registry = mock(StrategyRepository.class);
    StrategyRepository.StrategyRow strategyRow = strategyRow(strategyId, publishedVersion);
    when(registry.listAll()).thenReturn(List.of(strategyRow));
    when(registry.findById(strategyId)).thenReturn(Optional.of(strategyRow));
    when(registry.findVersionById(publishedVersion))
        .thenReturn(Optional.of(version(publishedVersion, strategyId, "2", config)));
    when(registry.findVersionById(supersededVersion))
        .thenReturn(Optional.of(version(supersededVersion, strategyId, "1", config)));

    List<EngineCandle> series = craft(3_000L);
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.activeEntries())
        .thenReturn(List.of(anchor(42L, supersededVersion, new BigDecimal("100"), series)));

    MinerviniFunnelClient funnel = mock(MinerviniFunnelClient.class);
    when(funnel.buyableAndOnDeck())
        .thenReturn(
            List.of(new MinerviniFunnelClient.Candidate("TESTCO", new BigDecimal("152"), PIVOT, null, false, 2, "40W 31/3 4T", false)));
    MarketDataCandlesClient candles = mock(MarketDataCandlesClient.class);
    when(candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any())).thenReturn(series);

    SwingBatchEngine.SwingRun run = engine(registry, candles, signals, funnel).runDaily(doctrine(funnel, signals, true, 60));

    assertThat(run.entries()).as("the superseded-version anchor's symbol blocks re-entry").isZero();
  }

  @Test
  void exitPassRetriesAFailedFetchOnceThenEvaluatesTheStop() throws IOException {
    ExitHarness h = new ExitHarness();
    when(h.candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any()))
        .thenReturn(List.of())
        .thenReturn(h.series);

    SwingBatchEngine.SwingRun run = h.engine().runDaily(h.doctrine());

    assertThat(run.exits()).as("the retry recovers the series and the stop fires").isEqualTo(1);
    assertThat(run.exitSkipped()).isZero();
    verify(h.candles, times(2)).fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any());
  }

  @Test
  void exitPassCountsAndScreamsWhenTheSeriesIsUnavailableAfterRetry() throws IOException {
    ExitHarness h = new ExitHarness();
    when(h.candles.fetch(any(), any(), any(), any(), any())).thenReturn(List.of());

    SwingBatchEngine.SwingRun run = h.engine().runDaily(h.doctrine());

    assertThat(run.exits()).isZero();
    assertThat(run.exitSkipped()).as("the unevaluated stop is surfaced, not swallowed").isEqualTo(1);
  }

  @Test
  void catchUpPinnedToTheSessionOfTheLastBarEvaluatesTheStopNormally() throws IOException {
    // The catch-up path (2026-07-17 incident): pinned to the session whose bar IS the newest, the run
    // is the ordinary run — same bar, same decision, same settle price the on-time batch would have got.
    ExitHarness h = new ExitHarness();
    when(h.candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any())).thenReturn(h.series);

    SwingBatchEngine.SwingRun run = h.engine().runDaily(h.doctrine(), LAST_BAR_DATE);

    assertThat(run.exits()).isEqualTo(1);
    assertThat(run.exitSkipped()).isZero();
  }

  @Test
  void catchUpRefusesToSettleOffABarFromTheWrongSession() throws IOException {
    // THE money property. A catch-up fired for a session whose daily bar has NOT landed would otherwise
    // read whatever bar is newest and settle the position at THAT day's close — the exact harm the
    // catch-up exists to undo (position 29 was closed three days late). Pinned, the stale series is
    // dropped and the un-evaluated stop is COUNTED + screamed, never silently priced off the wrong day.
    ExitHarness h = new ExitHarness();
    when(h.candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any())).thenReturn(h.series);

    SwingBatchEngine.SwingRun run = h.engine().runDaily(h.doctrine(), LAST_BAR_DATE.plusDays(1));

    assertThat(run.exits()).as("no exit is emitted off the wrong session's bar").isZero();
    assertThat(run.exitSkipped()).as("the unevaluated stop is surfaced, not swallowed").isEqualTo(1);
    verify(h.signals, org.mockito.Mockito.never()).transition(any(Long.class), any());
  }

  @Test
  void aLotOpenedAfterThePinnedSessionIsNotEvaluated() throws IOException {
    ExitHarness h = new ExitHarness();
    when(h.signals.activeEntries())
        .thenReturn(
            List.of(
                anchorAt(
                    42L, h.versionId, new BigDecimal("152"), LAST_BAR_DATE.plusDays(1))));

    SwingBatchEngine.SwingRun run = h.engine().runDaily(h.doctrine(), LAST_BAR_DATE);

    assertThat(run.exits()).isZero();
    assertThat(run.exitSkipped()).as("post-session lots are outside the pinned position").isZero();
    verify(h.candles, org.mockito.Mockito.never()).fetch(any(), any(), any(), any(), any());
  }

  @Test
  void aMixedPreAndPostSessionPositionIsRefusedRatherThanEvaluatedApproximately()
      throws IOException {
    ExitHarness h = new ExitHarness();
    when(h.signals.activeEntries())
        .thenReturn(
            List.of(
                anchorAt(42L, h.versionId, new BigDecimal("152"), LAST_BAR_DATE),
                anchorAt(43L, h.versionId, new BigDecimal("155"), LAST_BAR_DATE.plusDays(1))));

    SwingBatchEngine.SwingRun run = h.engine().runDaily(h.doctrine(), LAST_BAR_DATE);

    assertThat(run.exits()).isZero();
    assertThat(run.exitSkipped()).as("mixed lots are refused, not reported as a missing bar").isZero();
    assertThat(run.refusalReasons()).containsExactly("MIXED_PRE_POST_LOTS:TESTCO");
    verify(h.signals, org.mockito.Mockito.never()).transition(any(Long.class), any());
    verify(h.candles, org.mockito.Mockito.never()).fetch(any(), any(), any(), any(), any());
  }

  @Test
  void oneSnapshotFeedsTheEngineWithoutASecondFunnelRead() throws IOException {
    ExitHarness h = new ExitHarness();
    when(h.candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any()))
        .thenReturn(h.series);
    SwingCandidate candidate =
        new SwingCandidate(
            "TESTCO", seeds(PIVOT, BigDecimal.ZERO, false), null,
            new ObjectMapper().createObjectNode(), false);
    SwingDoctrine.CandidateSnapshot snapshot =
        new SwingDoctrine.CandidateSnapshot(LAST_BAR_DATE, List.of(candidate));

    SwingBatchEngine.SwingRun run =
        h.engine().runDaily(h.doctrine(), LAST_BAR_DATE, true, Optional.of(snapshot));

    assertThat(run.candidates()).isEqualTo(1);
    org.mockito.Mockito.verifyNoInteractions(h.funnel);
  }

  @Test
  void unpinnedRunsAreUnaffectedByTheCatchUpBarGuard() throws IOException {
    // The scheduled/on-demand path passes no session, so nothing is ever dropped — the guard is inert
    // unless a catch-up explicitly pins a date.
    ExitHarness h = new ExitHarness();
    when(h.candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any())).thenReturn(h.series);

    assertThat(h.engine().runDaily(h.doctrine()).exits())
        .isEqualTo(h.engine().runDaily(h.doctrine(), null).exits());
  }

  @Test
  void anExitsOnlyPassReportsTheBookItWalkedAndZeroForTheEntryCountersItDidNot() throws IOException {
    // Ledger H16. The evening settle runs entriesEnabled=false and used to take AdmissionProbe.empty()
    // — "nothing measured" — so swing_batch_runs.open_at_start read 0 for a run that had just walked
    // the whole book. Until H23 (#1457) the next morning's entries catch-up overwrote that row, so the
    // zero was transient; with `pass` in the primary key the settle row survives and the zero is the
    // permanent answer to the one question that column exists to answer.
    ExitHarness h = new ExitHarness();
    when(h.candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any())).thenReturn(h.series);
    // A THREE-name book, and every name is a different KIND of walk — that is what makes the
    // assertion pin the book size rather than a proxy for it:
    //   TESTCO  stop fires        -> exits = 1
    //   HOLDCO  bar never arrives -> exitSkipped = 1  (unstubbed fetch → empty)
    //   STAYCO  bar arrives, stop does NOT fire -> the ORDINARY settle name, eventless
    // So openAtStart = 3 while the post-exit book is 2, the exits are 1, and closed+skipped is 2.
    // ⚠️ STAYCO exists because a two-name book CANNOT catch closed+skipped: with every name eventful
    // the touched-count equals the book size, and substituting it ran 30/30 GREEN (review, 2026-08-25).
    // The eventless name is the typical settle, and it was the one the fixture did not represent.
    SignalRepository.SignalRow exiting = anchor(42L, h.versionId, new BigDecimal("152"), h.series);
    SignalRepository.SignalRow skipped = heldElsewhere(44L, h.versionId, "HOLDCO", h.series);
    SignalRepository.SignalRow eventless = heldElsewhere(45L, h.versionId, "STAYCO", h.series);
    when(h.candles.fetch(eq("NSE"), eq("STAYCO"), eq("1d"), any(), any())).thenReturn(craft(1_000L));
    // The book as the DB actually reports it across the run: all three for the heldBefore snapshot
    // and for the exit pass, then two once the stop has closed TESTCO. A probe that re-read the book
    // AFTER the exits would see 2 — a different question from "how big was the book we walked".
    when(h.signals.activeEntries())
        .thenReturn(
            List.of(exiting, skipped, eventless),
            List.of(exiting, skipped, eventless),
            List.of(skipped, eventless));

    SwingBatchEngine.SwingRun run = h.engine().runDaily(h.doctrine(), null, false);

    assertThat(run.exits()).as("the exit pass genuinely walked the book and fired the stop").isEqualTo(1);
    assertThat(run.exitSkipped()).as("the missing bar was walked too, and its miss counted").isEqualTo(1);
    SwingBatchEngine.AdmissionProbe probe = run.admission();
    assertThat(probe.openAtStart())
        .as("the PRE-exit book size — not 0, not the post-exit 2, not the 1 exit, not the 2 touched")
        .isEqualTo(3);
    // The entry-specific counters stay zero because no entry was ATTEMPTED — that honest zero is the
    // whole distinction from empty(), and fabricating anything here would be the mirror defect.
    assertThat(probe.wouldEnter()).as("an exits-only pass attempts no entry").isZero();
    assertThat(probe.admitted()).isZero();
    assertThat(probe.capExceedance()).isZero();
    assertThat(probe.capBound()).isFalse();
    assertThat(probe.droppedByCap()).isEmpty();
    assertThat(run.entries()).isZero();
    assertThat(run.candidates()).as("the funnel is not read on an exits-only pass").isZero();
    verifyNoInteractions(h.funnel);
  }

  @Test
  void aProbeThatThrowsStillReportsTheBookBecauseItWasSnapshottedBeforeTheThrow() throws IOException {
    // The same H16 zero, one branch over: the ENTRIES probe is wrapped fail-soft, and its catch used
    // to return empty() — writing open_at_start = 0 for a run whose book was known before the probe
    // was ever called. heldBefore is a PARAMETER of admissionProbe, so it survives the throw.
    ExitHarness h = new ExitHarness();
    when(h.candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any())).thenReturn(h.series);
    SignalRepository.SignalRow exiting = anchor(42L, h.versionId, new BigDecimal("152"), h.series);
    SignalRepository.SignalRow skipped = heldElsewhere(44L, h.versionId, "HOLDCO", h.series);
    // Reads 1 (heldBefore) and 2 (the exit pass) succeed; the entry pass early-outs on the empty
    // funnel without reading. Read 3 is the probe's own heldAfter snapshot — that is the one that
    // throws, which is exactly where a probe defect lands.
    when(h.signals.activeEntries())
        .thenReturn(List.of(exiting, skipped), List.of(exiting, skipped))
        .thenThrow(new IllegalStateException("probe boom"));

    SwingBatchEngine.SwingRun run = h.engine().runDaily(h.doctrine());

    assertThat(run.exits()).as("the throw is swallowed — the batch's exits are untouched").isEqualTo(1);
    SwingBatchEngine.AdmissionProbe probe = run.admission();
    assertThat(probe.openAtStart())
        .as("measured before the probe ran, so it survives the probe's failure")
        .isEqualTo(2);
    assertThat(probe.wouldEnter()).as("the funnel walk was genuinely lost").isZero();
    assertThat(probe.capBound()).isFalse();
    assertThat(probe.droppedByCap()).isEmpty();
  }

  @Test
  void entryGateIsReCheckedPerEntryAndStopsTheRunWhenTheBookTrips() throws IOException {
    UUID strategyId = UUID.randomUUID();
    UUID publishedVersion = UUID.randomUUID();
    JsonNode config = vcpConfig();
    StrategyRepository registry = mock(StrategyRepository.class);
    when(registry.listAll()).thenReturn(List.of(strategyRow(strategyId, publishedVersion)));
    when(registry.findVersionById(publishedVersion))
        .thenReturn(Optional.of(version(publishedVersion, strategyId, "1", config)));

    List<EngineCandle> series = craft(3_000L);
    MinerviniFunnelClient funnel = mock(MinerviniFunnelClient.class);
    when(funnel.buyableAndOnDeck())
        .thenReturn(
            List.of(
                new MinerviniFunnelClient.Candidate("AAA", new BigDecimal("152"), PIVOT, null, false, 2, "40W 31/3 4T", false),
                new MinerviniFunnelClient.Candidate("BBB", new BigDecimal("152"), PIVOT, null, false, 2, "40W 31/3 4T", false)));
    MarketDataCandlesClient candles = mock(MarketDataCandlesClient.class);
    when(candles.fetch(eq("NSE"), any(), eq("1d"), any(), any())).thenReturn(series);
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.activeEntries()).thenReturn(List.of());
    stubInsert(signals, 1L);

    EmissionGuard guard = mock(EmissionGuard.class);
    when(guard.entryAllowed(Books.MINERVINI)).thenReturn(true, true, false);
    when(guard.suggestedQty(any(), any(), any(), any(), any(), any())).thenReturn(new BigDecimal("10"));

    SwingBatchEngine engine =
        new SwingBatchEngine(
            registry, candles, signals, mock(SignalPublisher.class),
            mock(ApplicationEventPublisher.class), Optional.of(guard), passthroughTx(),
            new ObjectMapper(), Clock.systemUTC());

    SwingBatchEngine.SwingRun run = engine.runDaily(doctrine(funnel, signals, true, 10));

    assertThat(run.entries()).as("the mid-run book trip halts entries after the first").isEqualTo(1);
    // Exact detail JSON (byte-identity of the minervini_detail side-channel): setup → stage →
    // footprint → pivot → thrust, in that order — locks the field set + ORDER (cheatPivot omitted
    // when null). AAA is the one candidate that fired before the mid-run trip.
    ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
    verify(signals).stampMinerviniDetail(any(Long.class), detail.capture());
    assertThat(detail.getValue())
        .isEqualTo("{\"setup\":\"minervini-vcp\",\"stage\":2,\"footprint\":\"40W 31/3 4T\",\"pivot\":\"150\",\"thrust\":false}");
  }

  @Test
  void admissionProbeMeasuresWouldEnterAdmittedAndTheCapDroppedTail() throws IOException {
    // Ledger F3: three RS-ordered funnel names — HELD (already held, must be skipped), ADMIT (fresh,
    // gets a slot), DROP (fresh, would enter but the book trips before it). The probe must read
    // wouldEnter=2 (ADMIT+DROP; HELD skipped), admitted=1, capExceedance=1, and drop DROP at its rank.
    UUID strategyId = UUID.randomUUID();
    UUID publishedVersion = UUID.randomUUID();
    JsonNode config = vcpConfig();
    StrategyRepository registry = mock(StrategyRepository.class);
    when(registry.listAll()).thenReturn(List.of(strategyRow(strategyId, publishedVersion)));
    when(registry.findVersionById(publishedVersion))
        .thenReturn(Optional.of(version(publishedVersion, strategyId, "1", config)));

    List<EngineCandle> series = craft(3_000L); // a breakout that fires the entry for every symbol
    MinerviniFunnelClient funnel = mock(MinerviniFunnelClient.class);
    when(funnel.buyableAndOnDeck())
        .thenReturn(
            List.of(
                candidate("HELD"), // rank 1 — held at start, skipped by the probe
                candidate("ADMIT"), // rank 2 — fresh, admitted
                candidate("DROP"))); // rank 3 — fresh, dropped by the mid-run book trip
    MarketDataCandlesClient candles = mock(MarketDataCandlesClient.class);
    when(candles.fetch(eq("NSE"), any(), eq("1d"), any(), any())).thenReturn(series);

    SignalRepository signals = mock(SignalRepository.class);
    // openLotsBySymbol -> activeEntries() is read 4×: heldBefore, entry pass, exit pass, then the
    // probe's heldAfter. The first three see HELD only; the last also sees the freshly-admitted ADMIT.
    SignalRepository.SignalRow heldAnchor =
        anchorFor("HELD", 42L, publishedVersion, new BigDecimal("100"), series);
    SignalRepository.SignalRow admitAnchor =
        anchorFor("ADMIT", 43L, publishedVersion, new BigDecimal("152"), series);
    when(signals.activeEntries())
        .thenReturn(List.of(heldAnchor))
        .thenReturn(List.of(heldAnchor))
        .thenReturn(List.of(heldAnchor))
        .thenReturn(List.of(heldAnchor, admitAnchor));
    stubInsert(signals, 43L);

    EmissionGuard guard = mock(EmissionGuard.class);
    when(guard.entryAllowed(Books.MINERVINI)).thenReturn(true, true, false); // trips before DROP
    when(guard.suggestedQty(any(), any(), any(), any(), any(), any())).thenReturn(new BigDecimal("10"));

    SwingBatchEngine engine =
        new SwingBatchEngine(
            registry, candles, signals, mock(SignalPublisher.class),
            mock(ApplicationEventPublisher.class), Optional.of(guard), passthroughTx(),
            new ObjectMapper(), Clock.systemUTC());

    SwingBatchEngine.SwingRun run = engine.runDaily(doctrine(funnel, signals, true, 10));

    assertThat(run.entries()).as("only ADMIT emitted before the trip").isEqualTo(1);
    SwingBatchEngine.AdmissionProbe probe = run.admission();
    assertThat(probe.openAtStart()).isEqualTo(1);
    assertThat(probe.wouldEnter()).as("ADMIT + DROP fire; HELD is skipped").isEqualTo(2);
    assertThat(probe.admitted()).isEqualTo(1);
    assertThat(probe.capExceedance()).isEqualTo(1);
    assertThat(probe.capBound()).isTrue();
    assertThat(probe.droppedByCap()).singleElement().satisfies(d -> {
      assertThat(d.symbol()).isEqualTo("DROP");
      assertThat(d.admissionRank()).as("DROP is the 3rd funnel name").isEqualTo(3);
    });
  }

  @Test
  void aBookBlockedAtRunStartStillReportsTheFunnelItNeverScanned() throws IOException {
    // The 2026-08-04 minervini collapse: the book sat at its slot cap, so entryPass early-outs before
    // the candidate scan — and used to report 0 candidates, publishing "the screen found nothing" to
    // swing_batch_runs / the summary alert / every session report, while the F3 probe on the same run
    // counted every one of them as a would-be entrant. The count must survive the early-out.
    UUID strategyId = UUID.randomUUID();
    UUID publishedVersion = UUID.randomUUID();
    JsonNode config = vcpConfig();
    StrategyRepository registry = mock(StrategyRepository.class);
    when(registry.listAll()).thenReturn(List.of(strategyRow(strategyId, publishedVersion)));
    when(registry.findVersionById(publishedVersion))
        .thenReturn(Optional.of(version(publishedVersion, strategyId, "1", config)));

    List<EngineCandle> series = craft(3_000L); // a breakout that fires the entry for every symbol
    MinerviniFunnelClient funnel = mock(MinerviniFunnelClient.class);
    when(funnel.buyableAndOnDeck())
        .thenReturn(List.of(candidate("AAA"), candidate("BBB"), candidate("CCC")));
    MarketDataCandlesClient candles = mock(MarketDataCandlesClient.class);
    when(candles.fetch(eq("NSE"), any(), eq("1d"), any(), any())).thenReturn(series);
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.activeEntries()).thenReturn(List.of());

    EmissionGuard guard = mock(EmissionGuard.class);
    // The book's entry governor refuses from the first check. The engine sees ONLY this boolean, so
    // this fixture models every rail that opens the wouldEnter-minus-admitted gap, not the slot cap
    // specifically — activeEntries is empty, so openAtStart is 0 and the row is a kill-switch /
    // daily-loss / daily-target shape rather than a MAX_OPEN one. That is deliberate: the assertion
    // below is about the counter, and the counter must be right whichever rail bound the run.
    when(guard.entryAllowed(Books.MINERVINI)).thenReturn(false);

    SwingBatchEngine engine =
        new SwingBatchEngine(
            registry, candles, signals, mock(SignalPublisher.class),
            mock(ApplicationEventPublisher.class), Optional.of(guard), passthroughTx(),
            new ObjectMapper(), Clock.systemUTC());

    SwingBatchEngine.SwingRun run = engine.runDaily(doctrine(funnel, signals, true, 10));

    assertThat(run.candidates())
        .as("the whole funnel is reported even though the blocked pass scanned none of it")
        .isEqualTo(3);
    assertThat(run.entries()).as("the gate admits nothing").isZero();
    verify(signals, org.mockito.Mockito.never())
        .insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    // The probe is the corroborating witness: candidates=0 alongside these numbers was the tell.
    SwingBatchEngine.AdmissionProbe probe = run.admission();
    assertThat(probe.wouldEnter()).isEqualTo(3);
    assertThat(probe.admitted()).isZero();
    assertThat(probe.capExceedance()).isEqualTo(3);
    assertThat(probe.capBound()).isTrue();
  }

  private static MinerviniFunnelClient.Candidate candidate(String symbol) {
    return new MinerviniFunnelClient.Candidate(
        symbol, new BigDecimal("152"), PIVOT, null, false, 2, "40W 31/3 4T", false);
  }

  private static SignalRepository.SignalRow anchorFor(
      String symbol, long id, UUID versionId, BigDecimal entryPrice, List<EngineCandle> series) {
    return new SignalRepository.SignalRow(
        id, versionId, "NSE", symbol, "1d", "ENTRY", "BUY", entryPrice, null, null, BigDecimal.ONE,
        new ObjectMapper().createObjectNode(), "TAKEN", series.get(0).bucketStart(),
        series.get(0).bucketStart().plusDays(1), null, null, null, null, null, null, null);
  }

  /**
   * THE DOCTRINE TEST for the 2026-08-03 data-coverage gate. An exit evaluated on a holed series must
   * still FIRE — "entries need fresh truth (you can always NOT enter), exits need the best available
   * truth (you cannot refuse to leave forever)". If a future change ever mirrors the entry refusal
   * onto the exit path, this reddens.
   *
   * <p>Concretely guards the 44-symbol corporate-action-purge cohort (TATASTEEL, WIPRO, TECHM …):
   * they hold only 44 daily bars, so a refusing guard could strand a real position in the most liquid
   * names in the universe.
   */
  @Test
  void exitStillFiresWhenCoverageIsIncomplete() throws IOException {
    // ⚠️ Asserted under ALL THREE MODES, because the doctrine is UNCONDITIONAL: the mode flag may
    // change what the exit half OBSERVES, never whether the held stop is EVALUATED. This is also the
    // standing answer to "what does ARMED mean on the exit side" — it means ALERT LOUDLY, never
    // REFUSE. If a future change ever mirrors the entry refusal onto the exit path to make the enum
    // look symmetric, the ARMED iteration below reddens.
    for (SwingBatchEngine.CoverageGateMode mode : SwingBatchEngine.CoverageGateMode.values()) {
      ExitHarness h = new ExitHarness();
      // drop 2026-06-18 — a real NSE trading day, and one of the sessions actually missing from
      // marketdata.candles on 2026-08-03. Three sessions, not one: materiality requires >~4.76% of
      // the probed span, so a single hole is deliberately BELOW the refusal/alert bar (that is the
      // Critical fix, not an oversight).
      List<EngineCandle> holed = holed(h.series, 6, 16, 18, 19);
      assertThat(holed).hasSize(h.series.size() - 3);
      when(h.candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any())).thenReturn(holed);

      SwingBatchEngine.SwingRun run = h.engine(mode).runDaily(h.doctrine());

      assertThat(run.exits())
          .as("%s: an incomplete window must DEGRADE the exit, never block it", mode)
          .isEqualTo(1);
      assertThat(run.exitSkipped())
          .as("%s: a coverage hole is not a skip — the stop WAS evaluated", mode)
          .isZero();
      if (mode == SwingBatchEngine.CoverageGateMode.DISABLED) {
        assertThat(h.coverageRows).as("DISABLED must leave no trace").isEmpty();
      } else {
        assertThat(h.coverageRows)
            .as("%s: exit coverage must persist the exact durable evidence row", mode)
            // ⚠️ NO WOULD_REFUSE_ prefix, unlike the entry side under OBSERVE_ONLY. The exit fires
            // in every mode, so there is no counterfactual to distinguish and the row states the
            // same true fact whichever mode wrote it.
            .containsExactly("minervini|2026-08-04|TESTCO|EXIT_DEGRADED_COVERAGE:TESTCO");
      }
    }
  }

  /**
   * THE FALSE-PAGE GUARD, and it asserts the OPPOSITE of what this test did in its first revision.
   *
   * <p>That revision required a February gap — 135 bars back, far outside the declared 50-bar depth —
   * to raise {@code EXIT_DEGRADED_COVERAGE} on a Minervini position, and red-proofed it. The proof was
   * mechanically valid and pinned the wrong requirement: cross-vendor review (2026-08-24) pointed out
   * that NO Minervini exit operand reads that history. Its stop is on the entry PRICE and its trail is
   * {@code basis: indicator} on {@code sma50}, whose branch DISCARDS the peak-since-entry {@code
   * trailing()} computes ({@code ExitEvaluator:569,659-670}).
   *
   * <p>Under ARMED each such row is a per-position page asserting the stop/trail may be stretched. The
   * PR's own design note rejected per-symbol entry paging because it would "bury the exit-side alerts
   * that actually carry money risk" — manufacturing false exit pages does exactly that. So the
   * requirement is the reverse: a long-held Minervini position with a gap in history its exit never
   * reads must stay SILENT.
   */
  @Test
  void aLongHeldMinerviniPositionDoesNotPageOnHistoryItsExitNeverReads() throws IOException {
    ExitHarness h = new ExitHarness(longDecline());
    List<EngineCandle> holed = holed(h.series, 2, 10, 11, 12);
    assertThat(holed).hasSize(h.series.size() - 3);
    when(h.candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any())).thenReturn(holed);

    SwingBatchEngine.SwingRun run =
        h.engine(SwingBatchEngine.CoverageGateMode.ARMED).runDaily(h.doctrine());

    assertThat(run.exits()).as("doctrine is unchanged — the exit still fires").isEqualTo(1);
    assertThat(h.coverageRows)
        .as(
            "an entry-price stop and a current-bar sma50 trail read NONE of this history — widening"
                + " the footprint here would page ops about a gap that cannot move either level")
        .isEmpty();
  }

  /**
   * Control: the same Minervini strategy DOES still page when the gap lands inside the window its
   * {@code sma50} trail actually reads. Without this, the guard above could pass because the exit
   * probe had been switched off entirely.
   */
  @Test
  void minerviniStillPagesWhenTheGapIsInsideTheTrailWindow() throws IOException {
    ExitHarness h = new ExitHarness(longDecline());
    List<EngineCandle> holed = holed(h.series, 6, 16, 18, 19);
    when(h.candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any())).thenReturn(holed);

    h.engine(SwingBatchEngine.CoverageGateMode.ARMED).runDaily(h.doctrine());

    assertThat(h.coverageRows)
        .as("a gap inside the 50-bar sma50 window is real and must still be reported")
        .containsExactly("minervini|2026-08-04|TESTCO|EXIT_DEGRADED_COVERAGE:TESTCO");
  }

  /**
   * The degraded exit is not silent WHEN ARMED: ops gets a per-position alert beside the evaluation.
   * Paging is precisely what arming buys on this half — the exit fires either way.
   */
  @Test
  void degradedExitPublishesAnOpsAlertWhenArmed() throws IOException {
    ExitHarness h = new ExitHarness();
    when(h.candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any()))
        .thenReturn(holed(h.series, 6, 16, 18, 19));

    h.engine(SwingBatchEngine.CoverageGateMode.ARMED).runDaily(h.doctrine());

    assertThat(alerts(h.events))
        .anySatisfy(
            a -> {
              assertThat(a.title()).contains("exit DEGRADED");
              // the message renders the hole as a RANGE (first..last), not every date
              assertThat(a.message()).contains("2026-06-16..2026-06-19");
              assertThat(a.message()).contains("3 of 25 sessions missing");
            });
  }

  /**
   * OBSERVE_ONLY — THE SHIPPED DEFAULT — accrues the evidence without paging. The identical fixture
   * that pages under ARMED must write the row, fire the exit, and NEVER publish the alert: "an
   * observation must never page", the same rule the entry half already follows.
   */
  @Test
  void observeOnlyRecordsTheDegradedExitButNeverPages() throws IOException {
    ExitHarness h = new ExitHarness();
    when(h.candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any()))
        .thenReturn(holed(h.series, 6, 16, 18, 19));

    SwingBatchEngine.SwingRun run =
        h.engine(SwingBatchEngine.CoverageGateMode.OBSERVE_ONLY).runDaily(h.doctrine());

    assertThat(run.exits()).as("OBSERVE_ONLY must not change exit behaviour").isEqualTo(1);
    assertThat(h.coverageRows)
        .as("the durable evidence is exactly what OBSERVE_ONLY is for")
        .containsExactly("minervini|2026-08-04|TESTCO|EXIT_DEGRADED_COVERAGE:TESTCO");
    assertThat(alerts(h.events))
        .as("an observation must never page")
        .noneSatisfy(a -> assertThat(a.title()).contains("DEGRADED"));
  }

  /**
   * ⚠️ THE INERTNESS PROOF, and the reason this whole change exists. Before it, the exit half was
   * NOT mode-gated at all: the probe ran, an ERROR was logged, a swing_batch_refusals row was
   * written and an ntfy page fired in EVERY mode including DISABLED — so a flag sold as "ships
   * inert" turned a durable-row-writing, paging detector on for every book the moment it merged.
   *
   * <p>Asserts all three traces are absent — no row, no log line, no alert — on the SAME holed
   * fixture that produces all three when observed. The OBSERVE_ONLY positive control at the end is
   * load-bearing: without it this test would keep passing if the fixture stopped being degraded at
   * all, which is the "guard that checks nothing" shape.
   */
  @Test
  void disabledExitProbesNothingAndLeavesNoTrace() throws IOException {
    ExitHarness h = new ExitHarness();
    when(h.candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any()))
        .thenReturn(holed(h.series, 6, 16, 18, 19));

    ch.qos.logback.classic.Logger engineLog =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SwingBatchEngine.class);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
        new ch.qos.logback.core.read.ListAppender<>();
    logs.start();
    engineLog.addAppender(logs);
    SwingBatchEngine.SwingRun run;
    String logged;
    try {
      run = h.engine(SwingBatchEngine.CoverageGateMode.DISABLED).runDaily(h.doctrine());
      logged =
          logs.list.stream()
              .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
              .collect(java.util.stream.Collectors.joining("\n"));
    } finally {
      engineLog.detachAppender(logs);
    }

    assertThat(run.exits()).as("DISABLED must still evaluate the held stop").isEqualTo(1);
    assertThat(run.exitSkipped()).isZero();
    assertThat(h.coverageRows).as("DISABLED must write no swing_batch_refusals row").isEmpty();
    assertThat(logged)
        .as("DISABLED must emit no coverage log line at any level")
        .doesNotContain("INCOMPLETE or UNKNOWN coverage");
    assertThat(alerts(h.events))
        .as("DISABLED must page nobody")
        .noneSatisfy(a -> assertThat(a.title()).contains("DEGRADED"));

    // ⚠️ POSITIVE CONTROL on the identical fixture. Without it, every assertion above would still
    // pass if the holed series simply stopped being degraded — a guard that checks nothing.
    ExitHarness observed = new ExitHarness();
    when(observed.candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any()))
        .thenReturn(holed(observed.series, 6, 16, 18, 19));
    observed.engine(SwingBatchEngine.CoverageGateMode.OBSERVE_ONLY).runDaily(observed.doctrine());
    assertThat(observed.coverageRows)
        .as("the fixture must genuinely be degraded, or the DISABLED assertions prove nothing")
        .isNotEmpty();
  }

  /** A gap-free series must not alert even when ARMED — otherwise the signal is noise. */
  @Test
  void completeCoverageRaisesNoDegradationAlert() throws IOException {
    ExitHarness h = new ExitHarness();
    when(h.candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any())).thenReturn(h.series);

    SwingBatchEngine.SwingRun run =
        h.engine(SwingBatchEngine.CoverageGateMode.ARMED).runDaily(h.doctrine());

    assertThat(run.exits()).isEqualTo(1);
    assertThat(h.coverageRows).isEmpty();
    assertThat(alerts(h.events))
        .noneSatisfy(a -> assertThat(a.title()).contains("DEGRADED"));
  }

  /**
   * ENTRY half, which the first round left entirely untested (cross-vendor review Major). A
   * materially holed window must refuse the entry — the mirror image of {@link
   * #exitStillFiresWhenCoverageIsIncomplete}, and the asymmetry is the design.
   */
  @Test
  void entryIsRefusedWhenTheGateWindowIsMateriallyHoled() throws IOException {
    EntryHarness h = new EntryHarness();
    when(h.candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any()))
        .thenReturn(holed(h.series, 6, 16, 18, 19));

    SwingBatchEngine.SwingRun run = h.engine(h.events).runDaily(h.doctrine());

    assertThat(run.entries()).as("a materially stretched entry window must refuse").isZero();
    assertThat(alerts(h.events))
        .anySatisfy(a -> assertThat(a.title()).contains("entries refused"));
    assertThat(h.coverageRows)
        .as("entry coverage must persist the exact durable evidence row")
        .containsExactly("minervini|2026-08-04|TESTCO|INCOMPLETE_COVERAGE:TESTCO");
  }

  /**
   * THE SHIPPED DEFAULT (owner decision 2026-08-11). The identical series that ARMED refuses must
   * ENTER under OBSERVE_ONLY — that is what "ships inert" means, and it is the whole basis on which
   * this gate was allowed to merge. The evidence still accrues, as a distinctly-prefixed row.
   */
  @Test
  void observeOnlyRecordsTheWouldBeRefusalAndLetsTheEntryFire() throws IOException {
    EntryHarness h = new EntryHarness();
    when(h.candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any()))
        .thenReturn(holed(h.series, 6, 16, 18, 19));

    SwingBatchEngine.SwingRun run =
        h.engine(h.events, SwingBatchEngine.CoverageGateMode.OBSERVE_ONLY).runDaily(h.doctrine());

    assertThat(run.entries()).as("OBSERVE_ONLY must not change live behaviour").isEqualTo(1);
    assertThat(alerts(h.events))
        .as("an observation must never page")
        .noneSatisfy(a -> assertThat(a.title()).contains("entries refused"));
    // ⚠️ The prefix is load-bearing: swing_batch_refusals rows are read as "this candidate was
    // refused", and the arming decision rests on counting them. A bare reason here would make the
    // table claim a refusal for an entry that fired.
    assertThat(h.coverageRows)
        .containsExactly("minervini|2026-08-04|TESTCO|WOULD_REFUSE_INCOMPLETE_COVERAGE:TESTCO");
    // The F3 probe must model the pass AS CONFIGURED, or wouldEnter under-counts what entered.
    assertThat(run.admission().wouldEnter())
        .as("the admission probe must agree with the entry pass under every mode")
        .isEqualTo(1);
  }

  /** DISABLED does no probe work at all — no refusal, and no durable row either. */
  @Test
  void disabledRunsNoProbeAndWritesNothing() throws IOException {
    EntryHarness h = new EntryHarness();
    when(h.candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any()))
        .thenReturn(holed(h.series, 6, 16, 18, 19));

    SwingBatchEngine.SwingRun run =
        h.engine(h.events, SwingBatchEngine.CoverageGateMode.DISABLED).runDaily(h.doctrine());

    assertThat(run.entries()).isEqualTo(1);
    assertThat(h.coverageRows).as("DISABLED must leave no trace").isEmpty();
  }

  /** The same series with the holes filled must still enter — proving the refusal is the cause. */
  @Test
  void entryFiresWhenCoverageIsComplete() throws IOException {
    EntryHarness h = new EntryHarness();
    when(h.candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any())).thenReturn(h.series);

    SwingBatchEngine.SwingRun run = h.engine(h.events).runDaily(h.doctrine());

    assertThat(run.entries()).as("the control: complete coverage enters").isEqualTo(1);
    assertThat(alerts(h.events)).noneSatisfy(a -> assertThat(a.title()).contains("entries refused"));
  }

  /**
   * The F3 admission probe must apply the same coverage gate as the emitting pass. Otherwise a
   * coverage-refused candidate still counts as {@code wouldEnter}, never becomes held, and is
   * persisted as a slot-cap drop — recording a DATA refusal as a CAPITAL-cap drop and corrupting the
   * ledger-F3 measurement (cross-vendor review Major).
   */
  @Test
  void aCoverageRefusedCandidateIsNotCountedAsASlotCapDrop() throws IOException {
    EntryHarness h = new EntryHarness();
    when(h.candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any()))
        .thenReturn(holed(h.series, 6, 16, 18, 19));

    SwingBatchEngine.SwingRun run = h.engine(h.events).runDaily(h.doctrine());

    assertThat(run.entries()).isZero();
    assertThat(run.admission().wouldEnter())
        .as("a DATA refusal must not be attributed to the capital cap")
        .isZero();
    assertThat(run.admission().capExceedance()).isZero();
    assertThat(run.admission().capBound()).isFalse();
    assertThat(run.admission().droppedByCap()).isEmpty();
  }

  // NOTE: the depth-relative materiality assertion for the Critical (the same hole refusing a
  // 50-bar reader but not a 252-bar one) is a probe-level property and lives in
  // SwingCoverageProbeTest#materialityUsesWindowFraction, not here.

  private static List<EngineCandle> holed(List<EngineCandle> series, int month, int... days) {
    java.util.Set<java.time.LocalDate> drop = new java.util.HashSet<>();
    for (int d : days) {
      drop.add(java.time.LocalDate.of(2026, month, d));
    }
    List<EngineCandle> out = new ArrayList<>(series);
    out.removeIf(b -> drop.contains(b.bucketStart().toLocalDate()));
    return out;
  }

  private static List<in.arthayantra.strategysignal.signals.SwingBatchAlert> alerts(
      ApplicationEventPublisher events) {
    org.mockito.ArgumentCaptor<Object> captor = org.mockito.ArgumentCaptor.forClass(Object.class);
    verify(events, org.mockito.Mockito.atLeast(0)).publishEvent(captor.capture());
    return captor.getAllValues().stream()
        .filter(in.arthayantra.strategysignal.signals.SwingBatchAlert.class::isInstance)
        .map(in.arthayantra.strategysignal.signals.SwingBatchAlert.class::cast)
        .toList();
  }

  // ---- harness --------------------------------------------------------------------------------

  /** Mirror of {@link ExitHarness} for the entry pass: one funnel candidate on a firing series. */
  private final class EntryHarness {
    final StrategyRepository registry = mock(StrategyRepository.class);
    final SignalRepository signals = mock(SignalRepository.class);
    final MinerviniFunnelClient funnel = mock(MinerviniFunnelClient.class);
    final MarketDataCandlesClient candles = mock(MarketDataCandlesClient.class);
    final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    final EmissionGuard guard = mock(EmissionGuard.class);
    final SwingBatchRefusalRepository refusals = mock(SwingBatchRefusalRepository.class);
    final List<String> coverageRows = new ArrayList<>();
    final List<EngineCandle> series = craft(3_000L);

    EntryHarness() throws IOException {
      captureCoverageRows();
      UUID strategyId = UUID.randomUUID();
      UUID publishedVersion = UUID.randomUUID();
      when(registry.listAll()).thenReturn(List.of(strategyRow(strategyId, publishedVersion)));
      when(registry.findVersionById(publishedVersion))
          .thenReturn(Optional.of(version(publishedVersion, strategyId, "1", vcpConfig())));
      when(funnel.buyableAndOnDeck())
          .thenReturn(
              List.of(
                  new MinerviniFunnelClient.Candidate(
                      "TESTCO", new BigDecimal("152"), PIVOT, null, false, 2, "40W 31/3 4T", false)));
      when(signals.activeEntries()).thenReturn(List.of());
      stubInsert(signals, 1L);
      when(guard.entryAllowed(Books.MINERVINI)).thenReturn(true);
      when(guard.suggestedQty(any(), any(), any(), any(), any(), any()))
          .thenReturn(new BigDecimal("10"));
    }

    private void captureCoverageRows() {
      doAnswer(
              invocation -> {
                coverageRows.add(
                    invocation.getArgument(0)
                        + "|"
                        + invocation.getArgument(1)
                        + "|"
                        + invocation.getArgument(2)
                        + "|"
                        + invocation.getArgument(3));
                return null;
              })
          .when(refusals)
          .record(any(), any(), any(), any());
    }

    SwingBatchEngine engine(ApplicationEventPublisher publisher) {
      return engine(publisher, SwingBatchEngine.CoverageGateMode.ARMED);
    }

    /**
     * ⚠️ The no-mode overload above ARMS the gate deliberately, and every coverage test states its
     * mode. The engine's own default is OBSERVE_ONLY (it ships inert, owner 2026-08-11), so a test
     * that silently inherited the default would assert refusals the live stack does not perform.
     */
    SwingBatchEngine engine(
        ApplicationEventPublisher publisher, SwingBatchEngine.CoverageGateMode mode) {
      return new SwingBatchEngine(
          registry, candles, signals, mock(SignalPublisher.class), publisher, Optional.of(guard),
          passthroughTx(), new ObjectMapper(), fixedTestClock(), null, refusals, mode.name());
    }

    MinerviniDoctrine doctrine() {
      return MinerviniSwingEngineTest.this.doctrine(funnel, signals, true, 10);
    }
  }

  private final class ExitHarness {
    final StrategyRepository registry = mock(StrategyRepository.class);
    final SignalRepository signals = mock(SignalRepository.class);
    final MinerviniFunnelClient funnel = mock(MinerviniFunnelClient.class);
    final MarketDataCandlesClient candles = mock(MarketDataCandlesClient.class);
    final SwingBatchRefusalRepository refusals = mock(SwingBatchRefusalRepository.class);
    final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    final List<String> coverageRows = new ArrayList<>();
    final List<EngineCandle> series;
    final UUID versionId;

    ExitHarness() throws IOException {
      this(craftDecline());
    }

    /** Same harness over a caller-supplied series — used by the exit-coverage sizing tests. */
    ExitHarness(List<EngineCandle> bars) throws IOException {
      this.series = bars;
      doAnswer(
              invocation -> {
                coverageRows.add(
                    invocation.getArgument(0)
                        + "|"
                        + invocation.getArgument(1)
                        + "|"
                        + invocation.getArgument(2)
                        + "|"
                        + invocation.getArgument(3));
                return null;
              })
          .when(refusals)
          .record(any(), any(), any(), any());
      UUID strategyId = UUID.randomUUID();
      versionId = UUID.randomUUID();
      JsonNode config = vcpConfig();
      when(registry.listAll()).thenReturn(List.of(strategyRow(strategyId, versionId)));
      when(registry.findVersionById(versionId))
          .thenReturn(Optional.of(version(versionId, strategyId, "1", config)));
      when(signals.activeEntries())
          .thenReturn(List.of(anchor(42L, versionId, new BigDecimal("152"), series)));
      stubInsert(signals, 43L);
      when(funnel.buyableAndOnDeck()).thenReturn(List.of());
    }

    /**
     * ⚠️ Takes the PRODUCTION default (OBSERVE_ONLY), unlike {@link EntryHarness#engine} which arms
     * deliberately — the non-coverage exit tests below must exercise the mode the live stack runs.
     * Every COVERAGE test states its mode explicitly via {@link #engine(SwingBatchEngine.CoverageGateMode)}.
     */
    SwingBatchEngine engine() {
      return engine(SwingBatchEngine.CoverageGateMode.OBSERVE_ONLY);
    }

    SwingBatchEngine engine(SwingBatchEngine.CoverageGateMode mode) {
      return MinerviniSwingEngineTest.this.engineWithRefusals(
          registry, candles, signals, refusals, events, mode);
    }

    MinerviniDoctrine doctrine() {
      return MinerviniSwingEngineTest.this.doctrine(funnel, signals, true, 60);
    }
  }

  private SwingBatchEngine engine(
      StrategyRepository registry, MarketDataCandlesClient candles, SignalRepository signals,
      MinerviniFunnelClient funnel) {
    return new SwingBatchEngine(
        registry, candles, signals, mock(SignalPublisher.class),
        mock(ApplicationEventPublisher.class), Optional.empty(), passthroughTx(),
        new ObjectMapper(), Clock.systemUTC());
  }

  private SwingBatchEngine engineWithRefusals(
      StrategyRepository registry, MarketDataCandlesClient candles, SignalRepository signals,
      SwingBatchRefusalRepository refusals, ApplicationEventPublisher events,
      SwingBatchEngine.CoverageGateMode mode) {
    return new SwingBatchEngine(
        registry, candles, signals, mock(SignalPublisher.class), events, Optional.empty(),
        passthroughTx(), new ObjectMapper(), fixedTestClock(), null, refusals, mode.name());
  }

  private static Clock fixedTestClock() {
    return Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC);
  }

  private MinerviniDoctrine doctrine(
      MinerviniFunnelClient funnel, SignalRepository signals, boolean enabled, int minBars) {
    return new MinerviniDoctrine(funnel, signals, new ObjectMapper(), enabled, 520, minBars, 1440);
  }

  private static TransactionTemplate passthroughTx() {
    TransactionTemplate tx = mock(TransactionTemplate.class);
    when(tx.execute(any()))
        .thenAnswer(inv -> inv.<TransactionCallback<Long>>getArgument(0).doInTransaction(null));
    return tx;
  }

  private static void stubInsert(SignalRepository signals, long id) {
    when(signals.insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(id);
  }

  private static StrategyRepository.StrategyRow strategyRow(UUID strategyId, UUID publishedVersion) {
    return new StrategyRepository.StrategyRow(
        strategyId, "minervini-vcp", "Minervini VCP", null, null, List.of("minervini"), true,
        publishedVersion, null, null, false, null);
  }

  private static SignalRepository.SignalRow anchor(
      long id, UUID versionId, BigDecimal entryPrice, List<EngineCandle> series) {
    return new SignalRepository.SignalRow(
        id, versionId, "NSE", "TESTCO", "1d", "ENTRY", "BUY", entryPrice, null, null, BigDecimal.ONE,
        new ObjectMapper().createObjectNode(), "TAKEN", series.get(0).bucketStart(),
        series.get(0).bucketStart().plusDays(1), null, null, null, null, null, null, null);
  }

  /** A held ENTRY anchor on a symbol other than TESTCO, timed to the same series as {@link #anchor}. */
  private static SignalRepository.SignalRow heldElsewhere(
      long id, UUID versionId, String symbol, List<EngineCandle> series) {
    return new SignalRepository.SignalRow(
        id, versionId, "NSE", symbol, "1d", "ENTRY", "BUY", new BigDecimal("152"), null, null,
        BigDecimal.ONE, new ObjectMapper().createObjectNode(), "TAKEN", series.get(0).bucketStart(),
        series.get(0).bucketStart().plusDays(1), null, null, null, null, null, null, null);
  }

  private static SignalRepository.SignalRow anchorAt(
      long id, UUID versionId, BigDecimal entryPrice, java.time.LocalDate date) {
    OffsetDateTime generatedAt = date.atStartOfDay(IST).toOffsetDateTime();
    return new SignalRepository.SignalRow(
        id, versionId, "NSE", "TESTCO", "1d", "ENTRY", "BUY", entryPrice, null, null,
        BigDecimal.ONE, new ObjectMapper().createObjectNode(), "TAKEN", generatedAt,
        generatedAt.plusDays(1), null, null, null, null, null, null, null);
  }

  private static StrategyDefinition vcp() throws IOException {
    return StrategyCompiler.compile(vcpConfig());
  }

  private static JsonNode vcpConfig() throws IOException {
    try (InputStream in =
        MinerviniSwingEngineTest.class.getResourceAsStream("/minervini-strategies/minervini-vcp.yaml")) {
      assertThat(in).isNotNull();
      return StrategyDocuments.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)).config();
    }
  }

  private static StrategyRepository.VersionRow version(
      UUID id, UUID strategyId, String version, JsonNode config) {
    return new StrategyRepository.VersionRow(
        id, strategyId, version, null, config, "1", "chk-" + version, "published", null, null, null, null);
  }

  private static List<EngineCandle> craft(long breakoutVolume) {
    double[] tail = {146, 148, 146, 148, 147};
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d <= 18; d++) {
      bars.add(bar(d, 100.0 + (149.0 - 100.0) * d / 18.0, 1_000L));
    }
    for (int i = 0; i < tail.length; i++) {
      bars.add(bar(19 + i, tail[i], 1_000L));
    }
    bars.add(bar(24, 152.0, breakoutVolume));
    return bars;
  }

  /** The IST date of {@link #craftDecline()}'s last bar — the session a catch-up would pin to. */
  private static final java.time.LocalDate LAST_BAR_DATE = java.time.LocalDate.of(2026, 6, 25);

  private static List<EngineCandle> craftDecline() {
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d <= 23; d++) {
      bars.add(bar(d, 150.0, 1_000L));
    }
    bars.add(bar(24, 135.0, 1_000L));
    return bars;
  }

  /**
   * {@link #craftDecline} stretched back to 2026-02-02 — 145 calendar-day bars, so the series is
   * LONGER than the strategy's declared 50-bar exit depth. Same ending decline, so the exit still
   * fires on the last bar; only the history behind it is longer.
   */
  private static List<EngineCandle> longDecline() {
    List<EngineCandle> bars = new ArrayList<>();
    java.time.LocalDate start = java.time.LocalDate.of(2026, 2, 2);
    int span = (int) java.time.temporal.ChronoUnit.DAYS.between(start, LAST_BAR_DATE);
    for (int d = 0; d < span; d++) {
      bars.add(barOn(start.plusDays(d), 150.0, 1_000L));
    }
    bars.add(barOn(LAST_BAR_DATE, 135.0, 1_000L));
    return bars;
  }

  private static EngineCandle barOn(java.time.LocalDate date, double price, long volume) {
    BigDecimal p = BigDecimal.valueOf(price);
    return new EngineCandle(
        date.atStartOfDay().atOffset(IST), p, p, p, p, volume, null);
  }

  private static EngineCandle bar(int day, double price, long volume) {
    OffsetDateTime bucket = OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, IST).plusDays(day);
    BigDecimal p = BigDecimal.valueOf(price);
    return new EngineCandle(bucket, p, p, p, p, volume, null);
  }
}
