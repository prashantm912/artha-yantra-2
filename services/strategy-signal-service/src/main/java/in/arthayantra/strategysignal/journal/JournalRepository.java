package in.arthayantra.strategysignal.journal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code journal_entries} (F-44A). Same-schema FK targets (signals / paper_positions)
 * are validated here; {@code backtest_run_id}/{@code backtest_trade_id} are plain soft references
 * (no cross-schema FK, D10). All link columns are nullable — free entries are first-class.
 */
@Repository
public class JournalRepository {

  /** One journal entry. */
  public record Entry(
      long id,
      @Schema(types = {"integer", "null"}) Long signalId,
      @Schema(types = {"integer", "null"}) Long paperPositionId,
      @Schema(types = {"string", "null"}) UUID backtestRunId,
      @Schema(types = {"integer", "null"}) Long backtestTradeId,
      String note,
      List<String> tags,
      @Schema(types = {"integer", "null"}) Integer disciplineRating,
      @Schema(types = {"integer", "null"}) Integer emotionRating,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** A create/update payload. */
  public record EntryInput(
      Long signalId,
      Long paperPositionId,
      UUID backtestRunId,
      Long backtestTradeId,
      String note,
      List<String> tags,
      Integer disciplineRating,
      Integer emotionRating) {}

  private final JdbcTemplate jdbc;

  /** Wires the strategy datasource. */
  public JournalRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** True when a same-schema signal exists (FK validation). */
  public boolean signalExists(long id) {
    Integer n = jdbc.queryForObject("SELECT count(*) FROM signals WHERE id=?", Integer.class, id);
    return n != null && n > 0;
  }

  /** True when a same-schema paper position exists (FK validation). */
  public boolean paperPositionExists(long id) {
    Integer n = jdbc.queryForObject("SELECT count(*) FROM paper_positions WHERE id=?", Integer.class, id);
    return n != null && n > 0;
  }

  /** Inserts an entry; returns the generated id. */
  public long insert(EntryInput in) {
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(
        con -> {
          PreparedStatement ps =
              con.prepareStatement(
                  """
                  INSERT INTO journal_entries
                    (signal_id, paper_position_id, backtest_run_id, backtest_trade_id, note, tags,
                     discipline_rating, emotion_rating)
                  VALUES (?,?,?,?,?,?,?,?)
                  """,
                  new String[] {"id"});
          ps.setObject(1, in.signalId(), Types.BIGINT);
          ps.setObject(2, in.paperPositionId(), Types.BIGINT);
          ps.setObject(3, in.backtestRunId());
          ps.setObject(4, in.backtestTradeId(), Types.BIGINT);
          ps.setString(5, in.note() == null ? "" : in.note());
          ps.setArray(6, con.createArrayOf("text", tagsArray(in.tags())));
          ps.setObject(7, in.disciplineRating(), Types.SMALLINT);
          ps.setObject(8, in.emotionRating(), Types.SMALLINT);
          return ps;
        },
        keys);
    Number id = keys.getKey();
    return id == null ? 0 : id.longValue();
  }

  /** Updates the mutable fields of an entry (links are immutable after create). */
  public void update(long id, EntryInput in) {
    jdbc.update(
        con -> {
          PreparedStatement ps =
              con.prepareStatement(
                  "UPDATE journal_entries SET note=?, tags=?, discipline_rating=?,"
                      + " emotion_rating=?, updated_at=now() WHERE id=?");
          ps.setString(1, in.note() == null ? "" : in.note());
          ps.setArray(2, con.createArrayOf("text", tagsArray(in.tags())));
          ps.setObject(3, in.disciplineRating(), Types.SMALLINT);
          ps.setObject(4, in.emotionRating(), Types.SMALLINT);
          ps.setLong(5, id);
          return ps;
        });
  }

  /** An entry by id. */
  public Optional<Entry> find(long id) {
    return jdbc.query("SELECT * FROM journal_entries WHERE id=?", JournalRepository::map, id).stream()
        .findFirst();
  }

  /** Deletes an entry; returns rows affected. */
  public int delete(long id) {
    return jdbc.update("DELETE FROM journal_entries WHERE id=?", id);
  }

  /** Paged list with tag / window / min-rating / linked-entity filters. */
  public List<Entry> list(
      String tag,
      OffsetDateTime from,
      OffsetDateTime to,
      Integer minRating,
      String linkedTo,
      int limit,
      int offset) {
    StringBuilder sql = new StringBuilder("SELECT * FROM journal_entries WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (tag != null) {
      sql.append(" AND ? = ANY(tags)");
      args.add(tag);
    }
    if (from != null) {
      sql.append(" AND created_at >= ?");
      args.add(from);
    }
    if (to != null) {
      sql.append(" AND created_at <= ?");
      args.add(to);
    }
    if (minRating != null) {
      sql.append(" AND (discipline_rating >= ? OR emotion_rating >= ?)");
      args.add(minRating);
      args.add(minRating);
    }
    sql.append(linkedFilter(linkedTo));
    sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
    args.add(Math.min(Math.max(limit, 1), 500));
    args.add(Math.max(offset, 0));
    return jdbc.query(sql.toString(), JournalRepository::map, args.toArray());
  }

  private static String linkedFilter(String linkedTo) {
    if (linkedTo == null) {
      return "";
    }
    return switch (linkedTo) {
      case "signal" -> " AND signal_id IS NOT NULL";
      case "paper" -> " AND paper_position_id IS NOT NULL";
      case "backtest" -> " AND (backtest_run_id IS NOT NULL OR backtest_trade_id IS NOT NULL)";
      case "none" ->
          " AND signal_id IS NULL AND paper_position_id IS NULL AND backtest_run_id IS NULL"
              + " AND backtest_trade_id IS NULL";
      default -> "";
    };
  }

  private static String[] tagsArray(List<String> tags) {
    return tags == null ? new String[0] : tags.toArray(new String[0]);
  }

  private static Entry map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    java.sql.Array tagsArray = rs.getArray("tags");
    List<String> tags =
        tagsArray == null ? List.of() : List.of((String[]) tagsArray.getArray());
    UUID runId = rs.getObject("backtest_run_id", UUID.class);
    return new Entry(
        rs.getLong("id"),
        (Long) rs.getObject("signal_id"),
        (Long) rs.getObject("paper_position_id"),
        runId,
        (Long) rs.getObject("backtest_trade_id"),
        rs.getString("note"),
        tags,
        (Integer) rs.getObject("discipline_rating"),
        (Integer) rs.getObject("emotion_rating"),
        rs.getObject("created_at", OffsetDateTime.class),
        rs.getObject("updated_at", OffsetDateTime.class));
  }
}
