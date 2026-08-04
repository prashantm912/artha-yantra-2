package in.arthayantra.strategysignal.paper;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seeds the LIVE-measured twin shape for the V058 strategy-scoped-open-key tests: two DISTINCT
 * strategies, each with a published version, each emitting an ACTIVE ENTRY on the SAME
 * {@code (exchange, tradingsymbol, side)}.
 *
 * <p>That is not a hypothetical — it is exactly what the scalper book does today. All 10 closed
 * scalper positions on the live book (re-measured 2026-08-03) are built from two ENTRY orders,
 * {@code scalp-golden-crossover-*} and {@code scalp-connect-the-dots-*}, placed 0.1–2.2 s apart at
 * BYTE-IDENTICAL fill prices; the second averages into the first and the merged row then exits on
 * whichever twin's doctrine fires first (connect-the-dots owns 5/5 TIME_STOP closes,
 * golden-crossover 5/5 STRUCTURAL_STOP).
 *
 * <p>Raw JDBC rather than {@code RegistryService}: these tests care only about strategy IDENTITY on
 * the position key, so a YAML-compiled strategy would add a large irrelevant surface. Every seed is
 * uniquely suffixed — the IT container is a shared singleton with no per-method cleanup, and
 * {@code strategies} is unique on BOTH slug and name.
 */
final class ScopedKeyTwinFixture {

  private ScopedKeyTwinFixture() {}

  /** One seeded strategy: its stable {@code strategies.id}, its version, and its ENTRY signal. */
  record Twin(UUID strategyId, UUID versionId, long signalId) {}

  /** A strategy + published version + one ACTIVE ENTRY signal on the given key. */
  static Twin seedTwin(
      JdbcTemplate jdbc, String label, String exchange, String tradingsymbol, String side) {
    UUID strategyId = seedStrategy(jdbc, label);
    UUID versionId = seedVersion(jdbc, strategyId, "1");
    long signalId = seedEntry(jdbc, versionId, exchange, tradingsymbol, side);
    return new Twin(strategyId, versionId, signalId);
  }

  /** A strategy row with a unique slug AND name (RegistryService 409s on either). */
  static UUID seedStrategy(JdbcTemplate jdbc, String label) {
    String suffix = UUID.randomUUID().toString();
    return jdbc.queryForObject(
        "INSERT INTO strategies (slug, name, tags) VALUES (?, ?, '{scalper}'::text[]) RETURNING id",
        UUID.class,
        label + "-" + suffix,
        label + " " + suffix);
  }

  /** A published version of an existing strategy — a REPUBLISH mints a new row with the same strategy_id. */
  static UUID seedVersion(JdbcTemplate jdbc, UUID strategyId, String version) {
    return seedVersion(jdbc, strategyId, version, "{}");
  }

  /**
   * The same, with an explicit {@code config} JSON — used to give a version a
   * {@code risk.session.style} of {@code intraday} or {@code btst}, which is what
   * {@code PaperPositionRepository.intradayOpen} classifies on.
   */
  static UUID seedVersion(JdbcTemplate jdbc, UUID strategyId, String version, String configJson) {
    return jdbc.queryForObject(
        """
        INSERT INTO strategy_versions
          (strategy_id, version, config_yaml, config, schema_version, checksum, status)
        VALUES (?, ?, '', ?::jsonb, '1', ?, 'published') RETURNING id
        """,
        UUID.class,
        strategyId,
        version,
        configJson,
        "chk-" + UUID.randomUUID());
  }

  /** A version whose config declares {@code risk.session.style} (intraday / btst / swing). */
  static UUID seedVersionWithStyle(JdbcTemplate jdbc, UUID strategyId, String style) {
    return seedVersion(
        jdbc, strategyId, "1", "{\"risk\":{\"session\":{\"style\":\"" + style + "\"}}}");
  }

  /** An ACTIVE ENTRY signal carrying a {@code scalper_detail} (a NEUTRAL one marks a straddle). */
  static long seedEntryWithDetail(
      JdbcTemplate jdbc,
      UUID versionId,
      String exchange,
      String tradingsymbol,
      String side,
      String scalperDetailJson) {
    return jdbc.queryForObject(
        """
        INSERT INTO signals
          (strategy_version_id, exchange, tradingsymbol, "interval", signal_type, side,
           composite_score, score_breakdown, scalper_detail)
        VALUES (?, ?, ?, '3m', 'ENTRY', ?, 0.7, '{}'::jsonb, ?::jsonb) RETURNING id
        """,
        Long.class,
        versionId,
        exchange,
        tradingsymbol,
        side,
        scalperDetailJson);
  }

  /** A FILLED entry order linking a signal to a book+key — what every position join traverses. */
  static long seedOrder(
      JdbcTemplate jdbc,
      String book,
      Long signalId,
      String exchange,
      String tradingsymbol,
      String side,
      long qty) {
    return jdbc.queryForObject(
        """
        INSERT INTO paper_orders
          (book, signal_id, exchange, tradingsymbol, side, qty, status, placed_at, filled_at, fill_price)
        VALUES (?, ?, ?, ?, ?, ?, 'FILLED', now(), now(), 100.00) RETURNING id
        """,
        Long.class,
        book,
        signalId,
        exchange,
        tradingsymbol,
        side,
        qty);
  }

  /** An ACTIVE ENTRY signal on a version (status defaults to ACTIVE, generated_at to now()). */
  static long seedEntry(
      JdbcTemplate jdbc, UUID versionId, String exchange, String tradingsymbol, String side) {
    return jdbc.queryForObject(
        """
        INSERT INTO signals
          (strategy_version_id, exchange, tradingsymbol, "interval", signal_type, side,
           composite_score, score_breakdown)
        VALUES (?, ?, ?, '3m', 'ENTRY', ?, 0.7, '{}'::jsonb) RETURNING id
        """,
        Long.class,
        versionId,
        exchange,
        tradingsymbol,
        side);
  }

  /** OPEN rows on one book+key, oldest first, as (id, qty, stop_loss, subaccount_idx, strategy_id). */
  static java.util.List<OpenLot> openLots(
      JdbcTemplate jdbc, String book, String exchange, String tradingsymbol, String side) {
    return jdbc.query(
        "SELECT id, qty, stop_loss, subaccount_idx, strategy_id FROM paper_positions"
            + " WHERE book=? AND exchange=? AND tradingsymbol=? AND side=? AND status='OPEN' ORDER BY id",
        (rs, n) ->
            new OpenLot(
                rs.getLong("id"),
                rs.getLong("qty"),
                rs.getBigDecimal("stop_loss"),
                rs.getObject("subaccount_idx", Integer.class),
                rs.getObject("strategy_id", UUID.class)),
        book,
        exchange,
        tradingsymbol,
        side);
  }

  /** One open lot, projected to the columns these tests discriminate on. */
  record OpenLot(
      long id,
      long qty,
      java.math.BigDecimal stopLoss,
      Integer subaccountIdx,
      UUID strategyId) {}
}
