package in.arthayantra.strategysignal.signals;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code exit_oracle_shadow} (V056) — the per-bar counterfactual for the E9 D4
 * confluence-flip EXIT oracle. INSERT-only from the live engine; the golden replay never reaches the
 * confluence gate and the backtest runner never constructs a {@code SignalEngine}, so no row is ever
 * written on a backtest (parity-safe by construction).
 *
 * <p>MEASUREMENT ONLY — writing a row never changes whether the oracle exits.
 */
@Repository
public class ExitOracleShadowRepository {

  private final JdbcTemplate jdbc;

  public ExitOracleShadowRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Persists one oracle evaluation. {@code ON CONFLICT DO NOTHING} against the
   * {@code (entry_signal_id, bar_time)} unique key: the oracle evaluates a given held position at
   * most once per bar, so a retried enqueue is idempotent rather than a duplicate. Deliberately NOT
   * an upsert — the first write for a bar is the true one, and a later re-evaluation of the same bar
   * would be a replay artefact, not a correction.
   */
  public void insert(
      long entrySignalId,
      String strategySlug,
      OffsetDateTime barTime,
      String heldSide,
      String evaluatedSide,
      String liveOracleSide,
      boolean liveFlip,
      BigDecimal flowPct,
      BigDecimal levelPct,
      boolean shadowVerdictKnown,
      Boolean shadowWouldFire,
      String shadowOracleSide,
      Boolean shadowFlip,
      BigDecimal shadowComposite,
      BigDecimal compositeThreshold,
      Boolean shadowCompositeValid,
      String shadowBlockingRail,
      Boolean dotWouldSupport,
      Boolean slopeGateWouldPass) {
    jdbc.update(
        """
        INSERT INTO exit_oracle_shadow
          (entry_signal_id, strategy_slug, bar_time, held_side, evaluated_side, live_oracle_side,
           live_flip, flow_pct, level_pct, shadow_verdict_known, shadow_would_fire,
           shadow_oracle_side, shadow_flip, shadow_composite, composite_threshold,
           shadow_composite_valid, shadow_blocking_rail, dot_would_support, slope_gate_would_pass)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (entry_signal_id, bar_time) DO NOTHING
        """,
        entrySignalId, strategySlug, barTime, heldSide, evaluatedSide, liveOracleSide,
        liveFlip, flowPct, levelPct, shadowVerdictKnown, shadowWouldFire, shadowOracleSide,
        shadowFlip, shadowComposite, compositeThreshold, shadowCompositeValid, shadowBlockingRail,
        dotWouldSupport, slopeGateWouldPass);
  }
}
