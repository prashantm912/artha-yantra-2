package in.arthayantra.backtest.replay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.backtest.analytics.BenchmarkAnalytics;
import in.arthayantra.backtest.replay.MetricsCalculator.Metrics;
import in.arthayantra.backtest.replay.folds.FoldPersistence;
import in.arthayantra.backtest.replay.options.PremiumSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists and reads {@code backtest_runs} (one row per completed engine replay, §D.3). */
@Repository
public class RunRepository {

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  /** Wires JDBC + Jackson. */
  public RunRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /**
   * Inserts one completed run; returns the generated run id. The {@code premiumSource} provenance
   * (§D.15) is written before results persist and is NEVER null — a synthetic run can never
   * masquerade as snapshot-grade.
   *
   * <p>{@code folds} (Phase 31, §D.4) carries the walk-forward / implicit-70-30 columns
   * ({@code fold_metrics}, {@code oos_fold_mean}, {@code oos_fold_std}, {@code sharpe_degradation}).
   * It is {@code null} for a plain full-window backtest, leaving those four columns NULL — the
   * explicit "no train/test structure" signal.
   */
  public UUID insert(
      UUID jobId,
      UUID strategyVersionId,
      String exchange,
      String tradingsymbol,
      String interval,
      OffsetDateTime startTs,
      OffsetDateTime endTs,
      ReplayResult result,
      Metrics metrics,
      JsonNode paramsOverride,
      long seed,
      String dataHash,
      String universeChecksum,
      String engineVersion,
      PremiumSource premiumSource,
      FoldPersistence folds,
      BenchmarkAnalytics benchmark) {
    ArrayNode foldMetrics = folds == null ? null : folds.foldMetrics();
    BigDecimal oosFoldMean = folds == null ? null : folds.oosFoldMean();
    BigDecimal oosFoldStd = folds == null ? null : folds.oosFoldStd();
    BigDecimal sharpeDegradation = folds == null ? null : folds.sharpeDegradation();
    // §D.16: benchmark columns persist NULL — flagged, never silently zero — when coverage absent.
    boolean bench = benchmark != null && benchmark.present();
    String benchmarkCurve =
        bench && !benchmark.benchmarkCurve().isEmpty() ? curveJson(benchmark.benchmarkCurve()) : null;
    return jdbc.queryForObject(
        """
        INSERT INTO backtest_runs (
          job_id, strategy_version_id, exchange, tradingsymbol, "interval", start_ts, end_ts,
          initial_equity, final_equity, params_override, seed, data_hash, universe_checksum,
          total_return, sharpe, sortino, max_drawdown, win_rate, profit_factor, trade_count,
          metrics, equity_curve, drawdown_curve, engine_version, premium_source,
          fold_metrics, oos_fold_mean, oos_fold_std, sharpe_degradation,
          alpha, beta, information_ratio, excess_cagr, benchmark_curve)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?,
                ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
        RETURNING id
        """,
        (rs, n) -> UUID.fromString(rs.getString("id")),
        jobId,
        strategyVersionId,
        exchange,
        tradingsymbol,
        interval,
        startTs,
        endTs,
        result.initialEquity(),
        result.finalEquity(),
        paramsOverride == null ? null : json(paramsOverride),
        seed,
        dataHash,
        universeChecksum,
        metrics.totalReturn(),
        metrics.sharpe(),
        metrics.sortino(),
        metrics.maxDrawdown(),
        metrics.winRate(),
        metrics.profitFactor(),
        metrics.tradeCount(),
        json(metrics.full()),
        curveJson(result.equityCurve()),
        curveJson(result.drawdownCurve()),
        engineVersion,
        premiumSource.name(),
        foldMetrics == null ? null : json(foldMetrics),
        oosFoldMean,
        oosFoldStd,
        sharpeDegradation,
        bench ? benchmark.alpha() : null,
        bench ? benchmark.beta() : null,
        bench ? benchmark.informationRatio() : null,
        bench ? benchmark.excessCagr() : null,
        benchmarkCurve);
  }

  /**
   * The persisted {@code fold_metrics} array for a run, or {@code null} when the run id is unknown.
   * An empty array is returned as {@code []} (a fold run where every fold failed {@code min_trades});
   * a plain full-window run (NULL column) is also returned as {@code []} — the §D.5 "empty when the
   * run had no walk_forward" contract. Because a present row ALWAYS maps to a non-null array, {@code
   * null} is returned <b>only</b> when no run row exists, so the controller distinguishes "unknown
   * run" (404) from "no folds" ([]) from this single read alone — no separate existence query.
   */
  public JsonNode findFoldMetrics(UUID runId) {
    return jdbc
        .query(
            "SELECT fold_metrics FROM backtest_runs WHERE id=?",
            (rs, n) -> {
              JsonNode parsed = parse(rs.getString("fold_metrics"));
              return (JsonNode) (parsed == null ? objectMapper.createArrayNode() : parsed);
            },
            runId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  /**
   * The {@code total_return} (plain decimal string) of each given job's completed run, for enriching
   * the jobs-list rows with a returns column. One batch query over the page's job ids; jobs with no
   * run (queued/running/failed) are simply absent from the map. Empty input ⇒ empty map.
   */
  public Map<UUID, String> findReturnsByJobIds(List<UUID> jobIds) {
    if (jobIds == null || jobIds.isEmpty()) {
      return Map.of();
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(jobIds.size(), "?"));
    Map<UUID, String> out = new LinkedHashMap<>();
    jdbc.query(
        "SELECT job_id, total_return FROM backtest_runs WHERE job_id IN (" + placeholders + ")",
        rs -> {
          out.put(UUID.fromString(rs.getString("job_id")), plain(rs.getBigDecimal("total_return")));
        },
        jobIds.toArray());
    return out;
  }

  /** The run id produced by a job (for the {@code resultRef} on the job-status payload). */
  public Optional<UUID> findRunIdByJobId(UUID jobId) {
    return jdbc
        .query(
            "SELECT id FROM backtest_runs WHERE job_id=? ORDER BY completed_at DESC LIMIT 1",
            (rs, n) -> UUID.fromString(rs.getString("id")),
            jobId)
        .stream()
        .findFirst();
  }

  /** The §D.5 results payload for one run. */
  public Optional<Map<String, Object>> findResult(UUID runId) {
    return jdbc
        .query(
            // join jobs for the originating strategyId (the results header maps it to a name
            // client-side, same schema-boundary discipline as the jobs list) + the run's completion
            // timestamp ("date when it was run").
            "SELECT br.metrics, br.equity_curve, br.drawdown_curve, br.benchmark_curve, br.data_hash, "
                + "br.seed, br.premium_source, br.universe_checksum, br.exchange, br.tradingsymbol, "
                + "br.completed_at, j.request->>'strategyId' AS strategy_id "
                + "FROM backtest_runs br LEFT JOIN jobs j ON j.id = br.job_id WHERE br.id=?",
            (rs, n) -> {
              Map<String, Object> out = new LinkedHashMap<>();
              JsonNode metrics = parse(rs.getString("metrics"));
              out.put("metrics", metrics);
              out.put("equityCurve", parse(rs.getString("equity_curve")));
              out.put("drawdownCurve", parse(rs.getString("drawdown_curve")));
              out.put("benchmarkCurve", parse(rs.getString("benchmark_curve")));
              out.put("dataHash", rs.getString("data_hash"));
              out.put("seed", rs.getLong("seed"));
              out.put("premiumSource", rs.getString("premium_source"));
              // The originating strategy (UUID) + when the run finished — the results header shows the
              // strategy name (mapped client-side) and the run date.
              out.put("strategyId", rs.getString("strategy_id"));
              out.put("ranAt", rs.getString("completed_at"));
              // Stage F follow-on: the run's instrument, so the results "View on chart" deep-link
              // and trade-mark filtering carry the right symbol instead of the persisted default.
              out.put("exchange", rs.getString("exchange"));
              out.put("tradingsymbol", rs.getString("tradingsymbol"));
              // Phase 44: the pinned universe hash, so the compare view flags non-like-for-like
              // runs (differing universe_checksum) beside the dataHash mismatch. NULL for explicit
              // single-instrument / unpinned universes — the compare banner ignores NULLs.
              out.put("universeChecksum", rs.getString("universe_checksum"));
              // §D.15: surface synthetic-premium caveats at the top level, never buried.
              out.put("caveats", caveats(metrics));
              return out;
            },
            runId)
        .stream()
        .findFirst();
  }

  /**
   * The latest completed run per strategy version (Stage F follow-on, for the strategy-list
   * last-backtest summary). One row per requested version id that has at least one run — newest by
   * {@code completed_at} via {@code DISTINCT ON} over {@code idx_runs_strategy}. Each row carries
   * the headline metrics (decimal strings) and a downsampled equity series ({@code ~32} value
   * strings) for a sparkline. Versions with no runs are simply absent. Empty input ⇒ empty list.
   */
  public List<Map<String, Object>> findLatestSummaries(List<UUID> versionIds) {
    if (versionIds == null || versionIds.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(versionIds.size(), "?"));
    String sql =
        "SELECT DISTINCT ON (strategy_version_id) strategy_version_id, id, sharpe, total_return, "
            + "max_drawdown, completed_at, equity_curve FROM backtest_runs "
            + "WHERE strategy_version_id IN ("
            + placeholders
            + ") ORDER BY strategy_version_id, completed_at DESC";
    return jdbc.query(
        sql,
        (rs, n) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("strategyVersionId", rs.getString("strategy_version_id"));
          row.put("runId", rs.getString("id"));
          row.put("sharpe", plain(rs.getBigDecimal("sharpe")));
          row.put("totalReturn", plain(rs.getBigDecimal("total_return")));
          row.put("maxDrawdown", plain(rs.getBigDecimal("max_drawdown")));
          row.put("completedAt", rs.getString("completed_at"));
          row.put("equity", compactEquity(parse(rs.getString("equity_curve")), 32));
          return row;
        },
        versionIds.toArray());
  }

  private static String plain(BigDecimal value) {
    return value == null ? null : value.toPlainString();
  }

  /** Downsamples a persisted equity curve to ~{@code target} value strings for a sparkline. */
  private static List<String> compactEquity(JsonNode curve, int target) {
    List<String> out = new java.util.ArrayList<>();
    if (curve == null || !curve.isArray() || curve.isEmpty()) {
      return out;
    }
    int size = curve.size();
    int stride = Math.max(1, (int) Math.ceil((double) size / target));
    for (int i = 0; i < size; i += stride) {
      out.add(curve.get(i).get("value").asText());
    }
    String last = curve.get(size - 1).get("value").asText();
    if (!out.get(out.size() - 1).equals(last)) {
      out.add(last);
    }
    return out;
  }

  /**
   * The inputs Monte Carlo needs from a run: starting equity, the window span (for the CAGR
   * statistic), and the previously-persisted {@code montecarlo_summary} (or {@code null} on first
   * computation). {@code null} when the run id is unknown.
   */
  public record MonteCarloRun(
      BigDecimal initialEquity,
      OffsetDateTime startTs,
      OffsetDateTime endTs,
      JsonNode montecarloSummary) {}

  /** Reads the §D.16 Monte Carlo inputs for a run, or empty when the run id is unknown. */
  public Optional<MonteCarloRun> findForMonteCarlo(UUID runId) {
    return jdbc
        .query(
            "SELECT initial_equity, start_ts, end_ts, montecarlo_summary "
                + "FROM backtest_runs WHERE id=?",
            (rs, n) ->
                new MonteCarloRun(
                    rs.getBigDecimal("initial_equity"),
                    rs.getObject("start_ts", OffsetDateTime.class),
                    rs.getObject("end_ts", OffsetDateTime.class),
                    parse(rs.getString("montecarlo_summary"))),
            runId)
        .stream()
        .findFirst();
  }

  /** Persists (or replaces) the §D.16 Monte Carlo summary for a run. */
  public void updateMonteCarloSummary(UUID runId, JsonNode summary) {
    jdbc.update(
        "UPDATE backtest_runs SET montecarlo_summary=?::jsonb WHERE id=?", json(summary), runId);
  }

  /** Extracts the {@code caveats} array embedded in the metrics JSONB (empty when absent). */
  private List<String> caveats(JsonNode metrics) {
    List<String> out = new java.util.ArrayList<>();
    if (metrics != null && metrics.has("caveats") && metrics.get("caveats").isArray()) {
      metrics.get("caveats").forEach(node -> out.add(node.asText()));
    }
    return out;
  }

  private String json(JsonNode node) {
    try {
      return objectMapper.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("run JSONB not serializable", e);
    }
  }

  private String curveJson(List<EquityPoint> curve) {
    ArrayNode array = objectMapper.createArrayNode();
    for (EquityPoint p : curve) {
      ObjectNode point = objectMapper.createObjectNode();
      point.put("ts", p.ts().toString());
      point.put("value", p.equity().toPlainString());
      array.add(point);
    }
    return json(array);
  }

  private JsonNode parse(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return objectMapper.readTree(raw);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("corrupt run JSONB", e);
    }
  }
}
