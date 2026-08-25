package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.paper.PaperService.OrderRequest;
import in.arthayantra.strategysignal.paper.ScopedKeyTwinFixture.OpenLot;
import in.arthayantra.strategysignal.paper.ScopedKeyTwinFixture.Twin;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V058 / option D ARMED: the OPEN-position key is STRATEGY-SCOPED on the listed books, so two
 * strategies entering the same contract on the same bar hold SEPARATE lots with their own brackets
 * and their own exits instead of the second averaging into the first.
 *
 * <p><b>Every fixture here is built so the scoped and unscoped keys give OPPOSITE answers</b> — a
 * test both implementations satisfy would prove nothing about the one thing that changed. The
 * disarmed twin of this class, {@link PaperStrategyScopedDisarmedIntegrationTest}, runs the SAME
 * two-twin fixture with the property unset and asserts the OLD outcome (one averaged row), so the
 * pair isolates the flag as the only difference. Read next to each other.
 *
 * <p>What each test would do under the OLD key, stated per test in its own comment, because "it
 * passes" is not evidence unless the failing case is named.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.signals.engine-enabled=false",
      "artha.paper.strategy-scoped-books=scoped-it-key,scoped-it-scalper"
    })
class PaperStrategyScopedOpenKeyIntegrationTest extends StrategySignalIntegrationTestBase {

  @TestConfiguration
  static class Stubs {
    @Bean
    @Primary
    InstrumentMetaClient stubMeta() {
      return (exchange, tradingsymbol) ->
          // Lot 5, not 50: openOrder now REFUSES a non-lot-multiple fill (the alignment rule
          // moved to the writer), and this fixture's quantities (10/20/25/30/40/65) were
          // chosen years before that rule existed. 5 divides every one of them, so the
          // QUANTITIES and every asserted figure derived from them are untouched — only the
          // stub's declared lot moves. Live is unaffected: all 40 F&O paper positions are
          // lot-aligned today (computed 2026-08-25), which is why the rule is safe to enforce.
          new InstrumentMeta(InstrumentClass.OPTION, new BigDecimal("0.05"), 5);
    }
  }

  /**
   * DEDICATED books, not `other`/`scalper`. {@link PaperStrategyScopeGuard} refuses to boot a
   * context that ARMS a book already holding unattributed OPEN rows, and the IT database is a shared
   * singleton that other classes leave rows in — so arming a real book name here would make this
   * class's context fail to start depending purely on test ORDER. The mechanism is book-agnostic.
   *
   * <p>Consequence, stated rather than hidden: the sub-account CEILING rail
   * ({@code ScalperAccountModel.wouldExceedSubAccount}) is inert on a book with no capital row, so
   * the sub-account test below pins the INHERITANCE rule only, not the ceiling arithmetic it feeds.
   */
  private static final String BOOK = "scoped-it-key";

  private static final String SCALPER_BOOK = "scoped-it-scalper";

  private static final String EX = "NFO";
  private static final BigDecimal PX = new BigDecimal("100.00");

  @Autowired private PaperService paper;
  @Autowired private PaperPositionRepository positions;
  @Autowired private JdbcTemplate jdbc;

  /**
   * The IT database is shared with no per-method cleanup and {@link PaperStrategyScopeGuard} refuses
   * to boot when a book's OPEN rows disagree with the flag — so leaving attributed rows behind would
   * make every LATER (disarmed) context fail to start. Closing them is also the guard's own remedy.
   */
  @org.junit.jupiter.api.AfterEach
  void leaveNoAttributedOpenRows() {
    jdbc.update(
        "UPDATE paper_positions SET status='CLOSED', closed_at=now(), close_reason='IT-CLEANUP'"
            + " WHERE status='OPEN' AND strategy_id IS NOT NULL");
  }

  /**
   * The headline behaviour: two strategies, one key, one bar, byte-identical price — TWO lots.
   *
   * <p>UNDER THE OLD KEY: {@code findOpen} would resolve the golden-crossover row for the
   * connect-the-dots fill, {@code upsertPosition} would average (qty 20, avg unchanged at an
   * identical fill) and connect-the-dots' 55.00 stop would be DISCARDED — the merged row keeps the
   * first opener's brackets. So `hasSize(2)`, the per-lot qty of 10, and the second lot's own stop
   * each fail independently.
   */
  @Test
  void twinsEnteringOneKeyOnTheSameBarHoldSeparateLotsWithTheirOwnBrackets() {
    String sym = "SCOPED-" + UUID.randomUUID().toString().substring(0, 8);
    Twin gc = ScopedKeyTwinFixture.seedTwin(jdbc, "scoped-gc", EX, sym, "BUY");
    Twin ctd = ScopedKeyTwinFixture.seedTwin(jdbc, "scoped-ctd", EX, sym, "BUY");

    paper.openOrder(entry(gc.signalId(), sym, new BigDecimal("40.00")));
    paper.openOrder(entry(ctd.signalId(), sym, new BigDecimal("55.00")));

    List<OpenLot> lots = ScopedKeyTwinFixture.openLots(jdbc, BOOK, EX, sym, "BUY");
    assertThat(lots).hasSize(2);
    assertThat(lots).allSatisfy(lot -> assertThat(lot.qty()).isEqualTo(10));
    // Each lot carries its OWN opener and its OWN stop — the merge destroyed both.
    assertThat(lots.get(0).strategyId()).isEqualTo(gc.strategyId());
    assertThat(lots.get(1).strategyId()).isEqualTo(ctd.strategyId());
    assertThat(lots.get(0).stopLoss()).isEqualByComparingTo("40.00");
    assertThat(lots.get(1).stopLoss()).isEqualByComparingTo("55.00");
  }

  /**
   * The load-bearing half, and the one the index alone does NOT buy: one twin's exit settles ONLY
   * its own lot.
   *
   * <p>{@code PaperPositionRepository.openForSignal} joins orders to positions on {@code (book,
   * exchange, tradingsymbol, side)} and carries no strategy of its own, so splitting the rows without
   * also splitting that join leaves both siblings reachable from either anchor — the exact merge this
   * feature exists to undo, now silently.
   *
   * <p>UNDER THE OLD {@code openForSignal}: the first assertion returns BOTH lots instead of one,
   * {@code closeForSignal} returns 2 instead of 1, and the surviving sibling is CLOSED instead of
   * OPEN. This is the test the red-proof reverts to.
   */
  @Test
  void oneTwinsExitClosesOnlyItsOwnLotAndLeavesTheSiblingOpen() {
    String sym = "SCOPEDX-" + UUID.randomUUID().toString().substring(0, 8);
    Twin gc = ScopedKeyTwinFixture.seedTwin(jdbc, "scopedx-gc", EX, sym, "BUY");
    Twin ctd = ScopedKeyTwinFixture.seedTwin(jdbc, "scopedx-ctd", EX, sym, "BUY");
    paper.openOrder(entry(gc.signalId(), sym, new BigDecimal("40.00")));
    paper.openOrder(entry(ctd.signalId(), sym, new BigDecimal("55.00")));
    List<OpenLot> before = ScopedKeyTwinFixture.openLots(jdbc, BOOK, EX, sym, "BUY");
    assertThat(before).hasSize(2);

    // The exit driver resolves the position through the ENTRY anchor (SignalExited carries it).
    assertThat(positions.openForSignal(ctd.signalId()))
        .singleElement()
        .satisfies(row -> assertThat(row.id()).isEqualTo(before.get(1).id()));

    assertThat(paper.closeForSignal(ctd.signalId(), "TIME_STOP", new BigDecimal("110.00")))
        .isEqualTo(1);

    List<OpenLot> after = ScopedKeyTwinFixture.openLots(jdbc, BOOK, EX, sym, "BUY");
    assertThat(after).singleElement().satisfies(lot -> {
      assertThat(lot.id()).isEqualTo(before.get(0).id());
      assertThat(lot.strategyId()).isEqualTo(gc.strategyId());
      assertThat(lot.qty()).isEqualTo(10);
    });
    assertThat(closeReasonOf(before.get(1).id())).isEqualTo("TIME_STOP");
    assertThat(closeReasonOf(before.get(0).id())).isNull();
  }

  /**
   * The constraint the split must NOT break: a genuine SAME-strategy pyramid add still averages.
   *
   * <p>The second entry is emitted from a REPUBLISHED version of the same strategy, which is what
   * discriminates the key we chose. UNDER A {@code strategy_version_id} KEY — the obvious wrong
   * choice, since the signal carries the version and not the strategy — the republished add would be
   * a different key and would open a SECOND row: `hasSize(1)` and `qty 20` both fail. Under the
   * shipped {@code strategies.id} key it averages, because that id is stable across every publish.
   */
  @Test
  void aSecondEntryFromTheSameStrategyStillAveragesEvenAcrossARepublish() {
    String sym = "SCOPEDP-" + UUID.randomUUID().toString().substring(0, 8);
    UUID strategyId = ScopedKeyTwinFixture.seedStrategy(jdbc, "scopedp");
    UUID v1 = ScopedKeyTwinFixture.seedVersion(jdbc, strategyId, "1");
    UUID v2 = ScopedKeyTwinFixture.seedVersion(jdbc, strategyId, "2"); // the republish
    long first = ScopedKeyTwinFixture.seedEntry(jdbc, v1, EX, sym, "BUY");
    long second = ScopedKeyTwinFixture.seedEntry(jdbc, v2, EX, sym, "BUY");

    paper.openOrder(entry(first, sym, new BigDecimal("40.00")));
    paper.openOrder(entry(second, sym, new BigDecimal("55.00")));

    List<OpenLot> lots = ScopedKeyTwinFixture.openLots(jdbc, BOOK, EX, sym, "BUY");
    assertThat(lots).singleElement().satisfies(lot -> {
      assertThat(lot.qty()).isEqualTo(20);
      assertThat(lot.strategyId()).isEqualTo(strategyId);
      // An averaging add keeps the ORIGINAL brackets (audit H5) — unchanged by V058.
      assertThat(lot.stopLoss()).isEqualByComparingTo("40.00");
    });
  }

  /**
   * The capital rail: a co-firing sibling INHERITS the key's existing sub-account rather than being
   * charged to a fresh one, so a pair still consumes ONE account exactly as the merged row did.
   *
   * <p>UNDER A NAIVE SPLIT — {@code upsertPosition} inserting with the REQUEST's idx, which is what
   * it did before V058 — the second lot would land on account 5. That is a real capital-governor
   * change smuggled in as a lookup detail: the pair's ₹ would spread across two of the five
   * sub-accounts, halving what each pair consumes per account and DOUBLING the accounts a losing pair
   * freezes. The assertion below is the difference between those two worlds.
   */
  @Test
  void aCoFiringSiblingInheritsTheKeysSubAccountSoTheCapitalRailIsUnchanged() {
    String sym = "SCOPEDA-" + UUID.randomUUID().toString().substring(0, 8);
    Twin gc = ScopedKeyTwinFixture.seedTwin(jdbc, "scopeda-gc", EX, sym, "BUY");
    Twin ctd = ScopedKeyTwinFixture.seedTwin(jdbc, "scopeda-ctd", EX, sym, "BUY");

    paper.openOrder(scalperEntry(gc.signalId(), sym, 3));
    paper.openOrder(scalperEntry(ctd.signalId(), sym, 5)); // asks for 5, must be charged to 3

    List<OpenLot> lots = ScopedKeyTwinFixture.openLots(jdbc, SCALPER_BOOK, EX, sym, "BUY");
    assertThat(lots).hasSize(2);
    assertThat(lots).allSatisfy(lot -> assertThat(lot.subaccountIdx()).isEqualTo(3));
  }

  /**
   * {@code NULLS NOT DISTINCT} on the widened index, both directions.
   *
   * <p>Adding a NULLABLE column to a unique index under PostgreSQL's DEFAULT ({@code NULLS
   * DISTINCT}) SILENTLY DELETES the guarantee it is meant to preserve: two NULL-strategy rows become
   * two different keys and an unscoped book can double-open one contract with nothing complaining.
   * Measured on this server (PG 17.3): without the clause both inserts land (`count = 2`); with it
   * the second raises {@code duplicate key ... =(…, null) already exists}. The failure is invisible
   * to any test that only exercises the NEW behaviour — two DISTINCT strategy ids land under either
   * spelling — which is why both halves are asserted here.
   */
  @Test
  void theWidenedIndexSeparatesStrategiesButStillRefusesASecondUnattributedOpen() {
    String sym = "SCOPEDI-" + UUID.randomUUID().toString().substring(0, 8);
    UUID a = ScopedKeyTwinFixture.seedStrategy(jdbc, "scopedi-a");
    UUID b = ScopedKeyTwinFixture.seedStrategy(jdbc, "scopedi-b");

    // Two DISTINCT strategies: separate keys, both land.
    assertThat(insertLot(sym, a)).isPositive();
    assertThat(insertLot(sym, b)).isPositive();

    // Two UNATTRIBUTED lots: one key (NULLS NOT DISTINCT), the second is refused.
    String bare = "SCOPEDI0-" + UUID.randomUUID().toString().substring(0, 8);
    assertThat(insertLot(bare, null)).isPositive();
    assertThatThrownBy(() -> insertLot(bare, null))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /**
   * Critical 3, write side: on a SCOPED book a signal-less hand ticket takes the explicit
   * {@code UNATTRIBUTED_SCOPE} sentinel, never NULL — and is therefore NOT reachable from another
   * strategy's exit anchor.
   *
   * <p>UNDER THE PRE-FIX WRITE (which left {@code strategy_id} NULL when there was no signal): the
   * first assertion sees null, and — the part that actually matters — NULL is the WILDCARD arm of
   * every scoped predicate, so {@code openForSignal(twin)} returns the hand-ticket lot and a
   * strategy's exit settles a position it never opened. The second assertion is the decisive one;
   * the first only explains why.
   */
  @Test
  void aSignallessTicketOnAScopedBookTakesTheSentinelAndIsNotClosableByAnotherStrategy() {
    String sym = "SCOPEDN-" + UUID.randomUUID().toString().substring(0, 8);
    // A hand ticket: no signal, so nothing to attribute it to.
    paper.openOrder(
        new OrderRequest(null, EX, sym, "BUY", 10, PX, null, null, null, BOOK));
    List<OpenLot> lots = ScopedKeyTwinFixture.openLots(jdbc, BOOK, EX, sym, "BUY");
    assertThat(lots).singleElement().satisfies(lot ->
        assertThat(lot.strategyId()).isEqualTo(PaperPositionRepository.UNATTRIBUTED_SCOPE));

    // A real strategy now fires on the SAME key; its exit anchor must not reach the hand ticket.
    Twin twin = ScopedKeyTwinFixture.seedTwin(jdbc, "scopedn-twin", EX, sym, "BUY");
    ScopedKeyTwinFixture.seedOrder(jdbc, BOOK, twin.signalId(), EX, sym, "BUY", 10);
    assertThat(positions.openForSignal(twin.signalId()))
        .noneSatisfy(row -> assertThat(row.id()).isEqualTo(lots.get(0).id()));
  }

  private long insertLot(String sym, UUID strategyId) {
    return positions.insertOpen(
        BOOK, EX, sym, "BUY", 10, PX, null, null, null, null, null, strategyId);
  }

  private String closeReasonOf(long positionId) {
    return jdbc.queryForObject(
        "SELECT close_reason FROM paper_positions WHERE id=?", String.class, positionId);
  }

  /** A signal-linked entry on the {@code other} book at an explicit price (no tick needed). */
  private OrderRequest entry(long signalId, String sym, BigDecimal stopLoss) {
    return new OrderRequest(
        signalId, EX, sym, "BUY", 10, PX, stopLoss, new BigDecimal("200.00"), null, BOOK);
  }

  /** The same, on the scalper book, charged to an explicit sub-account. */
  private OrderRequest scalperEntry(long signalId, String sym, int subaccountIdx) {
    return new OrderRequest(
        signalId, EX, sym, "BUY", 10, PX, new BigDecimal("40.00"), null, subaccountIdx,
        SCALPER_BOOK);
  }
}
