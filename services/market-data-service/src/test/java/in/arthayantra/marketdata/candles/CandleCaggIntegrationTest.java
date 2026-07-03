package in.arthayantra.marketdata.candles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase-10 IT: persisted+published closed bars, replay idempotence, 5m aggregate vs a
 * hand-computed fixture, and the 1w hierarchical aggregate incl. a holiday-shortened week.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class CandleCaggIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired private CandleRepository repository;
  @Autowired private BarWriter barWriter;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private RedisConnectionFactory connectionFactory;

  private static OffsetDateTime ist(String text) {
    return OffsetDateTime.parse(text + "+05:30");
  }

  private static Candle bar(
      String symbol, String time, String o, String h, String l, String c, long volume) {
    return new Candle(
        "NSE", symbol, "1m", ist(time),
        new BigDecimal(o), new BigDecimal(h), new BigDecimal(l), new BigDecimal(c),
        volume, null, "MOCK");
  }

  @Test
  void closedBarsPersistPublishAndReplayIdempotently() {
    List<String> messages = new CopyOnWriteArrayList<>();
    RedisMessageListenerContainer listener = new RedisMessageListenerContainer();
    listener.setConnectionFactory(connectionFactory);
    listener.addMessageListener(
        (message, pattern) -> messages.add(new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8)),
        new PatternTopic("candles.1m.*"));
    listener.afterPropertiesSet();
    listener.start();
    try {
      Candle bar = bar("PUBTEST", "2026-06-10T10:15:00", "100.00", "101.00", "99.00", "100.50", 500);
      barWriter.onClosedBar(bar);
      barWriter.onClosedBar(bar); // replay — must not double-count

      await().atMost(Duration.ofSeconds(10)).until(() -> !messages.isEmpty());
      assertThat(messages.get(0)).contains("\"close\":\"100.50\"").contains("\"volume\":500");

      List<Candle> rows =
          repository.range("NSE", "PUBTEST", "1m", ist("2026-06-10T10:00:00"), ist("2026-06-10T11:00:00"));
      assertThat(rows).hasSize(1);
      assertThat(rows.get(0).volume()).isEqualTo(500);
      assertThat(rows.get(0).close()).isEqualByComparingTo("100.50");
    } finally {
      listener.stop();
    }
  }

  @Test
  void fiveMinuteAggregateMatchesHandComputedFixture() {
    String symbol = "CAGG5M";
    for (int i = 0; i < 10; i++) {
      String minute = String.format("2026-06-10T09:%02d:00", 15 + i);
      repository.upsert(
          bar(symbol, minute,
              (100 + i) + ".00", (100 + i) + ".50", (99 + i) + ".50", (100 + i) + ".25", 10));
    }
    // other IT classes may have advanced the 5m watermark past this window (test order
    // differs across OSes) — refresh explicitly so the seeded bars are visible
    jdbc.execute("CALL public.refresh_continuous_aggregate('candles_5m', NULL, NULL)");

    List<Candle> fiveMin =
        repository.rangeFromAggregate(
            "candles_5m", "NSE", symbol, ist("2026-06-10T09:00:00"), ist("2026-06-10T10:00:00"));

    assertThat(fiveMin).hasSize(2);
    Candle first = fiveMin.get(0);
    assertThat(first.bucket()).isEqualTo(ist("2026-06-10T09:15:00"));
    assertThat(first.open()).isEqualByComparingTo("100.00"); // first bar's open
    assertThat(first.high()).isEqualByComparingTo("104.50"); // max of bars 0..4
    assertThat(first.low()).isEqualByComparingTo("99.50"); // min of bars 0..4
    assertThat(first.close()).isEqualByComparingTo("104.25"); // last bar's close
    assertThat(first.volume()).isEqualTo(50);
  }

  @Test
  void identicalRefetchKeepsProvenanceButValueChangeTakesTheNewSource() {
    // ledger #8 (2026-07-03): REST re-fetches used to re-stamp tick-agg rows as KITE even when
    // the bar was byte-identical — "did tick aggregation run" became unanswerable post-hoc
    String symbol = "PROV1";
    Candle tickAgg =
        new Candle(
            "NSE", symbol, "1m", ist("2026-06-10T10:15:00"),
            new BigDecimal("100.00"), new BigDecimal("101.00"), new BigDecimal("99.00"),
            new BigDecimal("100.50"), 500, null, "TICK_AGG");
    repository.upsert(tickAgg);

    // identical bar arriving from the REST path: values unchanged → provenance preserved
    repository.upsert(
        new Candle(
            "NSE", symbol, "1m", ist("2026-06-10T10:15:00"),
            new BigDecimal("100.00"), new BigDecimal("101.00"), new BigDecimal("99.00"),
            new BigDecimal("100.50"), 500, null, "KITE"));
    List<Candle> rows =
        repository.range("NSE", symbol, "1m", ist("2026-06-10T10:00:00"), ist("2026-06-10T11:00:00"));
    assertThat(rows.get(0).source()).isEqualTo("TICK_AGG");

    // a CORRECTING bar (different close/volume) honestly takes the new source
    repository.upsert(
        new Candle(
            "NSE", symbol, "1m", ist("2026-06-10T10:15:00"),
            new BigDecimal("100.00"), new BigDecimal("101.00"), new BigDecimal("99.00"),
            new BigDecimal("100.75"), 520, null, "KITE"));
    rows =
        repository.range("NSE", symbol, "1m", ist("2026-06-10T10:00:00"), ist("2026-06-10T11:00:00"));
    assertThat(rows.get(0).source()).isEqualTo("KITE");
    assertThat(rows.get(0).close()).isEqualByComparingTo("100.75");
  }

  @Test
  void authoritativeRefetchCorrectsAPoisonedSpikeHigh() {
    // audit row 16: under the GREATEST merge a poisoned tick-agg high could NEVER shrink —
    // the fetched-history path replaces values outright, so a Kite re-fetch corrects it
    String symbol = "AUTH1";
    repository.upsert(
        new Candle(
            "NSE", symbol, "1m", ist("2026-06-10T10:15:00"),
            new BigDecimal("100.00"), new BigDecimal("999.00"), new BigDecimal("99.00"),
            new BigDecimal("100.50"), 500, null, "TICK_AGG")); // poisoned spike high

    // value-identical authoritative re-fetch: provenance rule holds on replace semantics too
    repository.upsertAuthoritativeAll(
        List.of(
            new Candle(
                "NSE", symbol, "1m", ist("2026-06-10T10:15:00"),
                new BigDecimal("100.00"), new BigDecimal("999.00"), new BigDecimal("99.00"),
                new BigDecimal("100.50"), 500, null, "KITE")));
    List<Candle> rows =
        repository.range("NSE", symbol, "1m", ist("2026-06-10T10:00:00"), ist("2026-06-10T11:00:00"));
    assertThat(rows.get(0).source()).isEqualTo("TICK_AGG");

    // the TRUE bar from Kite historical: high shrinks, source honestly flips
    repository.upsertAuthoritativeAll(
        List.of(
            new Candle(
                "NSE", symbol, "1m", ist("2026-06-10T10:15:00"),
                new BigDecimal("100.00"), new BigDecimal("101.00"), new BigDecimal("99.00"),
                new BigDecimal("100.50"), 500, null, "KITE")));
    rows =
        repository.range("NSE", symbol, "1m", ist("2026-06-10T10:00:00"), ist("2026-06-10T11:00:00"));
    assertThat(rows.get(0).high()).isEqualByComparingTo("101.00"); // spike corrected outright
    assertThat(rows.get(0).source()).isEqualTo("KITE");
  }

  @Test
  void tickAggMergeUpsertStillGreatestLeasts() {
    // the live tick-agg path keeps the B-6 merge — a replayed narrower bar must not shrink the range
    String symbol = "MERGE1";
    repository.upsert(bar(symbol, "2026-06-10T10:15:00", "100.00", "101.00", "99.00", "100.50", 500));
    repository.upsert(bar(symbol, "2026-06-10T10:15:00", "100.00", "100.75", "98.50", "100.25", 510));

    List<Candle> rows =
        repository.range("NSE", symbol, "1m", ist("2026-06-10T10:00:00"), ist("2026-06-10T11:00:00"));
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).high()).isEqualByComparingTo("101.00"); // GREATEST kept the wider high
    assertThat(rows.get(0).low()).isEqualByComparingTo("98.50"); // LEAST took the lower low
    assertThat(rows.get(0).close()).isEqualByComparingTo("100.25"); // close from the newest write
  }

  @Test
  void compressionPolicyCoexistsWithUpserts() {
    // AUDIT GAP (Phase 10 Tests & Verification): upserts must keep working against a
    // COMPRESSED chunk — backfills older than the 7-day compress_after hit this in production
    String symbol = "COMPRESS1";
    repository.upsert(bar(symbol, "2026-01-05T09:15:00", "100.00", "101.00", "99.00", "100.50", 10));
    // compress ONLY the seeded January chunk — never the chunks other tests write into
    jdbc.execute(
        "SELECT public.compress_chunk(c, true) FROM public.show_chunks('candles',"
            + " older_than => '2026-02-01T00:00:00+05:30'::timestamptz) c");

    // ON CONFLICT DO UPDATE into the compressed chunk — the B-6 merge must still apply
    repository.upsert(bar(symbol, "2026-01-05T09:15:00", "100.00", "105.00", "98.00", "102.25", 25));

    List<Candle> rows =
        repository.range("NSE", symbol, "1m", ist("2026-01-05T09:00:00"), ist("2026-01-05T10:00:00"));
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).high()).isEqualByComparingTo("105.00"); // GREATEST applied
    assertThat(rows.get(0).low()).isEqualByComparingTo("98.00"); // LEAST applied
    assertThat(rows.get(0).close()).isEqualByComparingTo("102.25");
  }

  @Test
  void weeklyAggregateUsesIstMondayBucketsIncludingHolidayWeeks() {
    String symbol = "CAGGW";
    // week of Mon 2026-01-26 (Republic Day) — trading days are Tue 27 + Wed 28
    repository.upsert(bar(symbol, "2026-01-27T09:15:00", "200.00", "201.00", "199.00", "200.50", 10));
    repository.upsert(bar(symbol, "2026-01-28T09:15:00", "201.00", "203.00", "200.00", "202.50", 20));
    // refresh hierarchical caggs explicitly (policies own this in production)
    jdbc.execute("CALL public.refresh_continuous_aggregate('candles_1d', NULL, NULL)");
    jdbc.execute("CALL public.refresh_continuous_aggregate('candles_1w', NULL, NULL)");

    List<Candle> weekly =
        repository.rangeFromAggregate(
            "candles_1w", "NSE", symbol, ist("2026-01-01T00:00:00"), ist("2026-03-01T00:00:00"));

    assertThat(weekly).hasSize(1);
    Candle week = weekly.get(0);
    assertThat(week.bucket())
        .as("holiday-shortened week still rolls into its IST Monday bucket")
        .isEqualTo(ist("2026-01-26T00:00:00"));
    assertThat(week.open()).isEqualByComparingTo("200.00");
    assertThat(week.high()).isEqualByComparingTo("203.00");
    assertThat(week.close()).isEqualByComparingTo("202.50");
    assertThat(week.volume()).isEqualTo(30);
  }
}
