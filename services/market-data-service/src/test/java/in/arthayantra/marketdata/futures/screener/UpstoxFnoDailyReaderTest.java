package in.arthayantra.marketdata.futures.screener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.marketdata.futures.screener.MarketMoversScreener.DailyBar;
import in.arthayantra.marketdata.instruments.Instrument;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import in.arthayantra.marketdata.upstox.UpstoxExpiredInstrumentsClient;
import in.arthayantra.marketdata.upstox.UpstoxExpiredInstrumentsClient.Bar;
import in.arthayantra.marketdata.upstox.UpstoxFnoMasterClient;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** E1 v2: front-future resolve → Upstox key → daily candles → DailyBar (oldest→newest), best-effort. */
class UpstoxFnoDailyReaderTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-10T06:00:00Z"), ZoneId.of("Asia/Kolkata"));

  private final InstrumentRepository instruments = mock(InstrumentRepository.class);
  private final UpstoxFnoMasterClient fnoMaster = mock(UpstoxFnoMasterClient.class);
  private final UpstoxExpiredInstrumentsClient upstox = mock(UpstoxExpiredInstrumentsClient.class);
  private final UpstoxFnoDailyReader reader =
      new UpstoxFnoDailyReader(instruments, provider(fnoMaster), provider(upstox), CLOCK);

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(T value) {
    ObjectProvider<T> p = mock(ObjectProvider.class);
    when(p.getIfAvailable()).thenReturn(value);
    return p;
  }

  private static Instrument fut(String sym, LocalDate expiry) {
    return new Instrument(
        "NFO", sym, 1L, "RELIANCE", "NFO-FUT", "FUT", "NSE", "RELIANCE",
        expiry, null, new BigDecimal("0.05"), 250, true);
  }

  private static Bar bar(int day, String o, String h, String l, String c, long v, long oi) {
    OffsetDateTime t = OffsetDateTime.of(2026, 7, day, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    return new Bar(t, new BigDecimal(o), new BigDecimal(h), new BigDecimal(l), new BigDecimal(c), v, oi);
  }

  @Test
  void resolvesFrontFutureMapsKeyAndSortsBarsOldestFirst() {
    LocalDate jul = LocalDate.of(2026, 7, 30);
    LocalDate aug = LocalDate.of(2026, 8, 27);
    when(instruments.futures("RELIANCE"))
        .thenReturn(List.of(fut("RELIANCE26AUGFUT", aug), fut("RELIANCE26JULFUT", jul)));
    when(fnoMaster.keyFor("NFO", "RELIANCE", "FUT", jul, null)).thenReturn("NSE_FO|111"); // front = JUL
    // Upstox returns newest-first; the reader must sort oldest→newest (the screener needs today last).
    when(upstox.activeCandles(eq("NSE_FO|111"), eq("day"), any(), any()))
        .thenReturn(List.of(bar(9, "3", "4", "2", "3.5", 100, 10), bar(7, "1", "2", "0", "1.5", 100, 10)));

    List<DailyBar> bars =
        reader.dailyBars("RELIANCE", LocalDate.of(2026, 6, 10), LocalDate.of(2026, 7, 10));

    assertThat(bars).hasSize(2);
    assertThat(bars.get(0).close()).isEqualByComparingTo("1.5"); // earliest first
    assertThat(bars.get(1).close()).isEqualByComparingTo("3.5"); // today last
  }

  @Test
  void emptyWhenNoFrontFuture() {
    when(instruments.futures("FOO")).thenReturn(List.of());
    assertThat(reader.dailyBars("FOO", LocalDate.of(2026, 6, 10), LocalDate.of(2026, 7, 10))).isEmpty();
  }

  @Test
  void emptyWhenNoUpstoxKey() {
    when(instruments.futures("BAR")).thenReturn(List.of(fut("BAR26JULFUT", LocalDate.of(2026, 7, 30))));
    when(fnoMaster.keyFor(any(), any(), any(), any(), any())).thenReturn(null);
    assertThat(reader.dailyBars("BAR", LocalDate.of(2026, 6, 10), LocalDate.of(2026, 7, 10))).isEmpty();
  }
}
