package in.arthayantra.strategysignal.paper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC access to {@code strategy_graduations} — the F7 GRADUATED-stage marker set (one row per strategy). */
@Repository
public class StrategyGraduationRepository {

  /** One graduated strategy's marker + the metrics snapshot at graduation. */
  public record GraduationRow(
      UUID strategyId,
      OffsetDateTime graduatedAt,
      int trades,
      BigDecimal expectancy,
      BigDecimal sharpe,
      BigDecimal maxDrawdownPct) {}

  private final JdbcTemplate jdbc;

  /** Wires the strategy datasource. */
  public StrategyGraduationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The ids already marked GRADUATED — so the evaluator only ntfy-alerts a NEW graduation. */
  public Set<UUID> graduatedIds() {
    return jdbc
        .queryForList("SELECT strategy_id FROM strategy_graduations", UUID.class)
        .stream()
        .collect(Collectors.toSet());
  }

  /** Marks a strategy GRADUATED (upsert — re-stamps the metrics snapshot if it re-qualifies). */
  public void upsert(
      UUID strategyId,
      int trades,
      BigDecimal expectancy,
      BigDecimal sharpe,
      BigDecimal maxDrawdownPct,
      String metricsJson) {
    jdbc.update(
        """
        INSERT INTO strategy_graduations
          (strategy_id, graduated_at, trades, expectancy, sharpe, max_drawdown_pct, metrics)
        VALUES (?, now(), ?, ?, ?, ?, ?::jsonb)
        ON CONFLICT (strategy_id) DO UPDATE SET
          trades = EXCLUDED.trades, expectancy = EXCLUDED.expectancy, sharpe = EXCLUDED.sharpe,
          max_drawdown_pct = EXCLUDED.max_drawdown_pct, metrics = EXCLUDED.metrics
        """,
        strategyId,
        trades,
        expectancy,
        sharpe,
        maxDrawdownPct,
        metricsJson);
  }

  /** All graduated markers, newest first (the promotions read model). */
  public List<GraduationRow> list() {
    return jdbc.query(
        "SELECT strategy_id, graduated_at, trades, expectancy, sharpe, max_drawdown_pct"
            + " FROM strategy_graduations ORDER BY graduated_at DESC",
        (rs, n) ->
            new GraduationRow(
                rs.getObject("strategy_id", UUID.class),
                rs.getObject("graduated_at", OffsetDateTime.class),
                rs.getInt("trades"),
                rs.getBigDecimal("expectancy"),
                rs.getBigDecimal("sharpe"),
                rs.getBigDecimal("max_drawdown_pct")));
  }
}
