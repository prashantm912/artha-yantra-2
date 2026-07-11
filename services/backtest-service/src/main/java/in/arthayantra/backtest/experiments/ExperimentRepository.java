package in.arthayantra.backtest.experiments;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Reads the {@code experiment_runs} view (V010) — the audit §11.4 version→run(s)→trades join. Two
 * queries back the two endpoints: a filtered/paged {@link #list} and an order-preserving {@link
 * #compare} over an explicit run-id set. Read-only; no writes here.
 */
@Repository
public class ExperimentRepository {

  private final JdbcTemplate jdbc;

  /** Wires JDBC. */
  public ExperimentRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<ExperimentSummary> SUMMARY =
      (rs, n) ->
          new ExperimentSummary(
              rs.getString("run_id"),
              rs.getString("job_id"),
              rs.getString("kind"),
              rs.getString("parent_job_id"),
              rs.getString("strategy_version_id"),
              rs.getString("strategy_id"),
              rs.getString("strategy_version"),
              rs.getString("purpose"),
              rs.getString("exchange"),
              rs.getString("tradingsymbol"),
              rs.getString("interval"),
              rs.getString("start_ts"),
              rs.getString("end_ts"),
              plain(rs.getBigDecimal("total_return")),
              plain(rs.getBigDecimal("sharpe")),
              plain(rs.getBigDecimal("max_drawdown")),
              rs.getInt("trade_count"),
              rs.getString("data_hash"),
              rs.getString("universe_checksum"),
              rs.getString("engine_sha"),
              rs.getString("premium_source"),
              rs.getString("created_by"),
              rs.getString("completed_at"));

  private static final RowMapper<ExperimentCompareRun> COMPARE =
      (rs, n) ->
          new ExperimentCompareRun(
              rs.getString("run_id"),
              rs.getString("strategy_version_id"),
              rs.getString("strategy_id"),
              rs.getString("strategy_version"),
              rs.getString("data_hash"),
              rs.getString("universe_checksum"),
              rs.getString("engine_sha"),
              rs.getString("premium_source"),
              rs.getString("created_by"),
              rs.getString("completed_at"),
              new CompareMetrics(
                  plain(rs.getBigDecimal("total_return")),
                  rs.getString("cagr"), // text passthrough from metrics JSONB (already a plain decimal)
                  plain(rs.getBigDecimal("sharpe")),
                  plain(rs.getBigDecimal("sortino")),
                  plain(rs.getBigDecimal("max_drawdown")),
                  plain(rs.getBigDecimal("win_rate")),
                  plain(rs.getBigDecimal("profit_factor")),
                  plain(rs.getBigDecimal("alpha")),
                  plain(rs.getBigDecimal("beta")),
                  plain(rs.getBigDecimal("information_ratio")),
                  rs.getInt("trade_count")));

  private static final String SUMMARY_COLS =
      "run_id, job_id, kind, parent_job_id, strategy_version_id, strategy_id, strategy_version, "
          + "purpose, exchange, tradingsymbol, \"interval\", start_ts, end_ts, total_return, sharpe, "
          + "max_drawdown, trade_count, data_hash, universe_checksum, engine_sha, premium_source, "
          + "created_by, completed_at";

  private static final String COMPARE_COLS =
      "run_id, strategy_version_id, strategy_id, strategy_version, data_hash, universe_checksum, "
          + "engine_sha, premium_source, created_by, completed_at, total_return, cagr, sharpe, sortino, "
          + "max_drawdown, win_rate, profit_factor, alpha, beta, information_ratio, trade_count";

  /**
   * The experiment list, newest run first, optionally filtered by originating strategy id, resolved
   * strategy version id, and/or job kind (BACKTEST / TRIAL). Only completed runs surface (a run row
   * exists solely on completion), so there is no status filter. {@code limit}/{@code offset} are the
   * caller-bounded page.
   */
  public List<ExperimentSummary> list(
      String strategyId, UUID strategyVersionId, String kind, int limit, int offset) {
    StringBuilder sql = new StringBuilder("SELECT ").append(SUMMARY_COLS).append(" FROM experiment_runs WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (strategyId != null && !strategyId.isBlank()) {
      sql.append(" AND strategy_id = ?");
      args.add(strategyId);
    }
    if (strategyVersionId != null) {
      sql.append(" AND strategy_version_id = ?");
      args.add(strategyVersionId);
    }
    if (kind != null && !kind.isBlank()) {
      sql.append(" AND kind = ?");
      args.add(kind);
    }
    sql.append(" ORDER BY completed_at DESC LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), SUMMARY, args.toArray());
  }

  /**
   * The compare rows for an explicit run-id set, returned in the caller's requested order; a run id
   * that resolves to no completed run is absent (never a placeholder). Empty input ⇒ empty list. One
   * batch query, then re-emitted in request order (SQL {@code IN} does not preserve it).
   */
  public List<ExperimentCompareRun> compare(List<UUID> runIds) {
    if (runIds == null || runIds.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", Collections.nCopies(runIds.size(), "?"));
    List<ExperimentCompareRun> rows =
        jdbc.query(
            "SELECT " + COMPARE_COLS + " FROM experiment_runs WHERE run_id IN (" + placeholders + ")",
            COMPARE,
            runIds.toArray());
    Map<String, ExperimentCompareRun> byId = new LinkedHashMap<>();
    for (ExperimentCompareRun row : rows) {
      byId.put(row.runId(), row);
    }
    List<ExperimentCompareRun> ordered = new ArrayList<>();
    for (UUID id : runIds) {
      ExperimentCompareRun row = byId.get(id.toString());
      if (row != null) {
        ordered.add(row);
      }
    }
    return ordered;
  }

  private static String plain(BigDecimal value) {
    return value == null ? null : value.toPlainString();
  }
}
