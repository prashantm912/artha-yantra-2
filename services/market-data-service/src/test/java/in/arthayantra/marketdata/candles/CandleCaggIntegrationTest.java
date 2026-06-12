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
