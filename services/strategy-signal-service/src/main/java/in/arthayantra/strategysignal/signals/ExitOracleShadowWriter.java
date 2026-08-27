package in.arthayantra.strategysignal.signals;

import in.arthayantra.strategysignal.scalper.ScalperConfluenceGate;
import in.arthayantra.strategysignal.scalper.ScalperGates;
import in.arthayantra.strategysignal.scalper.SentimentLevelShadow;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Bounded ASYNC writer for {@code exit_oracle_shadow} (V056) — the measurement-only counterfactual
 * recorded on every E9 D4 confluence-flip EXIT oracle evaluation.
 *
 * <p>The sole {@code signal-eval} thread must never park on I/O, and this record is produced on the
 * PROTECTIVE EXIT path — the one path where a stall is most expensive, because a position that cannot
 * be evaluated cannot be exited. So the row is ENQUEUED (O(1), never blocks the caller) onto a bounded
 * queue drained by one background thread; a stalled DB fills the queue and further records are DROPPED
 * and counted ({@code ay_exit_oracle_shadow_dropped_total}), never back-pressuring the eval thread.
 * Fail-soft end to end: a persistence failure is logged by the writer thread, never thrown into the
 * caller. Same seam and same guarantees as {@link RiskSuppressionWriter}.
 *
 * <p>⚠️ <b>What these rows are, and are not.</b> They record whether the flip ORACLE would have
 * decided differently on the level operand — not exit timing, and not P&amp;L. The standard
 * {@code ExitEvaluator} runs AFTER the oracle, so a bar the counterfactual would not have exited can
 * still be closed on that same bar by a lower-priority rule (overcount); and where the live position
 * closed, the oracle stops running, so the counterfactual's later bars are absent entirely
 * (censoring). Those two errors run in OPPOSITE directions, so no aggregate over these rows is a
 * P&amp;L answer. The V056 header carries the full statement.
 *
 * <p><b>Dropping a record is the CORRECT failure.</b> This is observability for a decision nobody has
 * made yet; losing a measurement row costs a data point, whereas delaying an exit costs money. If the
 * drop counter is non-zero the measurement is incomplete for that window and the analysis must say so
 * — which is exactly why the drop is counted rather than swallowed.
 */
@Component
public class ExitOracleShadowWriter {

  private static final Logger log = LoggerFactory.getLogger(ExitOracleShadowWriter.class);

  /**
   * Bounded backlog. The oracle fires at most once per held position per bar across the 12 strategies
   * carrying {@code oi-confluence-exit}, so a burst is bounded by the number of concurrently-held
   * positions — far below this. A stalled DB drops past it.
   */
  static final int QUEUE_CAPACITY = 256;

  /** On shutdown, let the queued inserts DRAIN up to this long before abandoning them. */
  static final long SHUTDOWN_DRAIN_MILLIS = 5_000L;

  private final ExitOracleShadowRepository repository;
  private final BoundedAsyncWriter queue;

  /** Wires the JDBC repository + the dropped-record counters (runtime saturation + shutdown loss). */
  public ExitOracleShadowWriter(
      ExitOracleShadowRepository repository, MeterRegistry meterRegistry) {
    this.repository = repository;
    this.queue =
        new BoundedAsyncWriter(
            "exit-oracle-shadow",
            QUEUE_CAPACITY,
            log,
            meterRegistry.counter("ay_exit_oracle_shadow_dropped_total"),
            meterRegistry.counter("ay_exit_oracle_shadow_shutdown_dropped_total"));
  }

  /**
   * Enqueue one oracle evaluation for asynchronous persistence. NON-BLOCKING: the caller (the eval
   * thread, mid-exit-evaluation) returns in O(1) whether the record is queued or — on a saturated
   * queue — dropped and counted. Never throws.
   *
   * <p><b>WHY EACH ROW CARRIES A {@code shadow_reason} (V064, H40).</b> {@code
   * SentimentLevelShadow} carries a {@code reason} discriminator (COMPUTED / NO_OI_CONTEXT /
   * MONTHLY_EXPIRY_SUPPRESSED / LEVEL_UNAVAILABLE / SIDE_UNRESOLVED) which rides both JSONB
   * surfaces — {@code signals.fired_diagnostic} and {@code signal_rejections.diagnostic}. For a
   * while it did NOT ride here: this table decomposes the shadow into typed columns, had no column
   * for it, and the value was dropped on the floor. {@code shadow_verdict_known} records a
   * DIFFERENT fact ({@code counterfactual != null}) and never substituted for it, so on an NSE
   * monthly index expiry every exit-oracle row for the whole session was the same nulls with no
   * discriminator — the exact wall that cost two investigations on 2026-08-25. V064 adds the
   * column and the value is written on EVERY row, including rows whose verdict is not known.
   *
   * <p>⚠️ <b>A NULL {@code shadow_reason} IS A PRE-V064 ROW, NOT A FIFTH CAUSE.</b> Same contract as
   * the absent-key rule on the JSONB surfaces: exclude such rows from a cause breakdown and say how
   * many were excluded. It cannot be forged by a new row — {@link SentimentLevelShadow}'s canonical
   * constructor rejects a null reason, so every row this method writes carries one.
   */
  public void record(
      long entrySignalId,
      String strategySlug,
      OffsetDateTime barTime,
      String heldSide,
      String evaluatedSide,
      String liveOracleSide,
      boolean liveFlip,
      SentimentLevelShadow shadow,
      ScalperConfluenceGate.SentimentCounterfactual counterfactual) {
    // The EXACT counterfactual DECISION (round-3 review): null means "not evaluable", which is a
    // different fact from "would not fire" and must stay distinguishable in the row — every
    // shadow_* column goes NULL together and shadow_verdict_known records which case it is.
    boolean known = counterfactual != null;
    Boolean shadowFlip =
        known
            ? counterfactual.wouldFire()
                && ScalperGates.confluenceFlippedAgainst(
                    heldSide,
                    counterfactual.oracleSide() == null ? "" : counterfactual.oracleSide().name())
            : null;
    queue.submit(
        () -> {
          try {
            repository.insert(
                entrySignalId, strategySlug, barTime, heldSide, evaluatedSide, liveOracleSide,
                liveFlip, shadow.flowPct(), shadow.levelPct(),
                known, known ? counterfactual.wouldFire() : null,
                known && counterfactual.oracleSide() != null
                    ? counterfactual.oracleSide().name()
                    : null,
                shadowFlip,
                known ? counterfactual.composite() : null,
                known ? counterfactual.threshold() : null,
                known ? counterfactual.compositeValid() : null,
                known ? counterfactual.blockingRail() : null,
                // dot_would_support is an OPERAND fact (the `sentiment` dot is scored on every
                // bar), so it rides straight off the shadow. slope_gate_would_pass is NOT: the
                // schema contract is "null ⇒ tag unarmed", and SentimentLevelShadow evaluates
                // oi-slope-agree UNCONDITIONALLY. Persisting its raw value would report a real
                // boolean for the six strategies that never run that rail — a row claiming more
                // than it knows, and self-contradictory beside shadow_would_fire=true. Take the
                // gate's tag-aware value, which is already null when the tag is unarmed.
                shadow.sentimentDotWouldSupport(),
                known ? counterfactual.slopeGatePass() : null,
                // V064 (H40): WHY the verdict above is or is not computable. Unconditional and
                // NOT gated on `known` -- the reason is exactly what a NOT-known row needs, and
                // the record's constructor guarantees it is non-null, so a NULL in the column can
                // only mean "written before V064 deployed".
                shadow.reason());
            return true;
          } catch (RuntimeException e) {
            log.warn(
                "failed to persist exit-oracle shadow for entry {} ({} @ {}): {}",
                entrySignalId, strategySlug, barTime, e.toString());
            return false;
          }
        });
  }

  @PreDestroy
  void shutdown() {
    drainAndShutdown(SHUTDOWN_DRAIN_MILLIS);
  }

  /**
   * Graceful shutdown (package-private so a test can use a short drain window). Leftovers are COUNTED
   * ({@code ay_exit_oracle_shadow_shutdown_dropped_total}) and LOGGED rather than silently lost, so an
   * ordinary redeploy leaves a visible mark on the measurement rather than a quiet hole in it.
   */
  void drainAndShutdown(long drainMillis) {
    queue.drainAndShutdown(drainMillis);
  }
}
