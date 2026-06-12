package in.arthayantra.marketdata.instruments;

import in.arthayantra.marketdata.kite.InstrumentDumpGateway.InstrumentRecord;
import in.arthayantra.marketdata.kite.InstrumentKey;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code marketdata.instruments} (B-7). All writes are set-based — the staging
 * batch + one atomic upsert + one tombstone statement (B-3 steps 3–5); never row-by-row saves
 * (the v1 anti-pattern this phase exists to kill).
 */
@Repository
public class InstrumentRepository {

  static final int BATCH_SIZE = 1_000;

  private final JdbcTemplate jdbc;

  /** Wires the marketdata datasource. */
  public InstrumentRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<Instrument> INSTRUMENT_MAPPER =
      (rs, rowNum) ->
          new Instrument(
              rs.getString("exchange"),
              rs.getString("tradingsymbol"),
              rs.getObject("instrument_token", Long.class),
              rs.getString("name"),
              rs.getString("segment"),
              rs.getString("instrument_type"),
              rs.getString("underlying_exchange"),
              rs.getString("underlying_tradingsymbol"),
              Optional.ofNullable(rs.getDate("expiry")).map(Date::toLocalDate).orElse(null),
              rs.getBigDecimal("strike"),
              rs.getBigDecimal("tick_size"),
              rs.getObject("lot_size", Integer.class),
              rs.getBoolean("is_active"));

  /** Step 3: truncate staging and JDBC-batch the dump rows into it. */
  public int stageDump(List<InstrumentRecord> rows) {
    jdbc.execute("TRUNCATE TABLE instruments_staging");
    String sql =
        """
        INSERT INTO instruments_staging
          (exchange, tradingsymbol, instrument_token, exchange_token, name, segment,
           instrument_type, underlying_exchange, underlying_tradingsymbol, expiry,
           strike, tick_size, lot_size)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
        """;
    for (int from = 0; from < rows.size(); from += BATCH_SIZE) {
      List<InstrumentRecord> chunk = rows.subList(from, Math.min(from + BATCH_SIZE, rows.size()));
      jdbc.batchUpdate(
          sql,
          chunk,
          chunk.size(),
          (PreparedStatement ps, InstrumentRecord r) -> {
            UnderlyingRef underlying = UnderlyingRef.derive(r);
            ps.setString(1, r.exchange());
            ps.setString(2, r.tradingsymbol());
            ps.setLong(3, r.instrumentToken());
            ps.setObject(4, null);
            ps.setString(5, r.name());
            ps.setString(6, r.segment());
            ps.setString(7, r.instrumentType());
            ps.setString(8, underlying.exchange());
            ps.setString(9, underlying.tradingsymbol());
            ps.setObject(10, r.expiry() == null ? null : Date.valueOf(r.expiry()));
            ps.setBigDecimal(11, r.strike());
            ps.setBigDecimal(12, r.tickSize());
            ps.setObject(13, r.lotSize());
          });
    }
    return rows.size();
  }

  /** Step 4: one atomic upsert from staging keyed by the stable key. */
  public int upsertFromStaging() {
    return jdbc.update(
        """
        INSERT INTO instruments
          (exchange, tradingsymbol, instrument_token, exchange_token, name, segment,
           instrument_type, underlying_exchange, underlying_tradingsymbol, expiry,
           strike, tick_size, lot_size, is_active, first_seen_at, last_seen_at, updated_at)
        SELECT exchange, tradingsymbol, instrument_token, exchange_token, name, segment,
               instrument_type, underlying_exchange, underlying_tradingsymbol, expiry,
               strike, tick_size, lot_size, TRUE, now(), now(), now()
        FROM instruments_staging
        ON CONFLICT (exchange, tradingsymbol) DO UPDATE SET
          instrument_token = EXCLUDED.instrument_token,
          exchange_token = EXCLUDED.exchange_token,
          name = EXCLUDED.name,
          segment = EXCLUDED.segment,
          instrument_type = EXCLUDED.instrument_type,
          underlying_exchange = EXCLUDED.underlying_exchange,
          underlying_tradingsymbol = EXCLUDED.underlying_tradingsymbol,
          expiry = EXCLUDED.expiry,
          strike = EXCLUDED.strike,
          tick_size = EXCLUDED.tick_size,
          lot_size = EXCLUDED.lot_size,
          is_active = TRUE,
          last_seen_at = now(),
          updated_at = now()
        """);
  }

  /**
   * Step 5: tombstone rows that vanished from the dump — never delete, and only within the
   * exchanges the dump covered. {@code SYN-CONT} synthetic rows are exempt (B-19).
   */
  public int tombstoneVanished() {
    return jdbc.update(
        """
        UPDATE instruments i SET is_active = FALSE, updated_at = now()
        WHERE i.is_active
          AND i.segment IS DISTINCT FROM 'SYN-CONT'
          AND i.exchange IN (SELECT DISTINCT exchange FROM instruments_staging)
          AND NOT EXISTS (
            SELECT 1 FROM instruments_staging s
            WHERE s.exchange = i.exchange AND s.tradingsymbol = i.tradingsymbol)
        """);
  }

  /** Token → stable key for every active row (the normalizer's resolve map). */
  public Map<Long, InstrumentKey> activeTokenMap() {
    Map<Long, InstrumentKey> map = new HashMap<>();
    jdbc.query(
        "SELECT instrument_token, exchange, tradingsymbol FROM instruments "
            + "WHERE is_active AND instrument_token IS NOT NULL",
        rs -> {
          map.put(
              rs.getLong("instrument_token"),
              new InstrumentKey(rs.getString("exchange"), rs.getString("tradingsymbol")));
        });
    return map;
  }

  /** Single instrument by stable key. */
  public Optional<Instrument> findByKey(String exchange, String tradingsymbol) {
    List<Instrument> rows =
        jdbc.query(
            "SELECT * FROM instruments WHERE exchange = ? AND tradingsymbol = ?",
            INSTRUMENT_MAPPER,
            exchange,
            tradingsymbol);
    return rows.stream().findFirst();
  }

  /** Paged, filtered listing. */
  public List<Instrument> page(
      String exchange, String instrumentType, String q, int limit, int offset) {
    return jdbc.query(
        """
        SELECT * FROM instruments
        WHERE is_active
          AND (?::text IS NULL OR exchange = ?)
          AND (?::text IS NULL OR instrument_type = ?)
          AND (?::text IS NULL OR tradingsymbol ILIKE '%' || ? || '%' OR name ILIKE '%' || ? || '%')
        ORDER BY exchange, tradingsymbol
        LIMIT ? OFFSET ?
        """,
        INSTRUMENT_MAPPER,
        exchange, exchange, instrumentType, instrumentType, q, q, q, limit, offset);
  }

  /** Total for the paged listing. */
  public long count(String exchange, String instrumentType, String q) {
    Long total =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM instruments
            WHERE is_active
              AND (?::text IS NULL OR exchange = ?)
              AND (?::text IS NULL OR instrument_type = ?)
              AND (?::text IS NULL OR tradingsymbol ILIKE '%' || ? || '%' OR name ILIKE '%' || ? || '%')
            """,
            Long.class,
            exchange, exchange, instrumentType, instrumentType, q, q, q);
    return total == null ? 0 : total;
  }

  /** Trigram-ranked typeahead (B-7 GIN index). */
  public List<Instrument> searchRanked(String q, int limit) {
    return jdbc.query(
        """
        SELECT *, GREATEST(public.similarity(tradingsymbol, ?), public.similarity(coalesce(name,''), ?)) AS rank
        FROM instruments
        WHERE is_active AND (tradingsymbol OPERATOR(public.%) ?
                             OR coalesce(name,'') OPERATOR(public.%) ?
                             OR tradingsymbol ILIKE ? || '%')
        ORDER BY rank DESC, tradingsymbol
        LIMIT ?
        """,
        INSTRUMENT_MAPPER,
        q, q, q, q, q, limit);
  }

  /** Sorted distinct option expiries for an underlying. */
  public List<LocalDate> expiries(String underlying) {
    return jdbc.query(
        """
        SELECT DISTINCT expiry FROM instruments
        WHERE is_active AND underlying_tradingsymbol = ? AND instrument_type IN ('CE','PE')
          AND expiry IS NOT NULL
        ORDER BY expiry
        """,
        (rs, n) -> rs.getDate(1).toLocalDate(),
        underlying);
  }

  /** Sorted strikes for an underlying + expiry. */
  public List<java.math.BigDecimal> strikes(String underlying, LocalDate expiry) {
    return jdbc.query(
        """
        SELECT DISTINCT strike FROM instruments
        WHERE is_active AND underlying_tradingsymbol = ? AND expiry = ?
          AND instrument_type IN ('CE','PE') AND strike IS NOT NULL
        ORDER BY strike
        """,
        (rs, n) -> rs.getBigDecimal(1),
        underlying,
        expiry == null ? null : Date.valueOf(expiry));
  }

  /** The CE/PE rows of one (underlying, expiry) chain, strike-ordered (Phase 15). */
  public List<Instrument> optionChain(String underlying, LocalDate expiry) {
    return jdbc.query(
        """
        SELECT * FROM instruments
        WHERE is_active AND underlying_tradingsymbol = ? AND expiry = ?
          AND instrument_type IN ('CE','PE') AND strike IS NOT NULL
        ORDER BY strike, instrument_type
        """,
        INSTRUMENT_MAPPER,
        underlying,
        Date.valueOf(expiry));
  }

  /** Active FUT contracts of an underlying, expiry-sorted (Phase 15A / B-18). */
  public List<Instrument> futures(String underlying) {
    return jdbc.query(
        """
        SELECT * FROM instruments
        WHERE is_active AND underlying_tradingsymbol = ? AND instrument_type = 'FUT'
          AND expiry IS NOT NULL
        ORDER BY expiry
        """,
        INSTRUMENT_MAPPER,
        underlying);
  }

  /**
   * Upserts the synthetic CONT row (segment {@code SYN-CONT}, B-19) so search/charts resolve the
   * symbol; no token — it can never reach a Kite port or the subscription registry.
   */
  public void upsertSyntheticCont(
      String exchange, String tradingsymbol, String name, String underlyingExchange, String underlying) {
    jdbc.update(
        """
        INSERT INTO instruments
          (exchange, tradingsymbol, instrument_token, name, segment, instrument_type,
           underlying_exchange, underlying_tradingsymbol, is_active, first_seen_at, updated_at)
        VALUES (?, ?, NULL, ?, 'SYN-CONT', 'FUT', ?, ?, TRUE, now(), now())
        ON CONFLICT (exchange, tradingsymbol) DO UPDATE SET is_active = TRUE, updated_at = now()
        """,
        exchange, tradingsymbol, name, underlyingExchange, underlying);
  }

  /** Active row count (startup bootstrap check). */
  public long countActive() {
    Long count = jdbc.queryForObject("SELECT count(*) FROM instruments WHERE is_active", Long.class);
    return count == null ? 0 : count;
  }

  /** One full row read used by tests. */
  public Map<String, Object> rawRow(String exchange, String tradingsymbol) throws SQLException {
    return jdbc.queryForMap(
        "SELECT * FROM instruments WHERE exchange = ? AND tradingsymbol = ?", exchange, tradingsymbol);
  }
}
