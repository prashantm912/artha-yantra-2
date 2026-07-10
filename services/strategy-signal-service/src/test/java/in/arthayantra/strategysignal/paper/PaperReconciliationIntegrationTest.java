package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.paper.PaperReconciliationService.ReconciliationResult;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Audit §8 V5 + V16 (app-platform audit 2026-07-10): the nightly paper-ledger reconciliation runs
 * against the real Timescale container with the real {@code deploy/flyway} strategy lineage (so V030 +
 * the V021 {@code risk_settings} seed run exactly as in prod). Engine disabled — the reconciler is a
 * plain paper-module {@code @Service} with no {@code SignalEngine} dependency, proving it loads in the
 * engine-disabled paper context.
 *
 * <p>Shared singleton DB with NO per-method cleanup: every seeded row uses the {@code A12RECON} symbol
 * prefix and is delete-firsted, and every assertion is scoped to THIS test's own ids — so sibling ITs'
 * paper/signal rows (which also land in the reconciliation window) cannot flip a result. The window is
 * pinned explicitly ([now-1d, now+1m]) via the package-visible {@code reconcile(from, to)} so the pass
 * is deterministic regardless of the wall clock.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class PaperReconciliationIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final String PREFIX = "A12RECON";
  // Dedicated books for the V16 inverse check — with their OWN risk_settings rows, so the test never
  // depends on a shared book ('scalper'/'manual') whose auto_paper_trade toggle a sibling IT may flip
  // (singleton IT DB — tolerate what other ITs leave).
  private static final String AUTO_BOOK = "a12recon-auto";
  private static final String MANUAL_BOOK = "a12recon-manual";

  @Autowired private PaperReconciliationService reconciliation;
  @Autowired private PaperReconciliationScheduler scheduler; // present ⇒ bean loaded in engine-disabled ctx
  @Autowired private JdbcTemplate jdbc;

  private UUID versionId;

  @BeforeEach
  @AfterEach
  void clean() {
    jdbc.update("DELETE FROM paper_orders WHERE tradingsymbol LIKE ?", PREFIX + "%");
    jdbc.update("DELETE FROM paper_positions WHERE tradingsymbol LIKE ?", PREFIX + "%");
    jdbc.update("DELETE FROM signals WHERE tradingsymbol LIKE ?", PREFIX + "%");
    jdbc.update("DELETE FROM risk_settings WHERE book IN (?, ?)", AUTO_BOOK, MANUAL_BOOK);
  }

  @BeforeEach
  void resolveVersion() {
    // Any strategy_versions row satisfies the signals FK; the repeatable seed guarantees one exists.
    versionId =
        UUID.fromString(
            jdbc.queryForObject(
                "SELECT id::text FROM strategy_versions ORDER BY created_at LIMIT 1", String.class));
  }

  private ReconciliationResult run() {
    return reconciliation.reconcile(
        OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusMinutes(1));
  }

  @Test
  void engineDisabledContextLoadsTheReconciler() {
    assertThat(scheduler).isNotNull();
    assertThat(reconciliation).isNotNull();
  }

  @Test
  void cleanClosedPositionAndTakenSignalRaiseNoDiscrepancy() {
    OffsetDateTime opened = OffsetDateTime.now().minusHours(3);
    OffsetDateTime closed = OffsetDateTime.now().minusHours(2);
    String sym = sym("CLEAN");
    long signalId = seedSignal(sym, "TAKEN", 50, closed);
    long posId = seedPosition(sym, "BUY", 50, "CLOSED", opened, closed, signalId, "book1");
    // matching entry (same side, full qty) + exit (opposite side, null signal) legs within the lifetime
    seedOrder("book1", signalId, sym, "BUY", 50, opened);
    seedOrder("book1", null, sym, "SELL", 50, closed);

    ReconciliationResult r = run();

    assertThat(r.v5MissingEntryOrder()).doesNotContain(posId);
    assertThat(r.v5EntryQtyMismatch()).doesNotContain(posId);
    assertThat(r.v5MissingExitOrder()).doesNotContain(posId);
    assertThat(r.v16TakenWithoutOrder()).doesNotContain(signalId);
    assertThat(r.v16PositionWithoutSignal()).doesNotContain(posId);
  }

  @Test
  void takenSignalWithNoOrderIsFlagged() {
    String sym = sym("ORPHANSIG");
    long signalId = seedSignal(sym, "TAKEN", 50, OffsetDateTime.now().minusHours(2));
    // no paper_orders.signal_id row for this signal — the A1 "taken-but-never-opened" residual.

    ReconciliationResult r = run();

    assertThat(r.v16TakenWithoutOrder()).contains(signalId);
  }

  @Test
  void manualTakeWithoutQtyIsNotFlagged() {
    String sym = sym("MANUALACK");
    // suggested_qty NULL — a deliberate manual "take without qty" ack was never expected to open a
    // position (SignalsController.taken), so it must NOT be a V16 orphan.
    long signalId = seedSignal(sym, "TAKEN", null, OffsetDateTime.now().minusHours(2));

    ReconciliationResult r = run();

    assertThat(r.v16TakenWithoutOrder()).doesNotContain(signalId);
  }

  @Test
  void closedPositionWithNoOrdersIsFlagged() {
    OffsetDateTime opened = OffsetDateTime.now().minusHours(3);
    OffsetDateTime closed = OffsetDateTime.now().minusHours(2);
    String sym = sym("ORPHANPOS");
    long posId = seedPosition(sym, "BUY", 50, "CLOSED", opened, closed, null, "book1");
    // no order legs at all → both invariants fail.

    ReconciliationResult r = run();

    assertThat(r.v5MissingEntryOrder()).contains(posId);
    assertThat(r.v5MissingExitOrder()).contains(posId);
  }

  @Test
  void closedPositionWithEntryQtyMismatchIsFlagged() {
    OffsetDateTime opened = OffsetDateTime.now().minusHours(3);
    OffsetDateTime closed = OffsetDateTime.now().minusHours(2);
    String sym = sym("QTYMISMATCH");
    long posId = seedPosition(sym, "BUY", 50, "CLOSED", opened, closed, null, "book1");
    seedOrder("book1", null, sym, "BUY", 30, opened); // entry legs sum to 30, not 50
    seedOrder("book1", null, sym, "SELL", 50, closed); // exit leg present → only the qty class trips

    ReconciliationResult r = run();

    assertThat(r.v5EntryQtyMismatch()).contains(posId);
    assertThat(r.v5MissingEntryOrder()).doesNotContain(posId);
    assertThat(r.v5MissingExitOrder()).doesNotContain(posId);
  }

  @Test
  void straddleTwoLegSignalIsNotFlagged() {
    // Benign non-1:1: one signal opens TWO positions (CE + PE, distinct symbols) — each leg reconciles
    // independently, and the signal has ≥1 linked order, so neither check flags it.
    OffsetDateTime opened = OffsetDateTime.now().minusHours(3);
    OffsetDateTime closed = OffsetDateTime.now().minusHours(2);
    String ce = sym("STRADDLECE");
    String pe = sym("STRADDLEPE");
    long signalId = seedSignal(ce, "TAKEN", 75, closed);
    long ceId = seedPosition(ce, "BUY", 75, "CLOSED", opened, closed, signalId, "book1");
    long peId = seedPosition(pe, "BUY", 75, "CLOSED", opened, closed, signalId, "book1");
    seedOrder("book1", signalId, ce, "BUY", 75, opened);
    seedOrder("book1", null, ce, "SELL", 75, closed);
    seedOrder("book1", signalId, pe, "BUY", 75, opened);
    seedOrder("book1", null, pe, "SELL", 75, closed);

    ReconciliationResult r = run();

    assertThat(r.v16TakenWithoutOrder()).doesNotContain(signalId);
    assertThat(r.v5MissingEntryOrder()).doesNotContain(ceId, peId);
    assertThat(r.v5EntryQtyMismatch()).doesNotContain(ceId, peId);
    assertThat(r.v5MissingExitOrder()).doesNotContain(ceId, peId);
  }

  @Test
  void autoPaperPositionWithoutSignalIsFlaggedButManualBookIsNot() {
    // V16 inverse: an OPEN position (out of V5's closed-only scope) on an AUTO-paper book
    // (risk_settings.auto_paper_trade enabled) with no opening_signal_id is a linkage gap; the same on a
    // manual book (toggle OFF) is a legitimate hand order and must NOT be flagged.
    seedAutoPaperToggle(AUTO_BOOK, true);
    seedAutoPaperToggle(MANUAL_BOOK, false);
    OffsetDateTime opened = OffsetDateTime.now().minusHours(2);
    long autoId = seedPosition(sym("AUTO"), "BUY", 50, "OPEN", opened, null, null, AUTO_BOOK);
    long manualId = seedPosition(sym("HAND"), "BUY", 50, "OPEN", opened, null, null, MANUAL_BOOK);

    ReconciliationResult r = run();

    assertThat(r.v16PositionWithoutSignal()).contains(autoId);
    assertThat(r.v16PositionWithoutSignal()).doesNotContain(manualId);
  }

  private void seedAutoPaperToggle(String book, boolean enabled) {
    jdbc.update(
        "INSERT INTO risk_settings (book, key, value) VALUES (?, 'auto_paper_trade', ?::jsonb)",
        book,
        "{\"enabled\": " + enabled + "}");
  }

  @Test
  void runPersistsAnAppendOnlyRunRow() {
    int before = countRuns();

    run();

    assertThat(countRuns()).isEqualTo(before + 1);
  }

  // ── seed helpers ────────────────────────────────────────────────────────────────────────────────

  private String sym(String tag) {
    return PREFIX + tag + "-" + UUID.randomUUID().toString().substring(0, 6);
  }

  private long seedSignal(String sym, String status, Integer suggestedQty, OffsetDateTime generatedAt) {
    Long id =
        jdbc.queryForObject(
            """
            INSERT INTO signals
              (strategy_version_id, exchange, tradingsymbol, "interval", signal_type, side,
               composite_score, score_breakdown, status, generated_at, suggested_qty)
            VALUES (?, 'NFO', ?, '1m', 'ENTRY', 'BUY', 0.8000, '{}'::jsonb, ?, ?, ?)
            RETURNING id
            """,
            Long.class,
            versionId,
            sym,
            status,
            generatedAt,
            suggestedQty);
    return id == null ? 0 : id;
  }

  private long seedPosition(
      String sym,
      String side,
      long qty,
      String status,
      OffsetDateTime openedAt,
      OffsetDateTime closedAt,
      Long openingSignalId,
      String book) {
    Long id =
        jdbc.queryForObject(
            """
            INSERT INTO paper_positions
              (exchange, tradingsymbol, side, qty, avg_entry_price, status, opened_at, closed_at,
               close_reason, book, opening_signal_id)
            VALUES ('NFO', ?, ?, ?, 100.0000, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """,
            Long.class,
            sym,
            side,
            qty,
            status,
            openedAt,
            closedAt,
            closedAt == null ? null : "TEST",
            book,
            openingSignalId);
    return id == null ? 0 : id;
  }

  private void seedOrder(
      String book, Long signalId, String sym, String side, long qty, OffsetDateTime filledAt) {
    jdbc.update(
        """
        INSERT INTO paper_orders
          (book, signal_id, exchange, tradingsymbol, side, qty, status, placed_at, filled_at, fill_price)
        VALUES (?, ?, 'NFO', ?, ?, ?, 'FILLED', ?, ?, 100.0000)
        """,
        book,
        signalId,
        sym,
        side,
        qty,
        filledAt,
        filledAt);
  }

  private int countRuns() {
    Integer c = jdbc.queryForObject("SELECT count(*) FROM paper_reconciliation_runs", Integer.class);
    return c == null ? 0 : c;
  }
}
