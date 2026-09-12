package in.arthayantra.strategysignal.swing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyschema.StrategyDocuments;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import in.arthayantra.strategysignal.signals.EmissionGuard;
import in.arthayantra.strategysignal.signals.MarketDataCandlesClient;
import in.arthayantra.strategysignal.signals.SignalPublisher;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.signals.SwingPaperEffectRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ledger N7 — when two lots on one symbol carry DIFFERENT exit rules, the younger lot is exited by
 * the older lot's rules, and until now nothing said so.
 *
 * <p><b>The mechanism, which this test does not change.</b> {@code openLotsBySymbol} groups a
 * family's open anchors by {@code tradingsymbol} ALONE. {@code exitPass} drives the whole group off
 * {@code oldestLot(lots)} — one lot's definition decides, the shared paper position closes, and every
 * lot in the group is expired. {@link SwingLotVersionExitDivergenceTest} pins the DETECTOR that makes
 * the harmful case audible; re-keying the lot map is an exit-doctrine change and an owner decision.
 *
 * <p><b>Why the VERSION axis and not the strategy axis.</b> {@link SwingFamilyExitDoctrineTest}
 * already covers two strategies disagreeing at one instant, and its own javadoc records what it
 * cannot reach: two lots on different {@code strategy_version_id}s of the SAME strategy diverge
 * identically, and editing every family member together keeps that test green while creating exactly
 * that divergence across a republish. It says the gap "needs a live-data check, not a unit test".
 * This is that check, exercised through {@code runDaily} rather than by calling the detector
 * directly — a test that invoked the private helper would pass with the call site deleted.
 *
 * <p><b>The scenario is the one that javadoc predicts, run end to end:</b> lot 1 opens under v1
 * ({@code arm_pct 9}), the owner tunes the trail to 6, the seeder auto-publishes v2, lot 2 adds under
 * v2, and {@code oldestLot} hands the exit to v1. Both lots then exit on the 9% arm.
 *
 * <p><b>What a green run here does NOT prove.</b> The collapse needs two lots on one held symbol,
 * which production cannot produce today (Minervini {@code pyramid()} is {@code NONE} at compile time;
 * Manas's is gated behind {@code artha.manas-arora.pyramid.enabled}, measured false). This fixture
 * manufactures the second lot. So these tests pin the detector's LOGIC, not that the shape has ever
 * occurred live.
 */
class SwingLotVersionExitDivergenceTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final String SYM = "TESTCO";
  private static final String BATCH = "n7-batch";
  private static final BigDecimal ENTRY = new BigDecimal("152");
  private static final String COUNTER = "ay_swing_lot_exit_rule_divergence_total";

  @Test
  void twoLotsWhoseVersionsDisagreeOnExitRulesAreReported() {
    Harness h = new Harness();
    h.secondLotOnVersion(h.divergentVersion());

    h.engine().runDaily(h.doctrine, null, false);

    assertThat(h.counter(COUNTER))
        .as("the younger lot is being exited by the older lot's rules — that must not be silent")
        .isEqualTo(1.0);
  }

  @Test
  void theDivergentCaseStillExitsExactlyAsItDidBefore() {
    // The detector may not become a gate. Asserted separately from the counter because AssertJ stops
    // at the first failure: folded into one method, a broken exit count would never be demonstrated.
    Harness h = new Harness();
    h.secondLotOnVersion(h.divergentVersion());

    SwingBatchEngine.SwingRun run = h.engine().runDaily(h.doctrine, null, false);

    assertThat(run.exits())
        .as("observability only — the collapse keeps its current behaviour")
        .isEqualTo(1);
  }

  @Test
  void twoLotsOnDifferentVersionsThatAgreeOnExitRulesAreNotReported() {
    // The false-alarm guard, and the reason the detector compares exitRules rather than the version
    // CHECKSUM. This second version differs from the first in a NON-exit field, so a checksum-based
    // detector would fire here — on a divergence the collapse cannot harm.
    Harness h = new Harness();
    h.secondLotOnVersion(h.sameExitRulesDifferentElsewhereVersion());

    h.engine().runDaily(h.doctrine, null, false);

    assertThat(h.counter(COUNTER))
        .as("identical exit rules are exactly the case the collapse is harmless in")
        .isZero();
  }

  @Test
  void singleLotIsNeverReported() {
    // Today's production shape, on every symbol, every night. A detector that fired here would page
    // nightly on healthy books and be muted within a week.
    Harness h = new Harness();

    h.engine().runDaily(h.doctrine, null, false);

    assertThat(h.counter(COUNTER)).isZero();
  }

  // ---- harness -------------------------------------------------------------------------------

  private final class Harness {
    final StrategyRepository registry = mock(StrategyRepository.class);
    final SignalRepository signals = mock(SignalRepository.class);
    final MarketDataCandlesClient candles = mock(MarketDataCandlesClient.class);
    final SignalPublisher publisher = mock(SignalPublisher.class);
    final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    final EmissionGuard guard = mock(EmissionGuard.class);
    final SwingPaperEffectRepository paperEffects = mock(SwingPaperEffectRepository.class);
    final SwingBatchRefusalRepository refusals = mock(SwingBatchRefusalRepository.class);
    final SwingDoctrine doctrine = mock(SwingDoctrine.class);
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final List<EngineCandle> series = decliningSeriesThatStopsOut();
    final UUID strategyId = UUID.randomUUID();
    final UUID publishedVersion = UUID.randomUUID();
    final List<SignalRepository.SignalRow> anchors = new ArrayList<>();
    final Clock clock;

    Harness() {
      this.clock =
          Clock.fixed(
              series.get(series.size() - 1).bucketStart().plusHours(18).toInstant(),
              ZoneOffset.UTC);
      JsonNode config = swingConfig();
      StrategyRepository.StrategyRow strategy = strategyRow(strategyId, publishedVersion);
      when(registry.listAll()).thenReturn(List.of(strategy));
      when(registry.findById(strategyId)).thenReturn(Optional.of(strategy));
      when(registry.findVersionById(publishedVersion))
          .thenReturn(Optional.of(version(publishedVersion, strategyId, config)));
      // Lot 1 — the OLDEST, therefore the governing lot. Bar 24, as in the sibling H9 fixture.
      anchors.add(anchor(42L, publishedVersion, series.get(24).bucketStart()));
      when(signals.activeEntries()).thenAnswer(i -> List.copyOf(anchors));
      when(signals.insert(
              any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
              any(), any()))
          .thenReturn(43L);
      when(candles.fetch(any(), any(), any(), any(), any())).thenReturn(series);
      when(paperEffects.openPositionIdsForSignals(any())).thenReturn(List.of(7L));
      when(paperEffects.expectExit(any(), any(), anyLong(), any(), any(), any())).thenReturn(true);
      when(doctrine.enabled()).thenReturn(true);
      when(doctrine.batchName()).thenReturn(BATCH);
      when(doctrine.alertLabel()).thenReturn("N7 Test");
      when(doctrine.book()).thenReturn("n7-book");
      when(doctrine.universeMode()).thenReturn("manas_arora_funnel");
      when(doctrine.warmupDays()).thenReturn(520);
      when(doctrine.ttlMinutes()).thenReturn(1440L);
      when(doctrine.neutralContextSeeds())
          .thenReturn(Map.of("MANAS_BREAKOUT_PIVOT", BigDecimal.ZERO));
    }

    /**
     * Adds a YOUNGER second lot on the same symbol under {@code versionId}. Younger by one bar so
     * {@code oldestLot} still hands the exit to lot 1 — the whole point of the scenario.
     */
    void secondLotOnVersion(UUID versionId) {
      anchors.add(anchor(44L, versionId, series.get(25).bucketStart()));
    }

    /** A superseded version whose trailing stop arms at 6% instead of 9% — the javadoc's scenario. */
    UUID divergentVersion() {
      ObjectNode config = (ObjectNode) swingConfig();
      ObjectNode trailing = trailingStopRule(config);
      ((ObjectNode) trailing.get("params")).put("arm_pct", 6);
      return registerVersion(config);
    }

    /**
     * A superseded version with IDENTICAL exit rules that differs elsewhere — so its version checksum
     * would differ while the collapse stays harmless.
     */
    UUID sameExitRulesDifferentElsewhereVersion() {
      ObjectNode config = (ObjectNode) swingConfig();
      ((ObjectNode) config.path("risk")).put("max_positions", 9);
      return registerVersion(config);
    }

    private ObjectNode trailingStopRule(ObjectNode config) {
      for (JsonNode rule : config.path("exit_rules")) {
        if ("trailing_stop".equals(rule.path("type").asText())) {
          return (ObjectNode) rule;
        }
      }
      throw new AssertionError("fixture has no trailing_stop rule — the scenario cannot be built");
    }

    private UUID registerVersion(JsonNode config) {
      UUID id = UUID.randomUUID();
      when(registry.findVersionById(id))
          .thenReturn(Optional.of(version(id, strategyId, config)));
      return id;
    }

    SwingBatchEngine engine() {
      return new SwingBatchEngine(
          registry, candles, signals, publisher, events, Optional.of(guard), passthroughTx(),
          new ObjectMapper(), clock, paperEffects, refusals, "OBSERVE_ONLY", null, meters);
    }

    /** Absent (never incremented) reads 0, which is what a caller means by "not reported". */
    double counter(String name) {
      Counter c = meters.find(name).tag("batch", BATCH).counter();
      return c == null ? 0.0 : c.count();
    }
  }

  // ---- fixtures ------------------------------------------------------------------------------

  /** Same shape as the sibling H9 fixture: flat at 150, then 140, 120 — the stop fires. */
  private static List<EngineCandle> decliningSeriesThatStopsOut() {
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
    return new EngineCandle(
        bucket, c, BigDecimal.valueOf(price + 1), BigDecimal.valueOf(price - 1), c, 1_000L, null);
  }

  private static TransactionTemplate passthroughTx() {
    TransactionTemplate tx = mock(TransactionTemplate.class);
    when(tx.execute(any()))
        .thenAnswer(inv -> inv.<TransactionCallback<Long>>getArgument(0).doInTransaction(null));
    return tx;
  }

  private static StrategyRepository.StrategyRow strategyRow(UUID strategyId, UUID published) {
    return new StrategyRepository.StrategyRow(
        strategyId, "n7-swing", "N7 Swing", null, null, List.of("manas-arora"), true, published,
        null, null, false, null);
  }

  private static StrategyRepository.VersionRow version(UUID id, UUID strategyId, JsonNode config) {
    return new StrategyRepository.VersionRow(
        id, strategyId, "1", null, config, "1", "chk-" + id, "published", null, null, null, null);
  }

  private static SignalRepository.SignalRow anchor(long id, UUID versionId, OffsetDateTime at) {
    return new SignalRepository.SignalRow(
        id, versionId, "NSE", SYM, "1d", "ENTRY", "BUY", ENTRY, null, null, BigDecimal.ONE,
        new ObjectMapper().createObjectNode(), "TAKEN", at, at.plusDays(1), null, null, null, null,
        null, null, null);
  }

  private static JsonNode swingConfig() {
    try (InputStream in =
        SwingLotVersionExitDivergenceTest.class.getResourceAsStream(
            "/manas-arora-strategies/manas-arora-breakout.yaml")) {
      assertThat(in).isNotNull();
      return StrategyDocuments.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)).config();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
