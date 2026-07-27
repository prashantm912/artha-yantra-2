package in.arthayantra.marketdata.candles;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.canary.DataHealthState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class BarWriterTest {

  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-10T10:00:00Z"), ZoneOffset.UTC);

  private final CandleRepository repository = mock(CandleRepository.class);
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final ObjectMapper objectMapper = mock(ObjectMapper.class);
  private final BarWriter writer =
      new BarWriter(
          repository,
          redis,
          objectMapper,
          CLOCK,
          new SimpleMeterRegistry(),
          new DataHealthState(CLOCK, ""),
          MarketCalendar.nse());

  BarWriterTest() throws Exception {
    when(objectMapper.writeValueAsString(any())).thenReturn("{}");
  }

  @Test
  void realInSessionTickAggBarIsPersisted() {
    writer.onClosedBar(bar("2026-06-10T10:15:00+05:30"));

    verify(repository).upsert(any(Candle.class));
  }

  @Test
  void sundayTickAggBarIsNotPersisted() {
    writer.onClosedBar(bar("2026-06-14T10:15:00+05:30"));

    verify(repository, never()).upsert(any(Candle.class));
  }

  @Test
  void exchangeHolidayTickAggBarIsNotPersisted() {
    writer.onClosedBar(bar("2026-01-26T10:15:00+05:30"));

    verify(repository, never()).upsert(any(Candle.class));
  }

  @Test
  void sessionBoundaryTickAggBarsArePersisted() {
    writer.onClosedBar(bar("2026-06-10T09:15:00+05:30"));
    writer.onClosedBar(bar("2026-06-10T15:29:00+05:30"));

    verify(repository, org.mockito.Mockito.times(2)).upsert(any(Candle.class));
  }

  @Test
  void calendarOutOfRangeAllowsTheWrite() {
    writer.onClosedBar(bar("2027-01-04T10:15:00+05:30"));

    verify(repository).upsert(any(Candle.class));
  }

  private static Candle bar(String bucket) {
    return new Candle(
        "NSE", "TEST", "1m", OffsetDateTime.parse(bucket),
        new BigDecimal("100.00"), new BigDecimal("100.00"), new BigDecimal("100.00"),
        new BigDecimal("100.00"), 0, null, "TICK_AGG");
  }
}
