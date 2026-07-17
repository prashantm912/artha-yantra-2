package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.registry.RegistryService;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * task_6f1372da (MONEY): a cockpit manual ticket (POST /api/v1/paper/orders → {@code
 * PaperService.openManualOrder}) must CAS its anchor signal ACTIVE→TAKEN, exactly as the explicit take
 * ({@code SignalsController:130}) and the auto path ({@code AutoPaperListener:57}) already do.
 *
 * <p>Before the fix the manual path never transitioned the signal, so the anchor stayed ACTIVE — and
 * the two 15:45 sweeps filter style INCONSISTENTLY: {@code SignalRepository.expireAllActive} expires
 * every non-swing ACTIVE row, while {@code PaperPositionRepository.intradayOpen} closes only style =
 * intraday. A btst / expiry_day / positional ticket therefore ended the day with an EXPIRED anchor and
 * an OPEN position, and {@code SignalRepository.activeEntry} anchors only ACTIVE/TAKEN — so the live
 * engine could never emit that position's exit, ever. btst and expiry_day are both live-enabled, so a
 * single cockpit click arms this.
 *
 * <p>The first test asserts the CONSEQUENCE, not the mechanism: after BOTH 15:45 sweeps run, the
 * position is still OPEN and the engine's own anchor lookup still resolves it. That is exactly what
 * fails pre-fix (activeEntry empty while the position is open = the orphan).
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class PaperManualTicketAnchorIntegrationTest extends StrategySignalIntegrationTestBase {

  /** The btst carry style: NOT swing (so the signal sweep expires it) and NOT intraday (so the
   * position sweep leaves it open) — the exact gap between the two 15:45 filters. */
  private static final String BTST_CONFIG =
      """
      schema: strategy-schema/v1
      id: btst-anchor-it
      name: "BTST Anchor IT"
      version: 1.0.0
      universe: { mode: explicit, instruments: [ { exchange: NSE, tradingsymbol: RELIANCE } ] }
      timeframes: { primary: 1m }
      indicators:
        - { name: RSI, alias: rsi_1m, timeframe: 1m, params: { period: 2 }, weight: 1.0,
            normalize: { type: step, bands: [ { score: 1.0 } ] } }
      entry_rules: { direction: long, gate: { all: [ "close > 1" ] }, scoring: { threshold: 0.05 } }
      exit_rules: [ { type: stop_loss, params: { basis: premium_pct, value: 50 } } ]
      risk: { position_sizing: { method: fixed_quantity, params: { quantity: 1 } }, max_positions: 1,
              session: { style: btst, allow_overnight: true, pre_close_at: "15:20", square_off: "15:20" } }
      """;

  private static final String BOOK = "manual";

  @TestConfiguration
  static class Stubs {
    @Bean
    @Primary
    InstrumentMetaClient stubMeta() {
      // every test symbol resolves as a plain equity (lot 1) so an explicit-price fill always prices.
      return (exchange, tradingsymbol) ->
          new InstrumentMeta(InstrumentClass.EQUITY, new BigDecimal("0.05"), 1);
    }
  }

  @Autowired private PaperService paper;
  @Autowired private PaperPositionRepository positions;
  @Autowired private RegistryService registry;
  @Autowired private StrategyRepository strategyRepo;
  @Autowired private SignalRepository signalRepo;
  @Autowired private RiskService risk;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void disableGovernors() {
    // openManualOrder (unlike openOrder) clears the per-book risk governor — neutralise every rail so a
    // sibling IT's leftover ledger state on the shared singleton DB can never veto this fill.
    risk.update(BOOK, RiskService.KILL_SWITCH, "{\"enabled\":false}");
    risk.update(BOOK, RiskService.MAX_OPEN, "{\"enabled\":false}");
    risk.update(BOOK, RiskService.DAILY_LOSS, "{\"enabled\":false}");
  }

  @Test
  void btstManualTicketAnchorSurvivesBoth1545SweepsSoTheEngineCanStillExit() {
    Fixture f = openBtstTicketFor("BTSTANCH");

    // The two 15:45 IST sweeps, exactly as the schedulers run them.
    signalRepo.expireAllActive();
    paper.markToCloseIntraday();

    // The position is a contractual overnight carry (allow_overnight: true) — the intraday sweep must
    // NOT close it. That half is correct today and is the reason the anchor MUST survive.
    assertThat(positions.findOpen(BOOK, "NFO", f.symbol, "BUY")).isPresent();

    // THE REGRESSION: the engine resolves a position's exit anchor ONLY via activeEntry (ACTIVE/TAKEN).
    // Pre-fix the anchor was ACTIVE, the signal sweep expired it, and this came back empty while the
    // position stayed open — an exit the live engine could never emit.
    assertThat(signalRepo.activeEntry(f.versionId, "NFO", f.symbol))
        .as("btst anchor must remain engine-visible after the 15:45 sweep")
        .isPresent()
        .get()
        .extracting(SignalRepository.SignalRow::id)
        .isEqualTo(f.signalId);

    assertThat(signalRepo.find(f.signalId).orElseThrow().status()).isEqualTo("TAKEN");
  }

  @Test
  void manualTicketOnAnAlreadyTakenAnchorIsAnIdempotentNoOpAndStillFills() {
    // The CAS-lost-but-benign path: auto-paper (or an explicit take) already anchored the signal. The
    // ticket must still fill, and the anchor must stay TAKEN — never bounced back to ACTIVE.
    Fixture f = openBtstTicketFor("BTSTTAKEN");

    paper.openManualOrder(
        new PaperService.OrderRequest(
            f.signalId, "NFO", f.symbol, "BUY", 1, new BigDecimal("80.00"), null, null, null, BOOK));

    assertThat(signalRepo.find(f.signalId).orElseThrow().status()).isEqualTo("TAKEN");
    // both legs filled onto the one open position (the §F.6 partial-unique open key averages the add).
    assertThat(positions.findOpen(BOOK, "NFO", f.symbol, "BUY")).isPresent().get()
        .extracting(PaperPositionRepository.PositionRow::qty)
        .isEqualTo(2L);
  }

  @Test
  void manualTicketWithNoAnchorStillFills() {
    // An ad-hoc ticket carries no signalId — there is nothing to transition and nothing to NPE on.
    String sym = "NOANCHOR-" + UUID.randomUUID().toString().substring(0, 8);

    PaperService.PositionDto opened =
        paper.openManualOrder(
            new PaperService.OrderRequest(
                null, "NSE", sym, "BUY", 1, new BigDecimal("100.00"), null, null, null, BOOK));

    assertThat(opened.status()).isEqualTo("OPEN");
    assertThat(positions.findOpen(BOOK, "NSE", sym, "BUY")).isPresent();
  }

  /** A published btst strategy + a fresh ACTIVE ENTRY signal + one filled manual ticket against it. */
  private Fixture openBtstTicketFor(String prefix) {
    // Shared singleton IT DB, no per-method cleanup: unique slug + name + symbol per method.
    String slug = "btst-anchor-it-" + UUID.randomUUID().toString().substring(0, 8);
    UUID strategyId =
        (UUID)
            registry
                .create(
                    "BTST Anchor IT " + slug,
                    null,
                    List.of("it"),
                    BTST_CONFIG.replace("id: btst-anchor-it", "id: " + slug))
                .get("id");
    registry.publish(strategyId, null, null);
    UUID versionId = strategyRepo.latestVersion(strategyId).orElseThrow().id();

    String symbol = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    OffsetDateTime now = OffsetDateTime.now();
    long signalId =
        signalRepo.insert(
            versionId, "NFO", symbol, "1m", "ENTRY", "BUY",
            new BigDecimal("80.00"), new BigDecimal("40.00"), new BigDecimal("120.00"),
            new BigDecimal("0.80"), "{}", now, now.plusHours(1));
    assertThat(signalRepo.find(signalId).orElseThrow().status()).isEqualTo("ACTIVE");

    // The cockpit ticket: an explicit price so the fill never depends on a live tick, and an explicit
    // book so the governor + the fill resolve the seeded 'manual' ledger.
    paper.openManualOrder(
        new PaperService.OrderRequest(
            signalId, "NFO", symbol, "BUY", 1, new BigDecimal("80.00"), null, null, null, BOOK));
    assertThat(positions.findOpen(BOOK, "NFO", symbol, "BUY")).isPresent();

    // Pin the premise the whole orphan rests on: this strategy's style is swept by expireAllActive
    // (not swing) but NOT closed by intradayOpen (not intraday) — the gap between the two filters.
    String style =
        jdbc.queryForObject(
            "SELECT config->'risk'->'session'->>'style' FROM strategy_versions WHERE id = ?",
            String.class,
            versionId);
    assertThat(style).isEqualTo("btst");

    return new Fixture(versionId, signalId, symbol);
  }

  private record Fixture(UUID versionId, long signalId, String symbol) {}
}
