package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.notifier.NotifierClient;
import in.arthayantra.strategysignal.paper.PaperReconciliationService.ReconciliationResult;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
  @Autowired private MeterRegistry meters;
  @MockitoBean private NotifierClient notifier;

  private UUID versionId;

  @BeforeEach
  @AfterEach
  void clean() {
    jdbc.update("DELETE FROM paper_orders WHERE tradingsymbol LIKE ?", PREFIX + "%");
    jdbc.update("DELETE FROM paper_positions WHERE tradingsymbol LIKE ?", PREFIX + "%");
    jdbc.update("DELETE FROM signals WHERE tradingsymbol LIKE ?", PREFIX + "%");
    jdbc.update("DELETE FROM risk_settings WHERE book IN (?, ?)", AUTO_BOOK, MANUAL_BOOK);
    // AFTER the signals delete — they carry the FK onto it. published_version_id is nulled first: it is a
    // circular FK (strategies → strategy_versions → strategies) that would otherwise block the delete.
    jdbc.update("UPDATE strategies SET published_version_id = NULL WHERE slug LIKE ?", PREFIX + "%");
    jdbc.update("DELETE FROM strategy_versions WHERE version LIKE ?", PREFIX + "%");
    jdbc.update("DELETE FROM strategies WHERE slug LIKE ?", PREFIX + "%");
  }

  @BeforeEach
  void resolveVersion() {
    // Any strategy_versions row satisfies the signals FK; the repeatable seed guarantees one exists.
    // PREFIX-tagged rows are THIS test's own sibling versions (seedSiblingVersion) — excluded explicitly
    // so an @BeforeEach ordering that ran before clean() could never pin versionId to a row clean() is
    // about to delete (JUnit does not order sibling @BeforeEach methods).
    versionId =
        UUID.fromString(
            jdbc.queryForObject(
                "SELECT id::text FROM strategy_versions WHERE version NOT LIKE ?"
                    + " ORDER BY created_at LIMIT 1",
                String.class,
                PREFIX + "%"));
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
  void openEntryPositionWithItsPersistedExitIsFlaggedInRunDetail() {
    OffsetDateTime now = OffsetDateTime.now();
    String sym = sym("CARRY");
    long anchorId = seedSignal(sym, "EXPIRED", 50, "ENTRY", "BUY", now.minusHours(3));
    OffsetDateTime transitionAt = now.minusHours(2);
    seedSignal(sym, "EXPIRED", null, "EXIT", "SELL", transitionAt);
    // A later ENTRY can be persisted after the EXIT with the same candle timestamp. Signal id is the
    // causal tie-break: the EXIT still belongs to the older anchor because it was inserted first.
    seedSignal(sym, "EXPIRED", 50, "ENTRY", "BUY", transitionAt);
    long positionId = seedPosition(sym, "BUY", 50, "OPEN", now.minusHours(3), null, anchorId, "book1");

    ReconciliationResult r = run();

    assertThat(strandedCarryIds(latestRunId())).contains(positionId);
    assertThat(meters.find("ay_paper_recon_stranded_carry").gauge().value())
        .isEqualTo(r.strandedCarryDiscrepancies());
    assertThat(meters.find("ay_paper_recon_stranded_carry").counter()).isNull();
  }

  @Test
  void exitAnchoredOpenPositionIsExcludedByTheEntryAnchorGuard() {
    OffsetDateTime now = OffsetDateTime.now();
    String sym = sym("EXITANCHOR");
    long anchorId = seedSignal(sym, "EXPIRED", null, "EXIT", "SELL", now.minusHours(2));
    seedSignal(sym, "EXPIRED", null, "EXIT", "BUY", now.minusHours(1));
    long positionId = seedPosition(sym, "SELL", 50, "OPEN", now.minusHours(2), null, anchorId, "book1");

    run();

    assertThat(strandedCarryIds(latestRunId())).doesNotContain(positionId);
  }

  /**
   * Pins guard 2 (`x.side <> s.side`): a sibling position's EXIT must not match the other side.
   *
   * <p><b>SEED ORDER IS LOAD-BEARING — do not "tidy" it.</b> Both anchors share a generated_at, so the
   * predicate's `(generated_at, id)` tie-break makes seed order = causal order. The BUY anchor must be
   * seeded LAST so it is the newest ENTRY before the EXIT: with the BUY anchor first, the SELL anchor
   * became an intervening ENTRY that closed the BUY's window, so `buyPosition` was excluded by the
   * WINDOW BOUND and never by the side guard — the assertion then passed identically with
   * `x.side <> s.side` deleted (proven by mutation against live Postgres). A test that cannot fail is
   * exactly what got v1 of this reconciler rejected.
   */
  @Test
  void oppositeSideOpenPositionDoesNotMatchAnotherSidesExit() {
    OffsetDateTime now = OffsetDateTime.now();
    String sym = sym("SIDEMATCH");
    long sellAnchor = seedSignal(sym, "EXPIRED", 50, "ENTRY", "SELL", now.minusHours(2));
    long buyAnchor = seedSignal(sym, "EXPIRED", 50, "ENTRY", "BUY", now.minusHours(2));
    long sellPosition = seedPosition(sym, "SELL", 50, "OPEN", now.minusHours(2), null, sellAnchor, "book1");
    long buyPosition = seedPosition(sym, "BUY", 50, "OPEN", now.minusHours(2), null, buyAnchor, "book1");
    // A BUY-side EXIT: it closes the SELL anchor (opposite side) and must NOT match the BUY anchor.
    seedSignal(sym, "EXPIRED", null, "EXIT", "BUY", now.minusHours(1));

    run();

    // sellPosition IS flagged: the BUY anchor is opposite-side, so `n.side = s.side` keeps it from
    // bounding the SELL's window, and the BUY EXIT satisfies `x.side <> s.side`.
    // buyPosition is NOT flagged: the EXIT is its OWN side, which only guard 2 can exclude.
    assertThat(strandedCarryIds(latestRunId())).contains(sellPosition).doesNotContain(buyPosition);
  }

  @Test
  void pyramidAddDoesNotEndWindowButLaterEntryDoes() {
    OffsetDateTime now = OffsetDateTime.now();
    String pyramidSym = sym("PYRAMID");
    long pyramidAnchor =
        seedSignal(pyramidSym, "EXPIRED", 50, "ENTRY", "BUY", now.minusHours(4));
    long pyramidAdd =
        seedSignal(pyramidSym, "EXPIRED", 50, "ENTRY", "BUY", now.minusHours(3));
    jdbc.update(
        "UPDATE signals SET manas_arora_detail = '{\"pyramidLot\":2}'::jsonb WHERE id = ?",
        pyramidAdd);
    long pyramidPosition =
        seedPosition(
            pyramidSym, "BUY", 100, "OPEN", now.minusHours(4), null, pyramidAnchor, "book-pyramid");
    seedSignal(pyramidSym, "EXPIRED", null, "EXIT", "SELL", now.minusHours(2));

    String sym = sym("WINDOW");
    OffsetDateTime entryAt = now.minusHours(3);
    // Equal generated_at is legal. The later identity is the later anchor and must own the EXIT.
    long sweptAnchor = seedSignal(sym, "EXPIRED", null, "ENTRY", "BUY", entryAt);
    long laterAnchor = seedSignal(sym, "EXPIRED", 50, "ENTRY", "BUY", entryAt);
    long sweptPosition =
        seedPosition(sym, "BUY", 50, "OPEN", entryAt, null, sweptAnchor, "book-swept");
    long laterPosition =
        seedPosition(sym, "BUY", 50, "OPEN", entryAt, null, laterAnchor, "book-later");
    seedSignal(sym, "EXPIRED", null, "EXIT", "SELL", entryAt);

    run();

    assertThat(strandedCarryIds(latestRunId()))
        .contains(pyramidPosition, laterPosition)
        .doesNotContain(sweptPosition);
  }

  @Test
  void healthyClosedPositionIsExcludedAlongsideAFlaggedOpenCarry() {
    OffsetDateTime now = OffsetDateTime.now();
    String openSym = sym("OPENPOS");
    long openAnchor = seedSignal(openSym, "EXPIRED", 50, "ENTRY", "BUY", now.minusHours(3));
    long openPosition = seedPosition(openSym, "BUY", 50, "OPEN", now.minusHours(3), null, openAnchor, "book1");
    seedSignal(openSym, "EXPIRED", null, "EXIT", "SELL", now.minusHours(2));

    String closedSym = sym("CLOSED");
    long closedAnchor = seedSignal(closedSym, "EXPIRED", 50, "ENTRY", "BUY", now.minusHours(3));
    OffsetDateTime closedAt = now.minusHours(2);
    long closedPosition = seedPosition(closedSym, "BUY", 50, "CLOSED", now.minusHours(3), closedAt, closedAnchor, "book1");
    seedOrder("book1", closedAnchor, closedSym, "BUY", 50, now.minusHours(3));
    seedOrder("book1", null, closedSym, "SELL", 50, closedAt);

    run();

    assertThat(strandedCarryIds(latestRunId())).contains(openPosition).doesNotContain(closedPosition);
  }

  @Test
  void repeatedCarryAlertsOnlyForNewIdsAndDoNotEnterActivityTotal() {
    OffsetDateTime now = OffsetDateTime.now();
    String sym = sym("ALERT");
    long anchorId = seedSignal(sym, "EXPIRED", 50, "ENTRY", "BUY", now.minusHours(2));
    long positionId = seedPosition(sym, "BUY", 50, "OPEN", now.minusHours(2), null, anchorId, "book1");
    seedSignal(sym, "EXPIRED", null, "EXIT", "SELL", now.minusHours(1));
    when(notifier.configured("NTFY")).thenReturn(true);

    ReconciliationResult first = run();
    verify(notifier, times(1)).send(eq("NTFY"), contains("stranded carry"), contains(Long.toString(positionId)));
    clearInvocations(notifier);
    ReconciliationResult second = run();

    assertThat(first.totalDiscrepancies())
        .isEqualTo(first.v5Discrepancies() + first.v16Discrepancies());
    assertThat(second.totalDiscrepancies())
        .isEqualTo(second.v5Discrepancies() + second.v16Discrepancies());
    assertThat(latestRunTotal()).isEqualTo(second.totalDiscrepancies());
    assertThat(strandedCarryIds(latestRunId())).contains(positionId);
    verify(notifier, never()).send(eq("NTFY"), contains("stranded carry"), contains(Long.toString(positionId)));
  }

  // ── dead-anchor orphans (the class strandedCarry is structurally blind to) ──────────────────────

  /**
   * The core class: an OPEN position whose only linked anchor is dead. Both drivers gate the exit branch
   * on {@code signal_type='ENTRY' AND status IN ('ACTIVE','TAKEN')} (activeEntry:166-178, required by
   * SignalEngine:745-747 / activeEntries:149-152), so nothing ever evaluates an exit and no EXIT row is
   * ever written — precisely why {@code strandedCarryPositions}' EXISTS-an-EXIT predicate cannot see it.
   * The healthy control is the same shape with a live, ORDER-LINKED anchor and must NOT be flagged.
   */
  @Test
  void deadAnchorOpenPositionIsFlaggedWhileALiveLinkedAnchorIsNot() {
    seedAutoPaperToggle(AUTO_BOOK, true);
    UUID swing = seedSiblingVersion("swing");
    OffsetDateTime now = OffsetDateTime.now();

    String deadSym = sym("DEADANCHOR");
    long deadAnchor = seedSignal(swing, deadSym, "EXPIRED", 50, "ENTRY", "BUY", now.minusHours(3));
    long deadPosition = seedPosition(deadSym, "BUY", 50, "OPEN", now.minusHours(3), null, deadAnchor, AUTO_BOOK);
    seedOrder(AUTO_BOOK, deadAnchor, deadSym, "BUY", 50, now.minusHours(3));

    String liveSym = sym("LIVEANCHOR");
    long liveAnchor = seedSignal(swing, liveSym, "TAKEN", 50, "ENTRY", "BUY", now.minusHours(3));
    long livePosition = seedPosition(liveSym, "BUY", 50, "OPEN", now.minusHours(3), null, liveAnchor, AUTO_BOOK);
    seedOrder(AUTO_BOOK, liveAnchor, liveSym, "BUY", 50, now.minusHours(3));

    run();

    assertThat(deadAnchorOrphanIds(latestRunId())).contains(deadPosition).doesNotContain(livePosition);
  }

  /**
   * Pins the ENTRY-TYPE condition — <b>live {@code paper_positions} id=28's exact shape</b> (an OPEN SELL
   * in {@code manas-arora} linked to signals id=46, an <b>ACTIVE EXIT</b>, whose symbol's only ENTRY is
   * EXPIRED). The anchor's status IS 'ACTIVE', so a status-only predicate reads it healthy and is blind to
   * the ONE real live instance of this bug; {@code activeEntry} filters {@code signal_type='ENTRY'}, so an
   * ACTIVE EXIT can never be returned as an anchor. The ACTIVE-ENTRY control keeps the assertion honest.
   */
  @Test
  void activeExitAnchorIsFlaggedBecauseActiveEntryOnlyAnchorsEntries() {
    seedAutoPaperToggle(AUTO_BOOK, true);
    UUID swing = seedSiblingVersion("swing");
    OffsetDateTime now = OffsetDateTime.now();

    String sym = sym("EXITANCHORLIVE");
    seedSignal(swing, sym, "EXPIRED", 50, "ENTRY", "BUY", now.minusHours(4)); // the only ENTRY — dead
    long activeExit = seedSignal(swing, sym, "ACTIVE", null, "EXIT", "SELL", now.minusHours(2));
    long exitAnchored = seedPosition(sym, "SELL", 50, "OPEN", now.minusHours(2), null, activeExit, AUTO_BOOK);
    seedOrder(AUTO_BOOK, activeExit, sym, "SELL", 50, now.minusHours(2));

    String controlSym = sym("ENTRYANCHORLIVE");
    long activeEntry = seedSignal(swing, controlSym, "ACTIVE", 50, "ENTRY", "BUY", now.minusHours(2));
    long entryAnchored =
        seedPosition(controlSym, "BUY", 50, "OPEN", now.minusHours(2), null, activeEntry, AUTO_BOOK);
    seedOrder(AUTO_BOOK, activeEntry, controlSym, "BUY", 50, now.minusHours(2));

    run();

    assertThat(deadAnchorOrphanIds(latestRunId())).contains(exitAnchored).doesNotContain(entryAnchored);
  }

  /**
   * Pins the LINK condition: "a live ENTRY row exists" is NOT "this position can be exited". The settle
   * chain reaches a position ONLY through the chosen anchor's own order — SignalEngine:1508 publishes
   * {@code SignalExited(anchor.id(), …)}, PaperService.closeForSignal:885-887 calls openForSignal:357-370,
   * which filters {@code o.signal_id = <that anchor id>} on the §F.6 open key.
   *
   * <p><b>The rescuer here is REAL</b> — an ACTIVE ENTRY with its OWN order row on the position's open key
   * (the averaged-add shape: {@code signalIdsFor}'s "usually one; averaged adds share it"). It genuinely
   * would close the position, so it must NOT be flagged. The unlinked case is the same ACTIVE ENTRY with
   * NO order: it looks alive and cannot rescue, so it MUST be flagged. Strip the rescuer's order link and
   * this test goes RED — that is the mutation the first version of this test could not survive, because
   * its "rescuer" had no order and was inert.
   */
  @Test
  void onlyAnOrderLinkedLiveEntryRescuesAPosition() {
    seedAutoPaperToggle(AUTO_BOOK, true);
    UUID swing = seedSiblingVersion("swing");
    OffsetDateTime now = OffsetDateTime.now();

    String rescuedSym = sym("REALRESCUE");
    long deadAnchor = seedSignal(swing, rescuedSym, "EXPIRED", 50, "ENTRY", "BUY", now.minusHours(4));
    long rescuedPosition =
        seedPosition(rescuedSym, "BUY", 50, "OPEN", now.minusHours(4), null, deadAnchor, AUTO_BOOK);
    seedOrder(AUTO_BOOK, deadAnchor, rescuedSym, "BUY", 50, now.minusHours(4));
    // The rescuer: ACTIVE ENTRY *with its own order* on this position's open key → really can settle it.
    long linkedRescuer = seedSignal(swing, rescuedSym, "ACTIVE", 50, "ENTRY", "BUY", now.minusHours(1));
    seedOrder(AUTO_BOOK, linkedRescuer, rescuedSym, "BUY", 50, now.minusHours(1));

    String unlinkedSym = sym("INERTRESCUE");
    long ownAnchor = seedSignal(swing, unlinkedSym, "EXPIRED", 50, "ENTRY", "BUY", now.minusHours(4));
    long unrescuedPosition =
        seedPosition(unlinkedSym, "BUY", 50, "OPEN", now.minusHours(4), null, ownAnchor, AUTO_BOOK);
    seedOrder(AUTO_BOOK, ownAnchor, unlinkedSym, "BUY", 50, now.minusHours(4));
    // Same ACTIVE ENTRY shape but NO order row — openForSignal can never reach this position through it.
    seedSignal(swing, unlinkedSym, "ACTIVE", 50, "ENTRY", "BUY", now.minusHours(1));

    run();

    assertThat(deadAnchorOrphanIds(latestRunId()))
        .contains(unrescuedPosition)
        .doesNotContain(rescuedPosition);
  }

  /**
   * Pins the DRIVER-BY-STYLE condition — the two exit drivers genuinely disagree about superseded
   * versions, so this cannot be a global "must be current published" rule.
   *
   * <p>SWING: {@code SwingBatchEngine.adoptVersion:209-235} resolves a superseded/unpublished version for
   * exit management and deliberately does NOT check {@code enabled} ("a disabled strategy no longer ENTERS
   * but its open positions must still be exit-managed"), so a superseded swing lot is healthy and must NOT
   * be flagged. This is not hypothetical: 5 of the 19 live OPEN positions (ids 10/21/22/26/27) are exactly
   * this shape, so a global current-published rule would false-alarm on all five.
   *
   * <p>NON-SWING: {@code SignalEngine.reload():331} skips unless {@code enabled} and loads ONLY {@code
   * publishedVersionId}, so a SUPERSEDED non-swing anchor is never evaluated — genuinely unexitable, and it
   * MUST be flagged; the same anchor on the strategy's CURRENT published version IS evaluated and must NOT
   * be. That last control is load-bearing and was MISSING on the first pass: without it, dropping the
   * {@code published_version_id = sv.id AND st.enabled} branch entirely left the whole suite GREEN (the
   * superseded-intraday row is flagged by the failing swing check either way), i.e. the branch was
   * vacuously unpinned.
   */
  @Test
  void versionEvaluabilityFollowsTheOwningDriver() {
    seedAutoPaperToggle(AUTO_BOOK, true);
    // Drafts off the sample strategy → never its published_version_id → SUPERSEDED by construction.
    UUID supersededSwing = seedSiblingVersion("swing");
    UUID supersededIntraday = seedSiblingVersion("intraday");
    UUID publishedIntraday = seedPublishedStrategyVersion("intraday");
    OffsetDateTime now = OffsetDateTime.now();

    String swingSym = sym("SUPERSWING");
    long swingAnchor = seedSignal(supersededSwing, swingSym, "TAKEN", 50, "ENTRY", "BUY", now.minusHours(3));
    long swingPosition =
        seedPosition(swingSym, "BUY", 50, "OPEN", now.minusHours(3), null, swingAnchor, AUTO_BOOK);
    seedOrder(AUTO_BOOK, swingAnchor, swingSym, "BUY", 50, now.minusHours(3));

    String staleSym = sym("SUPERINTRA");
    long staleAnchor = seedSignal(supersededIntraday, staleSym, "TAKEN", 50, "ENTRY", "BUY", now.minusHours(3));
    long stalePosition =
        seedPosition(staleSym, "BUY", 50, "OPEN", now.minusHours(3), null, staleAnchor, AUTO_BOOK);
    seedOrder(AUTO_BOOK, staleAnchor, staleSym, "BUY", 50, now.minusHours(3));

    String liveSym = sym("PUBINTRA");
    long liveAnchor = seedSignal(publishedIntraday, liveSym, "TAKEN", 50, "ENTRY", "BUY", now.minusHours(3));
    long livePosition =
        seedPosition(liveSym, "BUY", 50, "OPEN", now.minusHours(3), null, liveAnchor, AUTO_BOOK);
    seedOrder(AUTO_BOOK, liveAnchor, liveSym, "BUY", 50, now.minusHours(3));

    run();

    assertThat(deadAnchorOrphanIds(latestRunId()))
        .contains(stalePosition)
        .doesNotContain(swingPosition, livePosition);
  }

  /**
   * The no-linkage shape on an auto-paper book: nothing can close it (the engine reaches a position only
   * through a signal, and intradayOpen:339-353 joins {@code o.signal_id IS NOT NULL}, so the 15:45 sweep
   * cannot see it either). Both controls are load-bearing: a MANUAL book's hand position is closed BY HAND
   * (V16's own auto-book join), and a position whose order links a LIVE ENTRY is reachable through it even
   * with a NULL {@code opening_signal_id}.
   */
  @Test
  void unanchoredAutoBookPositionIsFlaggedButManualAndLiveLinkedAreNot() {
    seedAutoPaperToggle(AUTO_BOOK, true);
    seedAutoPaperToggle(MANUAL_BOOK, false);
    UUID swing = seedSiblingVersion("swing");
    OffsetDateTime opened = OffsetDateTime.now().minusHours(2);

    long orphanId = seedPosition(sym("UNANCHORED"), "BUY", 50, "OPEN", opened, null, null, AUTO_BOOK);
    long handId = seedPosition(sym("BYHAND"), "BUY", 50, "OPEN", opened, null, null, MANUAL_BOOK);

    String linkedSym = sym("ORDERLINKED");
    long linkedSignal = seedSignal(swing, linkedSym, "TAKEN", 50, "ENTRY", "BUY", opened);
    long linkedId = seedPosition(linkedSym, "BUY", 50, "OPEN", opened, null, null, AUTO_BOOK);
    seedOrder(AUTO_BOOK, linkedSignal, linkedSym, "BUY", 50, opened);

    run();

    assertThat(deadAnchorOrphanIds(latestRunId())).contains(orphanId).doesNotContain(handId, linkedId);
  }

  /**
   * The alert contract (the exact shape v1 of the stranded-carry check was REJECTED for getting wrong): a
   * persistent STATE count must stay OUT of {@code totalDiscrepancies()} — that value gates the nightly
   * V5/V16 ntfy push at {@code == 0}, so one standing orphan folded in would push an identical alert every
   * night until the owner muted the channel, taking V5 and V16 blind. The count rides a Gauge (never a
   * Counter, which fed a state value would re-report the same orphan forever) and alerts only on newly-seen
   * ids.
   */
  @Test
  void deadAnchorOrphansStayOutOfTheActivityTotalAndAlertOnlyOnNewIds() {
    seedAutoPaperToggle(AUTO_BOOK, true);
    UUID swing = seedSiblingVersion("swing");
    OffsetDateTime now = OffsetDateTime.now();
    String sym = sym("ORPHANALERT");
    long anchorId = seedSignal(swing, sym, "EXPIRED", 50, "ENTRY", "BUY", now.minusHours(2));
    long positionId = seedPosition(sym, "BUY", 50, "OPEN", now.minusHours(2), null, anchorId, AUTO_BOOK);
    seedOrder(AUTO_BOOK, anchorId, sym, "BUY", 50, now.minusHours(2));
    when(notifier.configured("NTFY")).thenReturn(true);

    ReconciliationResult first = run();
    verify(notifier, times(1))
        .send(eq("NTFY"), contains("dead-anchor orphan"), contains(Long.toString(positionId)));
    clearInvocations(notifier);
    ReconciliationResult second = run();

    // The orphan is real and counted…
    assertThat(first.deadAnchorOrphanPositions()).contains(positionId);
    assertThat(meters.find("ay_paper_recon_dead_anchor_orphans").gauge().value())
        .isEqualTo(second.deadAnchorOrphanDiscrepancies());
    assertThat(meters.find("ay_paper_recon_dead_anchor_orphans").counter()).isNull();
    // …yet it never enters the activity total that gates the V5/V16 push.
    assertThat(first.totalDiscrepancies())
        .isEqualTo(first.v5Discrepancies() + first.v16Discrepancies());
    assertThat(second.totalDiscrepancies())
        .isEqualTo(second.v5Discrepancies() + second.v16Discrepancies());
    assertThat(latestRunTotal()).isEqualTo(second.totalDiscrepancies());
    assertThat(deadAnchorOrphanIds(latestRunId())).contains(positionId);
    // …and a standing orphan is silent on every later pass.
    verify(notifier, never())
        .send(eq("NTFY"), contains("dead-anchor orphan"), contains(Long.toString(positionId)));
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

  /**
   * A NEW enabled strategy whose CURRENT {@code published_version_id} is the returned version — the only
   * shape {@code SignalEngine.reload():331} loads, and the positive control for the published branch.
   * Needs its own strategy row (the sample strategy's published pointer must not be disturbed), with a
   * unique slug AND name: the singleton IT DB has no per-method cleanup.
   */
  private UUID seedPublishedStrategyVersion(String style) {
    String unique = PREFIX + "-" + UUID.randomUUID().toString().substring(0, 8);
    String strategyId =
        jdbc.queryForObject(
            "INSERT INTO strategies (slug, name, enabled) VALUES (?, ?, true) RETURNING id::text",
            String.class,
            unique,
            unique);
    String versionRow =
        jdbc.queryForObject(
            """
            INSERT INTO strategy_versions
              (strategy_id, version, config_yaml, config, schema_version, checksum, status, published_at)
            VALUES (?::uuid, ?, '', ?::jsonb, '1', ?, 'published', now())
            RETURNING id::text
            """,
            String.class,
            strategyId,
            unique,
            "{\"risk\":{\"session\":{\"style\":\"" + style + "\"}}}",
            unique);
    jdbc.update(
        "UPDATE strategies SET published_version_id = ?::uuid WHERE id = ?::uuid", versionRow, strategyId);
    return UUID.fromString(versionRow);
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
    return seedSignal(sym, status, suggestedQty, "ENTRY", "BUY", generatedAt);
  }

  private long seedSignal(
      String sym,
      String status,
      Integer suggestedQty,
      String signalType,
      String side,
      OffsetDateTime generatedAt) {
    return seedSignal(versionId, sym, status, suggestedQty, signalType, side, generatedAt);
  }

  private long seedSignal(
      UUID version,
      String sym,
      String status,
      Integer suggestedQty,
      String signalType,
      String side,
      OffsetDateTime generatedAt) {
    Long id =
        jdbc.queryForObject(
            """
            INSERT INTO signals
              (strategy_version_id, exchange, tradingsymbol, "interval", signal_type, side,
               composite_score, score_breakdown, status, generated_at, suggested_qty)
            VALUES (?, 'NFO', ?, '1m', ?, ?, 0.8000, '{}'::jsonb, ?, ?, ?)
            RETURNING id
            """,
            Long.class,
            version,
            sym,
            signalType,
            side,
            status,
            generatedAt,
            suggestedQty);
    return id == null ? 0 : id;
  }

  /**
   * A strategy_versions row of a given {@code session.style}, cloned off {@link #versionId}'s own strategy.
   * Reusing the strategy_id keeps the strategies FK satisfied; the unique constraint is (strategy_id,
   * version), so only the version string must be fresh. It is inserted as a {@code draft} and never
   * published, so it is ALWAYS a SUPERSEDED version — which is what the driver-by-style test needs, and is
   * harmless for the swing tests (the swing batch adopts superseded versions by design). The config is
   * built fresh rather than cloned: the detector reads exactly one path from it
   * ({@code config->'risk'->'session'->>'style'}), so pinning that path makes the style explicit instead of
   * inheriting whatever the flyway seed happens to carry. PREFIX-tagged so {@link #clean()} can delete it
   * without touching the seeded sample strategy.
   */
  private UUID seedSiblingVersion(String style) {
    String id =
        jdbc.queryForObject(
            """
            INSERT INTO strategy_versions
              (strategy_id, version, config_yaml, config, schema_version, checksum, status)
            SELECT strategy_id, ?, config_yaml, ?::jsonb, schema_version, ?, 'draft'
            FROM strategy_versions WHERE id = ?
            RETURNING id::text
            """,
            String.class,
            PREFIX + "-" + UUID.randomUUID().toString().substring(0, 8),
            "{\"risk\":{\"session\":{\"style\":\"" + style + "\"}}}",
            PREFIX + UUID.randomUUID().toString().substring(0, 8),
            versionId);
    return UUID.fromString(id);
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

  private long latestRunId() {
    Long id = jdbc.queryForObject(
        "SELECT id FROM paper_reconciliation_runs ORDER BY ran_at DESC, id DESC LIMIT 1", Long.class);
    return id == null ? 0 : id;
  }

  private List<Long> strandedCarryIds(long runId) {
    return runDetailIds(runId, "strandedCarry");
  }

  private List<Long> deadAnchorOrphanIds(long runId) {
    return runDetailIds(runId, "deadAnchorOrphans");
  }

  private List<Long> runDetailIds(long runId, String detailKey) {
    return jdbc.query(
        """
        SELECT v::bigint
        FROM paper_reconciliation_runs r,
             jsonb_array_elements_text(r.detail->?::text->'positions') v
        WHERE r.id = ?
        ORDER BY v::bigint
        """,
        (rs, rowNum) -> rs.getLong(1),
        detailKey,
        runId);
  }

  private int latestRunTotal() {
    Integer total = jdbc.queryForObject(
        "SELECT total_discrepancies FROM paper_reconciliation_runs ORDER BY ran_at DESC, id DESC LIMIT 1",
        Integer.class);
    return total == null ? 0 : total;
  }
}
