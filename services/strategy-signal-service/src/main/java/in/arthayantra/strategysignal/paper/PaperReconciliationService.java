package in.arthayantra.strategysignal.paper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.notifier.NotifierClient;
import in.arthayantra.strategysignal.paper.PaperReconciliationRepository.ClosedPositionRecon;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Nightly paper-ledger reconciliation (app-platform audit 2026-07-10 §8, findings V5 + V16). READ-ONLY
 * over the paper/signal tables; the only write is its own append-only {@code paper_reconciliation_runs}
 * row. V5 and V16 are bounded IST-day activity checks; stranded carry is a global state check:
 *
 * <ul>
 *   <li><b>V5</b> — every CLOSED position's lifecycle is explicable by its order legs: Σ(entry-leg qty)
 *       == position qty AND ≥ 1 opposite-side exit leg exists. Entry legs are matched on the §F.6 open
 *       key time-scoped to the position lifetime; exit legs prefer V059's exact
 *       {@code paper_orders.settles_position_id} link and fall back to that same key rule only for an
 *       unlinked (pre-V059) order — without which one settle order satisfied every position sharing the
 *       key, and the {@code exitCount() == 0} test below turned a real missing exit into a FALSE
 *       NEGATIVE. Classes: missing-entry / entry-qty-mismatch / missing-exit.
 *   <li><b>V16</b> — every TAKEN signal expected to open a position ({@code suggested_qty > 0}) has ≥ 1
 *       {@code paper_orders.signal_id} row (the A1 "taken-but-never-opened" residual), plus the inverse:
 *       a position on an auto-paper book with no {@code opening_signal_id}.
 *   <li><b>Stranded carry</b> — every OPEN position anchored to an ENTRY with a persisted opposite-side
 *       EXIT in that anchor's own ENTRY-to-next-non-pyramid-ENTRY window. It alerts only when a position
 *       id is newly seen.
 *   <li><b>Dead-anchor orphans</b> — every OPEN position that no exit evaluator will ever anchor, so no
 *       EXIT is ever emitted for it. Stranded carry needs a persisted EXIT row to see a position and is
 *       therefore structurally blind to this class; the two are complementary halves of one net. Also
 *       newly-seen-gated.
 * </ul>
 *
 * <p>Both state checks stay OUT of {@link ReconciliationResult#totalDiscrepancies()} — see its javadoc.
 *
 * <p>The audit's "report to the ingest health board" is served IN-SCHEMA (that board is marketdata's —
 * a cross-schema write violates D10): the run row + {@code ay_paper_recon_*} Micrometer counters/gauge +
 * the V5/V16 once-per-run fail-soft ntfy plus a separate newly-seen stranded-carry ntfy (the
 * {@link RiskService} governor-trip idiom).
 * A plain paper-module {@code @Service} — NO {@code SignalEngine} dependency, so it loads in the
 * engine-disabled paper contexts.
 */
@Service
public class PaperReconciliationService {

  private static final Logger log = LoggerFactory.getLogger(PaperReconciliationService.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final PaperReconciliationRepository repo;
  private final NotifierClient notifier;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final int lookbackDays;

  private final Counter runsTotal;
  private final Counter v5DiscrepanciesTotal;
  private final Counter v16DiscrepanciesTotal;
  private final AtomicInteger strandedCarryGauge;
  private final AtomicInteger deadAnchorOrphanGauge;

  /** Wires the reconciliation repo, notifier, JSON mapper, clock, meters + the lookback window. */
  public PaperReconciliationService(
      PaperReconciliationRepository repo,
      NotifierClient notifier,
      ObjectMapper mapper,
      Clock clock,
      MeterRegistry meterRegistry,
      @Value("${artha.paper.reconciliation.lookback-days:1}") int lookbackDays) {
    this.repo = repo;
    this.notifier = notifier;
    this.mapper = mapper;
    this.clock = clock;
    this.lookbackDays = Math.max(lookbackDays, 1);
    this.runsTotal = meterRegistry.counter("ay_paper_recon_runs_total");
    this.v5DiscrepanciesTotal = meterRegistry.counter("ay_paper_recon_v5_discrepancies_total");
    this.v16DiscrepanciesTotal = meterRegistry.counter("ay_paper_recon_v16_discrepancies_total");
    this.strandedCarryGauge = meterRegistry.gauge("ay_paper_recon_stranded_carry", new AtomicInteger());
    // A Gauge, never a Counter: this is persistent STATE, and a Counter fed a state value re-reports the
    // same standing orphan every night forever.
    this.deadAnchorOrphanGauge =
        meterRegistry.gauge("ay_paper_recon_dead_anchor_orphans", new AtomicInteger());
  }

  /** The full result of one reconciliation pass (ids per discrepancy class, for the run row + tests). */
  public record ReconciliationResult(
      OffsetDateTime windowStart,
      OffsetDateTime windowEnd,
      int positionsChecked,
      int takenSignalsChecked,
      List<Long> v5MissingEntryOrder,
      List<Long> v5EntryQtyMismatch,
      List<Long> v5MissingExitOrder,
      List<Long> v16TakenWithoutOrder,
      List<Long> v16PositionWithoutSignal,
      List<Long> strandedCarryPositions,
      List<Long> deadAnchorOrphanPositions) {

    /** Distinct positions failing any V5 invariant (a fully-orphaned position hits two classes). */
    public int v5Discrepancies() {
      Set<Long> distinct = new LinkedHashSet<>(v5MissingEntryOrder);
      distinct.addAll(v5EntryQtyMismatch);
      distinct.addAll(v5MissingExitOrder);
      return distinct.size();
    }

    /** V16 take-side orphans + position-side linkage gaps (disjoint id spaces). */
    public int v16Discrepancies() {
      return v16TakenWithoutOrder.size() + v16PositionWithoutSignal.size();
    }

    /**
     * Total discrepancies across both ACTIVITY checks. Deliberately V5 + V16 ONLY: it gates the nightly
     * ntfy push at {@code == 0}, so folding either persistent-STATE count in would push an identical
     * alert every night until the owner muted the channel — taking V5 and V16 blind with it. Each state
     * check reports through its own gauge + its own newly-seen-only alert instead.
     */
    public int totalDiscrepancies() {
      return v5Discrepancies() + v16Discrepancies();
    }

    /** Persistent OPEN positions whose exit was persisted but whose settle did not complete. */
    public int strandedCarryDiscrepancies() {
      return strandedCarryPositions.size();
    }

    /** Persistent OPEN positions that no exit evaluator will ever anchor (so no EXIT is ever emitted). */
    public int deadAnchorOrphanDiscrepancies() {
      return deadAnchorOrphanPositions.size();
    }
  }

  /** The nightly entry point: reconcile over the default lookback window (IST-day bounded). */
  public ReconciliationResult reconcile() {
    OffsetDateTime windowEnd = OffsetDateTime.ofInstant(clock.instant(), IST);
    OffsetDateTime windowStart =
        LocalDate.ofInstant(clock.instant(), IST)
            .minusDays(lookbackDays - 1L)
            .atStartOfDay(IST)
            .toOffsetDateTime();
    return reconcile(windowStart, windowEnd);
  }

  /**
   * Reconcile over an EXPLICIT window (both bounds absolute instants). Package-visible so an IT can pin
   * a deterministic window independent of the wall clock. Runs the bounded checks plus the global
   * stranded-carry check, persists the run row, bumps the metrics, and pushes each applicable fail-soft
   * alert independently.
   */
  ReconciliationResult reconcile(OffsetDateTime windowStart, OffsetDateTime windowEnd) {
    // V5 — closed position ↔ order legs.
    List<ClosedPositionRecon> recons = repo.closedPositionReconciliation(windowStart, windowEnd);
    List<Long> missingEntry = new java.util.ArrayList<>();
    List<Long> qtyMismatch = new java.util.ArrayList<>();
    List<Long> missingExit = new java.util.ArrayList<>();
    for (ClosedPositionRecon r : recons) {
      if (r.entryQty() == 0) {
        missingEntry.add(r.positionId());
      } else if (r.entryQty() != r.positionQty()) {
        qtyMismatch.add(r.positionId());
      }
      if (r.exitCount() == 0) {
        missingExit.add(r.positionId());
      }
    }

    // V16 — taken signal ↔ position (take-side orphans + inverse position-side linkage gaps).
    int takenChecked = repo.takenSignalsExpectedToOpen(windowStart, windowEnd);
    List<Long> takenWithoutOrder = repo.takenSignalsWithoutOrder(windowStart, windowEnd);
    List<Long> positionWithoutSignal = repo.autoPaperPositionsWithoutSignal(windowStart, windowEnd);
    List<Long> strandedCarryPositions = repo.strandedCarryPositions();
    List<Long> deadAnchorOrphanPositions = repo.deadAnchorOrphanPositions();

    ReconciliationResult result =
        new ReconciliationResult(
            windowStart,
            windowEnd,
            recons.size(),
            takenChecked,
            missingEntry,
            qtyMismatch,
            missingExit,
            takenWithoutOrder,
            positionWithoutSignal,
            strandedCarryPositions,
            deadAnchorOrphanPositions);

    persistAndAlert(result);
    return result;
  }

  private void persistAndAlert(ReconciliationResult r) {
    // Must precede insertRun: the just-written row would otherwise be selected as "previous" and
    // every persistent carry would be incorrectly treated as already seen.
    Set<Long> previousStrandedCarryIds = new LinkedHashSet<>(repo.previousStrandedCarryIds());
    Set<Long> newlySeenStrandedCarryIds = new LinkedHashSet<>(r.strandedCarryPositions());
    newlySeenStrandedCarryIds.removeAll(previousStrandedCarryIds);
    strandedCarryGauge.set(r.strandedCarryDiscrepancies());

    Set<Long> previousDeadAnchorOrphanIds = new LinkedHashSet<>(repo.previousDeadAnchorOrphanIds());
    Set<Long> newlySeenDeadAnchorOrphanIds = new LinkedHashSet<>(r.deadAnchorOrphanPositions());
    newlySeenDeadAnchorOrphanIds.removeAll(previousDeadAnchorOrphanIds);
    deadAnchorOrphanGauge.set(r.deadAnchorOrphanDiscrepancies());

    runsTotal.increment();
    v5DiscrepanciesTotal.increment(r.v5Discrepancies());
    v16DiscrepanciesTotal.increment(r.v16Discrepancies());

    repo.insertRun(
        r.windowStart(),
        r.windowEnd(),
        r.positionsChecked(),
        r.takenSignalsChecked(),
        r.v5Discrepancies(),
        r.v16Discrepancies(),
        detailJson(r));

    if (r.totalDiscrepancies() == 0) {
      log.info(
          "paper reconciliation clean: {} closed positions + {} taken signals checked ({} → {})",
          r.positionsChecked(), r.takenSignalsChecked(), r.windowStart(), r.windowEnd());
    } else {
      String detail =
          "V5 position↔order-leg: " + r.v5Discrepancies() + " (missing-entry "
              + r.v5MissingEntryOrder().size() + ", qty-mismatch " + r.v5EntryQtyMismatch().size()
              + ", missing-exit " + r.v5MissingExitOrder().size() + "); "
              + "V16 taken↔position: taken-without-order " + r.v16TakenWithoutOrder().size()
              + ", position-without-signal " + r.v16PositionWithoutSignal().size()
              + " — window " + r.windowStart() + " → " + r.windowEnd();
      log.warn("paper reconciliation found {} discrepancies: {}", r.totalDiscrepancies(), detail);
      pushAlert("ArthaYantra Paper — reconciliation drift (" + r.totalDiscrepancies() + ")", detail);
    }
    if (!newlySeenStrandedCarryIds.isEmpty()) {
      String detail =
          "New stranded-carry OPEN positions with persisted EXIT signals: "
              + newlySeenStrandedCarryIds + " — global state requires human repair";
      log.warn("paper reconciliation found {} newly-seen stranded carries: {}",
          newlySeenStrandedCarryIds.size(), newlySeenStrandedCarryIds);
      pushAlert(
          "ArthaYantra Paper — stranded carry (" + newlySeenStrandedCarryIds.size() + ")", detail);
    }
    if (!newlySeenDeadAnchorOrphanIds.isEmpty()) {
      String detail =
          "New dead-anchor orphan OPEN positions (no live ENTRY anchor ⇒ no exit will ever be"
              + " evaluated, so no EXIT signal can ever be written): " + newlySeenDeadAnchorOrphanIds
              + " — global state requires human repair";
      log.warn("paper reconciliation found {} newly-seen dead-anchor orphans: {}",
          newlySeenDeadAnchorOrphanIds.size(), newlySeenDeadAnchorOrphanIds);
      pushAlert(
          "ArthaYantra Paper — dead-anchor orphan (" + newlySeenDeadAnchorOrphanIds.size() + ")",
          detail);
    }
  }

  private String detailJson(ReconciliationResult r) {
    Map<String, Object> v5 = new LinkedHashMap<>();
    v5.put("missingEntryOrder", r.v5MissingEntryOrder());
    v5.put("entryQtyMismatch", r.v5EntryQtyMismatch());
    v5.put("missingExitOrder", r.v5MissingExitOrder());
    Map<String, Object> v16 = new LinkedHashMap<>();
    v16.put("takenWithoutOrder", r.v16TakenWithoutOrder());
    v16.put("positionWithoutSignal", r.v16PositionWithoutSignal());
    Map<String, Object> strandedCarry = new LinkedHashMap<>();
    strandedCarry.put("positions", r.strandedCarryPositions());
    Map<String, Object> deadAnchorOrphans = new LinkedHashMap<>();
    deadAnchorOrphans.put("positions", r.deadAnchorOrphanPositions());
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("v5", v5);
    root.put("v16", v16);
    root.put("strandedCarry", strandedCarry);
    root.put("deadAnchorOrphans", deadAnchorOrphans);
    try {
      return mapper.writeValueAsString(root);
    } catch (JsonProcessingException e) {
      log.warn("paper reconciliation detail serialize failed: {}", e.getMessage());
      return "{}";
    }
  }

  /** One fail-soft ntfy push (skipped silently when ntfy is unconfigured) — the RiskService idiom. */
  private void pushAlert(String title, String message) {
    try {
      if (notifier.configured("NTFY")) {
        notifier.send("NTFY", title, message);
      }
    } catch (RuntimeException e) {
      log.warn("paper reconciliation ntfy push failed: {}", e.getMessage());
    }
  }
}
