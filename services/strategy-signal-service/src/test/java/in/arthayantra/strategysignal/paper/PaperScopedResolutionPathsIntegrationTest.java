package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.signals.SwingPaperEffectRepository;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The OTHER position-resolution paths, all of which reach a position through the SAME four-column
 * key {@code (book, exchange, tradingsymbol, side)} that V058 splits (cross-vendor review Criticals
 * 1-2, Majors 4-5, plus a sixth found by sweeping for the same shape).
 *
 * <p><b>Scoping {@code openForSignal} alone is not enough, and the two Criticals are the reason:
 * they do not mis-LABEL a position, they CLOSE it.</b> A BTST sibling swept into the 15:45
 * mark-to-close, or a directional sibling closed by the straddle monitor, is a real exit at a real
 * price on the money path.
 *
 * <p>Every test states what it does under the UNSCOPED join, since the twins share a key by
 * construction and a fixture both versions satisfy would prove nothing.
 *
 * <p><b>Why the {@link AfterEach}.</b> {@link PaperStrategyScopeGuard} refuses to boot when a book
 * holds OPEN rows whose scope disagrees with the flag, and IT classes share one singleton database
 * with no per-method cleanup. Leaving attributed OPEN rows behind would therefore make every LATER
 * test class fail to start its (disarmed) context. Closing them is also what the guard is telling
 * operators to do.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.signals.engine-enabled=false",
      "artha.paper.strategy-scoped-books=scoped-it-paths,scoped-it-swing"
    })
class PaperScopedResolutionPathsIntegrationTest extends StrategySignalIntegrationTestBase {

  /** Dedicated books — see PaperStrategyScopedOpenKeyIntegrationTest#BOOK for why. */
  private static final String BOOK = "scoped-it-paths";

  private static final String SWING_BOOK = "scoped-it-swing";

  private static final String EX = "NFO";
  private static final BigDecimal PX = new BigDecimal("100.00");

  @Autowired private PaperPositionRepository positions;
  @Autowired private PaperOrderRepository orders;
  @Autowired private PaperReconciliationRepository recon;
  @Autowired private SwingPaperEffectRepository swingEffects;
  @Autowired private JdbcTemplate jdbc;

  @AfterEach
  void leaveNoAttributedOpenRows() {
    jdbc.update(
        "UPDATE paper_positions SET status='CLOSED', closed_at=now(), close_reason='IT-CLEANUP'"
            + " WHERE status='OPEN' AND strategy_id IS NOT NULL");
  }

  /**
   * Critical 1 — the 15:45 mark-to-close sweep.
   *
   * <p>{@code intradayOpen()} classifies a position by ANY matching order's session style, and the
   * scalper book runs both intraday and btst strategies. UNDER THE UNSCOPED JOIN the intraday
   * sibling's order matches the BTST sibling on the key, so BOTH ids come back and
   * {@code markToCloseIntraday} settles a position that is meant to carry overnight — this
   * assertion returns 2 ids instead of 1 and `doesNotContain` fails.
   */
  @Test
  void the1545SweepSeesOnlyTheIntradaySiblingNotItsBtstTwinOnTheSameKey() {
    String sym = "MTMSCOPE-" + UUID.randomUUID().toString().substring(0, 8);
    UUID intradayStrategy = ScopedKeyTwinFixture.seedStrategy(jdbc, "mtm-intraday");
    UUID btstStrategy = ScopedKeyTwinFixture.seedStrategy(jdbc, "mtm-btst");
    UUID intradayVersion = ScopedKeyTwinFixture.seedVersionWithStyle(jdbc, intradayStrategy, "intraday");
    UUID btstVersion = ScopedKeyTwinFixture.seedVersionWithStyle(jdbc, btstStrategy, "btst");
    long intradaySignal = ScopedKeyTwinFixture.seedEntry(jdbc, intradayVersion, EX, sym, "BUY");
    long btstSignal = ScopedKeyTwinFixture.seedEntry(jdbc, btstVersion, EX, sym, "BUY");

    long intradayLot = lot(BOOK, sym, intradayStrategy);
    long btstLot = lot(BOOK, sym, btstStrategy);
    ScopedKeyTwinFixture.seedOrder(jdbc, BOOK, intradaySignal, EX, sym, "BUY", 10);
    ScopedKeyTwinFixture.seedOrder(jdbc, BOOK, btstSignal, EX, sym, "BUY", 10);

    List<Long> swept = positions.intradayOpen().stream().map(p -> p.id()).toList();
    assertThat(swept).contains(intradayLot);
    assertThat(swept).doesNotContain(btstLot);
  }

  /**
   * Critical 2 — the straddle monitor, which closes EVERY id this query returns
   * ({@code StraddleExitMonitor:85-90}).
   *
   * <p>UNDER THE UNSCOPED JOIN the NEUTRAL straddle order attaches the co-fired DIRECTIONAL
   * sibling on the same key to the straddle signal, so the monitor closes a position belonging to a
   * strategy that has nothing to do with the straddle. `hasSize(1)` fails with 2.
   */
  @Test
  void theStraddleMonitorSeesOnlyItsOwnLegNotACoFiredDirectionalSibling() {
    String sym = "STRSCOPE-" + UUID.randomUUID().toString().substring(0, 8);
    UUID straddleStrategy = ScopedKeyTwinFixture.seedStrategy(jdbc, "str-neutral");
    UUID directionalStrategy = ScopedKeyTwinFixture.seedStrategy(jdbc, "str-directional");
    UUID straddleVersion = ScopedKeyTwinFixture.seedVersion(jdbc, straddleStrategy, "1");
    UUID directionalVersion = ScopedKeyTwinFixture.seedVersion(jdbc, directionalStrategy, "1");
    long straddleSignal =
        ScopedKeyTwinFixture.seedEntryWithDetail(
            jdbc, straddleVersion, EX, sym, "BUY", "{\"side\":\"NEUTRAL\"}");
    long directionalSignal = ScopedKeyTwinFixture.seedEntry(jdbc, directionalVersion, EX, sym, "BUY");

    long straddleLot = lot(BOOK, sym, straddleStrategy);
    long directionalLot = lot(BOOK, sym, directionalStrategy);
    ScopedKeyTwinFixture.seedOrder(jdbc, BOOK, straddleSignal, EX, sym, "BUY", 10);
    ScopedKeyTwinFixture.seedOrder(jdbc, BOOK, directionalSignal, EX, sym, "BUY", 10);

    List<Long> legs =
        positions.openStraddleLegs().stream()
            .filter(l -> l.positionId() == straddleLot || l.positionId() == directionalLot)
            .map(l -> l.positionId())
            .distinct()
            .toList();
    assertThat(legs).containsExactly(straddleLot);
  }

  /**
   * The SIXTH site, found by sweeping rather than by being told:
   * {@code SwingPaperEffectRepository.openPositionIdsForSignals} — whose ids
   * {@code SwingBatchEngine:1032} binds and then CLOSES via {@code closeForPosition}.
   *
   * <p>UNDER THE UNSCOPED JOIN one exited signal binds both siblings and settles the wrong lot;
   * `containsExactly` fails with 2 ids.
   */
  @Test
  void theSwingEffectBindsOnlyTheExitedStrategysLot() {
    String sym = "SWGSCOPE-" + UUID.randomUUID().toString().substring(0, 8);
    UUID aStrategy = ScopedKeyTwinFixture.seedStrategy(jdbc, "swg-a");
    UUID bStrategy = ScopedKeyTwinFixture.seedStrategy(jdbc, "swg-b");
    long aSignal =
        ScopedKeyTwinFixture.seedEntry(
            jdbc, ScopedKeyTwinFixture.seedVersion(jdbc, aStrategy, "1"), "NSE", sym, "BUY");
    long bSignal =
        ScopedKeyTwinFixture.seedEntry(
            jdbc, ScopedKeyTwinFixture.seedVersion(jdbc, bStrategy, "1"), "NSE", sym, "BUY");

    long aLot = lot(SWING_BOOK, sym, aStrategy, "NSE");
    long bLot = lot(SWING_BOOK, sym, bStrategy, "NSE");
    ScopedKeyTwinFixture.seedOrder(jdbc, SWING_BOOK, aSignal, "NSE", sym, "BUY", 10);
    ScopedKeyTwinFixture.seedOrder(jdbc, SWING_BOOK, bSignal, "NSE", sym, "BUY", 10);

    assertThat(swingEffects.openPositionIdsForSignals(List.of(aSignal))).containsExactly(aLot);
    assertThat(swingEffects.openPositionIdsForSignals(List.of(bSignal))).containsExactly(bLot);
  }

  /**
   * Major 4 — idempotent replay resolves the key to the NEWEST row.
   *
   * <p>UNDER THE UNSCOPED READ, {@code findLatestForKey} returns twin B (opened later) for twin A's
   * retry, so the replay hands back B's id, quantity and brackets as if they were A's fill. The
   * first assertion returns B's id instead of A's.
   *
   * <p>Covers the repository seam plus the strategy lookup the service composes it with; the
   * private {@code PaperService.replayFor} wiring itself has one call site and is not driven
   * end-to-end here.
   */
  @Test
  void theReplayReadBackResolvesToTheRetryingTwinNotTheNewestRowOnTheKey() {
    String sym = "RPYSCOPE-" + UUID.randomUUID().toString().substring(0, 8);
    UUID aStrategy = ScopedKeyTwinFixture.seedStrategy(jdbc, "rpy-a");
    UUID bStrategy = ScopedKeyTwinFixture.seedStrategy(jdbc, "rpy-b");
    long aSignal =
        ScopedKeyTwinFixture.seedEntry(
            jdbc, ScopedKeyTwinFixture.seedVersion(jdbc, aStrategy, "1"), EX, sym, "BUY");
    long aLot = lot(BOOK, sym, aStrategy);
    long bLot = lot(BOOK, sym, bStrategy); // opened AFTER a — the newest row on the key

    assertThat(positions.findLatestForKey(BOOK, EX, sym, "BUY", aStrategy))
        .hasValueSatisfying(p -> assertThat(p.id()).isEqualTo(aLot));
    assertThat(positions.findLatestForKey(BOOK, EX, sym, "BUY", bStrategy))
        .hasValueSatisfying(p -> assertThat(p.id()).isEqualTo(bLot));
    // Unscoped (null) keeps the pre-V058 "newest row on the key" behaviour for unscoped books.
    assertThat(positions.findLatestForKey(BOOK, EX, sym, "BUY", null))
        .hasValueSatisfying(p -> assertThat(p.id()).isEqualTo(bLot));

    // The scope the service feeds it comes from the ORDER that carried the clientOrderId.
    String clientOrderId = "coid-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO paper_orders (book, signal_id, exchange, tradingsymbol, side, qty, status,"
            + " placed_at, filled_at, fill_price, client_order_id)"
            + " VALUES (?,?,?,?,?,?,'FILLED',now(),now(),100.00,?)",
        BOOK, aSignal, EX, sym, "BUY", 10L, clientOrderId);
    assertThat(orders.strategyIdForClientOrderId(BOOK, clientOrderId)).contains(aStrategy);
  }

  /**
   * Major 5 — the nightly reconciliation's ENTRY-leg reconstruction.
   *
   * <p>UNDER THE UNSCOPED LATERAL the sum counts BOTH twins' entry orders against EACH 10-unit row,
   * so both report entry_qty 20 vs position_qty 10 and the 21:15 reconciler raises a
   * {@code entryQtyMismatch} on a perfectly valid split. Both `isEqualTo(10)` assertions fail with 20.
   */
  @Test
  void theNightlyReconciliationCountsOnlyEachTwinsOwnEntryLeg() {
    String sym = "RCNSCOPE-" + UUID.randomUUID().toString().substring(0, 8);
    UUID aStrategy = ScopedKeyTwinFixture.seedStrategy(jdbc, "rcn-a");
    UUID bStrategy = ScopedKeyTwinFixture.seedStrategy(jdbc, "rcn-b");
    long aSignal =
        ScopedKeyTwinFixture.seedEntry(
            jdbc, ScopedKeyTwinFixture.seedVersion(jdbc, aStrategy, "1"), EX, sym, "BUY");
    long bSignal =
        ScopedKeyTwinFixture.seedEntry(
            jdbc, ScopedKeyTwinFixture.seedVersion(jdbc, bStrategy, "1"), EX, sym, "BUY");

    long aLot = lot(BOOK, sym, aStrategy);
    long bLot = lot(BOOK, sym, bStrategy);
    ScopedKeyTwinFixture.seedOrder(jdbc, BOOK, aSignal, EX, sym, "BUY", 10);
    ScopedKeyTwinFixture.seedOrder(jdbc, BOOK, bSignal, EX, sym, "BUY", 10);
    // An exit leg each, so exit_count is non-zero (only exitCount == 0 classifies as missingExit).
    ScopedKeyTwinFixture.seedOrder(jdbc, BOOK, null, EX, sym, "SELL", 10);

    OffsetDateTime from = OffsetDateTime.now().minusMinutes(5);
    positions.close(aLot, BigDecimal.ZERO, "TEST");
    positions.close(bLot, BigDecimal.ZERO, "TEST");
    OffsetDateTime to = OffsetDateTime.now().plusMinutes(5);

    var rows =
        recon.closedPositionReconciliation(from, to).stream()
            .filter(r -> r.positionId() == aLot || r.positionId() == bLot)
            .toList();
    assertThat(rows).hasSize(2);
    assertThat(rows).allSatisfy(r -> {
      assertThat(r.entryQty()).isEqualTo(10);
      assertThat(r.positionQty()).isEqualTo(10);
      assertThat(r.exitCount()).isGreaterThan(0);
    });
  }

  private long lot(String book, String sym, UUID strategyId) {
    return lot(book, sym, strategyId, EX);
  }

  private long lot(String book, String sym, UUID strategyId, String exchange) {
    return positions.insertOpen(
        book, exchange, sym, "BUY", 10, PX, null, null, null, null, null, strategyId);
  }
}
