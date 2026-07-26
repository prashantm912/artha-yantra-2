package in.arthayantra.strategysignal.signals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Durable expected/claimed/confirmed ledger for swing paper effects (V049). */
@Repository
public class SwingPaperEffectRepository {

  private static final int STALE_LEASE_MINUTES = 30;

  private final JdbcTemplate jdbc;

  public SwingPaperEffectRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record Effect(
      long id,
      String batch,
      LocalDate sessionDate,
      String effectKey,
      String effectType,
      String tradingsymbol,
      Long signalId,
      Long anchorSignalId,
      Long exitSignalId,
      String closeReason,
      BigDecimal closePrice,
      long expectedQty,
      long quantityBefore,
      String status) {}

  private static Effect map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    return new Effect(
        rs.getLong("id"),
        rs.getString("batch"),
        rs.getObject("session_date", LocalDate.class),
        rs.getString("effect_key"),
        rs.getString("effect_type"),
        rs.getString("tradingsymbol"),
        rs.getObject("signal_id", Long.class),
        rs.getObject("anchor_signal_id", Long.class),
        rs.getObject("exit_signal_id", Long.class),
        rs.getString("close_reason"),
        rs.getBigDecimal("close_price"),
        rs.getLong("expected_qty"),
        rs.getLong("quantity_before"),
        rs.getString("status"));
  }

  /** Creates the expected entry before the signal is emitted. */
  public boolean expectEntry(String batch, LocalDate sessionDate, String tradingsymbol) {
    return jdbc.update(
            "INSERT INTO swing_paper_effects"
                + " (batch, session_date, effect_key, effect_type, tradingsymbol, status)"
                + " VALUES (?, ?, ?, 'ENTRY', ?, 'EXPECTED')"
                + " ON CONFLICT (batch, session_date, effect_key) DO NOTHING",
            batch, java.sql.Date.valueOf(sessionDate), entryKey(tradingsymbol), tradingsymbol)
        == 1;
  }

  /** Binds the signal and expected quantity after the signal row is inserted in the same transaction. */
  public void bindEntry(
      String batch, LocalDate sessionDate, String tradingsymbol, long signalId, long expectedQty) {
    jdbc.update(
        "UPDATE swing_paper_effects SET signal_id=?, expected_qty=?, updated_at=now()"
            + " WHERE batch=? AND session_date=? AND effect_key=? AND status='EXPECTED'",
        signalId,
        expectedQty,
        batch,
        java.sql.Date.valueOf(sessionDate),
        entryKey(tradingsymbol));
  }

  /** Creates the expected close before the EXIT event is published. */
  public boolean expectExit(
      String batch,
      LocalDate sessionDate,
      long anchorSignalId,
      String reason,
      BigDecimal price) {
    return jdbc.update(
            "INSERT INTO swing_paper_effects"
                + " (batch, session_date, effect_key, effect_type, anchor_signal_id, close_reason, close_price, status)"
                + " VALUES (?, ?, ?, 'EXIT', ?, ?, ?, 'EXPECTED')"
                + " ON CONFLICT (batch, session_date, effect_key) DO NOTHING",
            batch,
            java.sql.Date.valueOf(sessionDate),
            exitKey(anchorSignalId),
            anchorSignalId,
            reason,
            price)
        == 1;
  }

  /** Binds the generated EXIT signal after the expected close exists. */
  public void bindExit(
      String batch, LocalDate sessionDate, long anchorSignalId, long exitSignalId) {
    jdbc.update(
        "UPDATE swing_paper_effects SET exit_signal_id=?, updated_at=now()"
            + " WHERE batch=? AND session_date=? AND effect_key=? AND status='EXPECTED'",
        exitSignalId,
        batch,
        java.sql.Date.valueOf(sessionDate),
        exitKey(anchorSignalId));
  }

  /** Claims an entry immediately before PaperService.openOrder. */
  public Optional<Effect> claimEntryEffect(long effectId, long quantityBefore) {
    return jdbc
        .query(
            "UPDATE swing_paper_effects SET status='CLAIMED', attempts=attempts+1,"
                + " quantity_before=CASE WHEN status='EXPECTED' THEN ? ELSE quantity_before END,"
                + " claimed_at=now(), updated_at=now() WHERE id=? AND (status='EXPECTED' OR"
                + " (status='CLAIMED' AND claimed_at < now() - make_interval(mins => ?)))"
                + " RETURNING " + columns(),
            SwingPaperEffectRepository::map,
            quantityBefore,
            effectId,
            STALE_LEASE_MINUTES)
        .stream()
        .findFirst();
  }

  /** Claims a close immediately before PaperService.closeForSignal. */
  public Optional<Effect> claimExitEffect(long effectId) {
    return jdbc
        .query(
            "UPDATE swing_paper_effects SET status='CLAIMED', attempts=attempts+1, claimed_at=now(),"
                + " updated_at=now() WHERE id=? AND (status='EXPECTED' OR"
                + " (status='CLAIMED' AND claimed_at < now() - make_interval(mins => ?)))"
                + " RETURNING " + columns(),
            SwingPaperEffectRepository::map,
            effectId,
            STALE_LEASE_MINUTES)
        .stream()
        .findFirst();
  }

  /** Marks an effect confirmed after a durable paper read-back. */
  public void confirm(long effectId) {
    jdbc.update(
        "UPDATE swing_paper_effects SET status='CONFIRMED', confirmed_at=now(), updated_at=now()"
            + " WHERE id=? AND status='CLAIMED'",
        effectId);
  }

  /** A disabled auto-paper toggle or an un-sized swing signal has no expected paper effect. */
  public void confirmEntry(long signalId) {
    jdbc.update(
        "UPDATE swing_paper_effects SET status='CONFIRMED', confirmed_at=now(), updated_at=now()"
            + " WHERE effect_type='ENTRY' AND signal_id=? AND status IN ('EXPECTED','CLAIMED')",
        signalId);
  }

  public void confirmExit(long anchorSignalId) {
    jdbc.update(
        "UPDATE swing_paper_effects SET status='CONFIRMED', confirmed_at=now(), updated_at=now()"
            + " WHERE effect_type='EXIT' AND anchor_signal_id=? AND status IN ('EXPECTED','CLAIMED')",
        anchorSignalId);
  }

  public Optional<Effect> find(long effectId) {
    return jdbc.query("SELECT " + columns() + " FROM swing_paper_effects WHERE id=?", SwingPaperEffectRepository::map, effectId)
        .stream()
        .findFirst();
  }

  public Optional<Effect> findEntryBySignal(long signalId) {
    return findBy(
        "effect_type='ENTRY' AND signal_id=?", signalId);
  }

  /** Swing-paper terminology for an ENTRY effect. */
  public Optional<Effect> findOpenBySignal(long signalId) {
    return findEntryBySignal(signalId);
  }

  public Optional<Effect> findExitByAnchor(long anchorSignalId) {
    return findBy(
        "effect_type='EXIT' AND anchor_signal_id=?", anchorSignalId);
  }

  /** Swing-paper terminology for an EXIT effect. */
  public Optional<Effect> findCloseByAnchor(long anchorSignalId) {
    return findExitByAnchor(anchorSignalId);
  }

  private Optional<Effect> findBy(String predicate, Object value) {
    return jdbc
        .query("SELECT " + columns() + " FROM swing_paper_effects WHERE " + predicate, SwingPaperEffectRepository::map, value)
        .stream()
        .findFirst();
  }

  /** Every expected effect for a session is confirmed, or no paper effect was expected. */
  public boolean allConfirmed(String batch, LocalDate sessionDate) {
    Boolean result =
        jdbc.queryForObject(
            "SELECT NOT EXISTS (SELECT 1 FROM swing_paper_effects"
                + " WHERE batch=? AND session_date=? AND status <> 'CONFIRMED')",
            Boolean.class,
            batch,
            java.sql.Date.valueOf(sessionDate));
    return Boolean.TRUE.equals(result);
  }

  /** Pending expected/claimed rows are replayed by the catch-up repair pass. */
  public List<Effect> pending(String batch, LocalDate sessionDate) {
    return jdbc.query(
        "SELECT " + columns() + " FROM swing_paper_effects"
            + " WHERE batch=? AND session_date=? AND status <> 'CONFIRMED' ORDER BY id",
        SwingPaperEffectRepository::map,
        batch,
        java.sql.Date.valueOf(sessionDate));
  }

  /** Alias used by repair-oriented callers. */
  public List<Effect> repairable(String batch, LocalDate sessionDate) {
    return pending(batch, sessionDate);
  }

  /** Sessions with an unresolved effect, including sessions whose canonical batch marker already exists. */
  public List<LocalDate> pendingSessions(String batch) {
    return jdbc.query(
        "SELECT DISTINCT session_date FROM swing_paper_effects"
            + " WHERE batch=? AND status <> 'CONFIRMED' ORDER BY session_date",
        (rs, n) -> rs.getObject("session_date", LocalDate.class),
        batch);
  }

  /** Claims an ENTRY effect immediately before the paper open. */
  public Optional<Effect> claimOpen(long effectId, long quantityBefore, int ignoredExpectedQty) {
    return claimEntryEffect(effectId, quantityBefore);
  }

  /** Claims an EXIT effect immediately before the paper close. */
  public Optional<Effect> claimClose(long effectId) {
    return claimExitEffect(effectId);
  }

  public boolean entryAttempted(String batch, LocalDate sessionDate, String tradingsymbol) {
    Boolean result =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM swing_paper_effects"
                + " WHERE batch=? AND session_date=? AND effect_key=? AND effect_type='ENTRY')",
            Boolean.class,
            batch,
            java.sql.Date.valueOf(sessionDate),
            entryKey(tradingsymbol));
    return Boolean.TRUE.equals(result);
  }

  public boolean pendingEntry(long signalId) {
    return pendingExists("effect_type='ENTRY' AND signal_id=?", signalId);
  }

  public boolean pendingExit(long anchorSignalId) {
    return pendingExists("effect_type='EXIT' AND anchor_signal_id=?", anchorSignalId);
  }

  private boolean pendingExists(String predicate, Object value) {
    Boolean result =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM swing_paper_effects WHERE status <> 'CONFIRMED' AND "
                + predicate
                + ")",
            Boolean.class,
            value);
    return Boolean.TRUE.equals(result);
  }

  private static String columns() {
    return "id, batch, session_date, effect_key, effect_type, tradingsymbol, signal_id,"
        + " anchor_signal_id, exit_signal_id, close_reason, close_price, expected_qty,"
        + " quantity_before, status";
  }

  private static String entryKey(String symbol) {
    return "ENTRY:" + symbol;
  }

  private static String exitKey(long anchorSignalId) {
    return "EXIT:" + anchorSignalId;
  }

  // Compatibility shims for the partial V049 implementation already present in this worktree.
  public boolean claimEntry(String batch, LocalDate sessionDate, String tradingsymbol, long signalId) {
    if (expectEntry(batch, sessionDate, tradingsymbol)) {
      bindEntry(batch, sessionDate, tradingsymbol, signalId, 0);
      return true;
    }
    return jdbc.query(
            "SELECT " + columns()
                + " FROM swing_paper_effects WHERE batch=? AND session_date=? AND effect_key=?",
            SwingPaperEffectRepository::map,
            batch,
            java.sql.Date.valueOf(sessionDate),
            entryKey(tradingsymbol))
        .stream()
        .anyMatch(
            effect ->
                effect.signalId() != null
                    && effect.signalId() == signalId
                    && !"CONFIRMED".equals(effect.status()));
  }

  public boolean claimExit(
      String batch,
      LocalDate sessionDate,
      long anchorSignalId,
      long exitSignalId,
      String reason,
      BigDecimal price) {
    if (!expectExit(batch, sessionDate, anchorSignalId, reason, price)) {
      return false;
    }
    bindExit(batch, sessionDate, anchorSignalId, exitSignalId);
    return true;
  }

  public boolean entryConfirmedByPaper(long signalId) {
    Boolean result =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM paper_orders WHERE signal_id=? AND status='FILLED')",
            Boolean.class,
            signalId);
    return Boolean.TRUE.equals(result);
  }

  public boolean exitConfirmedByPaper(long anchorSignalId) {
    Boolean result =
        jdbc.queryForObject(
            "SELECT NOT EXISTS(SELECT 1 FROM paper_positions p JOIN signals s ON s.id=?"
                + " WHERE p.status='OPEN' AND p.book=COALESCE(s.book, 'other')"
                + " AND p.exchange=s.exchange AND p.tradingsymbol=s.tradingsymbol"
                + " AND p.side=s.side)",
            Boolean.class,
            anchorSignalId);
    return Boolean.TRUE.equals(result);
  }
}
