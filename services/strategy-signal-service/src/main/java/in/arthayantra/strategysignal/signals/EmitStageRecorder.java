package in.arthayantra.strategysignal.signals;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * G8 / T26 stage-level emit-latency stamping. {@code ay_signal_bar_to_emit_seconds} measured a
 * 17.0s MEAN across all 20 of 2026-07-29's signals — entries AND exits, with exactly one fast exit
 * (606 ms) — killing the original strike-resolution hypothesis (the cost is a SHARED emit-path
 * stage) but naming nothing. These timers split the emit path into five telescoping stages so the
 * next session's scrape names the stage instead of the total:
 *
 * <ol>
 *   <li>{@code pre_eval} — bar receipt (the causal Redis receipt stamp) → THIS strategy's
 *       evaluation start. Contains the pending-queue drain wait, the series append, and every
 *       EARLIER strategy's evaluation of the same bar — the serial-eval-thread suspect.
 *   <li>{@code gate_eval} — evaluation start → emit-method entry. Entry: series REST refresh (a
 *       coarse-primary boundary), indicator bank, chart gate, confluence gate INCLUDING the chain
 *       read and strike/leg pick. Exit: the active-entry read + structural-stop / confluence-flip
 *       / exit-rule evaluation.
 *   <li>{@code leg_resolve} — emit-method entry → transaction start. Entry: risk-gate veto check,
 *       instrument-master leg-exchange resolution, suggested-qty sizing (a REST call), diagnostic
 *       JSON build. Exit: only the closing-side derivation (~0 by construction — a fat exit
 *       reading here would itself be a finding).
 *   <li>{@code persist} — the insert transaction (row + stamps + anchor transition).
 *   <li>{@code publish} — transaction commit → emit complete: the emitted-at stamp write, the
 *       fired-diagnostic stamp, the Redis channel publish and the synchronous in-process listeners
 *       (paper open/close, scalp alert).
 * </ol>
 *
 * <p>The stages TELESCOPE: they are computed from five stamps of the SAME clock, so
 * {@code Σ stages == total} exactly (per emit and therefore cumulatively per direction tag) —
 * {@code ay_signal_emit_total_seconds{direction}} records that same-boundary total, giving the
 * entry-vs-exit split the untagged {@code ay_signal_bar_to_emit_seconds} cannot (that metric is
 * untouched; it stops at the emitted-at stamp, INSIDE the publish stage here).
 *
 * <p>INSTRUMENTATION ONLY. Thread-confined to the single {@code signal-eval} thread via a
 * ThreadLocal (the {@code currentBarReceivedAtMs} pattern); every hook is a nullable no-op, so the
 * clock-driven BTST pre-close path (no bar receipt → no trace) records nothing and can never
 * throw; a failed record WARNs and never reaches the emit path. Timers are PRE-REGISTERED at boot
 * (the outcome-counter doctrine: a missing series must never be ambiguous with a never-emitted
 * one). All stamps come from the engine's injected {@code Clock}, never a second time source.
 */
final class EmitStageRecorder {

  private static final Logger log = LoggerFactory.getLogger(EmitStageRecorder.class);

  static final String STAGE_PRE_EVAL = "pre_eval";
  static final String STAGE_GATE_EVAL = "gate_eval";
  static final String STAGE_LEG_RESOLVE = "leg_resolve";
  static final String STAGE_PERSIST = "persist";
  static final String STAGE_PUBLISH = "publish";
  static final String[] STAGES = {
    STAGE_PRE_EVAL, STAGE_GATE_EVAL, STAGE_LEG_RESOLVE, STAGE_PERSIST, STAGE_PUBLISH
  };

  static final String DIRECTION_ENTRY = "entry";
  static final String DIRECTION_EXIT = "exit";

  /** Mutable per-evaluation stamps; one per eval, reset by {@link #beginEvaluation}. */
  private static final class Trace {
    final long barReceivedAtMs;
    final long evalStartMs;
    long emitStartMs;
    long txStartMs;
    long txEndMs;

    Trace(long barReceivedAtMs, long evalStartMs) {
      this.barReceivedAtMs = barReceivedAtMs;
      this.evalStartMs = evalStartMs;
    }
  }

  private final Map<String, Timer> stageTimers = new HashMap<>();
  private final Map<String, Timer> totalTimers = new HashMap<>();
  private final ThreadLocal<Trace> trace = new ThreadLocal<>();

  EmitStageRecorder(MeterRegistry meterRegistry) {
    for (String direction : new String[] {DIRECTION_ENTRY, DIRECTION_EXIT}) {
      for (String stage : STAGES) {
        stageTimers.put(
            stage + '|' + direction,
            Timer.builder("ay_signal_emit_stage_seconds")
                .description(
                    "One telescoping stage of the live bar-close->emit path; stages sum exactly"
                        + " to ay_signal_emit_total_seconds for the same direction")
                .tag("stage", stage)
                .tag("direction", direction)
                .register(meterRegistry));
      }
      totalTimers.put(
          direction,
          Timer.builder("ay_signal_emit_total_seconds")
              .description(
                  "Bar receipt -> emit complete for one emitted signal, by direction (the"
                      + " same-boundary total the stage timers telescope to)")
              .tag("direction", direction)
              .register(meterRegistry));
    }
  }

  /**
   * Opens a trace for one strategy's evaluation of one bar. {@code barReceivedAtMs <= 0} means no
   * causal receipt stamp exists (a clock-driven path) — no trace, every later hook a no-op.
   */
  void beginEvaluation(long barReceivedAtMs, long nowMs) {
    trace.set(barReceivedAtMs > 0 ? new Trace(barReceivedAtMs, nowMs) : null);
  }

  /** Closes the evaluation scope (finally-guarded by the caller) so no stale stamps leak. */
  void endEvaluation() {
    trace.remove();
  }

  /** The gate verdict is in and an emit method was entered — gate_eval ends here. */
  void markEmitStart(long nowMs) {
    Trace t = trace.get();
    if (t != null) {
      t.emitStartMs = nowMs;
    }
  }

  /** The insert transaction is about to start — leg_resolve ends here. */
  void markPersistStart(long nowMs) {
    Trace t = trace.get();
    if (t != null) {
      t.txStartMs = nowMs;
    }
  }

  /** The insert transaction committed — persist ends here. */
  void markPersistEnd(long nowMs) {
    Trace t = trace.get();
    if (t != null) {
      t.txEndMs = nowMs;
    }
  }

  /**
   * The emit completed (post publish + in-process events): record all five stages + the total, or
   * NOTHING when any stamp is missing (a partially-walked trace must not ship lying zeros). The
   * trace is consumed, so a hypothetical second emit in the same evaluation cannot double-record
   * stale stamps. Never throws — instrumentation must never break the emit path.
   */
  void recordEmitComplete(String direction, long nowMs) {
    try {
      Trace t = trace.get();
      if (t == null || t.emitStartMs == 0 || t.txStartMs == 0 || t.txEndMs == 0) {
        return;
      }
      trace.set(null); // consumed — endEvaluation still clears the slot itself
      record(STAGE_PRE_EVAL, direction, t.evalStartMs - t.barReceivedAtMs);
      record(STAGE_GATE_EVAL, direction, t.emitStartMs - t.evalStartMs);
      record(STAGE_LEG_RESOLVE, direction, t.txStartMs - t.emitStartMs);
      record(STAGE_PERSIST, direction, t.txEndMs - t.txStartMs);
      record(STAGE_PUBLISH, direction, nowMs - t.txEndMs);
      totalTimers.get(direction).record(
          Math.max(0, nowMs - t.barReceivedAtMs), TimeUnit.MILLISECONDS);
    } catch (RuntimeException e) {
      log.warn("emit-stage recording failed ({}): {}", direction, e.toString());
    }
  }

  private void record(String stage, String direction, long millis) {
    // A wall-clock step back would yield a negative duration; clamp rather than throw (the sum
    // property degrades for that one emit, the path never breaks).
    stageTimers.get(stage + '|' + direction).record(Math.max(0, millis), TimeUnit.MILLISECONDS);
  }
}
