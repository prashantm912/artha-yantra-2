package in.arthayantra.strategysignal.paper;

import java.sql.Array;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC for the nightly paper-ledger reconciliation (audit §8, V5 + V16). All reads are READ-ONLY over
 * the strategy-schema paper/signal tables ({@code paper_positions}, {@code paper_orders},
 * {@code signals}, {@code risk_settings}); the only write is the append-only run row on
 * {@code paper_reconciliation_runs}. Stranded carry and dead-anchor orphans are global state and are
 * deliberately not bounded by the V5/V16 window. Every window bound is an EXPLICIT instant (timestamptz), never a
 * {@code ::date = CURRENT_DATE} predicate (in-container {@code now()} is UTC — off-by-one across IST
 * midnight).
 *
 * <p><b>Positions ↔ orders join (V5).</b> There is no position-id FK on order rows. An order is tied
 * to a position by the §F.6 open key {@code (book, exchange, tradingsymbol, side)} — the exact join the
 * live paths use ({@link PaperPositionRepository#signalIdsFor}, {@code intradayOpen}, {@code
 * openForSignal}) — and TIME-SCOPED to the position's own lifetime {@code [opened_at, closed_at]} (the
 * V026 backfill's rule, so a re-opened key is attributed to the right lifetime). The ENTRY legs carry
 * the position's own side ({@code PaperService.openOrder}); the EXIT leg carries the OPPOSITE side and a
 * NULL {@code signal_id} ({@code PaperService.doSettle}). So per closed position: Σ(same-side leg qty)
 * must equal the position qty, and ≥ 1 opposite-side leg must exist.
 */
@Repository
public class PaperReconciliationRepository {

  /**
   * One CLOSED position's V5 reconciliation tallies: the summed ENTRY-leg qty and the EXIT-leg count,
   * both time-scoped to {@code [opened_at, closed_at]}, against the position's own qty.
   */
  public record ClosedPositionRecon(long positionId, long entryQty, long positionQty, long exitCount) {}

  private final JdbcTemplate jdbc;

  /** Wires the strategy datasource. */
  public PaperReconciliationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Every CLOSED position closed within the window, with its ENTRY-leg qty sum and EXIT-leg count
   * (both time-scoped to the position lifetime, matched on the §F.6 open key). Only CLOSED positions
   * are checked — an OPEN position legitimately has no exit leg yet. The caller classifies each row
   * into the V5 discrepancy classes (missing-entry / qty-mismatch / missing-exit).
   */
  public List<ClosedPositionRecon> closedPositionReconciliation(OffsetDateTime from, OffsetDateTime to) {
    return jdbc.query(
        """
        SELECT p.id AS position_id,
               COALESCE(e.entry_qty, 0) AS entry_qty,
               p.qty                    AS position_qty,
               COALESCE(x.exit_count, 0) AS exit_count
        FROM paper_positions p
        LEFT JOIN LATERAL (
          SELECT COALESCE(SUM(o.qty), 0) AS entry_qty
          FROM paper_orders o
          WHERE o.book = p.book AND o.exchange = p.exchange AND o.tradingsymbol = p.tradingsymbol
            AND o.side = p.side
            AND COALESCE(o.filled_at, o.placed_at) BETWEEN p.opened_at AND p.closed_at
        ) e ON true
        LEFT JOIN LATERAL (
          SELECT COUNT(*) AS exit_count
          FROM paper_orders o
          WHERE o.book = p.book AND o.exchange = p.exchange AND o.tradingsymbol = p.tradingsymbol
            AND o.side <> p.side
            AND COALESCE(o.filled_at, o.placed_at) BETWEEN p.opened_at AND p.closed_at
        ) x ON true
        WHERE p.status = 'CLOSED' AND p.closed_at >= ? AND p.closed_at <= ?
        """,
        (rs, n) ->
            new ClosedPositionRecon(
                rs.getLong("position_id"),
                rs.getLong("entry_qty"),
                rs.getLong("position_qty"),
                rs.getLong("exit_count")),
        from,
        to);
  }

  /**
   * How many TAKEN signals generated within the window were EXPECTED to open a position — i.e. carried a
   * positive {@code suggested_qty} (the auto-paper path only takes a signal that has one;
   * {@link AutoPaperListener}). The denominator for the V16 take-side check.
   */
  public int takenSignalsExpectedToOpen(OffsetDateTime from, OffsetDateTime to) {
    Integer c =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM signals s
            WHERE s.status = 'TAKEN'
              AND s.suggested_qty IS NOT NULL AND s.suggested_qty > 0
              AND s.generated_at >= ? AND s.generated_at <= ?
            """,
            Integer.class,
            from,
            to);
    return c == null ? 0 : c;
  }

  /**
   * V16 take-side orphans: TAKEN signals in the window that were expected to open a position
   * ({@code suggested_qty > 0}) yet have NO {@code paper_orders.signal_id} row — the auto-paper open
   * threw after the CAS take (the A1 residual). {@code suggested_qty > 0} excludes a deliberate manual
   * "take without qty" ack (never expected to open a position — {@code SignalsController.taken}).
   */
  public List<Long> takenSignalsWithoutOrder(OffsetDateTime from, OffsetDateTime to) {
    return jdbc.queryForList(
        """
        SELECT s.id FROM signals s
        WHERE s.status = 'TAKEN'
          AND s.suggested_qty IS NOT NULL AND s.suggested_qty > 0
          AND s.generated_at >= ? AND s.generated_at <= ?
          AND NOT EXISTS (SELECT 1 FROM paper_orders o WHERE o.signal_id = s.id)
        ORDER BY s.id
        """,
        Long.class,
        from,
        to);
  }

  /**
   * V16 position-side inverse: positions opened in the window on an AUTO-PAPER book (a book whose
   * {@code auto_paper_trade} risk toggle is ON — data-driven, not a hardcoded family list) carrying NO
   * {@code opening_signal_id}. On an automated book every open should trace to a signal; a NULL there is
   * a linkage gap. A {@code manual}/{@code other} book (toggle OFF) legitimately carries hand orders with
   * no signal linkage and is excluded by the join.
   */
  public List<Long> autoPaperPositionsWithoutSignal(OffsetDateTime from, OffsetDateTime to) {
    return jdbc.queryForList(
        """
        SELECT p.id FROM paper_positions p
        JOIN risk_settings rs
          ON rs.book = p.book AND rs.key = 'auto_paper_trade'
          AND COALESCE((rs.value->>'enabled')::boolean, false) = true
        WHERE p.opening_signal_id IS NULL
          AND p.opened_at >= ? AND p.opened_at <= ?
        ORDER BY p.id
        """,
        Long.class,
        from,
        to);
  }

  /**
   * Global stranded-carry state: an OPEN position anchored to an ENTRY signal that has a persisted
   * opposite-side EXIT in that anchor's own ENTRY-to-next-ENTRY window. Deliberate pyramid adds remain
   * in the original position's window; signal id breaks ties when candle-derived timestamps are equal.
   * This is deliberately not windowed; the state remains actionable until a human repairs it.
   *
   * <p><b>Both side predicates are SAME-SIDE-SCOPED on purpose, and today both are unreachable.</b>
   * {@code uq_paper_positions_open} (V021:15-16) keys on {@code (book, exchange, tradingsymbol, side)}
   * WHERE status='OPEN', so a BUY and a SELL position MAY structurally coexist on one symbol. No live
   * path reaches that today — {@code SignalRepository.activeEntry:167-179} forces the engine down the
   * exit branch while an anchor is ACTIVE/TAKEN, and {@code SwingBatchEngine.entryPass:277-279} skips a
   * held symbol unless the pyramid policy adds (an add is same-side AND {@code pyramidLot}-tagged, so
   * it is excluded anyway) — and every live ENTRY is BUY, so {@code x.side <> s.side} is currently a
   * tautology. The guards are carried anyway because F9 short-premium would arm short entries, and
   * they must be carried TOGETHER: {@code x.side <> s.side} alone (a sibling's EXIT must not match)
   * without {@code n.side = s.side} (a sibling's ENTRY must not close this anchor's window) is
   * internally inconsistent — the second omission is a FALSE NEGATIVE, which for a safety net is
   * strictly worse than the false positive the first one prevents.
   *
   * <p><b>Known gap (dormant):</b> {@code SwingBatchEngine.openLotsBySymbol:338-346} groups
   * {@code signals.activeEntries()} — a version-less read — by tradingsymbol only, and
   * {@code emitExit:599-600} writes ONE EXIT under the OLDEST lot's {@code strategy_version_id} while
   * expiring every lot in the group. If a non-oldest lot anchors a position, this predicate looks for
   * an EXIT under THAT version and finds none (a false negative). Dormant: live same-symbol overlaps
   * are all minervini_funnel x manas_arora_funnel, which {@code adoptVersion:219-221} filters apart by
   * universeMode, and within a family {@code entryPass} blocks a second lot while pyramiding is
   * disarmed. <b>This predicate assumes one version per (symbol, family).</b>
   */
  public List<Long> strandedCarryPositions() {
    return jdbc.query(
        """
        SELECT p.id
        FROM paper_positions p
        JOIN signals s ON s.id = p.opening_signal_id
        WHERE p.status = 'OPEN'
          AND s.signal_type = 'ENTRY'
          AND EXISTS (
            SELECT 1 FROM signals x
            WHERE x.strategy_version_id = s.strategy_version_id
              AND x.exchange            = s.exchange
              AND x.tradingsymbol       = s.tradingsymbol
              AND x.signal_type         = 'EXIT'
              AND x.side <> s.side
              AND (x.generated_at, x.id) > (s.generated_at, s.id)
              AND NOT EXISTS (
                    SELECT 1 FROM signals n
                    WHERE n.strategy_version_id = s.strategy_version_id
                      AND n.exchange            = s.exchange
                      AND n.tradingsymbol       = s.tradingsymbol
                      AND n.signal_type         = 'ENTRY'
                      AND n.side                = s.side
                      AND NOT (
                            COALESCE(n.minervini_detail ? 'pyramidLot', false)
                            OR COALESCE(n.manas_arora_detail ? 'pyramidLot', false)
                          )
                      AND (n.generated_at, n.id) > (s.generated_at, s.id)
                      AND (n.generated_at, n.id) < (x.generated_at, x.id)
                  )
          );
        """,
        (rs, rowNum) -> rs.getLong("id"));
  }

  /**
   * Global dead-anchor-orphan state: an OPEN position with NO <b>healthy anchor</b>, i.e. one that no exit
   * driver will ever settle. This is the structural blind spot of {@link #strandedCarryPositions()} —
   * that predicate tests {@code EXISTS(… signal_type='EXIT' …)}, so it needs an EXIT row to have been
   * persisted; here no exit is ever EVALUATED, no EXIT row is ever written, and its EXISTS can never fire.
   * Not windowed: the state stays actionable until a human repairs it.
   *
   * <p><b>A healthy anchor is defined by the settle path, not by "a live ENTRY row exists somewhere".</b>
   * The exit chain is: {@code SignalEngine:1722} publishes {@code SignalExited(anchor.id(), …)} — the
   * chosen ANCHOR's exact id — {@code PaperService.closeForSignal:1002-1004} then calls {@code
   * PaperPositionRepository.openForSignal:357-370}, which reaches a position ONLY through {@code
   * paper_orders o … WHERE o.signal_id = <that anchor id>} joined on the §F.6 open key ({@code book},
   * {@code exchange}, {@code tradingsymbol}, {@code side}). So an ENTRY that is ACTIVE but is NOT linked
   * to THIS position by an order can never close it: it looks alive and cannot rescue. Three conditions,
   * all necessary:
   *
   * <ol>
   *   <li><b>Live ENTRY</b> — {@code signal_type='ENTRY' AND status IN ('ACTIVE','TAKEN')}: both drivers
   *       gate on it ({@code SignalRepository.activeEntry:167-179}, which {@code SignalEngine:959-961}
   *       requires to enter the exit branch at all, and {@code activeEntries:149-152} for the swing batch).
   *       A position whose only linked signal is an <b>EXIT</b> fails here: {@code activeEntry} can never
   *       return one, so a status-only test reads such a position healthy. ⚠️ <b>Historical provenance,
   *       re-verified 2026-08-02:</b> this condition was written against a real live row,
   *       {@code paper_positions} id=28 linked to signal id=46, and earlier wording here described that row
   *       as live. It is not: id=28 has been CLOSED since 2026-07-17 13:04 IST ({@code close_reason=MANUAL})
   *       and signal 46 is now EXPIRED rather than ACTIVE. No currently-open position matches the shape.
   *       The condition stands on its own logic; do not cite id=28 as a current example.
   *   <li><b>Linked to this position</b> — through {@code paper_orders.signal_id} on the open key, the
   *       exact join {@code openForSignal} uses. Deliberately NOT lifetime-bounded: {@code openForSignal}
   *       has no lifetime bound either (it filters only {@code p.status='OPEN'}), so a stale same-key link
   *       DOES settle the current position in production. Adding a bound here would make the detector
   *       stricter than the code it models and flag a position that production would actually close.
   *   <li><b>On a version that is PLAUSIBLY driver-owned</b> — a deliberately weaker test than "a driver
   *       will load it" (see the scope note below), and necessarily <b>per-style, because the two drivers
   *       disagree</b>. The swing batch adopts SUPERSEDED/unpublished versions of its own family for exit
   *       management ({@code SwingBatchEngine.adoptVersion:216-247}; {@code enabled} is deliberately NOT
   *       checked — "a disabled strategy no longer ENTERS but its open positions must still be
   *       exit-managed"), so {@code style='swing'} is the closest DB-answerable proxy. The live engine
   *       does NOT adopt: {@code SignalEngine.reload():425-427} skips a strategy unless {@code enabled} and
   *       loads ONLY {@code publishedVersionId}. Requiring current-published GLOBALLY would be a mass
   *       false positive — 5 of the 19 live OPEN positions (ids 10/21/22/26/27) are swing lots on
   *       SUPERSEDED versions the batch is correctly still exit-managing — and dropping either arm
   *       re-opens the inverse false negative. Both arms are kept for exactly that reason.
   * </ol>
   *
   * <p><b>SCOPE — what this check does NOT prove, stated plainly.</b> Both version arms are NECESSARY but
   * NOT SUFFICIENT: they establish that a version is plausibly driver-owned, never that a driver will
   * actually load it. Where a driver refuses the anchor anyway, the position is a FALSE NEGATIVE here —
   * it looks healthy and nothing exits it. Only TWO of those routes are covered by another signal:
   *
   * <ul>
   *   <li>a FAILED (Kite-dependent) universe resolution — {@code SignalEngine:475-476} counts it into
   *       {@code unresolvedDrops}, exposed as {@code lastReloadUnresolvedDrops:234} and gating
   *       {@code ReloadOutcome.isHealthy():189} ({@code unresolvedDrops == 0 && loadErrors == 0}), which
   *       drives the DEGRADED path + retry chain;
   *   <li>an exception thrown while loading — {@code SignalEngine:542} counts it into {@code loadErrors}
   *       (F10 / #892), same {@code isHealthy()} gate.
   * </ul>
   *
   * <p><b>Everything else in that class is a BLIND SPOT — nothing counts it, nothing alerts on it, and
   * this check cannot see it either.</b> These are not owned elsewhere; they are simply unobserved:
   *
   * <ul>
   *   <li><b>A disabled swing doctrine.</b> {@code SwingBatchEngine.runDaily:151-153} no-ops, and
   *       {@code SwingBatchRecorder.runAndRecord:61-63} returns BEFORE the record + announce ("disarmed
   *       no-op — nothing ran, nothing to record or announce"). No run row, no alert, no counter.
   *   <li><b>A rejected swing adoption.</b> {@code adoptVersion:216-247} returns empty when the config
   *       fails to compile. {@code openLotsBySymbol:354-361} then
   *       silently omits the anchor ({@code resolve(...).isPresent()}), and {@code exitSkipped}
   *       ({@code ExitResult.skipped():112}, reported at {@code :178}) only counts lots that REACHED the
   *       exit pass — a rejected anchor never does, so it increments nothing. (The {@code universe.mode}
   *       mismatch at {@code :219-221} is NOT part of this: that is deliberate cross-family routing — the
   *       owning family's doctrine adopts it. It only bites when NO family's mode matches.)
   *   <li><b>The uncounted {@code reload()} skips</b>, each a {@code continue} counted by NEITHER
   *       counter: a scalper with no bounding exit ({@code :450-455}), a non-rollable primary on a
   *       non-btst strategy ({@code :468-473}), {@code NOT_LIVE_RESOLVABLE} ({@code :481-488}, whose own
   *       comment says "NOT counted"), and {@code RESOLVED_EMPTY} ({@code :490-497}, "never counted" —
   *       standing aside is correct for ENTRIES, but the strategy is not loaded, so its open positions
   *       are not exit-evaluated either). Each logs; nothing aggregates it.
   * </ul>
   *
   * <p>Two skips are NOT blind spots because this check itself catches them: {@code !enabled ||
   * publishedVersionId == null} ({@code :425-427}) is exactly what the published arm excludes, and a
   * missing version row ({@code :430-432}) breaks the {@code strategy_versions} join — both flag the
   * position. A {@code style='swing'} skip ({@code :459-461}) is correct routing, not a drop.
   *
   * <p>The narrow scope is deliberate: compilation and universe resolution are not SQL-expressible facts,
   * and reading the engine's loaded-version state would couple this nightly DB reconciler to engine
   * RUNTIME state — the exact coupling the F10 work removed. But narrow scope must not be dressed up as
   * delegation. <b>A documented blind spot is honest; naming an owner that does not exist is worse than
   * silence, because it tells the next reader to stop looking</b> — which is the
   * {@link #strandedCarryPositions()} hole all over again. Closing any of the above needs its own signal
   * and its own decision; it is not this query's job and is not silently handled by anyone today.
   *
   * <p>The auto-paper-book join is V16's ({@link #autoPaperPositionsWithoutSignal}) and answers the "no
   * other mechanism can close it" question: a {@code manual}/{@code other} book's position is closed BY
   * HAND, and the engine reaches a position only through a signal — {@code
   * PaperPositionRepository.intradayOpen:339-353} (the 15:45 mark-to-close) also joins {@code o.signal_id
   * IS NOT NULL}, so an unlinked position is outside the sweep set too. V16 sees the no-linkage shape only
   * INSIDE its activity window, so a stale one is invisible to it forever; this check is the state half.
   */
  public List<Long> deadAnchorOrphanPositions() {
    return jdbc.query(
        """
        SELECT p.id
        FROM paper_positions p
        JOIN risk_settings rs
          ON rs.book = p.book AND rs.key = 'auto_paper_trade'
          AND COALESCE((rs.value->>'enabled')::boolean, false) = true
        WHERE p.status = 'OPEN'
          AND NOT EXISTS (
                SELECT 1
                FROM paper_orders o
                JOIN signals a            ON a.id  = o.signal_id
                JOIN strategy_versions sv ON sv.id = a.strategy_version_id
                JOIN strategies st        ON st.id = sv.strategy_id
                WHERE o.book          = p.book
                  AND o.exchange      = p.exchange
                  AND o.tradingsymbol = p.tradingsymbol
                  AND o.side          = p.side
                  -- V058: mirrors PaperPositionRepository.openForSignal's strategy predicate. On a
                  -- strategy-scoped book a live ENTRY from a SIBLING strategy no longer reaches this
                  -- position through closeForSignal, so counting it as a healthy anchor here would be
                  -- a false NEGATIVE in a detector whose whole job is "no exit driver will ever settle
                  -- this row". Null strategy_id (unscoped/legacy) matches on the key alone, as before.
                  AND (p.strategy_id IS NULL OR p.strategy_id = sv.strategy_id)
                  AND a.signal_type   = 'ENTRY'
                  AND a.status IN ('ACTIVE', 'TAKEN')
                  AND (
                        sv.config->'risk'->'session'->>'style' = 'swing'
                        OR (st.published_version_id = sv.id AND st.enabled)
                      )
              )
        ORDER BY 1;
        """,
        (rs, rowNum) -> rs.getLong("id"));
  }

  /** Reads the stranded-carry ids stored by the most recent prior run, treating missing keys as empty. */
  public List<Long> previousStrandedCarryIds() {
    return previousRunIds("strandedCarry");
  }

  /**
   * Reads the dead-anchor-orphan ids stored by the most recent prior run. Runs written before this check
   * existed carry no such key — COALESCE treats them as empty, so the first pass after deploy reports every
   * standing orphan exactly once and is silent thereafter.
   */
  public List<Long> previousDeadAnchorOrphanIds() {
    return previousRunIds("deadAnchorOrphans");
  }

  /** The position ids the most recent prior run stored under {@code detail-><key>->'positions'}. */
  private List<Long> previousRunIds(String detailKey) {
    return jdbc.query(
            """
            SELECT COALESCE((SELECT array_agg(v::bigint)
                             FROM jsonb_array_elements_text(detail->?::text->'positions') v), '{}')
            FROM paper_reconciliation_runs ORDER BY ran_at DESC, id DESC LIMIT 1
            """,
            (rs, rowNum) -> {
              Array array = rs.getArray(1);
              if (array == null) {
                return List.<Long>of();
              }
              Object raw = array.getArray();
              if (!(raw instanceof Object[] values)) {
                return List.<Long>of();
              }
              return Arrays.stream(values)
                  .map(value -> value instanceof Number n ? n.longValue() : Long.parseLong(value.toString()))
                  .toList();
            },
            detailKey)
        .stream()
        .findFirst()
        .orElseGet(List::of);
  }

  /** Appends the run row (audit output). Returns the generated id. */
  public long insertRun(
      OffsetDateTime windowStart,
      OffsetDateTime windowEnd,
      int positionsChecked,
      int takenSignalsChecked,
      int v5Discrepancies,
      int v16Discrepancies,
      String detailJson) {
    Long id =
        jdbc.queryForObject(
            """
            INSERT INTO paper_reconciliation_runs
              (window_start, window_end, positions_checked, taken_signals_checked,
               v5_discrepancies, v16_discrepancies, total_discrepancies, detail)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb) RETURNING id
            """,
            Long.class,
            windowStart,
            windowEnd,
            positionsChecked,
            takenSignalsChecked,
            v5Discrepancies,
            v16Discrepancies,
            v5Discrepancies + v16Discrepancies,
            detailJson);
    return id == null ? 0 : id;
  }
}
