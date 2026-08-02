package in.arthayantra.marketdata.candles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Settles — empirically, against the real Timescale + Flyway lineage — whether {@link Candle}'s
 * OHLCV fields can be NULL on any read path the {@code Candle} OpenAPI schema describes, so the
 * spec's nullability declaration is a measured fact rather than a re-derived assumption.
 *
 * <p>The spec (contracts/market-data-service.openapi.json) declares {@code open}/{@code high}/{@code
 * low}/{@code close} as non-null {@code string}, {@code volume} as a non-null integer, and {@code
 * oi} as {@code ["integer","null"]}. Typing a field commits to it: a non-null declaration a
 * continuous aggregate could violate would publish a guarantee the service does not honour, and the
 * generated TS client would omit null handling on a field that can arrive null.
 *
 * <p>Three genuinely different read paths reach this record, and they are exercised separately:
 *
 * <ul>
 *   <li>{@link CandleRepository#range} — the base {@code candles} hypertable (serves 1m AND the
 *       native 1d; {@code CandleQueryService} routes both here, NOT to {@code candles_1d})
 *   <li>{@link CandleRepository#rangeFromAggregate} — the {@code candles_<iv>} continuous
 *       aggregates (5m/15m/1h/1d, plus the 1w cagg-on-cagg rolled from {@code candles_1d})
 *   <li>{@link CandleRepository#rangeRolledFromOneMinute} — the read-time 1m→3m rollup (the scalper
 *       PRIMARY; {@code candles_3m} was dropped in V027, so 3m has no materialised view)
 * </ul>
 *
 * <p>The adverse states constructed here are the ones that could plausibly produce a NULL: a cagg
 * read BEFORE any refresh (served by the real-time union rather than the materialisation), a bucket
 * with NO underlying 1m rows, and a partially-filled in-progress bucket. {@link
 * #oiIsGenuinelyNullOnEveryReadPath()} is the POSITIVE CONTROL — without it, every "is not null"
 * assertion here could pass vacuously on a fixture that simply never exercises a null.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class CandleNullabilityIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired private CandleRepository repository;
  @Autowired private JdbcTemplate jdbc;

  private static OffsetDateTime ist(String text) {
    return OffsetDateTime.parse(text + "+05:30");
  }

  /**
   * A 1m bar exactly as the live producers emit one: {@code BarWriter} (tick aggregation) and the
   * historical gateways always supply all four prices and a volume, and pass {@code oi = null} for
   * every instrument that has no open interest (all cash equities and indices) — which is why the
   * fixtures below leave oi null rather than inventing a value to make an assertion convenient.
   */
  private static Candle bar(
      String symbol, String time, String o, String h, String l, String c, long volume, Long oi) {
    return new Candle(
        "NSE", symbol, "1m", ist(time),
        new BigDecimal(o), new BigDecimal(h), new BigDecimal(l), new BigDecimal(c),
        volume, oi, "MOCK");
  }

  private static void assertOhlcvNonNull(Candle row) {
    assertThat(row.open()).as("open on %s bucket %s", row.interval(), row.bucket()).isNotNull();
    assertThat(row.high()).as("high on %s bucket %s", row.interval(), row.bucket()).isNotNull();
    assertThat(row.low()).as("low on %s bucket %s", row.interval(), row.bucket()).isNotNull();
    assertThat(row.close()).as("close on %s bucket %s", row.interval(), row.bucket()).isNotNull();
  }

  // ---------------------------------------------------------------------------------------------
  // The storage boundary: what the producer is even ABLE to emit.
  // ---------------------------------------------------------------------------------------------

  /**
   * The root reason the four price fields are declared non-null: {@code V003__candles_hypertable}
   * declares them {@code NUMERIC(18,4) NOT NULL} and {@code volume BIGINT NOT NULL DEFAULT 0}. No
   * read path can serve a NULL that the base table refuses to store, and every aggregate below
   * ultimately reads these columns — so this is the constraint the whole verdict rests on. Asserted
   * rather than read off the migration, because an applied migration and the live column are two
   * different facts.
   */
  @Test
  void baseTableRefusesNullOhlcv() {
    for (String column : List.of("open", "high", "low", "close", "volume")) {
      assertThatThrownBy(() -> insertWithNullColumn("NULLPROBE" + column, column))
          .as("candles.%s must be NOT NULL — the spec's non-null declaration depends on it", column)
          .isInstanceOf(DataIntegrityViolationException.class);
    }
  }

  /** Inserts a bar that is well-formed except for a single explicitly-NULL column. */
  private void insertWithNullColumn(String symbol, String nullColumn) {
    jdbc.update(
        "INSERT INTO candles (exchange, tradingsymbol, \"interval\", bucket,"
            + " open, high, low, close, volume, oi, source) VALUES ('NSE', ?, '1m', ?,"
            + ("open".equals(nullColumn) ? "NULL" : "100.00")
            + ", "
            + ("high".equals(nullColumn) ? "NULL" : "101.00")
            + ", "
            + ("low".equals(nullColumn) ? "NULL" : "99.00")
            + ", "
            + ("close".equals(nullColumn) ? "NULL" : "100.50")
            + ", "
            + ("volume".equals(nullColumn) ? "NULL" : "10")
            + ", NULL, 'MOCK')",
        symbol,
        Timestamp.from(ist("2026-06-12T09:15:00").toInstant()));
  }

  // ---------------------------------------------------------------------------------------------
  // Adverse state 1 — a cagg read with NOTHING materialised (served by the real-time union).
  // ---------------------------------------------------------------------------------------------

  /**
   * {@code candles_5m} is {@code materialized_only = false}, so a bucket whose bars have never been
   * through a refresh is computed live at read time. That is the state a fresh boot is permanently
   * in for recent data, and it is the read that would surface an aggregate's NULL if one existed.
   */
  @Test
  void unrefreshedSparseCaggServesNonNullOhlcFromTheRealTimeUnion() {
    String symbol = "NULLSPARSE";
    for (int i = 0; i < 5; i++) {
      repository.upsert(
          bar(symbol, String.format("2026-06-12T10:%02d:00", 15 + i),
              (100 + i) + ".00", (100 + i) + ".50", (99 + i) + ".50", (100 + i) + ".25", 10, null));
    }
    // deliberately NO refresh_continuous_aggregate — the real-time union must serve this

    List<Candle> rows =
        repository.rangeFromAggregate(
            "candles_5m", "NSE", symbol, ist("2026-06-12T10:00:00"), ist("2026-06-12T11:00:00"));

    assertThat(rows).as("real-time union must surface never-materialised bars").hasSize(1);
    assertOhlcvNonNull(rows.get(0));
    assertThat(rows.get(0).volume()).isEqualTo(50);
  }

  // ---------------------------------------------------------------------------------------------
  // Adverse state 2 — a bucket with NO underlying 1m rows.
  // ---------------------------------------------------------------------------------------------

  /**
   * The state most likely to be imagined as producing a null-filled row, and the reason it cannot:
   * the aggregates are {@code GROUP BY time_bucket(...)}, and a group with no rows produces NO ROW
   * — never a row of NULLs. A gap is therefore an ABSENT bucket, which the client sees as a missing
   * array element, not as a present element with null prices.
   */
  @Test
  void aBucketWithNoUnderlyingRowsIsAbsentRatherThanNullFilled() {
    String symbol = "NULLGAP";
    // 10:15 bucket populated, 10:20 bucket deliberately EMPTY, 10:25 bucket populated
    repository.upsert(bar(symbol, "2026-06-12T10:15:00", "100.00", "101.00", "99.00", "100.50", 10, null));
    repository.upsert(bar(symbol, "2026-06-12T10:25:00", "102.00", "103.00", "101.00", "102.50", 20, null));
    jdbc.execute("CALL public.refresh_continuous_aggregate('candles_5m', NULL, NULL)");

    List<Candle> rows =
        repository.rangeFromAggregate(
            "candles_5m", "NSE", symbol, ist("2026-06-12T10:00:00"), ist("2026-06-12T11:00:00"));

    assertThat(rows)
        .as("the empty 10:20 bucket must be ABSENT, not present-with-nulls")
        .hasSize(2);
    // compared as INSTANTS: JDBC time_bucket hands back +00 while our literals carry +05:30, and
    // OffsetDateTime.equals is offset-sensitive (the #214 timestamp-key trap in assertion form)
    assertThat(rows).extracting(row -> row.bucket().toInstant())
        .containsExactly(
            ist("2026-06-12T10:15:00").toInstant(), ist("2026-06-12T10:25:00").toInstant());
    rows.forEach(CandleNullabilityIntegrationTest::assertOhlcvNonNull);
  }

  // ---------------------------------------------------------------------------------------------
  // Adverse state 3 — a partially-filled, in-progress bucket.
  // ---------------------------------------------------------------------------------------------

  /**
   * A 5m bucket holding only ONE of its five minutes — the in-progress state {@code GapDetector}
   * keeps re-fetching (a bucket is "missing" iff absent OR its END clears now−10m). A partial group
   * is still a non-empty group, so every aggregate has at least one input row and none degrade to
   * NULL.
   */
  @Test
  void partiallyFilledInProgressBucketServesNonNullOhlc() {
    String symbol = "NULLPARTIAL";
    // only 10:15 of the 10:15–10:20 bucket exists; 10:16..10:19 have not arrived yet
    repository.upsert(bar(symbol, "2026-06-12T10:15:00", "100.00", "101.00", "99.00", "100.50", 7, null));
    jdbc.execute("CALL public.refresh_continuous_aggregate('candles_5m', NULL, NULL)");

    List<Candle> rows =
        repository.rangeFromAggregate(
            "candles_5m", "NSE", symbol, ist("2026-06-12T10:00:00"), ist("2026-06-12T11:00:00"));

    assertThat(rows).hasSize(1);
    assertOhlcvNonNull(rows.get(0));
    assertThat(rows.get(0).open()).isEqualByComparingTo("100.00");
    assertThat(rows.get(0).close()).isEqualByComparingTo("100.50");
    assertThat(rows.get(0).volume()).isEqualTo(7);
  }

  // ---------------------------------------------------------------------------------------------
  // The other two read paths, in the same adverse states.
  // ---------------------------------------------------------------------------------------------

  /**
   * The read-time 1m→3m rollup ({@code CandleRepository#rangeRolledFromOneMinute}) — no
   * materialised view exists for 3m, so this SQL runs on every read. Exercised with BOTH a partial
   * bucket and a gap, since it is the live scalper primary.
   */
  @Test
  void threeMinuteReadTimeRollupServesNonNullOhlcWhenPartialAndGapped() {
    String symbol = "NULLROLL3M";
    // 09:15–09:18 bucket: only 2 of 3 minutes (partial). 09:18–09:21 bucket: EMPTY.
    // 09:21–09:24 bucket: 1 of 3 minutes.
    repository.upsert(bar(symbol, "2026-06-12T09:15:00", "100.00", "101.00", "99.00", "100.50", 10, null));
    repository.upsert(bar(symbol, "2026-06-12T09:16:00", "100.50", "102.00", "100.00", "101.50", 12, null));
    repository.upsert(bar(symbol, "2026-06-12T09:21:00", "103.00", "104.00", "102.00", "103.50", 15, null));

    List<Candle> rows =
        repository.rangeRolledFromOneMinute(
            "NSE", symbol, 3, ist("2026-06-12T09:00:00"), ist("2026-06-12T10:00:00"));

    assertThat(rows).as("the empty 09:18 bucket is absent, not null-filled").hasSize(2);
    assertThat(rows).extracting(row -> row.bucket().toInstant())
        .containsExactly(
            ist("2026-06-12T09:15:00").toInstant(), ist("2026-06-12T09:21:00").toInstant());
    rows.forEach(CandleNullabilityIntegrationTest::assertOhlcvNonNull);
    assertThat(rows.get(0).open()).isEqualByComparingTo("100.00"); // first(open)
    assertThat(rows.get(0).high()).isEqualByComparingTo("102.00"); // max(high)
    assertThat(rows.get(0).close()).isEqualByComparingTo("101.50"); // last(close)
    assertThat(rows.get(0).volume()).isEqualTo(22);
    assertThat(rows.get(1).volume()).isEqualTo(15);
  }

  /**
   * The native daily path. {@code CandleQueryService} routes 1d to the BASE hypertable, not to
   * {@code candles_1d} — the documented divergence between the dense native daily and the sparse
   * cagg — so 1d inherits the base table's NOT NULL columns directly.
   */
  @Test
  void nativeDailyRangeServesNonNullOhlc() {
    String symbol = "NULLDAILY";
    repository.upsert(
        new Candle(
            "NSE", symbol, "1d", ist("2026-06-12T00:00:00"),
            new BigDecimal("100.00"), new BigDecimal("110.00"), new BigDecimal("95.00"),
            new BigDecimal("105.00"), 1000, null, "MOCK"));

    List<Candle> rows =
        repository.range("NSE", symbol, "1d", ist("2026-06-01T00:00:00"), ist("2026-07-01T00:00:00"));

    assertThat(rows).hasSize(1);
    assertOhlcvNonNull(rows.get(0));
    assertThat(rows.get(0).volume()).isEqualTo(1000);
  }

  /**
   * The 1w hierarchical cagg-on-cagg: it aggregates {@code candles_1d} (itself an aggregate) rather
   * than the base table, so it is the read path furthest removed from the NOT NULL columns and the
   * one where a null would most plausibly creep in.
   */
  @Test
  void weeklyCaggOnCaggServesNonNullOhlc() {
    String symbol = "NULLWEEK";
    repository.upsert(bar(symbol, "2026-06-09T09:15:00", "200.00", "201.00", "199.00", "200.50", 10, null));
    repository.upsert(bar(symbol, "2026-06-10T09:15:00", "201.00", "203.00", "200.00", "202.50", 20, null));
    jdbc.execute("CALL public.refresh_continuous_aggregate('candles_1d', NULL, NULL)");
    jdbc.execute("CALL public.refresh_continuous_aggregate('candles_1w', NULL, NULL)");

    List<Candle> rows =
        repository.rangeFromAggregate(
            "candles_1w", "NSE", symbol, ist("2026-06-01T00:00:00"), ist("2026-07-01T00:00:00"));

    assertThat(rows).hasSize(1);
    assertOhlcvNonNull(rows.get(0));
    assertThat(rows.get(0).volume()).isEqualTo(30);
  }

  // ---------------------------------------------------------------------------------------------
  // POSITIVE CONTROL — the one field that IS nullable actually goes null on every path.
  // ---------------------------------------------------------------------------------------------

  /**
   * Without this, every {@code isNotNull()} above could be passing vacuously on a fixture that never
   * produces a null at all. {@code candles.oi} is the one nullable column ({@code BIGINT}, no NOT
   * NULL), and cash equities genuinely carry no open interest — so a null oi must survive all three
   * read paths and reach the record. This is what makes the spec's {@code ["integer","null"]} on oi
   * correct and the non-null OHLCV declarations meaningful rather than untested.
   */
  @Test
  void oiIsGenuinelyNullOnEveryReadPath() {
    String symbol = "NULLOI";
    repository.upsert(bar(symbol, "2026-06-12T11:15:00", "100.00", "101.00", "99.00", "100.50", 10, null));
    repository.upsert(bar(symbol, "2026-06-12T11:16:00", "100.50", "102.00", "100.00", "101.50", 12, null));
    jdbc.execute("CALL public.refresh_continuous_aggregate('candles_5m', NULL, NULL)");

    List<Candle> base =
        repository.range("NSE", symbol, "1m", ist("2026-06-12T11:00:00"), ist("2026-06-12T12:00:00"));
    assertThat(base).isNotEmpty();
    assertThat(base.get(0).oi()).as("oi is null on the base hypertable read").isNull();
    assertOhlcvNonNull(base.get(0));

    List<Candle> cagg =
        repository.rangeFromAggregate(
            "candles_5m", "NSE", symbol, ist("2026-06-12T11:00:00"), ist("2026-06-12T12:00:00"));
    assertThat(cagg).isNotEmpty();
    assertThat(cagg.get(0).oi()).as("last(oi, bucket) over all-null oi is null").isNull();
    assertOhlcvNonNull(cagg.get(0));

    List<Candle> rolled =
        repository.rangeRolledFromOneMinute(
            "NSE", symbol, 3, ist("2026-06-12T11:00:00"), ist("2026-06-12T12:00:00"));
    assertThat(rolled).isNotEmpty();
    assertThat(rolled.get(0).oi()).as("oi is null through the 3m read-time rollup").isNull();
    assertOhlcvNonNull(rolled.get(0));
  }

  /**
   * Pins Timescale's {@code last(value, time)} null semantics, which decide whether a MIXED bucket
   * (some bars carrying oi, the newest not) yields a null oi. Behaviour recorded rather than
   * assumed: {@code last()} returns the value at the greatest time REGARDLESS of that value being
   * null, so a bucket whose newest bar has no oi reports null even though earlier bars had one —
   * another route by which the declared-nullable oi genuinely arrives null.
   */
  @Test
  void lastOiReflectsTheNewestBarEvenWhenThatBarHasNoOi() {
    String symbol = "NULLOIMIX";
    repository.upsert(bar(symbol, "2026-06-12T12:15:00", "100.00", "101.00", "99.00", "100.50", 10, 4200L));
    repository.upsert(bar(symbol, "2026-06-12T12:16:00", "100.50", "102.00", "100.00", "101.50", 12, null));
    jdbc.execute("CALL public.refresh_continuous_aggregate('candles_5m', NULL, NULL)");

    List<Candle> cagg =
        repository.rangeFromAggregate(
            "candles_5m", "NSE", symbol, ist("2026-06-12T12:00:00"), ist("2026-06-12T13:00:00"));

    assertThat(cagg).hasSize(1);
    assertThat(cagg.get(0).oi())
        .as("last(oi, bucket) takes the newest bar's oi — null here — not the newest NON-NULL oi")
        .isNull();
    assertOhlcvNonNull(cagg.get(0));
  }
}
