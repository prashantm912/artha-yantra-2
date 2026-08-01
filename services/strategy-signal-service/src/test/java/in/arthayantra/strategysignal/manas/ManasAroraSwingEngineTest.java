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
    // Add-path observability fix only (E4 §2f — NOT the whole of M40; the aggregate-risk gap on
    // FRESH entries is separate, live, and unfixed here — see
    // docs/signal-analysis/2026-08-02-m40-fresh-entry-risk-cap-gap.md). Three of RiskService's four
    // audited rails (daily-loss/profit-target/heat-cap) write a risk_audit row + push an ntfy alert on
    // trip; deployment audits only. Before this fix the pyramid risk-cap block matched neither group —
    // this proves it now reaches the same EmissionGuard governor surface (RiskServicePyramidCapTest
    // proves what the paper adapter does with the call from there: audits + alerts, deduped per IST
    // day, matching the audit+alert group).
    verify(r.guard()).recordPyramidRiskCapBreach(eq(Books.MANAS_ARORA), eq("TESTCO"), any());
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
