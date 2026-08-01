package in.arthayantra.strategysignal.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to the {@code insights} store (INT design §2.1). The service connects as {@code artha}
 * (D10 single-writer). Writes are idempotent UPSERTs on {@code dedupe_key} while OPEN (§2.5.1:
 * regeneration refreshes the row, never duplicates). Reads power the feed / Focus / summary surfaces.
 */
@Repository
public class InsightRepository {

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public InsightRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /**
   * The upsert outcome: the persisted row plus whether the INSERT branch ran (a new occurrence) or
   * the conflict branch refreshed the existing OPEN row. The engine delivers ONLY on {@code
   * inserted} — a 15-min resweep of a persistent condition refreshes in place and must never
   * re-push (pre-arm review M1).
   */
  public record Upsert(Insight insight, boolean inserted) {}

  /**
   * UPSERTs an insight on its OPEN {@code dedupe_key} (§2.5.1). A regenerated insight refreshes the
   * existing OPEN row's payload in place; a first sighting inserts. Insert-vs-refresh is read from
   * {@code RETURNING id}: the {@code DO UPDATE} branch never rewrites {@code id}, so getting our own
   * id back proves the INSERT branch ran.
   */
  public Upsert insertOrRefresh(Insight in) {
    String[] reasons =
        in.trustReasons() == null ? new String[0] : in.trustReasons().toArray(new String[0]);
    List<UUID> ids =
        jdbc.query(
        con -> {
          var ps =
              con.prepareStatement(
                  """
                  INSERT INTO insights
                    (id, generated_at, type, severity, scope, title, explanation, evidence, priority,
                     priority_detail, data_trust, trust_reasons, dedupe_key, cooldown_until, suppressed,
                     status, expires_at, engine_version, config_hash)
                  VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                  ON CONFLICT (dedupe_key) WHERE status = 'OPEN'
                  DO UPDATE SET
                    generated_at = EXCLUDED.generated_at, severity = EXCLUDED.severity,
                    title = EXCLUDED.title, explanation = EXCLUDED.explanation, evidence = EXCLUDED.evidence,
                    priority = EXCLUDED.priority, priority_detail = EXCLUDED.priority_detail,
                    data_trust = EXCLUDED.data_trust, trust_reasons = EXCLUDED.trust_reasons,
                    cooldown_until = CASE
                      WHEN insights.cooldown_until IS NOT NULL
                           AND insights.cooldown_until > EXCLUDED.generated_at
                        THEN insights.cooldown_until
                      ELSE EXCLUDED.cooldown_until
                    END,
                    suppressed = EXCLUDED.suppressed,
                    expires_at = EXCLUDED.expires_at, engine_version = EXCLUDED.engine_version,
                    config_hash = EXCLUDED.config_hash
                  RETURNING id
                  """);
          ps.setObject(1, in.id());
          ps.setObject(2, in.generatedAt());
          ps.setString(3, in.type());
          ps.setString(4, in.severity());
          ps.setString(5, in.scope());
          ps.setString(6, in.title());
          ps.setString(7, in.explanation());
          ps.setString(8, in.evidence() == null ? "[]" : in.evidence().toString());
          if (in.priority() == null) {
            ps.setNull(9, java.sql.Types.NUMERIC);
          } else {
            ps.setBigDecimal(9, in.priority());
          }
          ps.setString(10, in.priorityDetail() == null ? null : in.priorityDetail().toString());
          ps.setString(11, in.dataTrust());
          ps.setArray(12, con.createArrayOf("text", reasons));
          ps.setString(13, in.dedupeKey());
          ps.setObject(14, in.cooldownUntil());
          ps.setBoolean(15, in.suppressed());
          ps.setString(16, in.status());
          ps.setObject(17, in.expiresAt());
          ps.setString(18, in.engineVersion());
          ps.setString(19, in.configHash());
          return ps;
        },
        (rs, rowNum) -> rs.getObject("id", UUID.class));
    UUID persistedId = ids.stream().findFirst().orElseThrow();
    if (persistedId.equals(in.id())) {
      return new Upsert(in, true);
    }
    return new Upsert(
        new Insight(
            persistedId,
            in.generatedAt(),
            in.type(),
            in.severity(),
            in.scope(),
            in.title(),
            in.explanation(),
            in.evidence(),
            in.priority(),
            in.priorityDetail(),
            in.dataTrust(),
            in.trustReasons(),
            in.dedupeKey(),
            in.cooldownUntil(),
            in.suppressed(),
            in.status(),
            in.expiresAt(),
            in.engineVersion(),
            in.configHash()),
        false);
  }

  /**
   * The most recent occurrence of this dedupe key REGARDLESS of status (the engine's delivery
   * decision reads the PRIOR severity + cooldown stamp before the upsert overwrites/replaces it).
   * Status-blind on purpose (pre-arm review round 3, mirroring {@link #isCooling}): ACK/DISMISS
   * removes the OPEN row, and an escalation arriving inside the cooldown must still be judged
   * against the ACKed occurrence — an OPEN-only compare would cooldown-suppress a WARN→CRITICAL
   * worsening right after the owner acknowledged the WARN, and nothing would page.
   */
  public Optional<Insight> findLatest(String dedupeKey) {
    return jdbc
        .query(
            "SELECT * FROM insights WHERE dedupe_key = ? ORDER BY generated_at DESC, id DESC LIMIT 1",
            this::map,
            dedupeKey)
        .stream()
        .findFirst();
  }

  /**
   * True when the LATEST row for this dedupe key — ANY status — is still inside its cooldown
   * window. Status-blind on purpose (pre-arm review M3): ACK/DISMISS closes the OPEN row and the
   * next sweep re-inserts, so an OPEN-only check would let acknowledging an alert defeat the
   * cooldown and re-page inside the original window.
   */
  public boolean isCooling(String dedupeKey, OffsetDateTime now) {
    List<Boolean> latest =
        jdbc.query(
            "SELECT cooldown_until IS NOT NULL AND cooldown_until > ? AS cooling FROM insights"
                + " WHERE dedupe_key = ? ORDER BY generated_at DESC, id DESC LIMIT 1",
            (rs, n) -> rs.getBoolean("cooling"),
            now,
            dedupeKey);
    return !latest.isEmpty() && Boolean.TRUE.equals(latest.get(0));
  }

  /**
   * A persistent owner delivery mute from the MUTE_TYPE action seam. A missing scope is a global
   * type mute; a strategy scope limits the mute to that strategy's insight rows. Only rows carrying
   * the explicit {@code target_ref.delivery = true} marker count (pre-arm review M5): legacy
   * scopeless MUTE_TYPE audit rows predate I4 delivery and must never act as permanent global
   * mutes — they are ignored, not migrated (this PR deliberately carries no migration).
   */
  public boolean isMuted(String type, String scope) {
    if (latestMuteAction(type, null)) {
      return true;
    }
    return scope != null && scope.startsWith("strategy:") && latestMuteAction(type, scope);
  }

  private boolean latestMuteAction(String type, String scope) {
    String sql =
        "SELECT action FROM insight_actions"
            + " WHERE action IN ('MUTE_TYPE', 'UNMUTE_TYPE') AND target_ref->>'type' = ?"
            + " AND target_ref->>'delivery' = 'true'"
            + (scope == null ? " AND target_ref->>'scope' IS NULL" : " AND target_ref->>'scope' = ?")
            + " ORDER BY acted_at DESC, id DESC LIMIT 1";
    List<String> actions =
        scope == null
            ? jdbc.query(sql, (rs, n) -> rs.getString("action"), type)
            : jdbc.query(sql, (rs, n) -> rs.getString("action"), type, scope);
    return !actions.isEmpty() && "MUTE_TYPE".equals(actions.get(0));
  }

  /** Paged/filtered feed, newest first (INT §9.1). */
  public List<Insight> list(
      String type, String severity, String status, String scope,
      OffsetDateTime from, OffsetDateTime to, boolean includeSuppressed, int limit, int offset) {
    StringBuilder sql = new StringBuilder("SELECT * FROM insights WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (type != null) {
      sql.append(" AND type = ?");
      args.add(type);
    }
    if (severity != null) {
      sql.append(" AND severity = ?");
      args.add(severity);
    }
    if (status != null) {
      sql.append(" AND status = ?");
      args.add(status);
    }
    if (scope != null) {
      sql.append(" AND scope = ?");
      args.add(scope);
    }
    if (from != null) {
      sql.append(" AND generated_at >= ?");
      args.add(from);
    }
    if (to != null) {
      sql.append(" AND generated_at < ?");
      args.add(to);
    }
    if (!includeSuppressed) {
      sql.append(" AND suppressed = FALSE");
    }
    sql.append(" ORDER BY generated_at DESC, id DESC LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), this::map, args.toArray());
  }

  /** One insight by id. */
  public Optional<Insight> get(UUID id) {
    return jdbc.query("SELECT * FROM insights WHERE id = ?", this::map, id).stream().findFirst();
  }

  /** The ranked signal queue: OPEN SIGNAL_PRIORITY insights, priority desc (Focus, §3.1). */
  public List<Insight> focusSignals(int limit) {
    return jdbc.query(
        """
        SELECT * FROM insights
        WHERE status = 'OPEN' AND type = 'SIGNAL_PRIORITY' AND suppressed = FALSE
        ORDER BY priority DESC NULLS LAST, generated_at DESC
        LIMIT ?
        """,
        this::map,
        limit);
  }

  /** The attention queue: OPEN non-signal insights ordered by severity, then age (§3.1). */
  public List<Insight> focusAttention(int limit) {
    return jdbc.query(
        """
        SELECT * FROM insights
        WHERE status = 'OPEN' AND type <> 'SIGNAL_PRIORITY' AND suppressed = FALSE
        ORDER BY CASE severity
                   WHEN 'CRITICAL' THEN 0 WHEN 'WARN' THEN 1 WHEN 'NOTICE' THEN 2 ELSE 3 END,
                 generated_at DESC
        LIMIT ?
        """,
        this::map,
        limit);
  }

  /** Badge counts by severity and status (Focus header, §9.1). */
  public Map<String, Long> countsBy(String column) {
    Map<String, Long> out = new LinkedHashMap<>();
    jdbc.query(
        "SELECT " + column + " AS k, count(*) AS n FROM insights WHERE status = 'OPEN' GROUP BY " + column,
        rs -> {
          out.put(rs.getString("k"), rs.getLong("n"));
        });
    return out;
  }

  /** Count of suppressed OPEN insights (the "N muted" empty-state, §8.1). */
  public long suppressedOpenCount() {
    Long n =
        jdbc.queryForObject(
            "SELECT count(*) FROM insights WHERE status = 'OPEN' AND suppressed = TRUE", Long.class);
    return n == null ? 0 : n;
  }

  /** Transition an insight's status (ack/dismiss); returns rows affected. */
  public int updateStatus(UUID id, String status) {
    return jdbc.update("UPDATE insights SET status = ? WHERE id = ?", status, id);
  }

  /** Records a one-click action on an insight (§2.4). */
  public void insertAction(UUID insightId, String action, String targetRefJson, String actor) {
    jdbc.update(
        "INSERT INTO insight_actions (insight_id, action, target_ref, actor) VALUES (?, ?, ?::jsonb, ?)",
        insightId,
        action,
        targetRefJson,
        actor == null ? "owner" : actor);
  }

  /**
   * Aggregates the trailing-window insight-quality statistics for the QUALITY_REPORT job (§10.2) —
   * A-band act rate, suppression audit, and per-type feedback tallies, all from the {@code insights}
   * + {@code insight_feedback} tables (no outcome join; the priority-calibration outcome-delta is a
   * small-N §6.5-caveated metric deferred to a later wave).
   */
  public QualityStats qualityStats(InsightProperties props) {
    InsightProperties.Quality cfg = props.quality();
    int aBand = props.priority().bands().a();
    java.time.OffsetDateTime from = OffsetDateTime.now().minusDays(cfg.windowDays());

    Long aBandGenerated =
        jdbc.queryForObject(
            "SELECT count(*) FROM insights WHERE type = 'SIGNAL_PRIORITY' AND generated_at >= ?"
                + " AND priority >= ?",
            Long.class, from, aBand);
    Long aBandActed =
        jdbc.queryForObject(
            "SELECT count(*) FROM insights WHERE type = 'SIGNAL_PRIORITY' AND generated_at >= ?"
                + " AND priority >= ? AND status IN ('ACKED','ACTED','DISMISSED')",
            Long.class, from, aBand);
    long generated = aBandGenerated == null ? 0 : aBandGenerated;
    java.math.BigDecimal actRate =
        generated == 0
            ? java.math.BigDecimal.ZERO
            : java.math.BigDecimal.valueOf(aBandActed == null ? 0 : aBandActed)
                .divide(java.math.BigDecimal.valueOf(generated), 4, java.math.RoundingMode.HALF_UP);

    Long suppressed =
        jdbc.queryForObject(
            "SELECT count(*) FROM insights WHERE suppressed = TRUE AND generated_at >= ?",
            Long.class, from);

    List<QualityStats.KeyCount> topKeys =
        jdbc.query(
            "SELECT dedupe_key, count(*) AS n FROM insights WHERE suppressed = TRUE AND generated_at >= ?"
                + " GROUP BY dedupe_key ORDER BY n DESC LIMIT ?",
            (rs, i) -> new QualityStats.KeyCount(rs.getString("dedupe_key"), rs.getLong("n")),
            from, cfg.topSuppressedKeys());

    List<QualityStats.TypeStat> perType =
        jdbc.query(
            """
            SELECT i.type AS type, count(*) AS generated,
                   count(*) FILTER (WHERE f.verdict = 'USEFUL') AS useful,
                   count(*) FILTER (WHERE f.verdict = 'NOT_USEFUL') AS not_useful
            FROM insights i
            LEFT JOIN insight_feedback f ON f.insight_id = i.id
            WHERE i.generated_at >= ?
            GROUP BY i.type ORDER BY generated DESC
            """,
            (rs, i) -> {
              long useful = rs.getLong("useful");
              long notUseful = rs.getLong("not_useful");
              long votes = useful + notUseful;
              java.math.BigDecimal dismissRate =
                  votes == 0
                      ? null
                      : java.math.BigDecimal.valueOf(notUseful)
                          .divide(java.math.BigDecimal.valueOf(votes), 4, java.math.RoundingMode.HALF_UP);
              return new QualityStats.TypeStat(
                  rs.getString("type"), rs.getLong("generated"), useful, notUseful, dismissRate);
            },
            from);

    return new QualityStats(
        cfg.windowDays(), actRate, generated, suppressed == null ? 0 : suppressed, topKeys, perType);
  }

  /**
   * The most recent suppressed-INFO STRATEGY_EVIDENCE snapshot per strategy scope, generated strictly
   * before {@code before} (§5.2 "yesterday's snapshot"). Keyed by the strategy UUID parsed from the
   * {@code strategy:<id>} scope; the value is the snapshot's evidence JSON (the reader parses the
   * stage + criterion pass-map back out of it). Used by the 21:10 sweep to detect crossings with NO
   * new table — the always-written snapshot IS the durable baseline.
   */
  public Map<UUID, JsonNode> latestStrategyEvidenceSnapshots(OffsetDateTime before) {
    Map<UUID, JsonNode> out = new LinkedHashMap<>();
    jdbc.query(
        """
        SELECT DISTINCT ON (scope) scope, evidence
        FROM insights
        WHERE type = 'STRATEGY_EVIDENCE' AND dedupe_key LIKE 'STRATEGY_EVIDENCE_SNAP:%'
          AND generated_at < ?
        ORDER BY scope, generated_at DESC
        """,
        rs -> {
          String scope = rs.getString("scope");
          if (scope != null && scope.startsWith("strategy:")) {
            try {
              out.put(UUID.fromString(scope.substring("strategy:".length())), readTree(rs.getString("evidence")));
            } catch (IllegalArgumentException ignored) {
              // a non-UUID strategy scope can't be a graduation snapshot — skip it defensively
            }
          }
        },
        before);
    return out;
  }

  /** UPSERTs owner feedback on an insight (§2.4 / §10.2). */
  public void upsertFeedback(UUID insightId, String verdict, String note) {
    jdbc.update(
        """
        INSERT INTO insight_feedback (insight_id, verdict, note) VALUES (?, ?, ?)
        ON CONFLICT (insight_id) DO UPDATE SET verdict = EXCLUDED.verdict, note = EXCLUDED.note, given_at = now()
        """,
        insightId,
        verdict,
        note);
  }

  private Insight map(ResultSet rs, int rowNum) throws SQLException {
    return new Insight(
        rs.getObject("id", UUID.class),
        rs.getObject("generated_at", OffsetDateTime.class),
        rs.getString("type"),
        rs.getString("severity"),
        rs.getString("scope"),
        rs.getString("title"),
        rs.getString("explanation"),
        readTree(rs.getString("evidence")),
        rs.getBigDecimal("priority"),
        readTree(rs.getString("priority_detail")),
        rs.getString("data_trust"),
        reasons(rs),
        rs.getString("dedupe_key"),
        rs.getObject("cooldown_until", OffsetDateTime.class),
        rs.getBoolean("suppressed"),
        rs.getString("status"),
        rs.getObject("expires_at", OffsetDateTime.class),
        rs.getString("engine_version"),
        rs.getString("config_hash"));
  }

  private static List<String> reasons(ResultSet rs) throws SQLException {
    java.sql.Array arr = rs.getArray("trust_reasons");
    if (arr == null) {
      return List.of();
    }
    Object[] raw = (Object[]) arr.getArray();
    List<String> out = new ArrayList<>(raw.length);
    for (Object o : raw) {
      out.add(o == null ? null : o.toString());
    }
    return out;
  }

  private JsonNode readTree(String json) {
    if (json == null) {
      return null;
    }
    try {
      return objectMapper.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException("stored insight JSON is invalid", e);
    }
  }
}
