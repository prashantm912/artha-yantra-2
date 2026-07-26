package in.arthayantra.strategysignal.paper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code paper_positions}. The §F.6 partial-unique key guarantees at most one OPEN
 * position per {@code (exchange, tradingsymbol, side)}. Unrealized P&amp;L is NEVER persisted — only
 * realized P&amp;L (accumulated at close) lives on the row; unrealized is computed live elsewhere.
 */
@Repository
public class PaperPositionRepository {

  /** One position row. */
  public record PositionRow(
      long id,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      BigDecimal avgEntryPrice,
      BigDecimal realizedPnl,
      String status,
      OffsetDateTime openedAt,
      OffsetDateTime closedAt,
      String closeReason,
      BigDecimal stopLoss,
      BigDecimal takeProfit,
      String book) {}

  private static final String COLUMNS =
      "id, exchange, tradingsymbol, side, qty, avg_entry_price, realized_pnl, status,"
          + " opened_at, closed_at, close_reason, stop_loss, take_profit, book";

  private final JdbcTemplate jdbc;

  /** Wires the strategy datasource. */
  public PaperPositionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static PositionRow map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    return new PositionRow(
        rs.getLong("id"),
        rs.getString("exchange"),
        rs.getString("tradingsymbol"),
        rs.getString("side"),
        rs.getLong("qty"),
        rs.getBigDecimal("avg_entry_price"),
        rs.getBigDecimal("realized_pnl"),
        rs.getString("status"),
        rs.getObject("opened_at", OffsetDateTime.class),
        rs.getObject("closed_at", OffsetDateTime.class),
        rs.getString("close_reason"),
        rs.getBigDecimal("stop_loss"),
        rs.getBigDecimal("take_profit"),
        rs.getString("book"));
  }

  /** The OPEN position for a book+key+side, if any (the per-book §F.6 averaging key). */
  public Optional<PositionRow> findOpen(String book, String exchange, String tradingsymbol, String side) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM paper_positions WHERE book=? AND exchange=? AND tradingsymbol=?"
                + " AND side=? AND status='OPEN'",
            PaperPositionRepository::map,
            book,
            exchange,
            tradingsymbol,
            side)
        .stream()
        .findFirst();
  }

  /** A position by id. */
  public Optional<PositionRow> find(long id) {
    return jdbc
        .query("SELECT " + COLUMNS + " FROM paper_positions WHERE id=?", PaperPositionRepository::map, id)
        .stream()
        .findFirst();
  }

  /** The local book key for one position, without loading detail-pane or instrument-provenance columns. */
  public Optional<String> findBook(long id) {
    return jdbc
        .query("SELECT book FROM paper_positions WHERE id=?", (rs, n) -> rs.getString("book"), id)
        .stream()
        .findFirst();
  }

  /**
   * The FULL position row for the detail pane (Phase-2 §6.4): the base {@link PositionRow} fields plus
   * the provenance columns the compact row omits — {@code subaccount_idx} (E10 ledger), the F9
   * {@code advised_lots}/{@code margin_snapshot}/{@code margin_pct} advisory, and {@code
   * opening_signal_id} (the ONE signal that opened it, audit H5). A dedicated read so the hot
   * {@link PositionRow} used across the ledger stays unchanged.
   */
  public record DetailRow(
      long id,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      BigDecimal avgEntryPrice,
      BigDecimal realizedPnl,
      String status,
      OffsetDateTime openedAt,
      OffsetDateTime closedAt,
      String closeReason,
      BigDecimal stopLoss,
      BigDecimal takeProfit,
      String book,
      Integer subaccountIdx,
      Long advisedLots,
      BigDecimal marginSnapshot,
      BigDecimal marginPct,
      Long openingSignalId) {}

  /** The full detail row for one position id (all provenance columns), if any. */
  public Optional<DetailRow> findDetail(long id) {
    return jdbc
        .query(
            "SELECT id, exchange, tradingsymbol, side, qty, avg_entry_price, realized_pnl, status,"
                + " opened_at, closed_at, close_reason, stop_loss, take_profit, book,"
                + " subaccount_idx, advised_lots, margin_snapshot, margin_pct, opening_signal_id"
                + " FROM paper_positions WHERE id=?",
            (rs, n) ->
                new DetailRow(
                    rs.getLong("id"),
                    rs.getString("exchange"),
                    rs.getString("tradingsymbol"),
                    rs.getString("side"),
                    rs.getLong("qty"),
                    rs.getBigDecimal("avg_entry_price"),
                    rs.getBigDecimal("realized_pnl"),
                    rs.getString("status"),
                    rs.getObject("opened_at", OffsetDateTime.class),
                    rs.getObject("closed_at", OffsetDateTime.class),
                    rs.getString("close_reason"),
                    rs.getBigDecimal("stop_loss"),
                    rs.getBigDecimal("take_profit"),
                    rs.getString("book"),
                    rs.getObject("subaccount_idx", Integer.class),
                    rs.getObject("advised_lots", Long.class),
                    rs.getBigDecimal("margin_snapshot"),
                    rs.getBigDecimal("margin_pct"),
                    rs.getObject("opening_signal_id", Long.class)),
            id)
        .stream()
        .findFirst();
  }

  /**
   * Edits an OPEN position's bracket levels (Phase-2 manual override). {@code COALESCE} keeps a level
   * the caller left {@code null} (a partial edit touches only the field supplied); the {@code AND
   * status='OPEN'} guard is a compare-and-set — returns 1 iff the row was still open, 0 if a concurrent
   * close beat the edit. The 15s {@link PaperBracketEvaluator} poll reads {@code stop_loss}/{@code
   * take_profit} fresh every pass, so the new level is live-effective on the very next evaluation.
   */
  public int updateBrackets(long id, BigDecimal stopLoss, BigDecimal takeProfit) {
    return jdbc.update(
        "UPDATE paper_positions SET stop_loss=COALESCE(?, stop_loss),"
            + " take_profit=COALESCE(?, take_profit) WHERE id=? AND status='OPEN'",
        stopLoss,
        takeProfit,
        id);
  }

  /**
   * The most recent position for a book+key regardless of status (newest opened first) — the V2
   * idempotency read-back: a duplicate submission within the retry window resolves to the still-OPEN
   * position it created; a late replay resolves to that position's row even after it closed. (A key
   * traded, closed, and re-opened between the original and the replay would resolve to the newest
   * position — outside the realistic network-retry window this idempotency guard targets.)
   */
  public Optional<PositionRow> findLatestForKey(
      String book, String exchange, String tradingsymbol, String side) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM paper_positions WHERE book=? AND exchange=? AND tradingsymbol=?"
                + " AND side=? ORDER BY opened_at DESC, id DESC LIMIT 1",
            PaperPositionRepository::map,
            book,
            exchange,
            tradingsymbol,
            side)
        .stream()
        .findFirst();
  }

  /** Opens a new position in a book (optional SL/TP bracket levels), unstamped sub-account. */
  public long insertOpen(
      String book,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      BigDecimal avgEntryPrice,
      BigDecimal stopLoss,
      BigDecimal takeProfit) {
    return insertOpen(book, exchange, tradingsymbol, side, qty, avgEntryPrice, stopLoss, takeProfit, null);
  }

  /** Opens a new position in a book, charged to a 5-account sub-ledger (E10); idx null = unstamped. */
  public long insertOpen(
      String book,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      BigDecimal avgEntryPrice,
      BigDecimal stopLoss,
      BigDecimal takeProfit,
      Integer subaccountIdx) {
    return insertOpen(
        book, exchange, tradingsymbol, side, qty, avgEntryPrice, stopLoss, takeProfit, subaccountIdx,
        null, null);
  }

  /**
   * Opens a new position, additionally stamping the F9 {@code advised_lots} advisory (the risk-based
   * qty a 1%-of-book / stop-distance sizing would suggest; {@code null} when the entry carries no
   * stop) and the {@code openingSignalId} — the signal that opened this position (audit H5: lets F7
   * graduation attribute a position to the ONE strategy that opened it, not every strategy that ever
   * traded the same key). Advisory only — never overrides the actual filled {@code qty}; the SPAN
   * margin snapshot is priced fail-soft after commit ({@link #updateMarginSnapshot}).
   */
  public long insertOpen(
      String book,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      BigDecimal avgEntryPrice,
      BigDecimal stopLoss,
      BigDecimal takeProfit,
      Integer subaccountIdx,
      Long advisedLots,
      Long openingSignalId) {
    Long id =
        jdbc.queryForObject(
            """
            INSERT INTO paper_positions
              (book, exchange, tradingsymbol, side, qty, avg_entry_price, status, opened_at, stop_loss, take_profit, subaccount_idx, advised_lots, opening_signal_id)
            VALUES (?,?,?,?,?,?, 'OPEN', now(), ?, ?, ?, ?, ?) RETURNING id
            """,
            Long.class,
            book,
            exchange,
            tradingsymbol,
            side,
            qty,
            avgEntryPrice,
            stopLoss,
            takeProfit,
            subaccountIdx,
            advisedLots,
            openingSignalId);
    return id == null ? 0 : id;
  }

  /** Stamps the F9 SPAN margin snapshot (₹) + its % of book equity on an open position (fail-soft, post-open). */
  public void updateMarginSnapshot(long id, BigDecimal marginSnapshot, BigDecimal marginPct) {
    jdbc.update(
        "UPDATE paper_positions SET margin_snapshot=?, margin_pct=? WHERE id=? AND status='OPEN'",
        marginSnapshot,
        marginPct,
        id);
  }

  /** Grows an open position (averaged entry). */
  public void updateOpen(long id, long qty, BigDecimal avgEntryPrice) {
    jdbc.update(
        "UPDATE paper_positions SET qty=?, avg_entry_price=? WHERE id=? AND status='OPEN'",
        qty,
        avgEntryPrice,
        id);
  }

  /**
   * Closes a position with its realized P&amp;L + close reason (releases the partial-unique key).
   * The {@code AND status='OPEN'} guard makes the close a compare-and-set: it returns 1 iff THIS caller
   * won the transition, 0 if a concurrent closer already flipped it — so a bracket-vs-engine-exit race
   * cannot double-book the exit fill / journal / resolver (the caller acts only on rowcount==1).
   */
  public int close(long id, BigDecimal realizedPnl, String closeReason) {
    return jdbc.update(
        "UPDATE paper_positions SET status='CLOSED', realized_pnl=?, closed_at=now(), close_reason=?"
            + " WHERE id=? AND status='OPEN'",
        realizedPnl,
        closeReason,
        id);
  }

  /** All OPEN positions across every book (mechanical evaluators: brackets, expiry, margin heat). */
  public List<PositionRow> listOpen() {
    return listOpen(null);
  }

  /** OPEN positions in a book ({@code book} null → all books), newest first. */
  public List<PositionRow> listOpen(String book) {
    return jdbc.query(
        "SELECT " + COLUMNS + " FROM paper_positions WHERE status='OPEN'"
            + " AND (?::text IS NULL OR book = ?) ORDER BY opened_at DESC",
        PaperPositionRepository::map,
        book,
        book);
  }

  /** Closed trades in a window across every book. */
  public List<PositionRow> listClosed(
      OffsetDateTime from, OffsetDateTime to, String tradingsymbol, int limit, int offset) {
    return listClosed(null, from, to, tradingsymbol, limit, offset);
  }

  /** Closed trades in a window (optionally one book + one tradingsymbol), newest first. */
  public List<PositionRow> listClosed(
      String book, OffsetDateTime from, OffsetDateTime to, String tradingsymbol, int limit, int offset) {
    return jdbc.query(
        "SELECT " + COLUMNS + " FROM paper_positions WHERE status='CLOSED'"
            + " AND (?::text IS NULL OR book = ?)"
            + " AND (?::timestamptz IS NULL OR closed_at >= ?)"
            + " AND (?::timestamptz IS NULL OR closed_at <= ?)"
            + " AND (?::text IS NULL OR tradingsymbol = ?)"
            + " ORDER BY closed_at DESC LIMIT ? OFFSET ?",
        PaperPositionRepository::map,
        book,
        book,
        from,
        from,
        to,
        to,
        tradingsymbol,
        tradingsymbol,
        Math.min(Math.max(limit, 1), 500),
        Math.max(offset, 0));
  }

  /**
   * OPEN positions whose originating signal belongs to an {@code session.style: intraday} strategy —
   * the 15:45 mark-to-close set. All joins are SAME-schema (no cross-schema reference).
   */
  public List<PositionRow> intradayOpen() {
    return jdbc.query(
        """
        SELECT DISTINCT p.id, p.exchange, p.tradingsymbol, p.side, p.qty, p.avg_entry_price,
               p.realized_pnl, p.status, p.opened_at, p.closed_at, p.close_reason,
               p.stop_loss, p.take_profit, p.book
        FROM paper_positions p
        JOIN paper_orders o
          ON o.book = p.book AND o.exchange = p.exchange AND o.tradingsymbol = p.tradingsymbol
          AND o.side = p.side AND o.signal_id IS NOT NULL
        JOIN signals s ON s.id = o.signal_id
        JOIN strategy_versions sv ON sv.id = s.strategy_version_id
        WHERE p.status = 'OPEN' AND sv.config->'risk'->'session'->>'style' = 'intraday'
        """,
        PaperPositionRepository::map);
  }

  /** OPEN positions linked (via a signal-carrying order) to one signal — straddles yield both legs. */
  public List<PositionRow> openForSignal(long signalId) {
    return jdbc.query(
        """
        SELECT DISTINCT p.id, p.exchange, p.tradingsymbol, p.side, p.qty, p.avg_entry_price,
               p.realized_pnl, p.status, p.opened_at, p.closed_at, p.close_reason,
               p.stop_loss, p.take_profit, p.book
        FROM paper_positions p
        JOIN paper_orders o
          ON o.book = p.book AND o.exchange = p.exchange AND o.tradingsymbol = p.tradingsymbol
          AND o.side = p.side
        WHERE p.status = 'OPEN' AND o.signal_id = ?
        """,
        PaperPositionRepository::map,
        signalId);
  }

  /**
   * The signal ids whose orders opened this position key in a book (usually one; averaged adds share
   * it). Book-scoped so two books holding the same {@code (exchange, tradingsymbol, side)} never bleed
   * each other's signal ids into a resolve/expire pass.
   */
  public List<Long> signalIdsFor(String book, String exchange, String tradingsymbol, String side) {
    return jdbc.queryForList(
        "SELECT DISTINCT signal_id FROM paper_orders"
            + " WHERE book=? AND exchange=? AND tradingsymbol=? AND side=? AND signal_id IS NOT NULL",
        Long.class,
        book,
        exchange,
        tradingsymbol,
        side);
  }

  /** One open leg of a #11 straddle: the position id + its parent signal + that signal's detail. */
  public record StraddleLegRow(long positionId, long signalId, String scalperDetail) {}

  /**
   * Open positions whose parent signal is a #11 NEUTRAL straddle — the live combined-prem exit set. Both
   * legs of a straddle share the parent signal, so grouping the rows by {@code signalId} re-forms each
   * CE+PE pair; {@code scalper_detail} carries the underlying / expiry / ATM strike for the slLevel read.
   */
  public List<StraddleLegRow> openStraddleLegs() {
    return jdbc.query(
        """
        SELECT DISTINCT p.id, o.signal_id, s.scalper_detail::text AS scalper_detail
        FROM paper_positions p
        JOIN paper_orders o
          ON o.book = p.book AND o.exchange = p.exchange AND o.tradingsymbol = p.tradingsymbol
          AND o.side = p.side AND o.signal_id IS NOT NULL
        JOIN signals s ON s.id = o.signal_id
        WHERE p.status = 'OPEN' AND s.scalper_detail->>'side' = 'NEUTRAL'
        """,
        (rs, n) ->
            new StraddleLegRow(
                rs.getLong("id"), rs.getLong("signal_id"), rs.getString("scalper_detail")));
  }

  /** The strategy that opened a position in a book (via its first signal-linked order), for T-1. */
  public java.util.Optional<NotifyTarget> notifyTargetFor(
      String book, String exchange, String tradingsymbol, String side) {
    return jdbc
        .query(
            """
            SELECT s2.id AS strategy_id, COALESCE(s2.notification_channel, 'NTFY') AS channel
            FROM paper_orders o
            JOIN signals sg ON sg.id = o.signal_id
            JOIN strategy_versions sv ON sv.id = sg.strategy_version_id
            JOIN strategies s2 ON s2.id = sv.strategy_id
            WHERE o.book=? AND o.exchange=? AND o.tradingsymbol=? AND o.side=? AND o.signal_id IS NOT NULL
            ORDER BY o.id LIMIT 1
            """,
            (rs, n) ->
                new NotifyTarget(
                    java.util.UUID.fromString(rs.getString("strategy_id")), rs.getString("channel")),
            book,
            exchange,
            tradingsymbol,
            side)
        .stream()
        .findFirst();
  }

  /** A position's originating strategy + its notification channel. */
  public record NotifyTarget(java.util.UUID strategyId, String channel) {}

  /** Wipes a book's position ledger ({@code book} null → all books; paper reset). */
  public int deleteAll(String book) {
    return jdbc.update(
        "DELETE FROM paper_positions WHERE (?::text IS NULL OR book = ?)", book, book);
  }

  /** Count of OPEN positions across every book. */
  public int openCount() {
    return openCount(null);
  }

  /** Count of OPEN positions in a book ({@code book} null → all) — the {@code max_open} gate. */
  public int openCount(String book) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM paper_positions WHERE status='OPEN' AND (?::text IS NULL OR book = ?)",
            Integer.class,
            book,
            book);
    return count == null ? 0 : count;
  }

  /** Total realized P&amp;L over all closed trades across every book. */
  public BigDecimal realizedTotal() {
    return realizedTotal(null);
  }

  /** Total realized P&amp;L over closed trades in a book ({@code book} null → all). */
  public BigDecimal realizedTotal(String book) {
    return jdbc.queryForObject(
        "SELECT COALESCE(SUM(realized_pnl),0) FROM paper_positions WHERE status='CLOSED'"
            + " AND (?::text IS NULL OR book = ?)",
        BigDecimal.class,
        book,
        book);
  }

  /** Realized P&amp;L of trades closed on a given IST day across every book. */
  public BigDecimal realizedOn(java.time.LocalDate istDate) {
    return realizedOn(null, istDate);
  }

  /** Realized P&amp;L of trades closed on a given IST day in a book ({@code book} null → all). */
  public BigDecimal realizedOn(String book, java.time.LocalDate istDate) {
    return jdbc.queryForObject(
        "SELECT COALESCE(SUM(realized_pnl),0) FROM paper_positions"
            + " WHERE status='CLOSED' AND (?::text IS NULL OR book = ?)"
            + " AND (closed_at AT TIME ZONE 'Asia/Kolkata')::date = ?",
        BigDecimal.class,
        book,
        book,
        java.sql.Date.valueOf(istDate));
  }

  /** Win/loss counts of trades closed on an IST day. */
  public record WinLoss(int wins, int losses) {}

  /** Winning vs losing trades closed on a given IST day across every book. */
  public WinLoss winLossOn(java.time.LocalDate istDate) {
    return winLossOn(null, istDate);
  }

  /**
   * Winning vs losing trades closed on a given IST day in a book ({@code book} null → all) — the
   * scalper 5-account discipline input (§12.7). Realized P&amp;L &gt; 0 is a win; ≤ 0 a loss.
   */
  public WinLoss winLossOn(String book, java.time.LocalDate istDate) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FILTER (WHERE realized_pnl > 0) AS wins,"
            + " COUNT(*) FILTER (WHERE realized_pnl <= 0) AS losses"
            + " FROM paper_positions WHERE status='CLOSED' AND (?::text IS NULL OR book = ?)"
            + " AND (closed_at AT TIME ZONE 'Asia/Kolkata')::date = ?",
        (rs, n) -> new WinLoss(rs.getInt("wins"), rs.getInt("losses")),
        book,
        book,
        java.sql.Date.valueOf(istDate));
  }

  /**
   * Per-sub-account win/loss tally for an IST day (the E10 five-account ledger). {@code realized} is
   * the day's net realized P&amp;L summed over the account's closed trades — the input to the
   * per-account 1%-profit-lock (r15-16).
   */
  public record SubAccountTally(int idx, int wins, int losses, BigDecimal realized) {
    /** Back-compat: pre-1%-bank callers/tests with no realized sum (defaults zero ⇒ never profit-locked). */
    public SubAccountTally(int idx, int wins, int losses) {
      this(idx, wins, losses, BigDecimal.ZERO);
    }
  }

  /**
   * Per-sub-account win/loss counts + net realized P&amp;L of trades closed on an IST day — the
   * 5-account ledger input (risk-governance.md §3.2). ONLY idx-carrying (scalper) closed trades are
   * tallied; a NULL {@code subaccount_idx} (non-scalper / legacy) row is invisible to this model, so
   * an empty result signals the day carries no ledger trades and {@link ScalperAccountModel} falls
   * back to the day-granularity {@link #winLossOn} count. Win/loss uses the same convention as
   * {@code winLossOn}: realized P&amp;L &gt; 0 is a win, ≤ 0 (flat or losing) a loss.
   */
  public java.util.List<SubAccountTally> subAccountTalliesOn(java.time.LocalDate istDate) {
    return jdbc.query(
        "SELECT subaccount_idx,"
            + " COUNT(*) FILTER (WHERE realized_pnl > 0) AS wins,"
            + " COUNT(*) FILTER (WHERE realized_pnl <= 0) AS losses,"
            + " COALESCE(SUM(realized_pnl), 0) AS realized"
            + " FROM paper_positions WHERE status='CLOSED' AND subaccount_idx IS NOT NULL"
            + " AND (closed_at AT TIME ZONE 'Asia/Kolkata')::date = ?"
            + " GROUP BY subaccount_idx",
        (rs, n) ->
            new SubAccountTally(
                rs.getInt("subaccount_idx"),
                rs.getInt("wins"),
                rs.getInt("losses"),
                rs.getBigDecimal("realized")),
        java.sql.Date.valueOf(istDate));
  }

  /**
   * The per-sub-account capital fraction (config; equal 0.20 split by default) — the base for the
   * 1%-profit-lock target (account capital = equity × fraction). Read from {@code scalper_subaccount}.
   */
  public java.util.Map<Integer, BigDecimal> subAccountCapitalFractions() {
    return jdbc
        .query(
            "SELECT idx, capital_fraction FROM scalper_subaccount",
            (rs, n) -> java.util.Map.entry(rs.getInt("idx"), rs.getBigDecimal("capital_fraction")))
        .stream()
        .collect(
            java.util.stream.Collectors.toMap(
                java.util.Map.Entry::getKey, java.util.Map.Entry::getValue));
  }
}
