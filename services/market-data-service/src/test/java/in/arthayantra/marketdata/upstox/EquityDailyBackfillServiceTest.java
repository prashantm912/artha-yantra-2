package in.arthayantra.marketdata.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.marketdata.candles.Candle;
import in.arthayantra.marketdata.candles.CandleRepository;
import in.arthayantra.marketdata.upstox.UpstoxExpiredInstrumentsClient.Bar;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Unit test for {@link EquityDailyBackfillService}: the async trigger resolves each NSE equity to its
 * Upstox key, pulls daily candles, and upserts them into {@code candles}@1d as {@code BACKFILL}; a
 * symbol with no key is skipped, an Upstox error is counted failed and the run continues, and an
 * absent symbol list falls back to the universe query. Clients + repositories are mocked — no DB.
 */
class EquityDailyBackfillServiceTest {

  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-30T06:00:00Z"), ZoneOffset.UTC);
  private static final String KEY_RELIANCE = "NSE_EQ|INE002A01018";
  private static final String KEY_TCS = "NSE_EQ|INE467B01029";

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(T value) {
    ObjectProvider<T> p = mock(ObjectProvider.class);
    when(p.getIfAvailable()).thenReturn(value);
    return p;
  }

  private static Bar dayBar(String ts, String close) {
    return new Bar(
        OffsetDateTime.parse(ts),
        new BigDecimal("100"), new BigDecimal("110"),
        new BigDecimal("95"), new BigDecimal(close), 12345, null);
  }

  private static EquityDailyBackfillService service(
      UpstoxEquityMasterClient master,
      UpstoxExpiredInstrumentsClient upstox,
      CandleRepository candles,
      JdbcTemplate jdbc) {
    return new EquityDailyBackfillService(
        provider(master), provider(upstox), candles, jdbc, CLOCK, 0L);
  }

  /** Blocks until the async run leaves RUNNING (OK/FAILED), or fails after ~5s. */
  private static EquityDailyBackfillService.Status awaitDone(EquityDailyBackfillService service) {
    for (int i = 0; i < 250; i++) {
      EquityDailyBackfillService.Status s = service.status();
      if (!"RUNNING".equals(s.state()) && !"NEVER_RUN".equals(s.state())) {
        return s;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    throw new AssertionError("backfill did not finish in time: " + service.status().state());
  }

  @Test
  void backfillsExplicitSymbolsIntoOneDayBackfillCandles() {
    UpstoxEquityMasterClient master = mock(UpstoxEquityMasterClient.class);
    UpstoxExpiredInstrumentsClient upstox = mock(UpstoxExpiredInstrumentsClient.class);
    CandleRepository candles = mock(CandleRepository.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);

    when(master.equityKey("RELIANCE")).thenReturn(KEY_RELIANCE);
    when(master.equityKey("TCS")).thenReturn(KEY_TCS);
    when(upstox.activeCandlesForBatch(any(), eq("day"), any(), any()))
        .thenReturn(List.of(dayBar("2026-06-29T00:00:00+05:30", "108")));

    EquityDailyBackfillService service = service(master, upstox, candles, jdbc);
    service.triggerAsync(List.of("reliance", "TCS"), 300);
    EquityDailyBackfillService.Status s = awaitDone(service);

    assertThat(s.state()).isEqualTo("OK");
    assertThat(s.symbols()).isEqualTo(2);
    assertThat(s.written()).isEqualTo(2);
    assertThat(s.candleRows()).isEqualTo(2);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Candle>> rows = ArgumentCaptor.forClass(List.class);
    verify(candles, times(2)).upsertAuthoritativeAll(rows.capture());
    Candle row = rows.getAllValues().get(0).get(0);
    assertThat(row.exchange()).isEqualTo("NSE");
    assertThat(row.interval()).isEqualTo("1d");
    assertThat(row.source()).isEqualTo("BACKFILL");
    assertThat(row.oi()).isNull();
  }

  @Test
  void skipsSymbolsWithNoUpstoxKey() {
    UpstoxEquityMasterClient master = mock(UpstoxEquityMasterClient.class);
    UpstoxExpiredInstrumentsClient upstox = mock(UpstoxExpiredInstrumentsClient.class);
    CandleRepository candles = mock(CandleRepository.class);

    when(master.equityKey("RELIANCE")).thenReturn(KEY_RELIANCE);
    when(master.equityKey("NOTLISTED")).thenReturn(null);
    when(upstox.activeCandlesForBatch(any(), eq("day"), any(), any()))
        .thenReturn(List.of(dayBar("2026-06-29T00:00:00+05:30", "108")));

    EquityDailyBackfillService service = service(master, upstox, candles, mock(JdbcTemplate.class));
    service.triggerAsync(List.of("RELIANCE", "NOTLISTED"), null);
    EquityDailyBackfillService.Status s = awaitDone(service);

    assertThat(s.state()).isEqualTo("OK");
    assertThat(s.written()).isEqualTo(1);
    assertThat(s.skipped()).isEqualTo(1);
    verify(candles, times(1)).upsertAuthoritativeAll(any());
  }

  @Test
  void anUpstoxErrorIsCountedFailedAndTheRunContinues() {
    UpstoxEquityMasterClient master = mock(UpstoxEquityMasterClient.class);
    UpstoxExpiredInstrumentsClient upstox = mock(UpstoxExpiredInstrumentsClient.class);
    CandleRepository candles = mock(CandleRepository.class);

    when(master.equityKey("RELIANCE")).thenReturn(KEY_RELIANCE);
    when(master.equityKey("TCS")).thenReturn(KEY_TCS);
    when(upstox.activeCandlesForBatch(eq(KEY_RELIANCE), eq("day"), any(), any()))
        .thenThrow(new RuntimeException("upstream 500"));
    when(upstox.activeCandlesForBatch(eq(KEY_TCS), eq("day"), any(), any()))
        .thenReturn(List.of(dayBar("2026-06-29T00:00:00+05:30", "108")));

    EquityDailyBackfillService service = service(master, upstox, candles, mock(JdbcTemplate.class));
    service.triggerAsync(List.of("RELIANCE", "TCS"), null);
    EquityDailyBackfillService.Status s = awaitDone(service);

    assertThat(s.state()).isEqualTo("OK");
    assertThat(s.failed()).isGreaterThanOrEqualTo(1);
    assertThat(s.written()).isEqualTo(1);
    verify(candles, times(1)).upsertAuthoritativeAll(any());
  }

  @Test
  void anEmptySymbolListFallsBackToTheActiveEquityUniverseQuery() {
    UpstoxEquityMasterClient master = mock(UpstoxEquityMasterClient.class);
    UpstoxExpiredInstrumentsClient upstox = mock(UpstoxExpiredInstrumentsClient.class);
    CandleRepository candles = mock(CandleRepository.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);

    when(jdbc.queryForList(any(String.class), eq(String.class))).thenReturn(List.of("RELIANCE"));
    when(master.equityKey("RELIANCE")).thenReturn(KEY_RELIANCE);
    when(upstox.activeCandlesForBatch(any(), eq("day"), any(), any()))
        .thenReturn(List.of(dayBar("2026-06-29T00:00:00+05:30", "108")));

    EquityDailyBackfillService service = service(master, upstox, candles, jdbc);
    service.triggerAsync(null, null);
    EquityDailyBackfillService.Status s = awaitDone(service);

    assertThat(s.state()).isEqualTo("OK");
    assertThat(s.written()).isEqualTo(1);
    verify(jdbc).queryForList(any(String.class), eq(String.class));
  }

  @Test
  void mapsADailyBarToAnNseBackfillCandleAtIstMidnight() {
    Candle c =
        EquityDailyBackfillService.toCandle("RELIANCE", dayBar("2026-06-29T00:00:00+05:30", "108"));

    assertThat(c.exchange()).isEqualTo("NSE");
    assertThat(c.tradingsymbol()).isEqualTo("RELIANCE");
    assertThat(c.interval()).isEqualTo("1d");
    assertThat(c.source()).isEqualTo("BACKFILL");
    assertThat(c.oi()).isNull();
    assertThat(c.close()).isEqualByComparingTo("108");
    // bucket normalized to IST 00:00 of the trade day (2026-06-29T00:00+05:30 = 2026-06-28T18:30Z).
    assertThat(c.bucket().toInstant()).isEqualTo(Instant.parse("2026-06-28T18:30:00Z"));
  }

  @Test
  void a503WhenUpstoxAnalyticsIsDisabled() {
    EquityDailyBackfillService service =
        new EquityDailyBackfillService(
            provider(null), provider(null),
            mock(CandleRepository.class), mock(JdbcTemplate.class), CLOCK, 0L);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.triggerAsync(List.of("RELIANCE"), null))
        .isInstanceOf(in.arthayantra.common.web.error.ApiException.class);
    verify(mock(CandleRepository.class), never()).upsertAuthoritativeAll(any());
  }
}
