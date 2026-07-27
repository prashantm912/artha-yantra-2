package in.arthayantra.marketdata.candles;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

  /**
   * Pinned to a year the bundled CSV will NEVER cover. 2027 was the original pin, but the CD-2
   * refresh adds 2027 (the horizon canary fires ~45 days out), after which that date becomes a
   * covered TRADING day — the test would still pass while silently no longer exercising the
   * fail-open branch, which is the most safety-critical path here (cross-vendor review, Minor 5).
   */
  @Test
  void calendarOutOfRangeAllowsTheWrite() {
    writer.onClosedBar(bar("2099-01-04T10:15:00+05:30"));

    verify(repository).upsert(any(Candle.class));
  }

  /**
   * THE off-pattern-session guard (cross-vendor review, Critical 1). {@code isTradingDay} returns
   * false for ANY Sunday, and 2026-11-08 — Diwali Muhurat, a REAL session — is a Sunday. A date-only
   * gate drops those bars permanently, because GapDetector never marks a non-trading day missing so
   * the re-fetch never backfills. Traded volume is what distinguishes a real session from a WS
   * snapshot packet.
   */
  @Test
  void muhuratStyleSundayBarWithRealVolumeIsPersisted() {
    writer.onClosedBar(barWith("2026-11-08T18:15:00+05:30", 4200, "TICK_AGG"));

    verify(repository).upsert(any(Candle.class));
  }

  /**
   * The mock feed ticks 24/7 by design so the mock signal engine never starves. If the source
   * short-circuit is ever dropped or widened, every mock bar would die on every weekend and holiday
   * — and without this test the whole suite would still pass green (cross-vendor review, Major 3).
   */
  @Test
  void mockSourceBarOnSundayIsPersisted() {
    writer.onClosedBar(barWith("2026-06-14T10:15:00+05:30", 0, "MOCK"));

    verify(repository).upsert(any(Candle.class));
  }

  private static Candle bar(String bucket) {
    return barWith(bucket, 0, "TICK_AGG");
  }

  private static Candle barWith(String bucket, long volume, String source) {
    return new Candle(
        "NSE", "TEST", "1m", OffsetDateTime.parse(bucket),
        new BigDecimal("100.00"), new BigDecimal("100.00"), new BigDecimal("100.00"),
        new BigDecimal("100.00"), volume, null, source);
  }
}
