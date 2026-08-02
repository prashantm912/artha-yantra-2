package in.arthayantra.strategysignal.swing;

import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.eval.IndicatorBank;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategysignal.signals.EmissionGuard;
import in.arthayantra.strategysignal.signals.SignalRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * A swing family's rule for ADDING lots to a winning holding (Manas Arora §3.4). The {@link
 * SwingBatchEngine} speaks multi-lot natively (it groups open anchors by symbol, drives the exit off
 * the oldest lot, and closes all lots together); this policy decides whether a held symbol may take an
 * ADD this run and whether the add would breach the book's aggregate open-risk cap. A single-lot family
 * supplies {@link #NONE} — which never adds, so every symbol stays a singleton group and the engine
 * reduces exactly to the per-anchor loop.
 */
public interface PyramidPolicy {

  /**
   * §3.4: is there ROOM for another lot on this held symbol? Consulted BEFORE the series fetch (the
   * cheap early-out), so a held-at-max / single-lot symbol never pays a candle fetch. True only when
   * adds are enabled AND the lot count is below the family max. {@link #NONE} always returns false —
   * that is the held-skip (a held symbol is skipped before any fetch, exactly as before).
   */
  boolean hasRoom(List<SignalRepository.SignalRow> lots);

  /**
   * §3.4.1: given there is room, does today's bar QUALIFY for an add — has the close moved up ≥ the
   * family's min-gain since the most-recent lot? Consulted AFTER the series fetch (it needs the bar).
   * {@link #NONE} never reaches here ({@link #hasRoom} already returned false).
   */
  boolean qualifiesForAdd(List<SignalRepository.SignalRow> lots, EngineCandle bar);

  /**
   * §3.4.3: would opening the prospective new lot push the book's aggregate open risk over the family's
   * portfolio-risk cap ("add without increasing risk exposure")? Consulted at emit time for an add AND
   * (M40, 2026-08-02) for a FRESH entry — the aggregate-risk cap binds the book's total open risk
   * regardless of whether this is the symbol's first lot or another one. {@link #NONE} always returns
   * false (the Minervini/default family carries no portfolio-risk cap).
   */
  boolean wouldBreachRiskCap(
      StrategyDefinition definition,
      String exchange,
      String symbol,
      IndicatorBank bank,
      int index,
      BigDecimal entryPrice,
      EmissionGuard guard);

  /**
   * The pyramid regime as a flat map (P1-7 flag snapshot) — the out-of-YAML pyramiding env flags this run
   * used. {@link #NONE} reports only {@code enabled=false}; a real add-capable policy reports its knob set.
   */
  Map<String, Object> describe();

  /** No pyramiding: single-lot, never adds — the Minervini (and default) policy. */
  PyramidPolicy NONE =
      new PyramidPolicy() {
        @Override
        public boolean hasRoom(List<SignalRepository.SignalRow> lots) {
          return false;
        }

        @Override
        public Map<String, Object> describe() {
          return Map.of("enabled", false);
        }

        @Override
        public boolean qualifiesForAdd(List<SignalRepository.SignalRow> lots, EngineCandle bar) {
          return false;
        }

        @Override
        public boolean wouldBreachRiskCap(
            StrategyDefinition definition,
            String exchange,
            String symbol,
            IndicatorBank bank,
            int index,
            BigDecimal entryPrice,
            EmissionGuard guard) {
          return false;
        }
      };
}
