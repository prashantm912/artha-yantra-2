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
   * <p>⚠️ <b>THIS TABLE CANNOT DISCRIMINATE THE FOUR CAUSES OF AN ALL-NULL SENTIMENT SHADOW, AND A
   * READER MUST NOT INFER by-design-vs-failure FROM ITS NULLS.</b> {@code SentimentLevelShadow} now
   * carries a {@code reason} discriminator (COMPUTED / NO_OI_CONTEXT / MONTHLY_EXPIRY_SUPPRESSED /
   * LEVEL_UNAVAILABLE / SIDE_UNRESOLVED) and it rides both JSONB surfaces — {@code
   * signals.fired_diagnostic} and {@code signal_rejections.diagnostic}. It does <b>not</b> ride
   * here: {@code exit_oracle_shadow} decomposes the shadow into typed columns and has no column to
   * put it in, so {@code shadow.reason()} is deliberately DROPPED below. {@code
   * shadow_verdict_known} records a DIFFERENT fact ({@code counterfactual != null}) and cannot
   * substitute for it.
   *
   * <p>Consequence, stated so the next analyst does not re-run the 2026-08-25 investigation: on an
   * NSE monthly index expiry every exit-oracle row for the whole session carries the same nulls with
   * no discriminator, exactly the wall the {@code reason} field was built to remove on the other two
   * surfaces. Adding {@code shadow_reason} needs a new suffix-versioned migration (V056 is applied
   * and checksum-locked, so it can never be edited in place) — queued as its own ledger row rather
   * than smuggled into a diagnostic PR. Cross-vendor-equivalent review finding, 2026-08-25.
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
                known ? counterfactual.slopeGatePass() : null);
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
