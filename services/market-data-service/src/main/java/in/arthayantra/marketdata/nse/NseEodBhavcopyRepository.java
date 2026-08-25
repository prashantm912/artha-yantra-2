package in.arthayantra.marketdata.nse;

import in.arthayantra.marketdata.equitydaily.CashEquityUniverse;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Idempotent upsert of NSE bhavcopy rows by {@code (trade_date, symbol, series)}. */
@Repository
public class NseEodBhavcopyRepository {

  private final JdbcTemplate jdbc;

  public NseEodBhavcopyRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Newest {@code trade_date} present in ANY series — the catch-up watermark; null when the table is
   * empty.
   *
   * <p>⚠️ Series-agnostic ON PURPOSE, and it must stay that way: the backfill catch-up
   * ({@code BhavcopyBackfillService:334,396}) asks "did we fetch this day at all", which is a
   * question about the FILE, not about the cash universe. A day whose file landed with only
   * non-cash rows was still fetched.
   *
   * <p>⚠️ But a CONSUMER that pairs this watermark with a cash-universe population has a
   * mixed-watermark defect by construction — H24 names {@code DataQualityEodJob:103} and
   * {@code EquityBreadthEodJob:82} as the two instances. Those want {@link #maxCashTradeDate()}.
   */
  public LocalDate maxTradeDate() {
    return jdbc.query(
        "SELECT max(trade_date) AS d FROM nse_eod_bhavcopy",
        rs -> rs.next() ? rs.getObject("d", LocalDate.class) : null);
  }

  /**
   * Newest {@code trade_date} carrying CASH-EQUITY rows ({@link CashEquityUniverse}) — the watermark
   * for any consumer whose population is the cash universe, so that watermark and population agree.
   *
   * <p>Measured 2026-08-18: identical to {@link #maxTradeDate()} on live, and there is no date in
   * the table's whole span carrying non-cash rows without cash rows. The two can only diverge on a
   * partial-file day — which is exactly the day a data-quality report must not silently score.
   */
  public LocalDate maxCashTradeDate() {
    return jdbc.query(
        "SELECT max(trade_date) AS d FROM nse_eod_bhavcopy WHERE " + CashEquityUniverse.SERIES_PREDICATE,
        rs -> rs.next() ? rs.getObject("d", LocalDate.class) : null);
  }

  /**
   * Trade dates already stored in {@code [from, to]} — the catch-up anti-joins against these so a
   * day missed by a transient fetch error (which looks identical to a holiday) is re-attempted on a
   * later run instead of being permanently skipped once the watermark moves past it.
   */
  public List<LocalDate> presentTradeDates(LocalDate from, LocalDate to) {
    return jdbc.query(
        "SELECT DISTINCT trade_date FROM nse_eod_bhavcopy WHERE trade_date BETWEEN ? AND ?",
        (rs, n) -> rs.getObject("trade_date", LocalDate.class),
        from, to);
  }

  /**
   * Cash-equity (EQ+BE) symbols present on one settled trade date, sorted for deterministic diff
   * output.
   *
   * <p>⚠️ Was {@code eqSymbolsOn}, EQ-only, until H24 PR-5. Its one consumer diffs today's set
   * against the prior day's to report symbols "absent vs prior day", so an EQ-only read counted an
   * NSE surveillance move EQ→BE as a DISAPPEARANCE — the symbol is present and trading, merely
   * reclassified. Measured on the latest session pair: <b>3 reported drops, of which 2 were
   * migrations, not absences</b>, and ~400+ symbols changed between EQ and BE in the trailing
   * ~120 days (measured 2026-08-18; the exact count depends on whether the window is counted in
   * calendar days or sessions, and the table gap-fills, so it will not reproduce to the digit). It fails in the ALARMING direction, and it also depresses the {@code bhavcopy_eq}
   * coverage ratio against its 0.98 floor.
   */
  public Set<String> cashSymbolsOn(LocalDate date) {
    return new TreeSet<>(
        jdbc.query(
            "SELECT symbol FROM nse_eod_bhavcopy WHERE trade_date = ? AND "
                + CashEquityUniverse.SERIES_PREDICATE,
            (rs, n) -> rs.getString("symbol"),
            java.sql.Date.valueOf(date)));
  }

  /**
   * Previous settled trade date carrying CASH-EQUITY rows, or null on cold start.
   *
   * <p>⚠️ Was series-agnostic until H24 PR-6, and the pairing was the LAST residual of the
   * mixed-watermark defect this row exists to close — inside the very method PR-5 rewrote. Its one
   * consumer feeds the result straight into {@link #cashSymbolsOn}, so on a partial-file day the
   * agnostic form returned a date with no cash rows, {@code prior} came back EMPTY, and
   * {@code DataQualityEodJob}'s {@code ok} flag short-circuits to TRUE on {@code expected == 0}.
   * <b>The data-quality check went silently green on exactly the day it exists to catch</b>, which
   * is strictly worse than the false-alarm direction PR-5 fixed. Unreachable on today's data — no
   * date in the table's span carries non-cash rows without cash rows — but it is the same
   * "measured unreachable" argument that justified deferring the watermark, and it costs one line.
   */
  public LocalDate prevCashTradeDate(LocalDate before) {
    return jdbc.query(
        "SELECT max(trade_date) AS d FROM nse_eod_bhavcopy WHERE trade_date < ? AND "
            + CashEquityUniverse.SERIES_PREDICATE,
        rs -> rs.next() ? rs.getObject("d", LocalDate.class) : null,
        java.sql.Date.valueOf(before));
  }

  /**
   * The OFFICIAL NSE closing prices for {@code symbols} on one settled {@code date} — at most one
   * row per symbol. Ledger H9's read seam: the swing settle re-prices its exit FILL against this,
   * because Kite's daily bar excludes the 15:15–15:30 closing auction (see {@link OfficialClose}).
   *
   * <p>⚠️ <b>Scoped to the CASH-EQUITY universe via {@link CashEquityUniverse#SERIES_PREDICATE},
   * then EQ → BE inside it. Not {@code series = 'EQ'}, and not series-AGNOSTIC either — both are
   * wrong, in opposite directions.</b>
   *
   * <ul>
   *   <li><b>EQ-only would drop live holdings.</b> Measured 2026-08-25, the swing books hold
   *       <b>TIRUPATIFL</b> and <b>UNIDT</b> as BE-ONLY names, so an EQ-only filter sends exactly
   *       those two down the fallback path every night — and it fails in the ALARMING direction, so
   *       the missing row reads as an outage rather than as a filter artifact (H24; a probe written
   *       that way once manufactured a "53 symbols have no bar at all" alarm out of nothing).
   *   <li><b>Series-agnostic would price an exit off a DIFFERENT INSTRUMENT.</b> The first cut of
   *       this method queried every series and ranked non-cash rows LAST — which ACCEPTS them when
   *       nothing better exists rather than rejecting them. Measured 2026-08-25: <b>170,950</b>
   *       {@code (trade_date, symbol)} pairs carry a non-cash series and NO EQ/BE row at all —
   *       99,565 {@code SM} (SME platform), 27,879 {@code ST}, 14,142 {@code GS} (government
   *       securities), 13,534 {@code GB}, 9,084 {@code BZ}, and a tail of nine more. Zero of them
   *       are currently-held swing symbols, so the defect was latent rather than live — but it is
   *       one holding away from settling a real exit against a government security, and the guard
   *       is a single predicate that was <em>already written</em>.
   * </ul>
   *
   * <p>⚠️ <b>Use the constant, never a re-spelled literal.</b> {@link CashEquityUniverse} calls
   * itself "the ONE definition of the cash-equity series predicate" and names the sites that
   * already follow it ({@code BhavcopyBackfillService:123}, {@code AdjustedEquityDailySql},
   * {@code TrendTemplateService:81}, {@code ManasScreenService:84}). Re-deriving a weaker rule
   * beside a canonical constant is the mistake this bullet exists to stop being repeated.
   *
   * <p><b>Why the EQ-over-BE precedence is load-bearing rather than a tidy tiebreak.</b> Measured 2026-08-25
   * over the trailing ~400 days: 497 (trade_date, symbol) pairs carry an EQ row AND a row in some
   * other series, and <b>327 of those 497 disagree on {@code close_price}</b>. EQ and BE never
   * collide on the same date (0 pairs) — the collisions are EQ+P1, EQ+T0, EQ+N3 and BE+P1, the
   * special/trade-for-trade settlement series. So picking the wrong row is a WRONG PRICE, not a
   * cosmetic choice, and "no rows collide today" would have been a false comfort. The predicate
   * above already excludes those partners, so the ranking now only ever arbitrates EQ vs BE — but
   * it stays because the two rules answer different questions and must not be collapsed.
   *
   * <p><b>Why the precedence is applied in Java rather than in SQL.</b> The obvious form is
   * {@code SELECT DISTINCT ON (symbol) … ORDER BY symbol, CASE series WHEN 'EQ' THEN 0 …}. That is
   * a top-level DISTINCT/ORDER BY on a COMPUTED EXPRESSION over a compressed hypertable — the exact
   * shape of the TimescaleDB 2.18.2 sorted-merge planner assertion that took all three OI-confluence
   * dots offline for a session. The bug needs a {@code LIMIT} too, which this query does not have,
   * so it would probably plan fine — but "probably" is not a reason to write the one shape this repo
   * has already been burned by, when the alternative is a plain equality scan and a fold over at most
   * TWO rows per symbol — the cash predicate above admits only {@code EQ} and {@code BE}, and those
   * two never co-occur on one date (0 pairs measured). The {@code ORDER BY} below is on BARE COLUMNS
   * only, which is always safe.
   *
   * <p>A symbol with no row for the date, or whose only rows carry a NULL {@code close_price}, is
   * OMITTED rather than returned with a null price — the caller must be able to tell "absent" from
   * "present and zero", and an omitted symbol is what routes it to the documented fallback.
   */
  public List<OfficialClose> officialClosesOn(LocalDate date, Collection<String> symbols) {
    List<String> distinct =
        symbols.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
    if (distinct.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", Collections.nCopies(distinct.size(), "?"));
    Object[] args = new Object[distinct.size() + 1];
    args[0] = java.sql.Date.valueOf(date);
    for (int i = 0; i < distinct.size(); i++) {
      args[i + 1] = distinct.get(i);
    }
    List<OfficialClose> rows =
        jdbc.query(
            "SELECT symbol, trade_date, series, close_price, last_price FROM nse_eod_bhavcopy"
                + " WHERE trade_date = ? AND "
                + CashEquityUniverse.SERIES_PREDICATE
                + " AND close_price IS NOT NULL AND symbol IN ("
                + placeholders
                + ") ORDER BY symbol, series",
            (rs, n) ->
                new OfficialClose(
                    rs.getString("symbol"),
                    rs.getObject("trade_date", LocalDate.class),
                    rs.getBigDecimal("close_price"),
                    rs.getBigDecimal("last_price"),
                    rs.getString("series")),
            args);
    Map<String, OfficialClose> best = new LinkedHashMap<>();
    for (OfficialClose row : rows) {
      best.merge(
          row.tradingsymbol(),
          row,
          (kept, candidate) ->
              seriesRank(kept.series()) <= seriesRank(candidate.series()) ? kept : candidate);
    }
    return List.copyOf(best.values());
  }

  /**
   * EQ before BE — the only arbitration left once {@link CashEquityUniverse#SERIES_PREDICATE} has
   * already excluded everything else at the SQL level.
   *
   * <p>The {@code default} arm is unreachable by construction from {@link #officialClosesOn} and is
   * kept only because a {@code switch} over a {@code String} needs one. It deliberately ranks LAST
   * rather than throwing: if the predicate were ever weakened, a loud 500 on a read that feeds a
   * money path is not obviously better than the caller's own counted, alerted fallback. The
   * predicate is the guard; this is arithmetic.
   */
  private static int seriesRank(String series) {
    return switch (series) {
      case "EQ" -> 0;
      case "BE" -> 1;
      default -> 2;
    };
  }

  public void upsertAll(List<BhavcopyFetcher.BhavcopyRow> rows) {
    jdbc.batchUpdate(
        """
        INSERT INTO nse_eod_bhavcopy (
          trade_date, symbol, series,
          prev_close, open_price, high_price, low_price, last_price, close_price, avg_price,
          ttl_trd_qnty, turnover_lacs, no_of_trades, deliv_qty, deliv_per, fetched_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
        ON CONFLICT (trade_date, symbol, series) DO UPDATE SET
          prev_close = EXCLUDED.prev_close,
          open_price = EXCLUDED.open_price,
          high_price = EXCLUDED.high_price,
          low_price = EXCLUDED.low_price,
          last_price = EXCLUDED.last_price,
          close_price = EXCLUDED.close_price,
          avg_price = EXCLUDED.avg_price,
          ttl_trd_qnty = EXCLUDED.ttl_trd_qnty,
          turnover_lacs = EXCLUDED.turnover_lacs,
          no_of_trades = EXCLUDED.no_of_trades,
          deliv_qty = EXCLUDED.deliv_qty,
          deliv_per = EXCLUDED.deliv_per,
          fetched_at = now()
        """,
        rows,
        rows.size(),
        (ps, r) -> {
          ps.setObject(1, r.date());
          ps.setString(2, r.symbol());
          ps.setString(3, r.series());
          ps.setObject(4, r.prevClose());
          ps.setObject(5, r.open());
          ps.setObject(6, r.high());
          ps.setObject(7, r.low());
          ps.setObject(8, r.last());
          ps.setObject(9, r.close());
          ps.setObject(10, r.avgPrice());
          ps.setObject(11, r.totalTradedQty());
          ps.setObject(12, r.turnoverLacs());
          ps.setObject(13, r.noOfTrades());
          ps.setObject(14, r.delivQty());
          ps.setObject(15, r.delivPer());
        });
  }
}
