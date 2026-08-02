package in.arthayantra.strategysignal.manas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyschema.StrategyDocuments;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import in.arthayantra.strategysignal.signals.Books;
import in.arthayantra.strategysignal.signals.EmissionGuard;
import in.arthayantra.strategysignal.signals.MarketDataCandlesClient;
import in.arthayantra.strategysignal.signals.SignalEmitted;
import in.arthayantra.strategysignal.signals.SignalExited;
import in.arthayantra.strategysignal.signals.SignalPublisher;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.swing.SwingBatchEngine;
import in.arthayantra.strategysignal.swing.SwingDoctrine;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Manas doctrine driving the shared {@link SwingBatchEngine}: the P0-3 exit-pass fetch-failure
 * handling (the #573 ATR exits are entry-index-dependent, so an unevaluated exit mis-manages a live
 * stop) plus the F2 §3.4 pyramiding — the +gain-triggered add, the ≤6% open-risk cap, and the exit
 * grouping that closes ALL lots of a pyramided symbol at once (§3.5.D). (The pure §3.4 math lives in
 * {@code ManasPyramidPolicyTest}.)
 */
class ManasAroraSwingEngineTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  @Test
  void exitPassRetriesAFailedFetchOnceThenEvaluates() throws IOException {
    ExitHarness h = new ExitHarness();
    when(h.candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any()))
        .thenReturn(List.of())
        .thenReturn(h.series);

    SwingBatchEngine.SwingRun run = h.engine().runDaily(h.doctrine(false));

    assertThat(run.exitSkipped()).as("the retry recovers the series — nothing skipped").isZero();
    verify(h.candles, org.mockito.Mockito.times(2)).fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any());
  }

  @Test
  void exitPassCountsAndScreamsWhenTheSeriesIsUnavailableAfterRetry() throws IOException {
    ExitHarness h = new ExitHarness();
    when(h.candles.fetch(any(), any(), any(), any(), any())).thenReturn(List.of());

    SwingBatchEngine.SwingRun run = h.engine().runDaily(h.doctrine(false));

    assertThat(run.exits()).isZero();
    assertThat(run.exitSkipped()).as("the unevaluated ATR stop is surfaced, not swallowed").isEqualTo(1);
  }

  @Test
  void anArmedTrailRatchetsTheGoverningStopWithoutFiringAnExit() throws IOException {
    // M40 Critical 3 fix, round 3 (owner ruling, 2026-08-02): proves SwingBatchEngine's exit pass —
    // with the REAL ATR/Chandelier arithmetic (ExitEvaluator, unmodified) — calls the
    // cacheManasGoverningStop port (IN MEMORY ONLY, never stop_loss or any database column — see
    // that method's javadoc for why: stop_loss also serves as the intraday disaster-stop a 15s
    // poller reads with no book filter) when a held position's trail has armed but nothing exits,
    // and that it does NOT fire an exit in the same run (the two are mutually exclusive branches of
    // the same evaluation). See PaperServiceManasAggregateRiskIntegrationTest for the "lowers
    // computed risk and never touches stop_loss" half of this proof.
    UUID strategyId = UUID.randomUUID();
    UUID publishedVersion = UUID.randomUUID();
    JsonNode config = breakoutConfig();
    StrategyRepository registry = mock(StrategyRepository.class);
    when(registry.listAll()).thenReturn(List.of(strategyRow(strategyId, publishedVersion)));
    when(registry.findVersionById(publishedVersion))
        .thenReturn(Optional.of(version(publishedVersion, strategyId, config)));

    List<EngineCandle> series = craftArmedTrail();
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.activeEntries())
        .thenReturn(
            List.of(anchor(42L, publishedVersion, new BigDecimal("152"), series.get(0).bucketStart())));
    MarketDataCandlesClient candles = mock(MarketDataCandlesClient.class);
    when(candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any())).thenReturn(series);
    ManasFunnelClient funnel = mock(ManasFunnelClient.class);
    when(funnel.buyableAndOnDeck()).thenReturn(List.of());

    EmissionGuard guard = mock(EmissionGuard.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    SwingBatchEngine engine =
        new SwingBatchEngine(
            registry, candles, signals, mock(SignalPublisher.class), events, Optional.of(guard),
            passthroughTx(), new ObjectMapper(), Clock.systemUTC());
    ManasDoctrine doctrine =
        new ManasDoctrine(
            funnel, signals, new ManasPyramidPolicy(false, new BigDecimal("5.0"), 3, new BigDecimal("6.0")),
            new ObjectMapper(), true, 520, 10, 1440);

    SwingBatchEngine.SwingRun run = engine.runDaily(doctrine);

    assertThat(run.exits()).as("the armed trail has not been BREACHED — nothing exits").isZero();
    ArgumentCaptor<BigDecimal> stop = ArgumentCaptor.forClass(BigDecimal.class);
    verify(guard)
        .cacheManasGoverningStop(eq(Books.MANAS_ARORA), eq("NSE"), eq("TESTCO"), eq("BUY"), stop.capture());
    assertThat(stop.getValue())
        .as("the armed (breakeven-floored) trail ratchets to AT LEAST entry price — strictly tighter"
            + " than the persisted initial stop (entry − 2×ATR, well below entry)")
        .isGreaterThanOrEqualTo(new BigDecimal("152"));
  }

  @Test
  void aPyramidedSymbolExitsAllLotsAtOnceAndExpiresEverySibling() throws IOException {
    ExitHarness h = new ExitHarness();
    h.stubAnchors(
        h.anchor(42L, h.series.get(24).bucketStart()),
        h.anchor(43L, h.series.get(25).bucketStart()));
    when(h.candles.fetch(any(), any(), any(), any(), any())).thenReturn(h.series);

    SwingBatchEngine.SwingRun run = h.engine().runDaily(h.doctrine(false));

    assertThat(run.exits()).as("ONE symbol closed (not one-per-lot)").isEqualTo(1);
    verify(h.signals).transition(42L, "EXPIRED");
    verify(h.signals).transition(43L, "EXPIRED");

    // V048 propagation guard through the REAL engine path: the EXIT row's persisted reason must be
    // the SAME string the SignalExited events carry — the repository IT alone would stay green if
    // the engine regressed to inserting null (cross-vendor review, round 1).
    ArgumentCaptor<String> exitReason = ArgumentCaptor.forClass(String.class);
    verify(h.signals)
        .insert(
            any(), any(), any(), any(), eq("EXIT"), any(), any(), any(), any(), any(), any(),
            any(), any(), exitReason.capture());
    assertThat(exitReason.getValue()).as("the engine's computed reason reaches the row").isNotNull();
    verify(h.events)
        .publishEvent(
            argThat(
                (Object e) ->
                    e instanceof SignalExited s
                        && s.anchorSignalId() == 42L
                        && exitReason.getValue().equals(s.reason())));
    verify(h.events)
        .publishEvent(
            argThat(
                (Object e) ->
                    e instanceof SignalExited s
                        && s.anchorSignalId() == 43L
                        && exitReason.getValue().equals(s.reason())));
  }

  @Test
  void withPyramidingOffAHeldSymbolInTheFunnelIsNeverReEntered() throws IOException {
    ExitHarness h = new ExitHarness();
    when(h.funnel.buyableAndOnDeck())
        .thenReturn(
            List.of(new ManasFunnelClient.Candidate("TESTCO", bd(200), bd(150), "breakout", null, bd(150), null, false)));

    h.engine().runDaily(h.doctrine(false));

    verify(h.events, never()).publishEvent(argThat((Object e) -> e instanceof SignalEmitted));
  }

  @Test
  void aHeldWinnerThatMakesAFreshPivotWithinTheRiskCapTakesAPyramidAdd() throws IOException {
    AddResult r = runPyramidAdd(new BigDecimal("10000")); // book open risk 1% of ₹10L equity

    assertThat(r.run().entries()).as("the add fires as a 2nd lot").isEqualTo(1);
    verify(r.events()).publishEvent(argThat((Object e) -> e instanceof SignalEmitted));
    // Exact detail JSON (byte-identity of the manas_arora_detail side-channel): setup → setupType →
    // pivot → pyramidLot, in that order — locks the field set + ORDER, not just a substring.
    ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
    verify(r.signals()).stampManasAroraDetail(anyLong(), detail.capture());
    assertThat(detail.getValue())
        .isEqualTo("{\"setup\":\"manas-arora-breakout\",\"setupType\":\"breakout\",\"pivot\":\"150\",\"pyramidLot\":2}");
    // A non-breaching add must never spuriously trip the add-path observability fix's audit/alert.
    verify(r.guard(), never()).recordPyramidRiskCapBreach(any(), any(), any());
  }

  @Test
  void aPyramidAddIsBlockedWhenItWouldBreachTheOpenRiskCap() throws IOException {
    AddResult r = runPyramidAdd(new BigDecimal("60000")); // already at the 6% cap

    assertThat(r.run().entries()).as("the risk cap blocks the add").isZero();
    verify(r.events(), never()).publishEvent(argThat((Object e) -> e instanceof SignalEmitted));
    // Add-path observability fix (E4 §2f). Three of RiskService's four audited rails
    // (daily-loss/profit-target/heat-cap) write a risk_audit row + push an ntfy alert on trip;
    // deployment audits only. Before this fix the pyramid risk-cap block matched neither group —
    // this proves it now reaches the same EmissionGuard governor surface (RiskServicePyramidCapTest
    // proves what the paper adapter does with the call from there: audits + alerts, deduped per IST
    // day, matching the audit+alert group). The FRESH-entry half of M40 (the gap this add-path fix did
    // NOT close) is proven separately below by
    // aFreshEntryAtSixOpenPositionsIsRefusedWhenTheSeventhWouldBreachTheOpenRiskCap — see
    // docs/signal-analysis/2026-08-02-m40-fresh-entry-risk-cap-gap.md for the original gap record.
    verify(r.guard()).recordPyramidRiskCapBreach(eq(Books.MANAS_ARORA), eq("TESTCO"), any());
  }

  @Test
  void aFreshEntryAtSixOpenPositionsIsRefusedWhenTheSeventhWouldBreachTheOpenRiskCap() throws IOException {
    // M40 (owner-directed 2026-08-02): 6 open Manas positions already risking exactly 6% of a
    // ₹1,000,000 book (representative of 6 names each risking risk_pct_equity=1.0, the value both
    // manas-arora-breakout.yaml and manas-arora-vcp.yaml carry — max_open_paper_positions=7 makes a
    // 7th reachable at current config since both strategies share one Books.MANAS_ARORA key). NEWCO is
    // NOT held (signals.activeEntries() is empty) — this is a FRESH (first) entry, not a pyramid add,
    // and pyramiding is DISABLED (enabled=false) to prove the cap fires independently of that flag.
    FreshResult r = runFreshEntry(new BigDecimal("60000"));

    assertThat(r.run().entries())
        .as("the 7th fresh entry is refused by the aggregate open-risk cap")
        .isZero();
    verify(r.events(), never()).publishEvent(argThat((Object e) -> e instanceof SignalEmitted));
    verify(r.guard()).recordPyramidRiskCapBreach(eq(Books.MANAS_ARORA), eq("NEWCO"), any());
  }

  @Test
  void aFreshSeventhEntryStaysAdmittedWhenTheAggregateStaysUnderTheOpenRiskCap() throws IOException {
    // The discriminating counterpart: 6 open positions whose aggregate risk is well under the cap, so
    // the 7th name's own risk keeps the 7-name aggregate under 6% too — this check must not become an
    // over-eager blanket refusal of every fresh Manas entry.
    FreshResult r = runFreshEntry(new BigDecimal("10000"));

    assertThat(r.run().entries()).as("under the cap at 7 names — still admitted").isEqualTo(1);
    verify(r.events()).publishEvent(argThat((Object e) -> e instanceof SignalEmitted));
    verify(r.guard(), never()).recordPyramidRiskCapBreach(any(), any(), any());
  }

  @Test
  void aLotOpenedAfterThePinnedSessionIsNotEvaluated() throws IOException {
    ExitHarness h = new ExitHarness();
    h.stubAnchors(h.anchor(42L, h.series.get(25).bucketStart())); // 2026-06-26, after the pin

    SwingBatchEngine.SwingRun run =
        h.engine().runDaily(h.doctrine(false), LocalDate.of(2026, 6, 25), false);

    assertThat(run.exits()).isZero();
    assertThat(run.exitSkipped()).as("a future lot is not an approximate exit").isZero();
    verify(h.candles, never()).fetch(any(), any(), any(), any(), any());
    verify(h.events, never()).publishEvent(argThat((Object e) -> e instanceof SignalExited));
  }

  /**
   * M6 characterization (#128 batch scoping, market-data-service's {@code
   * ManasSwingExitEquivalenceTest} covers the deep-sim half): {@code lotsAsOf} admits a lot whose
   * {@code generatedAt} date EQUALS the pinned session (a same-day catch-up run, or a live run
   * where the entry pass just opened it), so {@code exitPass} evaluates {@code ExitEvaluator} at
   * {@code entryIndex == series.size()-1} — the SAME bar the lot was opened on. Exercised through
   * the REAL production path ({@code runDaily} → {@code exitPass} → {@code lotsAsOf} →
   * {@code buildBank} → {@code ExitEvaluator.evaluate}), not a hand-rolled equivalent — a change to
   * {@code lotsAsOf}'s date comparison (e.g. excluding same-day lots to "fix" M6 on the live side)
   * would turn {@code exits()} to 0 and redden this test. The 4-bar series is deliberately too
   * short for the config's ATR-based stop_loss/trailing_stop to warm up, isolating the square_off
   * rule (a pure close-vs-past-close check, independent of entry timing) as the only reachable exit.
   */
  @Test
  void aSameDayLotIsAdmittedAndEvaluatedOnItsOwnEntryBar() throws IOException {
    ExitHarness h = new ExitHarness();
    List<EngineCandle> sameDaySeries = fourBarSquareOffSeries();
    OffsetDateTime entryBar = sameDaySeries.get(sameDaySeries.size() - 1).bucketStart();
    LocalDate pinnedSession = entryBar.withOffsetSameInstant(IST).toLocalDate();
    when(h.candles.fetch(any(), any(), any(), any(), any())).thenReturn(sameDaySeries);
    h.stubAnchors(h.anchor(42L, entryBar)); // same calendar day as the pinned session

    SwingBatchEngine.SwingRun run = h.engine().runDaily(h.doctrine(false), pinnedSession);

    assertThat(run.exits())
        .as("live has no entry-bar guard: the same-day lot IS evaluated and its exit fires")
        .isEqualTo(1);
    assertThat(run.exitSkipped()).isZero();
    ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
    verify(h.signals)
        .insert(
            any(), any(), any(), any(), eq("EXIT"), any(), any(), any(), any(), any(), any(),
            any(), any(), reason.capture());
    assertThat(reason.getValue()).isEqualTo("SQUARE_OFF");
  }

  /** 4 flat-OHLC bars [100,100,100,140] — too short for ATR(20) to warm, so only square_off
   * (fast_pct=35/fast_bars=3, per manas-arora-breakout.yaml) is reachable: 140 >= 100*1.35. */
  private static List<EngineCandle> fourBarSquareOffSeries() {
    double[] closes = {100.0, 100.0, 100.0, 140.0};
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d < closes.length; d++) {
      bars.add(bar(200 + d, closes[d])); // day offset 200+ so it never collides with craftDecline()
    }
    return bars;
  }

  @Test
  void aPositionWithPreAndPostSessionLotsIsRefusedWithoutAFalseMissingBarSkip() throws IOException {
    ExitHarness h = new ExitHarness();
    h.stubAnchors(
        h.anchor(42L, h.series.get(24).bucketStart()), // 2026-06-25, before/equal to the pin
        h.anchor(43L, h.series.get(25).bucketStart())); // 2026-06-26, after the pin

    SwingBatchEngine.SwingRun run =
        h.engine().runDaily(h.doctrine(false), LocalDate.of(2026, 6, 25), false);

    assertThat(run.exits()).isZero();
    assertThat(run.exitSkipped()).as("mixed lots are refused, never partially evaluated").isZero();
    assertThat(run.refusalReasons()).containsExactly("MIXED_PRE_POST_LOTS:TESTCO");
    verify(h.candles, never()).fetch(any(), any(), any(), any(), any());
    verify(h.events, never()).publishEvent(argThat((Object e) -> e instanceof SignalExited));
  }

  @Test
  void aDeadlineCrossedDuringEvaluationStopsBeforeTheNextMoneyEffect() throws IOException {
    ExitHarness h = new ExitHarness();
    AtomicInteger checks = new AtomicInteger();
    SwingBatchEngine.SwingRun run =
        h.engine()
            .runDaily(
                h.doctrine(false), LocalDate.of(2026, 6, 28), false, Optional.empty(), true,
                () -> checks.incrementAndGet() > 1);

    assertThat(run.exits()).isZero();
    assertThat(run.deadlineReached()).isTrue();
    verify(h.events, never()).publishEvent(argThat((Object e) -> e instanceof SignalExited));
  }

  @Test
  void aPostSessionLotCannotQualifyAnEntryOrPyramidAdd() throws IOException {
    LocalDate session = LocalDate.of(2026, 6, 25);
    AddResult r = runPyramidAdd(new BigDecimal("10000"), session.plusDays(1), session);

    assertThat(r.run().entries()).as("a future lot must not be treated as an as-of pyramid position").isZero();
    verify(r.events(), never()).publishEvent(argThat((Object e) -> e instanceof SignalEmitted));
  }

  @Test
  void oneFunnelSnapshotReachesTheEngineWithoutASecondFunnelRead() throws IOException {
    ExitHarness h = new ExitHarness();
    ManasDoctrine doctrine = h.doctrine(false);
    when(h.funnel.snapshot())
        .thenReturn(Optional.of(new ManasFunnelClient.Snapshot(LocalDate.of(2026, 6, 25), List.of())));

    Optional<SwingDoctrine.CandidateSnapshot> snapshot = doctrine.candidateSnapshot().snapshot();
    assertThat(snapshot).isPresent();

    h.engine().runDaily(doctrine, LocalDate.of(2026, 6, 25), true, snapshot, true);

    verify(h.funnel).snapshot();
    verify(h.funnel, never()).buyableAndOnDeck();
    verify(h.funnel, never()).screenDate();
  }

  private record AddResult(
      SwingBatchEngine.SwingRun run,
      SignalRepository signals,
      ApplicationEventPublisher events,
      EmissionGuard guard) {}

  private AddResult runPyramidAdd(BigDecimal existingOpenRiskInr) throws IOException {
    return runPyramidAdd(existingOpenRiskInr, null, null);
  }

  private AddResult runPyramidAdd(
      BigDecimal existingOpenRiskInr, LocalDate anchorDate, LocalDate requiredBarDate) throws IOException {
    UUID strategyId = UUID.randomUUID();
    UUID publishedVersion = UUID.randomUUID();
    JsonNode config = breakoutConfig();
    StrategyRepository registry = mock(StrategyRepository.class);
    when(registry.listAll()).thenReturn(List.of(strategyRow(strategyId, publishedVersion)));
    when(registry.findVersionById(publishedVersion))
        .thenReturn(Optional.of(version(publishedVersion, strategyId, config)));

    List<EngineCandle> series = craft(3_000L);
    SignalRepository signals = mock(SignalRepository.class);
    OffsetDateTime anchorAt =
        anchorDate == null ? series.get(19).bucketStart() : anchorDate.atStartOfDay().atOffset(IST);
    when(signals.activeEntries())
        .thenReturn(List.of(anchor(42L, publishedVersion, new BigDecimal("142"), anchorAt)));
    stubInsert(signals, 55L);

    ManasFunnelClient funnel = mock(ManasFunnelClient.class);
    when(funnel.buyableAndOnDeck())
        .thenReturn(
            List.of(new ManasFunnelClient.Candidate("TESTCO", new BigDecimal("152"), new BigDecimal("150"), "breakout", null, new BigDecimal("150"), null, false)));
    MarketDataCandlesClient candles = mock(MarketDataCandlesClient.class);
    when(candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any())).thenReturn(series);

    EmissionGuard guard = mock(EmissionGuard.class);
    when(guard.entryAllowed(Books.MANAS_ARORA)).thenReturn(true);
    when(guard.bookEquity(Books.MANAS_ARORA)).thenReturn(new BigDecimal("1000000"));
    when(guard.openRiskInr(Books.MANAS_ARORA)).thenReturn(existingOpenRiskInr);
    when(guard.suggestedQty(any(), any(), any(), any(), any(), any())).thenReturn(new BigDecimal("100"));

    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    SwingBatchEngine engine =
        new SwingBatchEngine(
            registry, candles, signals, mock(SignalPublisher.class), events, Optional.of(guard),
            passthroughTx(), new ObjectMapper(), Clock.systemUTC());
    ManasDoctrine doctrine =
        new ManasDoctrine(
            funnel, signals, new ManasPyramidPolicy(true, new BigDecimal("5.0"), 3, new BigDecimal("6.0")),
            new ObjectMapper(), true, 520, 10, 1440);

    return new AddResult(engine.runDaily(doctrine, requiredBarDate), signals, events, guard);
  }

  private record FreshResult(
      SwingBatchEngine.SwingRun run,
      SignalRepository signals,
      ApplicationEventPublisher events,
      EmissionGuard guard) {}

  /**
   * Runs the entry pass for a NOT-held candidate ("NEWCO") against a stubbed pre-existing aggregate
   * open risk — the M40 fresh-entry counterpart of {@link #runPyramidAdd}. Pyramiding is DISABLED
   * (enabled=false) throughout: the fresh-entry risk-cap check must fire regardless of that flag.
   */
  private FreshResult runFreshEntry(BigDecimal existingOpenRiskInr) throws IOException {
    UUID strategyId = UUID.randomUUID();
    UUID publishedVersion = UUID.randomUUID();
    JsonNode config = breakoutConfig();
    StrategyRepository registry = mock(StrategyRepository.class);
    when(registry.listAll()).thenReturn(List.of(strategyRow(strategyId, publishedVersion)));
    when(registry.findVersionById(publishedVersion))
        .thenReturn(Optional.of(version(publishedVersion, strategyId, config)));

    List<EngineCandle> series = craft(3_000L);
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.activeEntries()).thenReturn(List.of()); // NEWCO is not held — a fresh (first) entry
    stubInsert(signals, 55L);

    ManasFunnelClient funnel = mock(ManasFunnelClient.class);
    when(funnel.buyableAndOnDeck())
        .thenReturn(
            List.of(
                new ManasFunnelClient.Candidate(
                    "NEWCO", new BigDecimal("152"), new BigDecimal("150"), "breakout", null,
                    new BigDecimal("150"), null, false)));
    MarketDataCandlesClient candles = mock(MarketDataCandlesClient.class);
    when(candles.fetch(eq("NSE"), eq("NEWCO"), eq("1d"), any(), any())).thenReturn(series);

    EmissionGuard guard = mock(EmissionGuard.class);
    when(guard.entryAllowed(Books.MANAS_ARORA)).thenReturn(true);
    when(guard.bookEquity(Books.MANAS_ARORA)).thenReturn(new BigDecimal("1000000"));
    when(guard.openRiskInr(Books.MANAS_ARORA)).thenReturn(existingOpenRiskInr);
    when(guard.suggestedQty(any(), any(), any(), any(), any(), any())).thenReturn(new BigDecimal("100"));

    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    SwingBatchEngine engine =
        new SwingBatchEngine(
            registry, candles, signals, mock(SignalPublisher.class), events, Optional.of(guard),
            passthroughTx(), new ObjectMapper(), Clock.systemUTC());
    ManasDoctrine doctrine =
        new ManasDoctrine(
            funnel, signals, new ManasPyramidPolicy(false, new BigDecimal("5.0"), 3, new BigDecimal("6.0")),
            new ObjectMapper(), true, 520, 10, 1440);

    return new FreshResult(engine.runDaily(doctrine), signals, events, guard);
  }

  // ---- harness --------------------------------------------------------------------------------

  private final class ExitHarness {
    final StrategyRepository registry = mock(StrategyRepository.class);
    final SignalRepository signals = mock(SignalRepository.class);
    final ManasFunnelClient funnel = mock(ManasFunnelClient.class);
    final MarketDataCandlesClient candles = mock(MarketDataCandlesClient.class);
    final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    final List<EngineCandle> series = craftDecline();
    final UUID publishedVersion = UUID.randomUUID();

    ExitHarness() throws IOException {
      UUID strategyId = UUID.randomUUID();
      JsonNode config = breakoutConfig();
      when(registry.listAll()).thenReturn(List.of(strategyRow(strategyId, publishedVersion)));
      when(registry.findVersionById(publishedVersion))
          .thenReturn(Optional.of(version(publishedVersion, strategyId, config)));
      stubAnchors(anchor(42L, series.get(0).bucketStart()));
      stubInsert(signals, 43L);
      when(funnel.buyableAndOnDeck()).thenReturn(List.of());
    }

    void stubAnchors(SignalRepository.SignalRow... anchors) {
      when(signals.activeEntries()).thenReturn(List.of(anchors));
    }

    SignalRepository.SignalRow anchor(long id, OffsetDateTime at) {
      return ManasAroraSwingEngineTest.anchor(id, publishedVersion, new BigDecimal("152"), at);
    }

    SwingBatchEngine engine() {
      return new SwingBatchEngine(
          registry, candles, signals, mock(SignalPublisher.class), events, Optional.empty(),
          passthroughTx(), new ObjectMapper(), Clock.systemUTC());
    }

    ManasDoctrine doctrine(boolean pyramidEnabled) {
      return new ManasDoctrine(
          funnel, signals,
          new ManasPyramidPolicy(pyramidEnabled, new BigDecimal("5.0"), 3, new BigDecimal("6.0")),
          new ObjectMapper(), true, 520, 60, 1440);
    }
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
        strategyId, "manas-arora-breakout", "Manas Breakout", null, null, List.of("manas-arora"),
        true, publishedVersion, null, null, false, null);
  }

  private static StrategyRepository.VersionRow version(UUID id, UUID strategyId, JsonNode config) {
    return new StrategyRepository.VersionRow(
        id, strategyId, "1", null, config, "1", "chk", "published", null, null, null, null);
  }

  private static SignalRepository.SignalRow anchor(
      long id, UUID versionId, BigDecimal entryPrice, OffsetDateTime at) {
    return new SignalRepository.SignalRow(
        id, versionId, "NSE", "TESTCO", "1d", "ENTRY", "BUY", entryPrice, null, null, BigDecimal.ONE,
        new ObjectMapper().createObjectNode(), "TAKEN", at, at.plusDays(1), null, null, null, null, null, null, null);
  }

  private static JsonNode breakoutConfig() throws IOException {
    try (InputStream in =
        ManasAroraSwingEngineTest.class.getResourceAsStream(
            "/manas-arora-strategies/manas-arora-breakout.yaml")) {
      assertThat(in).isNotNull();
      return StrategyDocuments.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)).config();
    }
  }

  private static List<EngineCandle> craftDecline() {
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d <= 25; d++) {
      bars.add(bar(d, 150.0));
    }
    bars.add(bar(26, 140.0));
    bars.add(bar(27, 120.0));
    return bars;
  }

  /**
   * Entry at ₹152 (day 0), flat through day 19 (ATR(20) warmup), then a gradual, controlled rise to
   * ~₹170 by day 35 (+11.8% off entry) — well past the §3.5B +9% arm threshold, but slow enough
   * (~1.1/day) to stay under BOTH square-off triggers (35% in ≤3 sessions; 40% over the 10-day MA) and
   * leave the armed Chandelier trail (peak − 2×rolling-ATR) comfortably below the current close (no
   * trailing_stop fire either). Used by {@code anArmedTrailRatchetsTheGoverningStopWithoutFiringAnExit}.
   */
  private static List<EngineCandle> craftArmedTrail() {
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d <= 19; d++) {
      bars.add(bar(d, 152.0));
    }
    for (int d = 20; d <= 35; d++) {
      bars.add(bar(d, 152.0 + 1.125 * (d - 19)));
    }
    return bars;
  }

  private static EngineCandle bar(int day, double price) {
    OffsetDateTime bucket = OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, IST).plusDays(day);
    BigDecimal c = BigDecimal.valueOf(price);
    return new EngineCandle(bucket, c, BigDecimal.valueOf(price + 1), BigDecimal.valueOf(price - 1), c, 1_000L, null);
  }

  private static List<EngineCandle> craft(long breakoutVolume) {
    double[] tail = {146, 148, 146, 148, 147};
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d <= 18; d++) {
      bars.add(volBar(d, 100.0 + (149.0 - 100.0) * d / 18.0, 1_000L));
    }
    for (int i = 0; i < tail.length; i++) {
      bars.add(volBar(19 + i, tail[i], 1_000L));
    }
    bars.add(volBar(24, 152.0, breakoutVolume));
    return bars;
  }

  private static EngineCandle volBar(int day, double price, long volume) {
    OffsetDateTime bucket = OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, IST).plusDays(day);
    BigDecimal p = BigDecimal.valueOf(price);
    return new EngineCandle(bucket, p, p, p, p, volume, null);
  }

  private static BigDecimal bd(long v) {
    return BigDecimal.valueOf(v);
  }
}
