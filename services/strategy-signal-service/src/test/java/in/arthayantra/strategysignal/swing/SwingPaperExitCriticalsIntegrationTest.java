package in.arthayantra.strategysignal.swing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.eval.ExitEvaluator;
import in.arthayantra.strategyengine.eval.IndicatorBank;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategysignal.manas.ManasDoctrine;
import in.arthayantra.strategysignal.manas.ManasFunnelClient;
import in.arthayantra.strategysignal.manas.ManasPyramidPolicy;
import in.arthayantra.strategysignal.paper.EngineExitListener;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.paper.PaperPositionRepository;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import in.arthayantra.strategysignal.paper.PaperService;
import in.arthayantra.strategysignal.paper.PaperSignalListener;
import in.arthayantra.strategysignal.registry.RegistryService;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import in.arthayantra.strategysignal.signals.Books;
import in.arthayantra.strategysignal.signals.EmissionGuard;
import in.arthayantra.strategysignal.signals.MarketDataCandlesClient;
import in.arthayantra.strategysignal.signals.SignalPublisher;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.signals.SignalTaken;
import in.arthayantra.strategysignal.signals.SwingBatchRunRepository;
import in.arthayantra.strategysignal.signals.SwingPaperEffectRepository;
import in.arthayantra.strategysignal.signals.SwingPaperEffectRetry;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Permanent regression coverage for the round-4 swing paper-exit criticals. */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.signals.engine-enabled=false",
      "artha.manas-arora.pyramid.enabled=false"
    })
class SwingPaperExitCriticalsIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final LocalDate SESSION = LocalDate.of(2026, 7, 24);
  private static final LocalDate CATCHUP_TODAY = LocalDate.of(2026, 7, 27);

  @TestConfiguration
  static class Stubs {
    @Bean
    @Primary
    InstrumentMetaClient stubMeta() {
      return (exchange, tradingsymbol) ->
          new InstrumentMeta(InstrumentClass.EQUITY, new BigDecimal("0.05"), 1);
    }

    @Bean
    @Primary
    MarketDataCandlesClient stubCandles() {
      return mock(MarketDataCandlesClient.class);
    }

    @Bean
    @Primary
    EmissionGuard stubGuard() {
      return mock(EmissionGuard.class);
    }
  }

  @Autowired private RegistryService registry;
  @Autowired private StrategyRepository strategyRepo;
  @Autowired private SignalRepository signals;
  @Autowired private PaperService paper;
  @Autowired private PaperSignalListener paperListener;
  @Autowired private PaperPositionRepository positions;
  @Autowired private EngineExitListener exitListener;
  @Autowired private SwingPaperEffectRepository effects;
  @Autowired private SwingCatchUpStateRepository catchupState;
  @Autowired private SwingBatchRunRepository runs;
  @Autowired private SwingBatchIntentRepository intents;
  @Autowired private SwingRunMutex mutex;
  @Autowired private SwingBatchRefusalRepository refusals;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private MarketDataCandlesClient candles;

  @Test
  void crashBeforeListenerDeliveryRemainsRetryableAndExactReplayClosesThePosition() throws Exception {
    String uid = uniqueId();
    String batch = "r4-crash-" + uid;
    String symbol = "R4CR" + uid;
    UUID versionId = publishManasBreakout(uid);
    List<EngineCandle> series = craftDecline(SESSION);
    long anchor = insertEntry(versionId, symbol, series.get(24).bucketStart(), new BigDecimal("152"));
    PositionRow position = openPosition(anchor, symbol, new BigDecimal("152"));
    SwingDoctrine doctrine = doctrine(batch);
    ApplicationEventPublisher droppedEvents = mock(ApplicationEventPublisher.class);
    when(candles.fetch(eq("NSE"), eq(symbol), eq("1d"), any(), any())).thenReturn(series);

    assertThat(signals.activeEntries()).anySatisfy(row -> assertThat(row.id()).isEqualTo(anchor));
    SwingBatchEngine.SwingRun run = runExit(doctrine, droppedEvents);
    SwingPaperEffectRepository.Effect effect = effects.findCloseByAnchor(anchor).orElseThrow();
    long exitSignal = effect.exitSignalId();

    assertThat(run.exits()).as("the real engine emitted the exit").isEqualTo(1);
    assertThat(effect.decision()).isEqualTo("REQUIRED");
    assertThat(effect.targetPositionIds()).containsExactly(position.id());
    assertThat(positions.find(position.id()).orElseThrow().status())
        .as("the dropped listener leaves the position recoverable, not silently closed")
        .isEqualTo("OPEN");

    runCatchup(doctrine, droppedEvents);
    assertThat(catchupStatus(batch, SESSION)).isEqualTo("PENDING");
    assertThat(catchupReason(batch, SESSION)).isEqualTo("PAPER_EFFECT_UNCONFIRMED");
    assertThat(effects.allConfirmed(batch, SESSION)).isFalse();

    exitListener.onSwingPaperEffectRetry(
        SwingPaperEffectRetry.exit(batch, SESSION, anchor, exitSignal, effect.closeReason(), effect.closePrice()));

    assertThat(positions.find(position.id()).orElseThrow().status()).isEqualTo("CLOSED");
    assertThat(effects.findCloseByAnchor(anchor).orElseThrow().status()).isEqualTo("CONFIRMED");
    assertThat(effects.allConfirmed(batch, SESSION)).isTrue();

    runCatchup(doctrine, droppedEvents);
    assertThat(catchupStatus(batch, SESSION))
        .as("a deterministic retry reaches DONE only after the exact position is closed")
        .isEqualTo("DONE");
  }

  @Test
  void bindingIdsBeforeRequiredDecisionRefusesCloseAndLeavesCatchupPending() throws Exception {
    String uid = uniqueId();
    String batch = "r4-bind-" + uid;
    String symbol = "R4BD" + uid;
    UUID versionId = publishManasBreakout(uid);
    long anchor = insertEntry(versionId, symbol, sessionAt(SESSION.minusDays(1)), new BigDecimal("150"));
    PositionRow position = openPosition(anchor, symbol, new BigDecimal("150"));
    long exitSignal = insertExit(versionId, symbol, sessionAt(SESSION), new BigDecimal("120"));

    assertThat(effects.expectExit(batch, SESSION, anchor, "STOP_LOSS", new BigDecimal("120"))).isTrue();
    SwingPaperEffectRepository.Effect beforeBinding = effects.findCloseByAnchor(anchor).orElseThrow();
    assertThat(effects.bindExitPositionIds(beforeBinding.id(), List.of(position.id()))).isTrue();
    effects.bindExit(batch, SESSION, anchor, exitSignal);

    exitListener.onSwingPaperEffectRetry(
        SwingPaperEffectRetry.exit(batch, SESSION, anchor, exitSignal, "STOP_LOSS", new BigDecimal("120")));
    runCatchup(doctrine(batch), mock(ApplicationEventPublisher.class));

    SwingPaperEffectRepository.Effect unresolved = effects.findCloseByAnchor(anchor).orElseThrow();
    assertThat(unresolved.targetPositionIds()).containsExactly(position.id());
    assertThat(unresolved.decision())
        .as("a crash between ID binding and REQUIRED must not grant close permission")
        .isEqualTo("UNDECIDED");
    assertThat(positions.find(position.id()).orElseThrow().status()).isEqualTo("OPEN");
    assertThat(catchupStatus(batch, SESSION)).isEqualTo("PENDING");
    assertThat(catchupReason(batch, SESSION)).isEqualTo("PAPER_EFFECT_UNCONFIRMED");
    assertThat(effects.allConfirmed(batch, SESSION)).isFalse();
  }

  /**
   * The observable half of the round-4 TOCTOU closure: once the swing EXIT settles an anchor
   * (EXPIRED), a late paper open for that anchor is REFUSED. Under the shared per-anchor advisory
   * lock the open that loses the race sees exactly this committed state — refusing it is what
   * prevents the permanently orphaned position the review traced (open lands between discovery and
   * commit, effect persists as stale-empty SKIPPED, nothing ever closes the position).
   */
  @Test
  void aPaperOpenForAnExpiredAnchorIsRefused() throws Exception {
    String uid = uniqueId();
    String symbol = "R4EX" + uid;
    UUID versionId = publishManasBreakout(uid);
    long anchor =
        insertEntry(
            versionId, symbol, SESSION.atStartOfDay(IST).toOffsetDateTime(), new BigDecimal("152"));
    jdbc.update("UPDATE signals SET status = 'EXPIRED' WHERE id = ?", anchor);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> openPosition(anchor, symbol, new BigDecimal("152")))
        .hasMessageContaining("EXPIRED");
    assertThat(positions.findOpen(Books.MANAS_ARORA, "NSE", symbol, "BUY"))
        .as("no position row may exist for a settled anchor")
        .isEmpty();
  }

  @Test
  void laterPyramidLotOwnsSharedRowAndUnionExitClosesBeforeCatchupCanBeDone() throws Exception {
    String uid = uniqueId();
    String batch = "r4-pyramid-" + uid;
    String symbol = "R4PY" + uid;
    UUID versionId = publishManasBreakout(uid);
    List<EngineCandle> series = craftDecline(SESSION);
    long firstLot = insertEntry(versionId, symbol, series.get(24).bucketStart(), new BigDecimal("152"));
    long laterLot = insertEntry(versionId, symbol, series.get(25).bucketStart(), new BigDecimal("160"));

    assertThat(effects.expectEntry(batch, SESSION.minusDays(1), symbol)).isTrue();
    effects.bindEntry(batch, SESSION.minusDays(1), symbol, firstLot, 10);
    assertThat(effects.skipEntry(firstLot)).isTrue();

    assertThat(effects.expectEntry(batch, SESSION, symbol)).isTrue();
    effects.bindEntry(batch, SESSION, symbol, laterLot, 10);
    assertThat(effects.requireEntry(laterLot)).isTrue();
    paperListener.onSignalTaken(new SignalTaken(laterLot, 10, new BigDecimal("160")));

    PositionRow position = positions.findOpen(Books.MANAS_ARORA, "NSE", symbol, "BUY").orElseThrow();
    assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM paper_orders WHERE signal_id = ?", Integer.class, firstLot))
        .as("the first lot's auto-paper decision is SKIPPED")
        .isZero();
    assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM paper_orders WHERE signal_id = ?", Integer.class, laterLot))
        .isEqualTo(1);
    assertThat(effects.openPositionIdsForSignals(List.of(firstLot))).isEmpty();
    assertThat(effects.openPositionIdsForSignals(List.of(laterLot))).containsExactly(position.id());

    SwingDoctrine doctrine = doctrine(batch);
    ApplicationEventPublisher droppedEvents = mock(ApplicationEventPublisher.class);
    when(candles.fetch(eq("NSE"), eq(symbol), eq("1d"), any(), any())).thenReturn(series);
    assertThat(signals.activeEntries())
        .extracting(SignalRepository.SignalRow::id)
        .contains(firstLot, laterLot);
    SwingBatchEngine.SwingRun run = runExit(doctrine, droppedEvents);
    SwingPaperEffectRepository.Effect effect = effects.findCloseByAnchor(firstLot).orElseThrow();

    assertThat(run.exits()).isEqualTo(1);
    assertThat(effect.targetPositionIds())
        .as("the exit binds the union across every exited lot, including a row owned only by the add")
        .containsExactlyElementsOf(effects.openPositionIdsForSignals(List.of(firstLot, laterLot)));
    assertThat(effect.targetPositionIds()).containsExactly(position.id());
    runCatchup(doctrine, droppedEvents);
    assertThat(catchupStatus(batch, SESSION))
        .as("an open shared row cannot make the session DONE")
        .isEqualTo("PENDING");
    assertThat(positions.find(position.id()).orElseThrow().status()).isEqualTo("OPEN");

    long exitSignal = effect.exitSignalId();
    exitListener.onSwingPaperEffectRetry(
        SwingPaperEffectRetry.exit(batch, SESSION, firstLot, exitSignal, effect.closeReason(), effect.closePrice()));
    assertThat(positions.find(position.id()).orElseThrow().status()).isEqualTo("CLOSED");
    assertThat(effects.findCloseByAnchor(firstLot).orElseThrow().status()).isEqualTo("CONFIRMED");
  }

  private SwingBatchEngine.SwingRun runExit(
      SwingDoctrine doctrine, ApplicationEventPublisher droppedEvents) {
    SwingBatchEngine engine =
        new SwingBatchEngine(
            strategyRepo,
            candles,
            signals,
            mock(SignalPublisher.class),
            droppedEvents,
            Optional.empty(),
            new TransactionTemplate(transactionManager),
            objectMapper,
            Clock.fixed(sessionAt(SESSION).toInstant(), IST),
            effects,
            refusals);
    return engine.runDaily(doctrine, SESSION, false);
  }

  private void runCatchup(SwingDoctrine doctrine, ApplicationEventPublisher droppedEvents) {
    String batch = doctrine.batchName();
    runs.record(batch, SESSION.minusDays(1), 0, 0, 0, 0, 0, 0, 0, 0, 0, false, List.of());
    intents.recordScheduled(batch, SESSION, true);
    SwingBatchRecorder recorder = mock(SwingBatchRecorder.class);
    SwingBatchEngine.SwingRun run =
        new SwingBatchEngine.SwingRun(1, 0, 0, 0, 0, SwingBatchEngine.AdmissionProbe.empty());
    when(recorder.runAndRecord(
            eq(doctrine),
            any(LocalDate.class),
            anyBoolean(),
            eq(SwingBatchRecorder.MarkerPolicy.ON_COMPLETE),
            any(),
            any()))
        .thenReturn(new SwingBatchRecorder.RunOutcome(run, true));
    Clock catchupClock =
        Clock.fixed(
            OffsetDateTime.of(CATCHUP_TODAY, java.time.LocalTime.of(8, 35), IST).toInstant(), IST);
    SwingBatchCatchUp catchup =
        new SwingBatchCatchUp(
            recorder,
            runs,
            catchupState,
            intents,
            effects,
            signals,
            mutex,
            List.of(doctrine),
            droppedEvents,
            catchupClock,
            true,
            5);
    catchup.catchUp();
  }

  private PositionRow openPosition(long signalId, String symbol, BigDecimal price) {
    paper.openOrder(new PaperService.OrderRequest(signalId, "NSE", symbol, "BUY", 10, price, null, null));
    return positions.findOpen(Books.MANAS_ARORA, "NSE", symbol, "BUY").orElseThrow();
  }

  private long insertEntry(UUID versionId, String symbol, OffsetDateTime generatedAt, BigDecimal price) {
    long id =
        signals.insert(
            versionId,
            "NSE",
            symbol,
            "1d",
            "ENTRY",
            "BUY",
            price,
            new BigDecimal("137"),
            new BigDecimal("170"),
            new BigDecimal("0.80"),
            "{}",
            generatedAt,
            generatedAt.plusDays(1),
            null);
    jdbc.update("UPDATE signals SET book = ? WHERE id = ?", Books.MANAS_ARORA, id);
    return id;
  }

  private long insertExit(UUID versionId, String symbol, OffsetDateTime generatedAt, BigDecimal price) {
    long id =
        signals.insert(
            versionId,
            "NSE",
            symbol,
            "1d",
            "EXIT",
            "SELL",
            price,
            null,
            null,
            new BigDecimal("0.80"),
            "{}",
            generatedAt,
            generatedAt.plusDays(1),
            "TRAILING_STOP");
    jdbc.update("UPDATE signals SET book = ? WHERE id = ?", Books.MANAS_ARORA, id);
    return id;
  }

  private SwingDoctrine doctrine(String batch) {
    ManasFunnelClient funnel = mock(ManasFunnelClient.class);
    when(funnel.snapshot())
        .thenReturn(Optional.of(new ManasFunnelClient.Snapshot(SESSION, List.of())));
    ManasDoctrine delegate =
        new ManasDoctrine(
            funnel,
            signals,
            new ManasPyramidPolicy(false, new BigDecimal("5.0"), 3, new BigDecimal("6.0")),
            objectMapper,
            true,
            520,
            10,
            1440);
    return new ScopedDoctrine(delegate, batch);
  }

  private String catchupStatus(String batch, LocalDate session) {
    return jdbc.queryForObject(
        "SELECT status FROM swing_catchup_runs WHERE batch = ? AND session_date = ?",
        String.class,
        batch,
        java.sql.Date.valueOf(session));
  }

  private String catchupReason(String batch, LocalDate session) {
    return jdbc.queryForObject(
        "SELECT reason FROM swing_catchup_runs WHERE batch = ? AND session_date = ?",
        String.class,
        batch,
        java.sql.Date.valueOf(session));
  }

  private UUID publishManasBreakout(String uid) throws Exception {
    String yaml;
    try (InputStream in =
        getClass().getResourceAsStream("/manas-arora-strategies/manas-arora-breakout.yaml")) {
      assertThat(in).isNotNull();
      yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    String config =
        yaml.replace("id: manas-arora-breakout", "id: r4-man" + uid.toLowerCase(Locale.ROOT));
    UUID strategyId =
        (UUID)
            registry
                .create(
                    "R4 Manas " + uid,
                    null,
                    List.of("manas-arora", "swing", "equity", "breakout"),
                    config)
                .get("id");
    registry.publish(strategyId, null, null);
    return strategyRepo.latestVersion(strategyId).orElseThrow().id();
  }

  private static String uniqueId() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
  }

  private static OffsetDateTime sessionAt(LocalDate date) {
    return date.atStartOfDay().atOffset(IST);
  }

  private static List<EngineCandle> craftDecline(LocalDate session) {
    List<EngineCandle> bars = new ArrayList<>();
    LocalDate first = session.minusDays(27);
    for (int day = 0; day <= 25; day++) {
      bars.add(bar(first.plusDays(day), 150.0));
    }
    bars.add(bar(first.plusDays(26), 140.0));
    bars.add(bar(first.plusDays(27), 120.0));
    return bars;
  }

  private static EngineCandle bar(LocalDate date, double price) {
    BigDecimal close = BigDecimal.valueOf(price);
    return new EngineCandle(
        sessionAt(date),
        close,
        close.add(BigDecimal.ONE),
        close.subtract(BigDecimal.ONE),
        close,
        1_000L,
        null);
  }

  /** Test-only batch namespace wrapper; it keeps catch-up rows unique in the singleton database. */
  private static final class ScopedDoctrine implements SwingDoctrine {
    private final SwingDoctrine delegate;
    private final String batch;

    private ScopedDoctrine(SwingDoctrine delegate, String batch) {
      this.delegate = delegate;
      this.batch = batch;
    }

    @Override
    public String book() {
      return delegate.book();
    }

    @Override
    public String universeMode() {
      return delegate.universeMode();
    }

    @Override
    public String batchName() {
      return batch;
    }

    @Override
    public String alertLabel() {
      return delegate.alertLabel();
    }

    @Override
    public boolean enabled() {
      return delegate.enabled();
    }

    @Override
    public int warmupDays() {
      return delegate.warmupDays();
    }

    @Override
    public int minBars() {
      return delegate.minBars();
    }

    @Override
    public long ttlMinutes() {
      return delegate.ttlMinutes();
    }

    @Override
    public String setupToken(JsonNode config, String slug) {
      return delegate.setupToken(config, slug);
    }

    @Override
    public List<SwingCandidate> candidates() {
      return delegate.candidates();
    }

    @Override
    public CandidateSnapshotRead candidateSnapshot() {
      return delegate.candidateSnapshot();
    }

    @Override
    public Optional<LocalDate> inputsAsOf() {
      return delegate.inputsAsOf();
    }

    @Override
    public Map<String, BigDecimal> contextSeedsFromDetail(JsonNode detail) {
      return delegate.contextSeedsFromDetail(detail);
    }

    @Override
    public Map<String, BigDecimal> neutralContextSeeds() {
      return delegate.neutralContextSeeds();
    }

    @Override
    public void stampDetail(long signalId, String detailJson) {
      delegate.stampDetail(signalId, detailJson);
    }

    @Override
    public JsonNode detailOf(SignalRepository.SignalRow anchor) {
      return delegate.detailOf(anchor);
    }

    @Override
    public PyramidPolicy pyramid() {
      return delegate.pyramid();
    }
    @Override
    public BigDecimal trailLevel(
        StrategyDefinition definition,
        IndicatorBank bank,
        ExitEvaluator.Position position,
        int lastIndex,
        int entryIndex) {
      return delegate.trailLevel(definition, bank, position, lastIndex, entryIndex);
    }
  }
}
