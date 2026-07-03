package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.paper.GraduationService.StrategyGraduation;
import in.arthayantra.strategysignal.registry.RegistryService;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The F7 graduation board (mock profile): a published+enabled strategy's CLOSED paper positions —
 * attributed via {@code paper_orders.signal_id → signals → strategy_versions} — aggregate into the
 * expected trades / net / win-rate / profit-factor / expectancy / drawdown and derive the stage.
 * Thresholds are lowered for the seed so a small set can reach {@code TAKE_ELIGIBLE}; a second
 * strategy with net-losing trades stays {@code PAPER}. MEASUREMENT ONLY — nothing is promoted.
 *
 * <p>Shared singleton IT DB with NO per-method cleanup: unique slug + name + symbol per method so
 * {@code RegistryService.create} never 409s on a duplicate and the graduation query only sees ours.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.signals.engine-enabled=false",
      "artha.graduation.min-trades=3",
      "artha.graduation.min-profit-factor=1.3",
      "artha.graduation.min-expectancy=0",
      "artha.graduation.max-drawdown-pct=25",
      "artha.graduation.base-capital=100000"
    })
class GraduationIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final String CONFIG =
      """
      schema: strategy-schema/v1
      id: grad-it
      name: "Grad IT"
      version: 1.0.0
      universe: { mode: explicit, instruments: [ { exchange: NSE, tradingsymbol: RELIANCE } ] }
      timeframes: { primary: 1m }
      indicators:
        - { name: RSI, alias: rsi_1m, timeframe: 1m, params: { period: 2 }, weight: 1.0,
            normalize: { type: step, bands: [ { score: 1.0 } ] } }
      entry_rules: { direction: long, gate: { all: [ "close > 1" ] }, scoring: { threshold: 0.05 } }
      exit_rules: [ { type: stop_loss, params: { basis: premium_pct, value: 50 } } ]
      risk: { position_sizing: { method: fixed_quantity, params: { quantity: 1 } }, max_positions: 1,
              session: { style: intraday } }
      """;

  @Autowired private GraduationService graduation;
  @Autowired private RegistryService registry;
  @Autowired private StrategyRepository strategyRepo;
  @Autowired private SignalRepository signalRepo;
  @Autowired private JdbcTemplate jdbc;

  /** Publishes a fresh strategy under a unique slug + name; returns its published version id. */
  private UUID publishStrategy(String tag) {
    String slug = tag + "-" + UUID.randomUUID().toString().substring(0, 8);
    UUID strategyId =
        (UUID)
            registry
                .create("Grad IT " + slug, null, List.of("it"), CONFIG.replace("id: grad-it", "id: " + slug))
                .get("id");
    registry.publish(strategyId, null, null);
    return strategyId;
  }

  /** Seeds one CLOSED paper position (with a signal-carrying order) for a strategy version. */
  private void seedClosedTrade(UUID versionId, BigDecimal realizedPnl, OffsetDateTime closedAt) {
    String sym = "GRADIT-" + UUID.randomUUID().toString().substring(0, 8);
    long signalId =
        signalRepo.insert(
            versionId, "NFO", sym, "1m", "ENTRY", "BUY",
            new BigDecimal("80.00"), new BigDecimal("40.00"), new BigDecimal("120.00"),
            new BigDecimal("0.80"), "{}", closedAt.minusMinutes(5), closedAt.plusHours(1));
    jdbc.update(
        "INSERT INTO paper_orders (signal_id, exchange, tradingsymbol, side, qty, status, placed_at)"
            + " VALUES (?, 'NFO', ?, 'BUY', 50, 'FILLED', ?)",
        signalId, sym, closedAt.minusMinutes(5));
    jdbc.update(
        "INSERT INTO paper_positions"
            + " (exchange, tradingsymbol, side, qty, avg_entry_price, realized_pnl, status, opened_at, closed_at)"
            + " VALUES ('NFO', ?, 'BUY', 50, 80.00, ?, 'CLOSED', ?, ?)",
        sym, realizedPnl, closedAt.minusMinutes(5), closedAt);
  }

  private StrategyGraduation rowFor(UUID strategyId) {
    return graduation.board().strategies().stream()
        .filter(g -> g.strategyId().equals(strategyId))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void computesMetricsAndDerivesTakeEligibleForProfitableStrategy() {
    UUID strategyId = publishStrategy("grad-take");
    UUID versionId = strategyRepo.latestVersion(strategyId).orElseThrow().id();

    OffsetDateTime t0 = OffsetDateTime.now(ZoneOffset.UTC).minusHours(4);
    // 3 wins (+100, +60, +80) and 1 loss (-40): net 200, PF = 240/40 = 6, expectancy 50, tiny DD.
    seedClosedTrade(versionId, new BigDecimal("100.00"), t0);
    seedClosedTrade(versionId, new BigDecimal("-40.00"), t0.plusMinutes(30));
    seedClosedTrade(versionId, new BigDecimal("60.00"), t0.plusMinutes(60));
    seedClosedTrade(versionId, new BigDecimal("80.00"), t0.plusMinutes(90));

    StrategyGraduation g = rowFor(strategyId);
    assertThat(g.trades()).isEqualTo(4);
    assertThat(g.netRealized()).isEqualByComparingTo("200");
    assertThat(g.winRate()).isEqualByComparingTo("0.75");
    assertThat(g.profitFactor()).isEqualByComparingTo("6");
    assertThat(g.expectancy()).isEqualByComparingTo("50");
    // only drawdown is the single -40 dip off the 100000+100 peak = 40/100100 = 0.03996%
    assertThat(g.maxDrawdownPct()).isEqualByComparingTo("0.04");
    assertThat(g.stage()).isEqualTo("TAKE_ELIGIBLE");
    assertThat(g.criteria()).extracting(GraduationService.Criterion::pass).containsOnly(true);
  }

  @Test
  void netLosingStrategyStaysInPaper() {
    UUID strategyId = publishStrategy("grad-paper");
    UUID versionId = strategyRepo.latestVersion(strategyId).orElseThrow().id();

    OffsetDateTime t0 = OffsetDateTime.now(ZoneOffset.UTC).minusHours(3);
    // net-negative: PF < 1 and expectancy < 0 both fail → PAPER.
    seedClosedTrade(versionId, new BigDecimal("50.00"), t0);
    seedClosedTrade(versionId, new BigDecimal("-120.00"), t0.plusMinutes(20));
    seedClosedTrade(versionId, new BigDecimal("-30.00"), t0.plusMinutes(40));

    StrategyGraduation g = rowFor(strategyId);
    assertThat(g.trades()).isEqualTo(3);
    assertThat(g.netRealized()).isEqualByComparingTo("-100");
    assertThat(g.profitFactor()).isEqualByComparingTo("0.3333"); // 50 / 150
    assertThat(g.expectancy()).isNegative();
    assertThat(g.stage()).isEqualTo("PAPER");
    assertThat(g.criteria()).extracting(GraduationService.Criterion::name).contains("profitFactor");
  }

  @Test
  void strategyWithNoClosedTradesReportsZeroesAndStaysPaper() {
    UUID strategyId = publishStrategy("grad-empty");
    StrategyGraduation g = rowFor(strategyId);
    assertThat(g.trades()).isZero();
    assertThat(g.netRealized()).isEqualByComparingTo("0");
    assertThat(g.profitFactor()).isNull();
    assertThat(g.stage()).isEqualTo("PAPER"); // 0 < minTrades → never eligible
  }
}
