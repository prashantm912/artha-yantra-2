package in.arthayantra.backtest.replay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.backtest.replay.MetricsCalculator.Metrics;
import in.arthayantra.backtest.replay.options.PremiumSource;
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
      String engineVersion,
      PremiumSource premiumSource) {
    return jdbc.queryForObject(
        """
        INSERT INTO backtest_runs (
          job_id, strategy_version_id, exchange, tradingsymbol, "interval", start_ts, end_ts,
          initial_equity, final_equity, params_override, seed, data_hash,
          total_return, sharpe, sortino, max_drawdown, win_rate, profit_factor, trade_count,
          metrics, equity_curve, drawdown_curve, engine_version, premium_source)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?)
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
        premiumSource.name());
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
            "SELECT metrics, equity_curve, drawdown_curve, benchmark_curve, data_hash, seed, "
                + "premium_source FROM backtest_runs WHERE id=?",
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
              // §D.15: surface synthetic-premium caveats at the top level, never buried.
              out.put("caveats", caveats(metrics));
              return out;
            },
            runId)
        .stream()
        .findFirst();
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
